use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use clap::Parser;
use peernet_client::{ClientOptions, ClientState, TunnelClient};
use peernet_proto::{write_frame, MessageKind, PeerMessage, TcpRelayHeader, UdpRelayHeader};
use peernet_tcp::{FlowKey, FromUpstream, TcpStack, ToUpstream};
use quinn::Connection;
use tokio::io::AsyncWriteExt;
use tokio::sync::watch;

/// PeerNet Windows CLI client — connect to a PeerNet Android host and share its internet.
#[derive(Parser)]
#[command(name = "peernet", version, about = "PeerNet CLI client for Windows")]
struct Cli {
    /// Host address (e.g. 192.168.49.1:4434)
    #[arg(short, long)]
    host: String,

    /// Host certificate fingerprint (64 hex chars)
    #[arg(short, long)]
    fingerprint: String,

    /// Device name for this client
    #[arg(short, long, default_value = "peernet-cli")]
    name: String,

    /// TUN adapter IP address
    #[arg(long, default_value = "10.215.17.2")]
    tun_ip: String,

    /// TUN adapter DNS server
    #[arg(long, default_value = "8.8.8.8")]
    dns: String,

    /// MTU for the TUN adapter
    #[arg(long, default_value = "1400")]
    mtu: u16,
}

// ── UDP flow table (reused from peernet-ffi) ──

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct UdpFlowKey {
    src_port: u16,
    dst_ip: [u8; 4],
    dst_port: u16,
}

#[derive(Clone, Copy)]
struct UdpFlow {
    local_ip: [u8; 4],
    last_seen: Instant,
}

const MAX_UDP_FLOWS: usize = 2048;
const UDP_FLOW_IDLE_SECS: u64 = peernet_proto::UDP_NAT_IDLE_TIMEOUT_SECS;

struct FlowTable {
    flows: HashMap<UdpFlowKey, UdpFlow>,
    last_by_port: HashMap<u16, UdpFlowKey>,
}

fn note_udp_flow(
    table: &mut FlowTable,
    src_port: u16,
    local_ip: [u8; 4],
    dst_ip: [u8; 4],
    dst_port: u16,
) {
    let key = UdpFlowKey { src_port, dst_ip, dst_port };
    let now = Instant::now();
    table.flows.insert(key, UdpFlow { local_ip, last_seen: now });
    table.last_by_port.insert(src_port, key);
    if table.flows.len() > MAX_UDP_FLOWS {
        sweep_udp_flows(table, now);
    }
}

fn sweep_udp_flows(table: &mut FlowTable, now: Instant) {
    let idle = Duration::from_secs(UDP_FLOW_IDLE_SECS);
    table.flows.retain(|_, f| now.duration_since(f.last_seen) < idle);
    if table.flows.len() > MAX_UDP_FLOWS {
        let target = MAX_UDP_FLOWS * 3 / 4;
        let mut aged: Vec<(UdpFlowKey, Instant)> =
            table.flows.iter().map(|(k, f)| (*k, f.last_seen)).collect();
        aged.sort_by_key(|(_, seen)| *seen);
        let excess = table.flows.len().saturating_sub(target);
        for (key, _) in aged.into_iter().take(excess) {
            table.flows.remove(&key);
        }
    }
    let removed_keys: Vec<_> = table
        .flows
        .iter()
        .filter(|(_, f)| now.duration_since(f.last_seen) >= idle)
        .map(|(k, _)| *k)
        .collect();
    let removed = removed_keys.len() as u64;
    for k in removed_keys {
        table.flows.remove(&k);
    }
    if removed > 0 {
        table.last_by_port.retain(|_, key| table.flows.contains_key(key));
    }
}

fn resolve_reply_source(
    table: &mut FlowTable,
    src_port: u16,
    peer: Option<([u8; 4], u16)>,
) -> Option<(Ipv4Addr, u16, Ipv4Addr)> {
    let now = Instant::now();
    if let Some((ip, port)) = peer {
        let key = UdpFlowKey { src_port, dst_ip: ip, dst_port: port };
        if let Some(flow) = table.flows.get_mut(&key) {
            flow.last_seen = now;
            return Some((Ipv4Addr::from(ip), port, Ipv4Addr::from(flow.local_ip)));
        }
    }
    let key = *table.last_by_port.get(&src_port)?;
    let flow = table.flows.get_mut(&key)?;
    flow.last_seen = now;
    Some((Ipv4Addr::from(key.dst_ip), key.dst_port, Ipv4Addr::from(flow.local_ip)))
}

// ── Packet building ──

