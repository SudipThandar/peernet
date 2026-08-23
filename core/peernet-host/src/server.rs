//! PNTP QUIC host server (Milestone 4).
//!
//! Binds a `quinn` endpoint with:
//! - ALPN `pn/1` (mismatched connections rejected by TLS)
//! - self-signed certificate; clients pin its SHA-256 fingerprint
//! - idle timeout 90s, keep-alive 20s, 64KB datagram buffers (spec §9.3)
//!
//! Per connection: one control stream (Hello/KeepAlive/Stats/Bye), an
//! unreliable-datagram echo path (replaced by real relays in Milestone 5),
//! and session bookkeeping through [`SessionManager`].

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use peernet_core::cert::{generate_self_signed, HostIdentity};
use peernet_core::{SessionId, TunnelStats};
use peernet_proto::{
    read_frame, write_frame, MessageKind, PeerMessage, TcpRelayHeader,
    ALPN, DATAGRAM_BUFFER_BYTES, IDLE_TIMEOUT_SECS, KEEPALIVE_INTERVAL_SECS,
    TCP_CONNECT_TIMEOUT_SECS, TCP_IDLE_TIMEOUT_SECS,
};
use quinn::{Connection, Endpoint};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::watch;

use crate::{SessionManager, UdpNat};

#[derive(Clone)]
pub struct HostServer {
    endpoint: Endpoint,
    local_addr: SocketAddr,
    certificate: peernet_core::cert::Certificate,
    fingerprint_hex: String,
    sessions: Arc<SessionManager>,
    stats: Arc<TunnelStats>,
    nat: Arc<UdpNat>,
    /// Ports with an active UDP reply-pump task (prevents duplicate pumps).
    udp_readers: Arc<Mutex<HashMap<u16, ()>>>,
    shutdown_tx: watch::Sender<bool>,
}

impl HostServer {
    /// Binds on `addr`. Use port 0 to let the OS pick (loopback tests).
    pub fn bind(addr: SocketAddr, device_name: &str) -> Result<Self, String> {
        let HostIdentity { certificate, private_key, fingerprint_hex } =
            generate_self_signed(device_name)?;

        // Install the ring crypto provider once per process.
        let _ = rustls::crypto::ring::default_provider().install_default();

        let mut tls = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                vec![certificate.clone()],
                rustls::pki_types::PrivateKeyDer::Pkcs8(private_key),
            )
            .map_err(|e| format!("tls cert setup failed: {e}"))?;
        tls.alpn_protocols = vec![ALPN.to_vec()];
        tls.max_early_data_size = u32::MAX; // 0-RTT tolerant

        let quic_config = quinn::crypto::rustls::QuicServerConfig::try_from(Arc::new(tls))
            .map_err(|e| format!("quinn server config failed: {e}"))?;
        let mut server_config = quinn::ServerConfig::with_crypto(Arc::new(quic_config));

        let mut transport = quinn::TransportConfig::default();
        transport.keep_alive_interval(Some(Duration::from_secs(KEEPALIVE_INTERVAL_SECS)));
        transport.max_idle_timeout(Some(
            quinn::IdleTimeout::try_from(Duration::from_secs(IDLE_TIMEOUT_SECS))
                .map_err(|e| format!("idle timeout invalid: {e}"))?,
        ));
        transport.datagram_receive_buffer_size(Some(DATAGRAM_BUFFER_BYTES));
        transport.datagram_send_buffer_size(DATAGRAM_BUFFER_BYTES);
        server_config.transport_config(Arc::new(transport));

        let endpoint =
            Endpoint::server(server_config, addr).map_err(|e| format!("bind failed: {e}"))?;
        let local_addr = endpoint.local_addr().map_err(|e| e.to_string())?;

        let (shutdown_tx, _) = watch::channel(false);

