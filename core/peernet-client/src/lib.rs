//! Client-side connection state machine and reconnect policy.
//!
//! Pure logic — the QUIC transport (later milestone) drives these states.

/// Mirrors the Kotlin `ClientStatus` lifecycle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    Disconnected,
    Discovering,
    Connecting,
    Connected,
    Backoff,
}

impl Default for ConnectionState {
    fn default() -> Self {
        ConnectionState::Disconnected
    }
}

/// Exponential backoff with cap: base * 2^attempt, clamped to `max_ms`.
#[derive(Debug, Clone)]
pub struct ReconnectPolicy {
    base_ms: u64,
    max_ms: u64,
    attempt: u32,
}

impl ReconnectPolicy {
    pub fn new(base_ms: u64, max_ms: u64) -> Self {
        ReconnectPolicy { base_ms: base_ms.max(1), max_ms: max_ms.max(1), attempt: 0 }
    }

    /// Returns the next wait and advances the attempt counter.
    pub fn next_delay(&mut self) -> u64 {
        let exp = self.attempt.min(20); // saturate shift to stay sane
        let delay = self
            .base_ms
            .saturating_mul(1u64 << exp)
            .min(self.max_ms);
        self.attempt = self.attempt.saturating_add(1);
        delay
    }

    /// Successful connection: back to immediate retries.
    pub fn reset(&mut self) {
        self.attempt = 0;
    }

    pub fn attempt(&self) -> u32 {
        self.attempt
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_doubles_and_caps() {
        let mut policy = ReconnectPolicy::new(500, 4000);
        assert_eq!(policy.next_delay(), 500);
        assert_eq!(policy.next_delay(), 1000);
        assert_eq!(policy.next_delay(), 2000);
        assert_eq!(policy.next_delay(), 4000);
        assert_eq!(policy.next_delay(), 4000); // capped forever after
    }

    #[test]
    fn reset_restores_base() {
        let mut policy = ReconnectPolicy::new(250, 10_000);
        policy.next_delay();
        policy.next_delay();
        policy.reset();
        assert_eq!(policy.attempt(), 0);
        assert_eq!(policy.next_delay(), 250);
    }

    #[test]
    fn state_defaults_to_disconnected() {
        assert_eq!(ConnectionState::default(), ConnectionState::Disconnected);
    }
}
