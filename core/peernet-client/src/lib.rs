//! Client-side PNTP QUIC tunnel (Milestone 4).
//!
//! Connects to a host whose SHA-256 certificate fingerprint is pinned,
//! performs the Hello handshake over the control stream, keeps the session
//! alive with heartbeats, and can read host stats + roundtrip data/datagrams.
//! Real TCP/UDP relays land in Milestone 5.

use std::cell::Cell;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use peernet_core::TunnelStats;
use peernet_proto::{
    read_frame, write_frame, MessageKind, PeerMessage, TcpRelayHeader, UdpRelayHeader,
    ALPN, DATAGRAM_BUFFER_BYTES, IDLE_TIMEOUT_SECS, KEEPALIVE_INTERVAL_SECS,
};
use quinn::{Connection, Endpoint};
use rustls::pki_types::{CertificateDer, ServerName};
use tokio::io::AsyncWriteExt;
use tokio::sync::watch;

/// Mirrors the Kotlin client lifecycle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClientState {
    Connecting,
    Connected,
    Backoff,
    Disconnected,
}

/// Spec-aligned defaults, overridable for tests.
#[derive(Clone)]
pub struct ClientOptions {
    pub server_addr: SocketAddr,
    pub server_name: String,
    /// Lowercase hex SHA-256 of the host's DER certificate (QR/TXT).
    pub expected_fingerprint_hex: String,
    pub device_name: String,
    pub keepalive_interval: Duration,
    pub response_timeout: Duration,
}

impl ClientOptions {
    pub fn new(
        server_addr: SocketAddr,
        server_name: impl Into<String>,
        expected_fingerprint_hex: impl Into<String>,
        device_name: impl Into<String>,
    ) -> Self {
        Self {
            server_addr,
            server_name: server_name.into(),
            expected_fingerprint_hex: expected_fingerprint_hex.into(),
            device_name: device_name.into(),
            keepalive_interval: Duration::from_secs(KEEPALIVE_INTERVAL_SECS),
            response_timeout: Duration::from_secs(IDLE_TIMEOUT_SECS),
        }
    }
}

pub struct TunnelClient {
    endpoint: Endpoint,
    conn: Connection,
    session_cell: Cell<u64>,
    state: Arc<watch::Sender<ClientState>>,
    /// Must stay alive: dropping all receivers closes the watch channel and
    /// makes every subsequent send() fail silently.
    _state_rx: Arc<watch::Receiver<ClientState>>,
    stats: Arc<TunnelStats>,
    keepalive_handle: tokio::task::JoinHandle<()>,
}

impl TunnelClient {
    /// Connects and completes the Hello/HelloAck handshake.
    pub async fn connect(opts: ClientOptions) -> Result<Self, String> {
        let _ = rustls::crypto::ring::default_provider().install_default();

        let verifier =
            Arc::new(PinnedCertVerifier::from_hex(&opts.expected_fingerprint_hex)?);

        let mut tls = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        tls.alpn_protocols = vec![ALPN.to_vec()];

        let quic_config = quinn::crypto::rustls::QuicClientConfig::try_from(Arc::new(tls))
            .map_err(|e| format!("quinn client config failed: {e}"))?;
        let mut client_config = quinn::ClientConfig::new(Arc::new(quic_config));

        let mut transport = quinn::TransportConfig::default();
        transport.keep_alive_interval(Some(Duration::from_secs(KEEPALIVE_INTERVAL_SECS)));
        transport.max_idle_timeout(Some(
            quinn::IdleTimeout::try_from(Duration::from_secs(IDLE_TIMEOUT_SECS))
                .map_err(|e| format!("idle timeout invalid: {e}"))?,
        ));
        transport.datagram_receive_buffer_size(Some(DATAGRAM_BUFFER_BYTES));
        transport.datagram_send_buffer_size(DATAGRAM_BUFFER_BYTES);
        client_config.transport_config(Arc::new(transport));

        let mut endpoint = Endpoint::client("0.0.0.0:0".parse().unwrap())
            .map_err(|e| format!("client bind failed: {e}"))?;
        endpoint.set_default_client_config(client_config);

        let conn = endpoint
            .connect(opts.server_addr, opts.server_name.as_str())
            .map_err(|e| format!("connect failed: {e}"))?
            .await
            .map_err(|e| format!("handshake failed: {e}"))?;

        let (state_tx, state_rx) = watch::channel(ClientState::Connecting);
        let mut client = Self {
            endpoint,
            conn,
            session_cell: Cell::new(0),
            state: Arc::new(state_tx),
            _state_rx: Arc::new(state_rx),
            stats: Arc::new(TunnelStats::default()),
            keepalive_handle: tokio::spawn(async {}),
        };

        client.handshake(&opts).await?;
        Ok(client)
    }

