// PeerNet Windows CLI client — only compiles on Windows (wintun TUN adapter).
// On other platforms, provides a stub so the workspace compiles cleanly.

#[cfg(not(target_os = "windows"))]
fn main() {
    eprintln!("peernet-cli is only supported on Windows. Use the Android app instead.");
}

#[cfg(target_os = "windows")]
mod windows_main {
    use std::collections::HashMap;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};
    use std::str::FromStr;
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    use clap::Parser;
    use peernet_client::{ClientOptions, TunnelClient};
    use peernet_proto::{write_frame, MessageKind, PeerMessage, TcpRelayHeader, UdpRelayHeader};
    use peernet_tcp::{FlowKey, FromUpstream, TcpStack, ToUpstream};
    use tokio::io::AsyncWriteExt;

    #[derive(Parser)]
    #[command(name = "peernet", version, about = "PeerNet CLI client for Windows")]
    struct Cli {
        #[arg(short, long)]
        host: String,
        #[arg(short, long)]
        fingerprint: String,
        #[arg(short, long, default_value = "peernet-cli")]
        name: String,
        #[arg(long, default_value = "10.215.17.2")]
        tun_ip: String,
        #[arg(long, default_value = "8.8.8.8")]
        dns: String,
        #[arg(long, default_value = "1400")]
        mtu: u16,
    }

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
    const UDP_FLOW_IDLE: u64 = peernet_proto::UDP_NAT_IDLE_TIMEOUT_SECS;

    struct FlowTable {
        flows: HashMap<UdpFlowKey, UdpFlow>,
        last_by_port: HashMap<u16, UdpFlowKey>,
    }

    fn note_flow(t: &mut FlowTable, sp: u16, lip: [u8; 4], dip: [u8; 4], dp: u16) {
        let k = UdpFlowKey { src_port: sp, dst_ip: dip, dst_port: dp };
        let now = Instant::now();
        t.flows.insert(k, UdpFlow { local_ip: lip, last_seen: now });
        t.last_by_port.insert(sp, k);
        if t.flows.len() > MAX_UDP_FLOWS {
            let idle = Duration::from_secs(UDP_FLOW_IDLE);
            t.flows.retain(|_, f| now.duration_since(f.last_seen) < idle);
        }
    }

    fn resolve_reply(
        t: &mut FlowTable,
        sp: u16,
        peer: Option<([u8; 4], u16)>,
    ) -> Option<(Ipv4Addr, u16, Ipv4Addr)> {
        let now = Instant::now();
        if let Some((ip, port)) = peer {
            let k = UdpFlowKey { src_port: sp, dst_ip: ip, dst_port: port };
            if let Some(f) = t.flows.get_mut(&k) {
                f.last_seen = now;
                return Some((Ipv4Addr::from(ip), port, Ipv4Addr::from(f.local_ip)));
            }
        }
        let k = *t.last_by_port.get(&sp)?;
        let f = t.flows.get_mut(&k)?;
        f.last_seen = now;
        Some((Ipv4Addr::from(k.dst_ip), k.dst_port, Ipv4Addr::from(f.local_ip)))
    }

    fn build_udp(src: (Ipv4Addr, u16), dst: (Ipv4Addr, u16), payload: &[u8]) -> Vec<u8> {
        let len = (20 + 8 + payload.len()) as u16;
        let mut p = Vec::with_capacity(len as usize);
        p.extend_from_slice(&[0x45, 0x00]);
        p.extend_from_slice(&len.to_be_bytes());
        p.extend_from_slice(&[0x00, 0x00, 0x40, 0x00]);
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
        let csum = ip_checksum(&p[..20]);
        p[10..12].copy_from_slice(&csum.to_be_bytes());
        p
    }

    fn ip_checksum(b: &[u8]) -> u16 {
        let mut s = 0u32;
        for c in b.chunks_exact(2) {
            s += u16::from_be_bytes([c[0], c[1]]) as u32;
        }
        if let [h] = b.chunks_exact(2).remainder() {
            s += u32::from(*h) << 8;
        }
        while s >> 16 != 0 {
            s = (s & 0xFFFF) + (s >> 16);
        }
        !(s as u16)
    }

    static TUN_PKTS: AtomicU64 = AtomicU64::new(0);
    static UDP_FWD: AtomicU64 = AtomicU64::new(0);
    static TCP_TERM: AtomicU64 = AtomicU64::new(0);
    static INBOUND: AtomicU64 = AtomicU64::new(0);
    static UNDELIVERED: AtomicU64 = AtomicU64::new(0);

    pub async fn run() -> Result<(), Box<dyn std::error::Error>> {
        env_logger::init();
        let cli = Cli::parse();

        let addr = SocketAddr::from_str(&cli.host).map_err(|e| format!("bad host: {e}"))?;
        if cli.fingerprint.len() != 64 || !cli.fingerprint.chars().all(|c| c.is_ascii_hexdigit())
        {
            return Err("fingerprint must be 64 hex chars".into());
        }
        let tun_ip: Ipv4Addr =
            cli.tun_ip.parse().map_err(|e| format!("bad TUN IP: {e}"))?;
        let _dns_ip: Ipv4Addr =
            cli.dns.parse().map_err(|e| format!("bad DNS: {e}"))?;

        log::info!("PeerNet CLI v{}", env!("CARGO_PKG_VERSION"));
        log::info!("Connecting to {addr}...");

        // Load wintun.dll (must be in working dir or PATH)
        let wintun = unsafe {
            wintun::load().map_err(|e| format!("failed to load wintun.dll: {e}. Download from https://wintun.net"))?
        };

        let adapter = match wintun::Adapter::open(&wintun, "PeerNet") {
            Ok(a) => a,
            Err(_) => wintun::Adapter::create(&wintun, "PeerNet", "PeerNet Tunnel", None)
                .map_err(|e| format!("wintun create failed: {e}"))?,
        };

        adapter.set_address(tun_ip)?;
        adapter.set_netmask(Ipv4Addr::new(255, 255, 255, 0))?;
        adapter.set_mtu(cli.mtu as usize)?;
        let session = Arc::new(adapter.start_session(wintun::MAX_RING_CAPACITY)?);
        log::info!("TUN: {}/{}", cli.tun_ip, cli.dns);

        let opts = ClientOptions::new(
            addr,
            "peernet-host",
            cli.fingerprint.clone(),
            cli.name.clone(),
        );
        let client = Arc::new(
            TunnelClient::connect(opts)
                .await
                .map_err(|e| format!("QUIC failed: {e}"))?,
        );
        log::info!("Connected (session {})", client.session_id());

        let flow_table = Arc::new(Mutex::new(FlowTable {
            flows: HashMap::new(),
            last_by_port: HashMap::new(),
        }));

        let (pkt_out_tx, pkt_out_rx) = std::sync::mpsc::channel::<Vec<u8>>();
        let (to_up_tx, to_up_rx) = std::sync::mpsc::channel::<ToUpstream>();
        let (stack, pkt_in_tx, up_tx) = TcpStack::channels(pkt_out_tx, to_up_tx);
        std::thread::spawn(move || stack.run());

        // Writer thread: receives outbound packets from TCP stack and sends to TUN
        {
            let sess = session.clone();
            std::thread::spawn(move || {
                while let Ok(pkt) = pkt_out_rx.recv() {
                    match sess.allocate_send_packet(pkt.len() as u16) {
                        Ok(mut wintun_pkt) => {
                            wintun_pkt.bytes_mut().copy_from_slice(&pkt);
                            sess.send_packet(wintun_pkt);
                        }
                        Err(e) => {
                            log::warn!("wintun alloc failed: {e}");
                        }
                    }
                }
            });
        }

        let (br_tx, mut br_rx) = tokio::sync::mpsc::unbounded_channel::<ToUpstream>();
        std::thread::spawn(move || {
            while let Ok(m) = to_up_rx.recv() {
                if br_tx.send(m).is_err() {
                    break;
                }
            }
        });

        // TCP upstream handler: forward data from host back to local TCP stack
        {
            let c = client.clone();
            tokio::spawn(async move {
                let mut flows: HashMap<FlowKey, quinn::SendStream> = HashMap::new();
                while let Some(msg) = br_rx.recv().await {
                    match msg {
                        ToUpstream::Open { flow } => {
                            let dip = Ipv4Addr::from(flow.dst_ip);
                            let (mut tx, _rx) = match c.connection().open_bi().await {
                                Ok(p) => p,
                                Err(_) => {
                                    let _ = up_tx.send(FromUpstream::Eof { flow });
                                    continue;
                                }
                            };
                            let frame = PeerMessage::new(
                                MessageKind::Data,
                                c.session_id(),
                                TcpRelayHeader {
                                    src_port: flow.src_port,
                                    dst_ip: IpAddr::V4(dip),
                                    dst_port: flow.dst_port,
                                }
                                .encode(),
                            );
                            if write_frame(&mut tx, &frame).await.is_err() {
                                let _ = up_tx.send(FromUpstream::Eof { flow });
                                continue;
                            }
                            TCP_TERM.fetch_add(1, Ordering::Relaxed);

                            let up = up_tx.clone();
                            let c2 = c.clone();
                            tokio::spawn(async move {
                                let mut rx = _rx;
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
                                drop(c2);
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
                for (f, _) in flows.drain() {
                    let _ = up_tx.send(FromUpstream::Eof { flow: f });
                }
            });
        }

        // UDP datagram handler: receive from QUIC and write to TUN
        {
            let c = client.clone();
            let ft = flow_table.clone();
            let sess = session.clone();
            tokio::spawn(async move {
                loop {
                    let dg = match c.connection().read_datagram().await {
                        Ok(d) => d,
                        Err(_) => return,
                    };
                    let (hdr, off) = match UdpRelayHeader::decode(&dg) {
                        Ok(v) => v,
                        Err(_) => continue,
                    };
                    let peer = match hdr.dst_ip {
                        IpAddr::V4(ip) => Some((ip.octets(), hdr.dst_port)),
                        _ => None,
                    };
                    let mut t = ft.lock().unwrap();
                    if let Some((sip, rp, lip)) = resolve_reply(&mut t, hdr.src_port, peer) {
                        let pkt = build_udp((sip, rp), (lip, hdr.src_port), &dg[off..]);
                        drop(t);
                        match sess.allocate_send_packet(pkt.len() as u16) {
                            Ok(mut wintun_pkt) => {
                                wintun_pkt.bytes_mut().copy_from_slice(&pkt);
                                sess.send_packet(wintun_pkt);
                                INBOUND
                                    .fetch_add((dg.len() - off) as u64, Ordering::Relaxed);
                            }
                            Err(e) => {
                                log::warn!("wintun alloc (UDP) failed: {e}");
                            }
                        }
                    } else {
                        UNDELIVERED.fetch_add(1, Ordering::Relaxed);
                    }
                }
            });
        }

        let running = Arc::new(AtomicBool::new(true));
        {
            let r = running.clone();
            ctrlc::set_handler(move || {
                r.store(false, Ordering::SeqCst);
            })?;
        }

        // TUN reader: receive packets from OS and forward to TCP stack
        {
            let sess = session.clone();
            let pkt_tx = pkt_in_tx.clone();
            let running2 = running.clone();
            std::thread::spawn(move || {
                loop {
                    if !running2.load(Ordering::SeqCst) {
                        break;
                    }
                    match sess.receive_blocking() {
                        Ok(pkt) => {
                            TUN_PKTS.fetch_add(1, Ordering::Relaxed);
                            let data = pkt.bytes().to_vec();
                            drop(pkt);
                            let _ = pkt_tx.send(data);
                        }
                        Err(_) => {
                            // Session shut down or interrupted
                            break;
                        }
                    }
                }
            });
        }

        log::info!("Tunnel active. Ctrl+C to stop.");

        // Stats reporter
        {
            let r2 = running.clone();
            let ft2 = flow_table.clone();
            tokio::spawn(async move {
                let mut iv = tokio::time::interval(Duration::from_secs(5));
                loop {
                    if !r2.load(Ordering::SeqCst) {
                        break;
                    }
                    iv.tick().await;
                    let t = ft2.lock().unwrap();
                    log::info!(
                        "tun={} udp={} tcp={} in={} undelivered={} flows={}",
                        TUN_PKTS.load(Ordering::Relaxed),
                        UDP_FWD.load(Ordering::Relaxed),
                        TCP_TERM.load(Ordering::Relaxed),
                        INBOUND.load(Ordering::Relaxed),
                        UNDELIVERED.load(Ordering::Relaxed),
                        t.flows.len()
                    );
                }
            });
        }

        // Wait for Ctrl+C
        while running.load(Ordering::SeqCst) {
            tokio::time::sleep(Duration::from_millis(100)).await;
        }

        log::info!("Shutting down...");
        session.shutdown().ok();
        client.shutdown();
        Ok(())
    }
}

#[cfg(target_os = "windows")]
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    windows_main::run().await
}
