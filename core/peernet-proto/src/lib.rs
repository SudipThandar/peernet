//! PNTP wire protocol: message types, framing, and errors.
//!
//! Frame layout (all integers big-endian):
//!
//! ```text
//! offset  size  field
//! 0       4     magic "PNTP"
//! 4       1     protocol version (currently 1)
//! 5       4     body length N
//! 9       N     bincode-encoded PeerMessage body
//! ```
//!
//! Transport constants (Section 9 of the spec) live here so host and client
//! can never drift apart.

use serde::{Deserialize, Serialize};
use thiserror::Error;

pub const MAGIC: [u8; 4] = *b"PNTP";
pub const PROTOCOL_VERSION: u8 = 1;
const HEADER_LEN: usize = 4 + 1 + 4;
/// Hard cap so a hostile peer cannot make us allocate gigabytes.
const MAX_BODY_LEN: u32 = 8 * 1024 * 1024;

pub mod relay;

pub use relay::{
    TcpRelayHeader, UdpRelayHeader,
    FLAG_DNS, FLAG_IPV4, FLAG_IPV6, FLAG_RTC, FLAG_STUN,
    RELAY_MAGIC, RELAY_TYPE_TCP, RELAY_TYPE_UDP,
};

/// QUIC ALPN protocol id. Mismatched connections are rejected by TLS itself.
pub const ALPN: &[u8] = b"pn/1";

/// QUIC idle timeout (spec 17.5 / user constraint): aligned with UDP NAT.
pub const IDLE_TIMEOUT_SECS: u64 = 90;

/// QUIC keep-alive interval: keeps NAT mappings warm.
pub const KEEPALIVE_INTERVAL_SECS: u64 = 20;

/// Datagram buffer size on both endpoints (user constraint).
pub const DATAGRAM_BUFFER_BYTES: usize = 64 * 1024;

/// Relay timeouts (spec Sections 12.4/12.8).
pub const TCP_CONNECT_TIMEOUT_SECS: u64 = 10;
pub const TCP_IDLE_TIMEOUT_SECS: u64 = 120;
pub const UDP_NAT_IDLE_TIMEOUT_SECS: u64 = 90;
/// Max concurrent UDP mappings per client (spec 12.8).
pub const MAX_UDP_MAPPINGS_PER_CLIENT: usize = 512;