fn build_udp_packet(src: (Ipv4Addr, u16), dst: (Ipv4Addr, u16), payload: &[u8]) -> Vec<u8> {
    let total_len = (20 + 8 + payload.len()) as u16;
    let mut p = Vec::with_capacity(total_len as usize);
    p.extend_from_slice(&[0x45, 0x00]);
    p.extend_from_slice(&total_len.to_be_bytes());
    p.extend_from_slice(&[0x00, 0x00]);
    p.extend_from_slice(&[0x40, 0x00]);
    p.push(64);
    p.push(17);
    p.extend_from_slice(&[0x00, 0x00]);
    p.extend_from_slice(&src.0.octets());
    p.extend_from_slice(&dst.0.octets());
    p.extend_from_slice(&src.1.to_be_bytes());
    p.extend_from_slice(&dst.1.to_be_bytes());
    p.extend_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
    p.extend_from_slice(&[0x00, 0x00]);
    p.extend_from_slice(payload);
    let csum = internet_checksum(&p[..20]);
    p[10..12].copy_from_slice(&csum.to_be_bytes());
    p
}

fn internet_checksum(bytes: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut chunks = bytes.chunks_exact(2);
    for c in &mut chunks {
        sum += u16::from_be_bytes([c[0], c[1]]) as u32;
    }
    if let [hi] = chunks.remainder() {
        sum += u32::from(*hi) << 8;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

fn describe_packet(packet: &[u8]) -> String {
    if packet.len() >= 20 && packet[0] >> 4 == 4 {
        let proto = packet[9];
        let src = format!("{}.{}.{}.{}", packet[12], packet[13], packet[14], packet[15]);
        let dst = format!("{}.{}.{}.{}", packet[16], packet[17], packet[18], packet[19]);
        format!("v4 proto={proto} {src}->{dst}")
    } else {
        format!("raw {}B", packet.len())
    }
}

// ── Stats ──

static TUN_PACKETS: AtomicU64 = AtomicU64::new(0);
static UDP_FORWARDED: AtomicU64 = AtomicU64::new(0);
static TCP_TERMINATED: AtomicU64 = AtomicU64::new(0);
static INBOUND: AtomicU64 = AtomicU64::new(0);
static UNDELIVERED: AtomicU64 = AtomicU64::new(0);

fn stats_line(table: &FlowTable) -> String {
    format!(
        "tun={} udp={} tcp={} in={} undelivered={} flows={}",
        TUN_PACKETS.load(Ordering::Relaxed),
        UDP_FORWARDED.load(Ordering::Relaxed),
        TCP_TERMINATED.load(Ordering::Relaxed),
        INBOUND.load(Ordering::Relaxed),
        UNDELIVERED.load(Ordering::Relaxed),
        table.flows.len(),
    )
}

// ── Main ──

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::init();
    let cli = Cli::parse();

    let server_addr = SocketAddr::from_str(&cli.host)
        .map_err(|e| format!("bad host address '{}': {}", cli.host, e))?;
    if cli.fingerprint.len() != 64 || !cli.fingerprint.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("fingerprint must be 64 hex characters".into());
    }

    log::info!("PeerNet CLI v{}", env!("CARGO_PKG_VERSION"));
    log::info!("Connecting to {server_addr}...");

    // ── Create wintun adapter ──
    let tun_ip: Ipv4Addr = cli.tun_ip.parse().map_err(|e| format!("bad TUN IP: {e}"))?;
    let dns_ip: Ipv4Addr = cli.dns.parse().map_err(|e| format!("bad DNS IP: {e}"))?;

    let wintun = wintun::Adapter::open("PeerNet")
        .or_else(|_| {
            wintun::Adapter::create("PeerNet", "PeerNet Tunnel")
        })
        .map_err(|e| format!("wintun adapter creation failed: {e}. Is wintun.dll in PATH?"))?;

    wintun.set_address(tun_ip)?;
    wintun.set_netmask(Ipv4Addr::new(255, 255, 255, 0))?;
    wintun.set_dns(dns_ip)?;
    wintun.set_mtu(cli.mtu)?;

    let session = wintun.start_session(cli.mtu as u32)?;
    log::info!("TUN adapter created: {}/{}", cli.tun_ip, cli.dns);

    // ── Connect QUIC client ──
    let opts = ClientOptions::new(
        server_addr,
        "peernet-host",
        cli.fingerprint.clone(),
        cli.name.clone(),
    );

    let client = TunnelClient::connect(opts)
        .await
        .map_err(|e| format!("QUIC connect failed: {e}"))?;

    let client = Arc::new(client);
    log::info!("QUIC connected (session {})", client.session_id());

    // ── Shared state ──
    let flow_table = Arc::new(Mutex::new(FlowTable {
        flows: HashMap::new(),
        last_by_port: HashMap::new(),
    }));

    let tun_writer: Arc<Mutex<Option<wintun::SendSender>>> = Arc::new(Mutex::new(None));

    // ── TCP termination ──
    let (pkt_out_tx, pkt_out_rx) = std::sync::mpsc::channel::<Vec<u8>>();
    let (to_up_tx, to_up_rx) = std::sync::mpsc::channel::<ToUpstream>();
    let (stack, pkt_in_tx, up_tx) = TcpStack::channels(pkt_out_tx, to_up_tx);
    std::thread::spawn(move || stack.run());

    // TCP packets back to TUN
    {
        let tun_writer = tun_writer.clone();
        std::thread::spawn(move || {
            while let Ok(pkt) = pkt_out_rx.recv() {
                for _ in 0..20 {
                    if let Some(ref sender) = *tun_writer.lock().unwrap() {
                        let _ = sender.send(&pkt);
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(50));
                }
            }
        });
    }

    // Bridge TCP upstream messages to async
    let (bridge_tx, mut bridge_rx) = tokio::sync::mpsc::unbounded_channel::<ToUpstream>();
    std::thread::spawn(move || {
        while let Ok(msg) = to_up_rx.recv() {
            if bridge_tx.send(msg).is_err() {
                break;
            }
        }
    });

    // TCP relay orchestrator
    {
        let client = client.clone();
        let flow_table = flow_table.clone();
        tokio::spawn(async move {
            let mut flows: HashMap<FlowKey, quinn::SendStream> = HashMap::new();
            while let Some(msg) = bridge_rx.recv().await {
                match msg {
                    ToUpstream::Open { flow } => {
                        let dst_ip = Ipv4Addr::from(flow.dst_ip);
                        let (mut tx, rx) = match client.connection().open_bi().await {
                            Ok(pair) => pair,
                            Err(e) => {
                                log::warn!("TCP open_bi failed for {flow:?}: {e}");
                                let _ = up_tx.send(FromUpstream::Eof { flow });
                                continue;
                            }
                        };
                        let frame = PeerMessage::new(
                            MessageKind::Data,
                            client.session_id(),
                            TcpRelayHeader {
                                src_port: flow.src_port,
                                dst_ip: IpAddr::V4(dst_ip),
                                dst_port: flow.dst_port,
                            }
                            .encode(),
                        );
                        if write_frame(&mut tx, &frame).await.is_err() {
                            let _ = up_tx.send(FromUpstream::Eof { flow });
                            continue;
                        }
                        log::info!("TCP relay -> {dst_ip}:{} (port {})", flow.dst_port, flow.src_port);
                        TCP_TERMINATED.fetch_add(1, Ordering::Relaxed);
                        let up = up_tx.clone();
                        tokio::spawn(async move {
                            let mut rx = rx;
                            let mut buf = vec![0u8; 16 * 1024];
                            loop {
                                match rx.read(&mut buf).await {
                                    Ok(Some(n)) if n > 0 => {
                                        INBOUND.fetch_add(n as u64, Ordering::Relaxed);
                                        let _ = up.send(FromUpstream::Data {
                                            flow,
                                            bytes: buf[..n].to_vec(),
                                        });
                                    }
                                    _ => break,
                                }
                            }
                            let _ = up.send(FromUpstream::Eof { flow });
                        });
                        flows.insert(flow, tx);
                    }
                    ToUpstream::Data { flow, bytes } => {
                        if let Some(tx) = flows.get_mut(&flow) {
                            if tx.write_all(&bytes).await.is_err() {
                                flows.remove(&flow);
                            }
                        }
                    }
                    ToUpstream::Eof { flow } => {
                        if let Some(mut tx) = flows.remove(&flow) {
                            let _ = tx.finish();
                        }
                    }
                }
            }
            for (flow, _) in flows.drain() {
                let _ = up_tx.send(FromUpstream::Eof { flow });
            }
        });
    }

    // ── UDP reply pump ──
    {
        let client = client.clone();
        let flow_table = flow_table.clone();
        let tun_writer = tun_writer.clone();
        tokio::spawn(async move {
            loop {
                let datagram = match client.connection().read_datagram().await {
                    Ok(d) => d,
                    Err(_) => return,
                };
                let (hdr, off) = match UdpRelayHeader::decode(&datagram) {
                    Ok(v) => v,
                    Err(_) => continue,
                };
                let peer = match hdr.dst_ip {
                    IpAddr::V4(ip) => Some((ip.octets(), hdr.dst_port)),
                    IpAddr::V6(_) => None,
                };
                let mut table = flow_table.lock().unwrap();
                let resolved = resolve_reply_source(&mut table, hdr.src_port, peer);
                if let Some((src_ip, reply_port, local_ip)) = resolved {
                    let packet = build_udp_packet(
                        (src_ip, reply_port),
                        (local_ip, hdr.src_port),
                        &datagram[off..],
                    );
                    drop(table);
                    if let Some(ref sender) = *tun_writer.lock().unwrap() {
                        let _ = sender.send(&packet);
                        INBOUND.fetch_add((datagram.len() - off) as u64, Ordering::Relaxed);
                    }
                } else {
                    UNDELIVERED.fetch_add(1, Ordering::Relaxed);
                }
            }
        });
    }

    // ── TUN capture loop + stats reporter ──
    let running = Arc::new(AtomicBool::new(true));
    {
        let running = running.clone();
        ctrlc::set_handler(move || {
            running.store(false, Ordering::SeqCst);
        })?;
    }

    // Give the session a reader and sender
    let reader = session.receive_reader()?;
    let sender = session.get_sender()?;
    *tun_writer.lock().unwrap() = Some(sender);

    log::info!("Tunnel active. Press Ctrl+C to stop.");

    // Stats reporter
    let running2 = running.clone();
    let flow_table2 = flow_table.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(5));
        loop {
            if !running2.load(Ordering::SeqCst) {
                break;
            }
            interval.tick().await;
            let table = flow_table2.lock().unwrap();
            log::info!("{}", stats_line(&table));
        }
    });

    // Read packets from TUN and forward
    let mut buf = vec![0u8; cli.mtu as usize];
    let mut logged = 0u32;
    while running.load(Ordering::SeqCst) {
        match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                TUN_PACKETS.fetch_add(1, Ordering::Relaxed);
                forward_outbound(
                    &buf[..n],
                    &client,
                    &mut flow_table.lock().unwrap(),
                    &pkt_in_tx,
                );
                if logged < 5 {
                    logged += 1;
                    log::info!("pkt#{} {}B {}", TUN_PACKETS.load(Ordering::Relaxed), n, describe_packet(&buf[..n]));
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(10));
                continue;
            }
            Err(_) => break,
        }
    }

    log::info!("Shutting down...");
    client.shutdown();
    log::info!("Final: {}", stats_line(&flow_table.lock().unwrap()));
    Ok(())
}

