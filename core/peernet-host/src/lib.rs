//! Host-side session bookkeeping and PNTP QUIC server.

pub mod server;

pub use server::HostServer;

use std::collections::HashMap;
use std::sync::Mutex;

/// One device bridged onto this host's tunnel.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientInfo {
    pub id: u64,
    pub name: String,
    /// Unix epoch seconds at connect time.
    pub connected_at_secs: u64,
    pub bytes_forwarded: u64,
}

/// Tracks currently-connected clients. Thread-safe.
pub struct SessionManager {
    clients: Mutex<HashMap<u64, ClientInfo>>,
}

impl SessionManager {
    pub fn new() -> Self {
        SessionManager { clients: Mutex::new(HashMap::new()) }
    }

    /// Registers a client. Returns false if the id is already active.
    pub fn register(&self, id: u64, name: impl Into<String>, now_secs: u64) -> bool {
        let mut clients = self.lock();
        if clients.contains_key(&id) {
            return false;
        }
        clients.insert(
            id,
            ClientInfo { id, name: name.into(), connected_at_secs: now_secs, bytes_forwarded: 0 },
        );
        true
    }

    pub fn disconnect(&self, id: u64) -> bool {
        self.lock().remove(&id).is_some()
    }

    /// Adds forwarded-byte count to a client's tally.
    pub fn record_forwarded(&self, id: u64, n: u64) {
        if let Some(client) = self.lock().get_mut(&id) {
            client.bytes_forwarded += n;
        }
    }

    /// Removes clients idle longer than `timeout_secs`; returns removed ids.
    pub fn prune_idle(&self, now_secs: u64, timeout_secs: u64) -> Vec<u64> {
        let mut clients = self.lock();
        let stale: Vec<u64> = clients
            .values()
            .filter(|c| now_secs.saturating_sub(c.connected_at_secs) > timeout_secs)
            .map(|c| c.id)
            .collect();
        stale.iter().for_each(|id| {
            clients.remove(id);
        });
        stale
    }

    /// Clients sorted by connect time (oldest first).
    pub fn list(&self) -> Vec<ClientInfo> {
        let mut v: Vec<ClientInfo> = self.lock().values().cloned().collect();
        v.sort_by_key(|c| c.connected_at_secs);
        v
    }

    pub fn count(&self) -> usize {
        self.lock().len()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<u64, ClientInfo>> {
        // A panic in another thread must not poison the whole engine.
        self.clients.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl Default for SessionManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn register_and_list() {
        let mgr = SessionManager::new();
        assert!(mgr.register(1, "Pixel 8", 1000));
        assert!(mgr.register(2, "Redmi", 2000));
        assert_eq!(mgr.count(), 2);
        let list = mgr.list();
        assert_eq!(list[0].name, "Pixel 8"); // oldest first
        assert!(!mgr.register(1, "dup", 3000)); // duplicate rejected
    }

    #[test]
    fn forward_accounting() {
        let mgr = SessionManager::new();
        mgr.register(7, "Tab", 100);
        mgr.record_forwarded(7, 500);
        mgr.record_forwarded(7, 250);
        assert_eq!(mgr.list()[0].bytes_forwarded, 750);
    }

    #[test]
    fn disconnect_and_prune() {
        let mgr = SessionManager::new();
        mgr.register(1, "a", 100);
        mgr.register(2, "b", 900);
        assert!(mgr.disconnect(1));
        assert!(!mgr.disconnect(1)); // already gone
        assert_eq!(mgr.prune_idle(1000, 60), vec![2]); // b idle 100s > 60s
        assert_eq!(mgr.count(), 0);
    }
}
