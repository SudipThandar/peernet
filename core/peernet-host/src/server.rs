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
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use peernet_core::cert::{generate_self_signed, HostIdentity};
use peernet_core::{SessionId, TunnelStats};
use peernet_proto::{
    read_frame, write_frame, MessageKind, PeerMessage,
    ALPN, DATAGRAM_BUFFER_BYTES, IDLE_TIMEOUT_SECS, KEEPALIVE_INTERVAL_SECS,
};
use quinn::{Connection, Endpoint};
use tokio::sync::watch;

use crate::SessionManager;

pub struct HostServer {
    endpoint: Endpoint,
    local_addr: SocketAddr,
    identity: HostIdentity,
    sessions: Arc<SessionManager>,
    stats: Arc<TunnelStats>,
    shutdown_tx: watch::Sender<bool>,
}

impl HostServer {
    /// Binds on `addr`. Use port 0 to let the OS pick (loopback tests).
    pub fn bind(addr: SocketAddr, device_name: &str) -> Result<Self, String> {
        let identity = generate_self_signed(device_name)?;

        // Install the ring crypto provider once per process.
        let _ = rustls::crypto::ring::default_provider().install_default();

        let mut tls = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![identity.certificate.clone()], identity.private_key.clone())
            .map_err(|e| format!("tls cert setup failed: {e}"))?;
        tls.alpn_protocols = vec![ALPN.to_vec()];
        tls.max_early_data_size = u32::MAX; // 0-RTT tolerant

        let quic_config = quinn::crypto::rustls::QuicServerConfig::try_from(Arc::new(tls))
            .map_err(|e| format!("quinn server config failed: {e}"))?;
        let mut server_config = quinn::ServerConfig::with_crypto(Arc::new(quic_config));

        let mut transport = quinn::TransportConfig::default();
        transport.keep_alive_interval(Some(Duration::from_secs(KEEPALIVE_INTERVAL_SECS)));
        transport.max_idle_timeout(Some(quinn::IdleTimeout::from_fixed(Duration::from_secs(
            IDLE_TIMEOUT_SECS,
        ))));
        transport.datagram_receive_buffer_size(Some(DATAGRAM_BUFFER_BYTES));
        transport.datagram_send_buffer_size(Some(DATAGRAM_BUFFER_BYTES));
        server_config.transport_config(Arc::new(transport));

        let endpoint =
            Endpoint::server(server_config, addr).map_err(|e| format!("bind failed: {e}"))?;
        let local_addr = endpoint.local_addr().map_err(|e| e.to_string())?;

        let (shutdown_tx, _) = watch::channel(false);

        Ok(Self {
            endpoint,
            local_addr,
            identity,
            sessions: Arc::new(SessionManager::new()),
            stats: Arc::new(TunnelStats::default()),
            shutdown_tx,
        })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// SHA-256 (hex) of this host's DER certificate — goes into QR/TXT records.
    pub fn fingerprint_hex(&self) -> &str {
        &self.identity.fingerprint_hex
    }

    pub fn certificate_der(&self) -> &[u8] {
        self.identity.certificate.as_ref()
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
        let mut shutdown_rx = self.shutdown_tx.subscribe();
        loop {
            tokio::select! {
                _ = shutdown_rx.changed() => break,
                incoming = self.endpoint.accept() => {
                    match incoming {
                        Some(incoming) => {
                            let sessions = self.sessions.clone();
                            let stats = self.stats.clone();
                            let shutdown = shutdown_rx.clone();
                            tokio::spawn(async move {
                                match incoming.into_connection() {
                                    Ok(conn) => handle_connection(conn, sessions, stats, shutdown).await,
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
    mut shutdown: watch::Receiver<bool>,
) {
    // Unreliable-datagram echo (loopback data plane until M5 relays land).
    {
        let conn = conn.clone();
        let stats = stats.clone();
        let mut shutdown = shutdown.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = shutdown.changed() => break,
                    dgram = conn.read_datagram() => match dgram {
                        Ok(data) => {
                            stats.record_down(data.len() as u64);
                            stats.record_up(data.len() as u64);
                            if conn.send_datagram(data).is_err() {
                                break;
                            }
                        }
                        Err(_) => break,
                    }
                }
            }
        });
    }

    // Control stream: the first bidirectional stream a client opens.
    let (mut tx, mut rx) = match conn.accept_bi().await {
        Ok(streams) => streams,
        Err(_) => return,
    };

    let mut session_id: u64 = 0;
    let mut last_seen: HashMap<u64, Instant> = HashMap::new();

    loop {
        let frame = tokio::select! {
            _ = shutdown.changed() => break,
            frame = read_frame(&mut rx) => match frame {
                Ok(msg) => msg,
                Err(_) => break,
            },
        };

        stats.record_up(frame.payload.len() as u64);

        match frame.kind {
            MessageKind::Hello => {
                if session_id == 0 {
                    session_id = SessionId::generate().as_u128() as u64;
                    let name = String::from_utf8_lossy(&frame.payload).to_string();
                    sessions.register(session_id, name, unix_now());
                    last_seen.insert(session_id, Instant::now());
                }
                let ack = PeerMessage::new(MessageKind::HelloAck, session_id, Vec::new());
                if write_frame(&mut tx, &ack).await.is_err() {
                    break;
                }
            }
            MessageKind::KeepAlive => {
                if session_id != 0 {
                    last_seen.insert(session_id, Instant::now());
                }
                let ack = PeerMessage::new(MessageKind::KeepAlive, frame.session_id, Vec::new());
                if write_frame(&mut tx, &ack).await.is_err() {
                    break;
                }
            }
            MessageKind::StatsRequest => {
                let snapshot = stats.snapshot();
                let payload = bincode::serialize(&snapshot).unwrap_or_default();
                let reply = PeerMessage::new(MessageKind::StatsResponse, frame.session_id, payload);
                if write_frame(&mut tx, &reply).await.is_err() {
                    break;
                }
            }
            MessageKind::Data => {
                stats.record_down(frame.payload.len() as u64);
                // Echo for the loopback harness; replaced by real relays in M5.
                let echo = PeerMessage::new(MessageKind::Data, frame.session_id, frame.payload);
                if write_frame(&mut tx, &echo).await.is_err() {
                    break;
                }
            }
            MessageKind::Bye => break,
            HelloAck | StatsResponse => {} // never sent by clients
        }
    }

    if session_id != 0 {
        sessions.disconnect(session_id);
    }
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