fn forward_outbound(
    packet: &[u8],
    client: &Arc<TunnelClient>,
    table: &mut FlowTable,
    pkt_in_tx: &std::sync::mpsc::Sender<Vec<u8>>,
) {
    if packet.len() < 20 || packet[0] >> 4 != 4 {
        return;
    }
    let ihl = ((packet[0] & 0x0F) as usize) * 4;
    if ihl < 20 || packet.len() < ihl {
        return;
    }
    let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    let end = total_len.min(packet.len());
    let proto = packet[9];

    if proto == 6 {
        let _ = pkt_in_tx.send(packet[..end].to_vec());
        TCP_TERMINATED.fetch_add(1, Ordering::Relaxed);
        return;
    }
    if proto != 17 || end < ihl + 8 {
        return;
    }

    let src_ip = Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]);
    let dst_ip = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
    let l4 = ihl;
    let sport = u16::from_be_bytes([packet[l4], packet[l4 + 1]]);
    let dport = u16::from_be_bytes([packet[l4 + 2], packet[l4 + 3]]);
    let declared = u16::from_be_bytes([packet[l4 + 4], packet[l4 + 5]]) as usize;
    let payload_end = if declared >= 8 && l4 + declared <= end {
        l4 + declared
    } else {
        end
    };
    if payload_end < l4 + 8 {
        return;
    }
    let payload = &packet[l4 + 8..payload_end];

    note_udp_flow(table, sport, src_ip.octets(), dst_ip.octets(), dport);

    let hdr = UdpRelayHeader {
        session_id: client.session_id() as u32,
        src_port: sport,
        dst_ip: IpAddr::V4(dst_ip),
        dst_port: dport,
    };
    let mut frame = hdr.encode(0);
    frame.extend_from_slice(payload);

    if let Err(e) = client.connection().send_datagram(frame.clone().into()) {
        log::debug!("datagram rejected ({e}), falling back to stream");
        let client = client.clone();
        let payload = payload.to_vec();
        let dst = SocketAddr::new(IpAddr::V4(dst_ip), dport);
        tokio::spawn(async move {
            match client.udp_exchange_via_stream(sport, dst, &payload).await {
                Ok(_) => {}
                Err(e) => log::warn!("stream relay failed: {e}"),
            }
        });
    }
    UDP_FORWARDED.fetch_add(1, Ordering::Relaxed);
}