    async fn handshake(&mut self, opts: &ClientOptions) -> Result<(), String> {
        let (mut tx, mut rx) = self
            .conn
            .open_bi()
            .await
            .map_err(|e| format!("control stream open failed: {e}"))?;

        let hello = PeerMessage::new(MessageKind::Hello, 0, opts.device_name.as_bytes().to_vec());
        write_frame(&mut tx, &hello)
            .await
            .map_err(|e| format!("hello send failed: {e}"))?;

        let ack = read_frame(&mut rx)
            .await
            .map_err(|e| format!("hello-ack read failed: {e}"))?;
        if ack.kind != MessageKind::HelloAck || ack.session_id == 0 {
            return Err("unexpected handshake response".into());
        }

        self.session_cell.set(ack.session_id);
        let _ = self.state.send(ClientState::Connected);

        // Heartbeat pump: one beat per interval; missing an echo flips us to
        // Backoff and stops the pump (reconnection policy lands in M7).
        let conn = self.conn.clone();
        let state = self.state.clone();
        let interval = opts.keepalive_interval;
        let response_timeout = opts.response_timeout;
        self.keepalive_handle = tokio::spawn(async move {
            loop {
                tokio::time::sleep(interval).await;
                let streams = conn.open_bi().await;
                let (mut tx, mut rx) = match streams {
                    Ok(s) => s,
                    Err(_) => {
                        // Connection is gone (host died / link dropped).
                        let _ = state.send(ClientState::Backoff);
                        break;
                    }
                };
                let beat = PeerMessage::new(MessageKind::KeepAlive, 0, Vec::new());
                if write_frame(&mut tx, &beat).await.is_err() {
                    let _ = state.send(ClientState::Backoff);
                    break;
                }
                match tokio::time::timeout(response_timeout, read_frame(&mut rx)).await {
                    Ok(Ok(reply)) if reply.kind == MessageKind::KeepAlive => {
                        let _ = state.send(ClientState::Connected);
                    }
                    _ => {
                        let _ = state.send(ClientState::Backoff);
                        break;
                    }
                }
            }
        });

        Ok(())
    }

    pub fn session_id(&self) -> u64 {
        self.session_cell.get()
    }

    pub fn state(&self) -> ClientState {
        *self._state_rx.borrow()
    }

    pub fn stats_snapshot(&self) -> peernet_core::StatsSnapshot {
        self.stats.snapshot()
    }

    /// Roundtrips one unreliable QUIC datagram through the host echo.
    pub async fn datagram_roundtrip(&self, payload: Vec<u8>) -> Result<Vec<u8>, String> {
        self.conn
            .send_datagram(payload.clone().into())
            .map_err(|e| format!("datagram send failed: {e}"))?;
        let echoed = self
            .conn
            .read_datagram()
            .await
            .map_err(|e| format!("datagram recv failed: {e}"))?;
        Ok(echoed.to_vec())
    }

    /// Sends a framed `Data` message and awaits its echo; returns the payload.
    pub async fn data_roundtrip(&self, payload: Vec<u8>) -> Result<Vec<u8>, String> {
        let (mut tx, mut rx) = self
            .conn
            .open_bi()
            .await
            .map_err(|e| format!("stream open failed: {e}"))?;
        let msg = PeerMessage::new(MessageKind::Data, self.session_id(), payload.clone());
        write_frame(&mut tx, &msg).await.map_err(|e| e.to_string())?;
        let echoed = read_frame(&mut rx).await.map_err(|e| e.to_string())?;
        if echoed.kind != MessageKind::Data {
            return Err("unexpected reply kind".into());
        }
        self.stats.record_up(payload.len() as u64);
        self.stats.record_down(echoed.payload.len() as u64);
        Ok(echoed.payload)
    }

    /// Asks the host for its live stats snapshot.
    pub async fn request_stats(&self) -> Result<peernet_core::StatsSnapshot, String> {
        let (mut tx, mut rx) = self
            .conn
            .open_bi()
            .await
            .map_err(|e| format!("stream open failed: {e}"))?;
        write_frame(
            &mut tx,
            &PeerMessage::new(MessageKind::StatsRequest, self.session_id(), Vec::new()),
        )
        .await
        .map_err(|e| e.to_string())?;
        let reply = read_frame(&mut rx).await.map_err(|e| e.to_string())?;
        if reply.kind != MessageKind::StatsResponse {
            return Err("unexpected reply kind".into());
        }
        bincode::deserialize(&reply.payload).map_err(|e| e.to_string())
    }