        Ok(Self {
            endpoint,
            local_addr,
            certificate,
            fingerprint_hex,
            sessions: Arc::new(SessionManager::new()),
            stats: Arc::new(TunnelStats::default()),
            nat: Arc::new(UdpNat::new()),
            udp_readers: Arc::new(Mutex::new(HashMap::new())),
            shutdown_tx,
        })
    }

    /// Points the DNS redirect at a custom upstream resolver.
    pub fn set_dns_upstream(&self, addr: SocketAddr) {
        self.nat.set_dns_upstream(addr);
    }

    pub fn nat(&self) -> Arc<UdpNat> {
        self.nat.clone()
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// SHA-256 (hex) of this host's DER certificate — goes into QR/TXT records.
    pub fn fingerprint_hex(&self) -> &str {
        &self.fingerprint_hex
    }

    pub fn certificate_der(&self) -> &[u8] {
        self.certificate.as_ref()
    }

    pub fn sessions(&self) -> Arc<SessionManager> {
        self.sessions.clone()
    }

    pub fn stats_snapshot(&self) -> peernet_core::StatsSnapshot {
        self.stats.snapshot()
    }

    pub fn session_count(&self) -> usize {
        self.sessions.count()
    }

    /// Signals the accept loop and all session tasks to wind down.
    pub fn shutdown(&self) {
        let _ = self.shutdown_tx.send(true);
    }

    /// Accept loop. Runs until [`shutdown`](Self::shutdown) is called.
    pub async fn run(&self) {
        let endpoint = self.endpoint.clone();
        let mut shutdown_rx = self.shutdown_tx.subscribe();
        loop {
            tokio::select! {
                _ = shutdown_rx.changed() => break,
                incoming = self.endpoint.accept() => {
                    match incoming {
                        Some(incoming) => {
                            let sessions = self.sessions.clone();
                            let stats = self.stats.clone();
                            let nat = self.nat.clone();
                            let udp_readers = self.udp_readers.clone();
                            let shutdown = shutdown_rx.clone();
                            tokio::spawn(async move {
                                match incoming.accept() {
                                    Ok(connecting) => match connecting.await {
                                        Ok(conn) => {
                                            handle_connection(
                                                conn,
                                                sessions,
                                                stats,
                                                nat,
                                                udp_readers,
                                                shutdown,
                                            )
                                            .await
                                        }
                                        Err(_) => {}
                                    },
                                    Err(_) => {}
                                }
                            });
                        }
                        None => break,
                    }
                }
            }
        }
    }
}

async fn handle_connection(
    conn: Connection,
    sessions: Arc<SessionManager>,
    stats: Arc<TunnelStats>,
    nat: Arc<UdpNat>,
    udp_readers: Arc<Mutex<HashMap<u16, ()>>>,
    mut shutdown: watch::Receiver<bool>,
) {
    // Unreliable datagrams = UDP relay path (spec Section 9.7).
    {
        let conn = conn.clone();
        let stats = stats.clone();
        let nat = nat.clone();
        let udp_readers = udp_readers.clone();
        let mut shutdown = shutdown.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = shutdown.changed() => break,
                    dgram = conn.read_datagram() => match dgram {
                        Ok(data) => {
                            let (hdr, start) = match peernet_proto::UdpRelayHeader::decode(&data) {
                                Ok(x) => x,
                                Err(_) => continue, // not a relay datagram; ignore
                            };
                            let payload = &data[start..];
                            stats.record_down(payload.len() as u64);

                            let dst = SocketAddr::new(hdr.dst_ip, hdr.dst_port);
                            let dst = nat.resolve_dst(dst);
                            let socket = match nat.get_or_create(hdr.src_port).await {
                                Ok(s) => s,
                                Err(_) => continue,
                            };
                            if socket.send_to(payload, dst).await.is_err() {
                                continue;
                            }

                            // Spawn one reply pump per outbound local port.
                            let local_port = match socket.local_addr() {
                                Ok(a) => a.port(),
                                Err(_) => continue,
                            };
                            let is_new = udp_readers
                                .lock()
                                .unwrap_or_else(|p| p.into_inner())
                                .insert(local_port, ())
                                .is_none();
                            if is_new {
                                let conn = conn.clone();
                                let stats = stats.clone();
                                tokio::spawn(pump_udp_replies(socket, conn, stats));
                            }
                        }
                        Err(_) => break,
                    }
                }
            }
        });
    }

    // Every bidirectional stream is serviced independently: the client opens
    // a fresh stream per request (control, data, stats, heartbeats), so the
    // server must keep accepting them for the life of the connection.
    let session_id: Arc<Mutex<u64>> = Arc::new(Mutex::new(0));

    loop {
        let streams = tokio::select! {
            _ = shutdown.changed() => break,
            streams = conn.accept_bi() => match streams {
                Ok(s) => s,
                Err(_) => break,
            },
        };
        let (tx, rx) = streams;
        tokio::spawn(service_stream(
            tx,
            rx,
            conn.clone(),
            sessions.clone(),
            stats.clone(),
            session_id.clone(),
            shutdown.clone(),
        ));
    }

    let id = *session_id.lock().unwrap_or_else(|p| p.into_inner());
    if id != 0 {
        sessions.disconnect(id);
    }
}

