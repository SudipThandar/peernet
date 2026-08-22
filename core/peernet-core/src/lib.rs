//! Shared engine services: session identity, stats counters, config.

use std::fmt;
use std::sync::atomic::{AtomicU64, Ordering};

/// Random 128-bit tunnel session identifier.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct SessionId(u128);

impl SessionId {
    pub fn generate() -> Self {
        let mut bytes = [0u8; 16];
        getrandom::getrandom(&mut bytes).expect("system RNG unavailable");
        SessionId(u128::from_le_bytes(bytes))
    }

    pub fn from_u128(value: u128) -> Self {
        SessionId(value)
    }

    pub fn as_u128(&self) -> u128 {
        self.0
    }

    /// 32-char lowercase hex, used on the wire and in logs.
    pub fn to_hex(&self) -> String {
        format!("{:032x}", self.0)
    }
}

impl fmt::Display for SessionId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.to_hex())
    }
}

/// Snapshot of the tunnel counters at a point in time.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StatsSnapshot {
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub sessions_started: u64,
}

/// Lock-free traffic/session counters shared across threads.
#[derive(Default)]
pub struct TunnelStats {
    bytes_up: AtomicU64,
    bytes_down: AtomicU64,
    sessions_started: AtomicU64,
}

impl TunnelStats {
    pub fn record_up(&self, n: u64) {
        self.bytes_up.fetch_add(n, Ordering::Relaxed);
    }

    pub fn record_down(&self, n: u64) {
        self.bytes_down.fetch_add(n, Ordering::Relaxed);
    }

    pub fn session_started(&self) {
        self.sessions_started.fetch_add(1, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> StatsSnapshot {
        StatsSnapshot {
            bytes_up: self.bytes_up.load(Ordering::Relaxed),
            bytes_down: self.bytes_down.load(Ordering::Relaxed),
            sessions_started: self.sessions_started.load(Ordering::Relaxed),
        }
    }
}

/// Engine-wide tunables; Android side fills this from DataStore settings.
#[derive(Debug, Clone)]
pub struct AppConfig {
    pub device_name: String,
    pub mtu: u16,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self { device_name: "PeerNet".to_string(), mtu: 1400 }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_ids_are_unique() {
        let a = SessionId::generate();
        let b = SessionId::generate();
        assert_ne!(a, b);
        assert_eq!(a.to_hex().len(), 32);
        assert_eq!(a.as_u128(), SessionId::from_u128(a.as_u128()).as_u128());
    }

    #[test]
    fn hex_roundtrip() {
        let id = SessionId::from_u128(0xDEAD_BEEF);
        let hex = id.to_hex();
        let parsed = u128::from_str_radix(&hex, 16).unwrap();
        assert_eq!(SessionId::from_u128(parsed), id);
    }

    #[test]
    fn stats_accumulate() {
        let stats = TunnelStats::default();
        stats.record_up(100);
        stats.record_up(50);
        stats.record_down(10);
        stats.session_started();
        stats.session_started();
        let snap = stats.snapshot();
        assert_eq!(snap.bytes_up, 150);
        assert_eq!(snap.bytes_down, 10);
        assert_eq!(snap.sessions_started, 2);
    }
}