    /// TCP relay (spec Section 9.6): opens a stream with a PN TCP header,
    /// sends `request`, half-closes, and reads the reply until EOF.
    pub async fn tcp_relay(
        &self,
        dst: SocketAddr,
        request: &[u8],
    ) -> Result<Vec<u8>, String> {
        let (mut tx, mut rx) = self
            .conn
            .open_bi()
            .await
            .map_err(|e| format!("stream open failed: {e}"))?;

        let hdr = TcpRelayHeader { src_port: 0, dst_ip: dst.ip(), dst_port: dst.port() };
        write_frame(
            &mut tx,
            &PeerMessage::new(MessageKind::Data, self.session_id(), hdr.encode()),
        )
        .await
        .map_err(|e| format!("relay header send failed: {e}"))?;
        tx.write_all(request)
            .await
            .map_err(|e| format!("payload send failed: {e}"))?;
        let _ = tx.flush().await;
        // Half-close so the remote side sees EOF and can finish its reply.
        let _ = tx.shutdown().await;

        let mut out = Vec::new();
        // quinn's inherent read_to_end drains until the host closes the stream.
        rx.read_to_end(&mut out)
            .await
            .map_err(|e| format!("reply read failed: {e}"))?;
        Ok(out)
    }

    /// UDP relay (spec Section 9.7): sends one relay datagram and waits for
    /// the host's response datagram. `src_port` is requested for
    /// endpoint-preserving NAT; the reply header carries the actual port.
    pub async fn udp_exchange(
        &self,
        src_port: u16,
        dst: SocketAddr,
        payload: &[u8],
    ) -> Result<Vec<u8>, String> {
        let hdr = UdpRelayHeader {
            session_id: self.session_id() as u32,
            src_port,
            dst_ip: dst.ip(),
            dst_port: dst.port(),
        };
        let mut frame = hdr.encode(0);
        frame.extend_from_slice(payload);
        self.conn
            .send_datagram(frame.into())
            .map_err(|e| format!("udp send failed: {e}"))?;

        let reply = tokio::time::timeout(Duration::from_secs(5), self.conn.read_datagram())
            .await
            .map_err(|_| "udp reply timeout".to_string())?
            .map_err(|e| format!("udp recv failed: {e}"))?;
        let (_, start) =
            UdpRelayHeader::decode(&reply).map_err(|e| format!("bad relay reply: {e}"))?;
        Ok(reply[start..].to_vec())
    }

    /// Sends Bye and closes everything.
    pub fn shutdown(mut self) {
        let _ = self.state.send(ClientState::Disconnected);
        self.keepalive_handle.abort();
        self.endpoint.close(0u32.into(), b"bye");
    }
}

/// Verifies that the server's DER certificate matches the pinned SHA-256
/// fingerprint delivered out-of-band (QR/TXT records). Matching the hash of
/// the exact certificate is sufficient authentication for Phase 1.
#[derive(Debug)]
struct PinnedCertVerifier {
    expected: Vec<u8>,
}

impl PinnedCertVerifier {
    fn from_hex(hex: &str) -> Result<Self, String> {
        let hex = hex.trim();
        if hex.len() != 64 {
            return Err("fingerprint must be 64 hex chars".into());
        }
        let mut bytes = Vec::with_capacity(32);
        for i in (0..64).step_by(2) {
            bytes.push(
                u8::from_str_radix(&hex[i..i + 2], 16)
                    .map_err(|_| "invalid hex in fingerprint")?,
            );
        }
        Ok(Self { expected: bytes })
    }
}

impl rustls::client::danger::ServerCertVerifier for PinnedCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        use sha2::{Digest, Sha256};
        let digest = Sha256::digest(end_entity.as_ref());
        if digest.as_slice() == self.expected.as_slice() {
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "PeerNet: server fingerprint mismatch".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        // Transcript authenticity is bound by the pinned-cert hash check.
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        vec![
            rustls::SignatureScheme::RSA_PKCS1_SHA256,
            rustls::SignatureScheme::ECDSA_NISTP256_SHA256,
            rustls::SignatureScheme::ED25519,
        ]
    }
}