#[derive(Debug, Error)]
pub enum PntpError {
    #[error("invalid magic bytes")]
    InvalidMagic,
    #[error("unsupported protocol version {0}")]
    BadVersion(u8),
    #[error("frame truncated or body too large")]
    UnexpectedEof,
    #[error("body exceeds maximum size")]
    BodyTooLarge,
    #[error("encoding error: {0}")]
    Encoding(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MessageKind {
    Hello,
    HelloAck,
    Data,
    KeepAlive,
    Bye,
    StatsRequest,
    StatsResponse,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PeerMessage {
    pub kind: MessageKind,
    /// Tunnel session this message belongs to (0 = pre-session handshake).
    pub session_id: u64,
    pub payload: Vec<u8>,
}

impl PeerMessage {
    pub fn new(kind: MessageKind, session_id: u64, payload: Vec<u8>) -> Self {
        Self { kind, session_id, payload }
    }

    pub fn encode(&self) -> Result<Vec<u8>, PntpError> {
        let body = bincode::serialize(self).map_err(|e| PntpError::Encoding(e.to_string()))?;
        if body.len() as u64 > MAX_BODY_LEN as u64 {
            return Err(PntpError::BodyTooLarge);
        }
        let mut out = Vec::with_capacity(HEADER_LEN + body.len());
        out.extend_from_slice(&MAGIC);
        out.push(PROTOCOL_VERSION);
        out.extend_from_slice(&(body.len() as u32).to_be_bytes());
        out.extend_from_slice(&body);
        Ok(out)
    }
}

/// Decodes exactly one frame from `bytes`; trailing bytes are ignored.
pub fn decode(bytes: &[u8]) -> Result<PeerMessage, PntpError> {
    if bytes.len() < HEADER_LEN {
        return Err(PntpError::UnexpectedEof);
    }
    if bytes[0..4] != MAGIC {
        return Err(PntpError::InvalidMagic);
    }
    if bytes[4] != PROTOCOL_VERSION {
        return Err(PntpError::BadVersion(bytes[4]));
    }
    let len = u32::from_be_bytes([bytes[5], bytes[6], bytes[7], bytes[8]]) as usize;
    if len as u64 > MAX_BODY_LEN as u64 {
        return Err(PntpError::BodyTooLarge);
    }
    if bytes.len() < HEADER_LEN + len {
        return Err(PntpError::UnexpectedEof);
    }
    bincode::deserialize(&bytes[HEADER_LEN..HEADER_LEN + len])
        .map_err(|e| PntpError::Encoding(e.to_string()))
}

// ---------- Stream framing (tokio AsyncRead/AsyncWrite) ----------

use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Reads exactly one framed [`PeerMessage`] from an async stream.
pub async fn read_frame<R: tokio::io::AsyncRead + Unpin>(
    reader: &mut R,
) -> Result<PeerMessage, PntpError> {
    let mut header = [0u8; HEADER_LEN];
    read_exact_or_eof(reader, &mut header).await?;
    if header[0..4] != MAGIC {
        return Err(PntpError::InvalidMagic);
    }
    if header[4] != PROTOCOL_VERSION {
        return Err(PntpError::BadVersion(header[4]));
    }
    let len = u32::from_be_bytes([header[5], header[6], header[7], header[8]]) as usize;
    if len as u64 > MAX_BODY_LEN as u64 {
        return Err(PntpError::BodyTooLarge);
    }

    // EOF right at a frame boundary is a clean close, not an error.
    if len == 0 && peek_eof(reader).await? {
        return Err(PntpError::UnexpectedEof);
    }

    let mut body = vec![0u8; len];
    read_exact_or_eof(reader, &mut body).await?;

    bincode::deserialize(&body).map_err(|e| PntpError::Encoding(e.to_string()))
}

async fn read_exact_or_eof<R: tokio::io::AsyncRead + Unpin>(
    reader: &mut R,
    buf: &mut [u8],
) -> Result<(), PntpError> {
    match reader.read_exact(buf).await {
        Ok(_) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => Err(PntpError::UnexpectedEof),
        Err(e) => Err(e.into()),
    }
}

async fn peek_eof<R: tokio::io::AsyncRead + Unpin>(reader: &mut R) -> Result<bool, PntpError> {
    let mut probe = [0u8; 1];
    match reader.read(&mut probe).await {
        Ok(0) => Ok(true),
        Ok(_) => Ok(false),
        Err(e) => Err(e.into()),
    }
}

/// Writes one framed [`PeerMessage`] to an async stream.
pub async fn write_frame<W: tokio::io::AsyncWrite + Unpin>(
    writer: &mut W,
    msg: &PeerMessage,
) -> Result<(), PntpError> {
    let frame = msg.encode()?;
    writer.write_all(&frame).await?;
    writer.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_all_kinds() {
        for kind in [
            MessageKind::Hello,
            MessageKind::HelloAck,
            MessageKind::Data,
            MessageKind::KeepAlive,
            MessageKind::Bye,
            MessageKind::StatsRequest,
            MessageKind::StatsResponse,
        ] {
            let msg = PeerMessage::new(kind, 0xDEAD_BEEF, vec![1, 2, 3, 42]);
            let encoded = msg.encode().unwrap();
            let decoded = decode(&encoded).unwrap();
            assert_eq!(msg, decoded);
        }
    }

    #[test]
    fn empty_payload_roundtrip() {
        let msg = PeerMessage::new(MessageKind::KeepAlive, 7, Vec::new());
        assert_eq!(decode(&msg.encode().unwrap()).unwrap(), msg);
    }

    #[test]
    fn rejects_bad_magic() {
        let mut frame = PeerMessage::new(MessageKind::Hello, 0, b"x".to_vec())
            .encode()
            .unwrap();
        frame[0] = b'X';
        assert!(matches!(decode(&frame), Err(PntpError::InvalidMagic)));
    }

    #[test]
    fn rejects_bad_version() {
        let mut frame = PeerMessage::new(MessageKind::Hello, 0, b"x".to_vec())
            .encode()
            .unwrap();
        frame[4] = 99;
        assert!(matches!(decode(&frame), Err(PntpError::BadVersion(99))));
    }

    #[test]
    fn rejects_truncated_frame() {
        let frame = PeerMessage::new(MessageKind::Hello, 0, vec![0u8; 128])
            .encode()
            .unwrap();
        assert!(matches!(decode(&frame[..20]), Err(PntpError::UnexpectedEof)));
    }

    #[tokio::test]
    async fn stream_roundtrip() {
        use std::io::Cursor;

        let msg = PeerMessage::new(MessageKind::HelloAck, 12345, b"hello-client".to_vec());
        let mut buf = Vec::new();
        write_frame(&mut buf, &msg).await.unwrap();

        let mut cursor = Cursor::new(buf);
        let decoded = read_frame(&mut cursor).await.unwrap();
        assert_eq!(decoded, msg);
    }
}