#[allow(clippy::too_many_arguments)]
async fn service_stream(
    mut tx: quinn::SendStream,
    mut rx: quinn::RecvStream,
    _conn: Connection,
    sessions: Arc<SessionManager>,
    stats: Arc<TunnelStats>,
    session_id: Arc<Mutex<u64>>,
    mut shutdown: watch::Receiver<bool>,
) {
    loop {
        let frame = tokio::select! {
            _ = shutdown.changed() => return,
            frame = read_frame(&mut rx) => match frame {
                Ok(msg) => msg,
                Err(_) => return,
            },
        };

        stats.record_up(frame.payload.len() as u64);

        // TCP relay: first frame on a stream carries a PN TCP header; the
        // rest of the stream is raw payload piped to the real destination.
        if frame.kind == MessageKind::Data {
            if let Ok((hdr, _start)) = TcpRelayHeader::decode(&frame.payload) {
                tcp_relay(tx, rx, hdr, stats).await;
                return;
            }
        }

        match frame.kind {
            MessageKind::Hello => {
                let assigned = {
                    let mut guard = session_id.lock().unwrap_or_else(|p| p.into_inner());
                    if *guard == 0 {
                        *guard = SessionId::generate().as_u128() as u64;
                        let name = String::from_utf8_lossy(&frame.payload).to_string();
                        sessions.register(*guard, name, unix_now());
                        stats.session_started();
                    }
                    *guard
                };
                let ack = PeerMessage::new(MessageKind::HelloAck, assigned, Vec::new());
                if write_frame(&mut tx, &ack).await.is_err() {
                    return;
                }
            }
            MessageKind::KeepAlive => {
                let ack = PeerMessage::new(MessageKind::KeepAlive, frame.session_id, Vec::new());
                if write_frame(&mut tx, &ack).await.is_err() {
                    return;
                }
            }
            MessageKind::StatsRequest => {
                let snapshot = stats.snapshot();
                let payload = bincode::serialize(&snapshot).unwrap_or_default();
                let reply = PeerMessage::new(MessageKind::StatsResponse, frame.session_id, payload);
                if write_frame(&mut tx, &reply).await.is_err() {
                    return;
                }
            }
            MessageKind::Data => {
                stats.record_down(frame.payload.len() as u64);
                // Echo for the loopback harness; replaced by real relays in M5.
                let echo = PeerMessage::new(MessageKind::Data, frame.session_id, frame.payload);
                if write_frame(&mut tx, &echo).await.is_err() {
                    return;
                }
            }
            MessageKind::Bye => return,
            MessageKind::HelloAck | MessageKind::StatsResponse => {} // never sent by clients
        }
    }
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

async fn tcp_relay(
    mut tx: quinn::SendStream,
    mut rx: quinn::RecvStream,
    hdr: TcpRelayHeader,
    stats: Arc<TunnelStats>,
) {
    let addr = SocketAddr::new(hdr.dst_ip, hdr.dst_port);
    let tcp = match tokio::time::timeout(
        Duration::from_secs(TCP_CONNECT_TIMEOUT_SECS),
        tokio::net::TcpStream::connect(addr),
    )
    .await
    {
        Ok(Ok(s)) => s,
        // Connect failed/timed out: closing the stream signals the client.
        _ => return,
    };
    let (mut tcp_r, mut tcp_w) = tokio::io::split(tcp);

    let up = tokio::spawn(async move {
        let mut buf = vec![0u8; 8192];
        loop {
            // quinn RecvStream::read yields Option<usize> (None = FIN).
            match tokio::time::timeout(Duration::from_secs(TCP_IDLE_TIMEOUT_SECS), rx.read(&mut buf)).await {
                Ok(Ok(Some(n))) => {
                    stats.record_down(n as u64);
                    if tcp_w.write_all(&buf[..n]).await.is_err() {
                        break;
                    }
                }
                Ok(Ok(None)) | Ok(Err(_)) | Err(_) => break,
            }
        }
        let _ = tcp_w.shutdown().await;
    });

    let down = tokio::spawn(async move {
        let mut buf = vec![0u8; 8192];
        loop {
            match tokio::time::timeout(Duration::from_secs(TCP_IDLE_TIMEOUT_SECS), tcp_r.read(&mut buf)).await {
                Ok(Ok(0)) | Err(_) | Ok(Err(_)) => break,
                Ok(Ok(n)) => {
                    stats.record_up(n as u64);
                    if tx.write_all(&buf[..n]).await.is_err() {
                        break;
                    }
                }
            }
        }
        let _ = tx.shutdown().await;
    });

    let _ = up.await;
    let _ = down.await;
}

/// Forwards UDP responses arriving on a NAT socket back to the client as
/// relay datagrams, preserving the outbound source port (spec 12.6).
async fn pump_udp_replies(
    socket: Arc<tokio::net::UdpSocket>,
    conn: Connection,
    stats: Arc<TunnelStats>,
) {
    let mut buf = vec![0u8; 65536];
    loop {
        let (n, peer) = match socket.recv_from(&mut buf).await {
            Ok(x) => x,
            Err(_) => break,
        };
        stats.record_down(n as u64);
        let src_port = socket.local_addr().map(|a| a.port()).unwrap_or(0);
        let hdr = peernet_proto::UdpRelayHeader {
            session_id: 0,
            src_port,
            dst_ip: peer.ip(),
            dst_port: peer.port(),
        };
        let mut frame = hdr.encode(0);
        frame.extend_from_slice(&buf[..n]);
        if conn.send_datagram(frame.into()).is_err() {
            break;
        }
    }
}
