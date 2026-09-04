// PeerNet Windows CLI client — only compiles on Windows (wintun TUN adapter).
// On other platforms, provides a stub so the workspace compiles cleanly.
#![cfg_attr(target_os = "windows",windows_subsystem = "windows")]

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
    struct UdpFlowKey { src_port: u16, dst_ip: [u8; 4], dst_port: u16 }
    #[derive(Clone, Copy)]
    struct UdpFlow { local_ip: [u8; 4], last_seen: Instant }
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

    fn resolve_reply(t: &mut FlowTable, sp: u16, peer: Option<([u8; 4], u16)>) -> Option<(Ipv4Addr, u16, Ipv4Addr)> {
        let now = Instant::now();
        if let Some((ip, port)) = peer {
            let k = UdpFlowKey { src_port: sp, dst_ip: ip, dst_port: port };
            if let Some(f) = t.flows.get_mut(&k) { f.last_seen = now; return Some((Ipv4Addr::from(ip), port, Ipv4Addr::from(f.local_ip))); }
        }
        let k = *t.last_by_port.get(&sp)?;
        let f = t.flows.get_mut(&k)?; f.last_seen = now;
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
        for c in b.chunks_exact(2) { s += u16::from_be_bytes([c[0], c[1]]) as u32; }
        if let [h] = b.chunks_exact(2).remainder() { s += u32::from(*h) << 8; }
        while s >> 16 != 0 { s = (s & 0xFFFF) + (s >> 16); }
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
        if cli.fingerprint.len() != 64 || !cli.fingerprint.chars().all(|c| c.is_ascii_hexdigit()) {
            return Err("fingerprint must be 64 hex chars".into());
        }
        let tun_ip: Ipv4Addr = cli.tun_ip.parse().map_err(|e| format!("bad TUN IP: {e}"))?;
        let dns_ip: Ipv4Addr = cli.dns.parse().map_err(|e| format!("bad DNS: {e}"))?;

        log::info!("PeerNet CLI v{}", env!("CARGO_PKG_VERSION"));
        log::info!("Connecting to {addr}...");

        let wintun = wintun::Adapter::open("PeerNet")
            .or_else(|_| wintun::Adapter::create("PeerNet", "PeerNet Tunnel"))
            .map_err(|e| format!("wintun failed: {e}. Is wintun.dll available?"))?;
        wintun.set_address(tun_ip)?;
        wintun.set_netmask(Ipv4Addr::new(255, 255, 255, 0))?;
        wintun.set_mtu(cli.mtu)?;
        let session = wintun.start_session(cli.mtu as u32)?;
        log::info!("TUN: {}/{}", cli.tun_ip, cli.dns);

        let opts = ClientOptions::new(addr, "peernet-host", cli.fingerprint.clone(), cli.name.clone());
        let client = Arc::new(TunnelClient::connect(opts).await.map_err(|e| format!("QUIC failed: {e}"))?);
        log::info!("Connected (session {})", client.session_id());

        let flow_table = Arc::new(Mutex::new(FlowTable { flows: HashMap::new(), last_by_port: HashMap::new() }));
        let tun_writer: Arc<Mutex<Option<wintun::SendSender>>> = Arc::new(Mutex::new(None));

        let (pkt_out_tx, pkt_out_rx) = std::sync::mpsc::channel::<Vec<u8>>();
        let (to_up_tx, to_up_rx) = std::sync::mpsc::channel::<ToUpstream>();
        let (stack, pkt_in_tx, up_tx) = TcpStack::channels(pkt_out_tx, to_up_tx);
        std::thread::spawn(move || stack.run());

        { let tw = tun_writer.clone(); std::thread::spawn(move || { while let Ok(pkt) = pkt_out_rx.recv() { for _ in 0..20 { if let Some(ref s) = *tw.lock().unwrap() { let _ = s.send(&pkt); break; } std::thread::sleep(Duration::from_millis(50)); } } }); }

        let (br_tx, mut br_rx) = tokio::sync::mpsc::unbounded_channel::<ToUpstream>();
        std::thread::spawn(move || { while let Ok(m) = to_up_rx.recv() { if br_tx.send(m).is_err() { break; } } });

        { let c = client.clone(); tokio::spawn(async move { let mut flows: HashMap<FlowKey, quinn::SendStream> = HashMap::new(); while let Some(msg) = br_rx.recv().await { match msg { ToUpstream::Open { flow } => { let dip = Ipv4Addr::from(flow.dst_ip); let (mut tx, rx) = match c.connection().open_bi().await { Ok(p) => p, Err(e) => { let _ = up_tx.send(FromUpstream::Eof { flow }); continue; } }; let frame = PeerMessage::new(MessageKind::Data, c.session_id(), TcpRelayHeader { src_port: flow.src_port, dst_ip: IpAddr::V4(dip), dst_port: flow.dst_port }.encode()); if write_frame(&mut tx, &frame).await.is_err() { let _ = up_tx.send(FromUpstream::Eof { flow }); continue; } TCP_TERM.fetch_add(1, Ordering::Relaxed); let up = up_tx.clone(); tokio::spawn(async move { let mut rx = rx; let mut buf = vec![0u8; 16*1024]; loop { match rx.read(&mut buf).await { Ok(Some(n)) if n > 0 => { INBOUND.fetch_add(n as u64, Ordering::Relaxed); let _ = up.send(FromUpstream::Data { flow, bytes: buf[..n].to_vec() }); } _ => break, } } let _ = up.send(FromUpstream::Eof { flow }); }); flows.insert(flow, tx); } ToUpstream::Data { flow, bytes } => { if let Some(tx) = flows.get_mut(&flow) { if tx.write_all(&bytes).await.is_err() { flows.remove(&flow); } } } ToUpstream::Eof { flow } => { if let Some(mut tx) = flows.remove(&flow) { let _ = tx.finish(); } } } } for (f, _) in flows.drain() { let _ = up_tx.send(FromUpstream::Eof { flow: f }); } }); }

        { let c = client.clone(); let ft = flow_table.clone(); let tw = tun_writer.clone(); tokio::spawn(async move { loop { let dg = match c.connection().read_datagram().await { Ok(d) => d, Err(_) => return }; let (hdr, off) = match UdpRelayHeader::decode(&dg) { Ok(v) => v, Err(_) => continue }; let peer = match hdr.dst_ip { IpAddr::V4(ip) => Some((ip.octets(), hdr.dst_port)), _ => None }; let mut t = ft.lock().unwrap(); if let Some((sip, rp, lip)) = resolve_reply(&mut t, hdr.src_port, peer) { let pkt = build_udp((sip, rp), (lip, hdr.src_port), &dg[off..]); drop(t); if let Some(ref s) = *tw.lock().unwrap() { let _ = s.send(&pkt); INBOUND.fetch_add((dg.len()-off) as u64, Ordering::Relaxed); } } else { UNDELIVERED.fetch_add(1, Ordering::Relaxed); } } }); }

        let running = Arc::new(AtomicBool::new(true));
        { let r = running.clone(); ctrlc::set_handler(move || { r.store(false, Ordering::SeqCst); })?; }

        let reader = session.receive_reader()?;
        let sender = session.get_sender()?;
        *tun_writer.lock().unwrap() = Some(sender);
        log::info!("Tunnel active. Ctrl+C to stop.");

        { let r2 = running.clone(); let ft2 = flow_table.clone(); tokio::spawn(async move { let mut iv = tokio::time::interval(Duration::from_secs(5)); loop { if !r2.load(Ordering::SeqCst) { break; } iv.tick().await; let t = ft2.lock().unwrap(); log::info!("tun={} udp={} tcp={} in={} undelivered={} flows={}", TUN_PKTS.load(Ordering::Relaxed), UDP_FWD.load(Ordering::Relaxed), TCP_TERM.load(Ordering::Relaxed), INBOUND.load(Ordering::Relaxed), UNDELIVERED.load(Ordering::Relaxed), t.flows.len()); } }); }

        let mut buf = vec![0u8; cli.mtu as usize];
        while running.load(Ordering::SeqCst) {
            match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    TUN_PKTS.fetch_add(1, Ordering::Relaxed);
                    forward(&buf[..n], &client, &mut flow_table.lock().unwrap(), &pkt_in_tx);
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => { std::thread::sleep(Duration::from_millis(10)); continue; }
                Err(_) => break,
            }
        }
        log::info!("Shutting down...");
        client.shutdown();
        Ok(())
    }

    fn forward(pkt: &[u8], client: &Arc<TunnelClient>, t: &mut FlowTable, tx: &std::sync::mpsc::Sender<Vec<u8>>) {
        if pkt.len() < 20 || pkt[0] >> 4 != 4 { return; }
        let ihl = ((pkt[0] & 0x0F) as usize) * 4;
        if ihl < 20 || pkt.len() < ihl { return; }
        let end = (u16::from_be_bytes([pkt[2], pkt[3]]) as usize).min(pkt.len());
        let proto = pkt[9];
        if proto == 6 { let _ = tx.send(pkt[..end].to_vec()); TCP_TERM.fetch_add(1, Ordering::Relaxed); return; }
        if proto != 17 || end < ihl + 8 { return; }
        let sip = Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]);
        let dip = Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
        let sp = u16::from_be_bytes([pkt[ihl], pkt[ihl+1]]);
        let dp = u16::from_be_bytes([pkt[ihl+2], pkt[ihl+3]]);
        let decl = u16::from_be_bytes([pkt[ihl+4], pkt[ihl+5]]) as usize;
        let pe = if decl >= 8 && ihl+decl <= end { ihl+decl } else { end };
        if pe < ihl + 8 { return; }
        let payload = &pkt[ihl+8..pe];
        note_flow(t, sp, sip.octets(), dip.octets(), dp);
        let hdr = UdpRelayHeader { session_id: client.session_id() as u32, src_port: sp, dst_ip: IpAddr::V4(dip), dst_port: dp };
        let mut frame = hdr.encode(0); frame.extend_from_slice(payload);
        if let Err(_) = client.connection().send_datagram(frame.into()) {
            let c = client.clone(); let p = payload.to_vec(); let dst = SocketAddr::new(IpAddr::V4(dip), dp);
            tokio::spawn(async move { let _ = c.udp_exchange_via_stream(sp, dst, &p).await; });
        }
        UDP_FWD.fetch_add(1, Ordering::Relaxed);
    }
}

#[cfg(target_os = "windows")]
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    windows_main::run().await
}
