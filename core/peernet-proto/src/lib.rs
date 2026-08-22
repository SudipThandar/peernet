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

use serde::{Deserialize, Serialize};
use thiserror::Error;

pub const MAGIC: [u8; 4] = *b"PNTP";
pub const PROTOCOL_VERSION: u8 = 1;
const HEADER_LEN: usize = 4 + 1 + 4;
/// Hard cap so a hostile peer cannot make us allocate gigabytes.
const MAX_BODY_LEN: u32 = 8 * 1024 * 1024;

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
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MessageKind {
    Hello,
    HelloAck,
    Data,
    KeepAlive,
    Bye,
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
}
