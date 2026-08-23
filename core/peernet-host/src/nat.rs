//! Endpoint-preserving UDP NAT (spec Sections 12.5–12.8).
//!
//! Keyed by the client's source port: whenever possible the host reuses that
//! exact local port for outbound sockets, which dramatically improves
//! STUN/WebRTC behavior (Milestone 8). Idle mappings are swept after 90s and
//! each client is capped at 512 concurrent mappings.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio::net::UdpSocket;

use peernet_proto::{MAX_UDP_MAPPINGS_PER_CLIENT, UDP_NAT_IDLE_TIMEOUT_SECS};

struct Mapping {
    socket: Arc<UdpSocket>,
    last_used: Instant,
}

pub struct UdpNat {
    /// DNS packets to any :53 destination are redirected here when set.
    upstream_dns: Mutex<Option<SocketAddr>>,
    /// client_src_port -> mapping.
    mappings: Mutex<HashMap<u16, Mapping>>,
}

impl UdpNat {
    pub fn new() -> Self {
        Self {
            upstream_dns: Mutex::new(None),
            mappings: Mutex::new(HashMap::new()),
        }
    }

    pub fn set_dns_upstream(&self, addr: SocketAddr) {
        *self.upstream_dns.lock().unwrap_or_else(|p| p.into_inner()) = Some(addr);
    }

    pub fn dns_upstream(&self) -> Option<SocketAddr> {
        *self.upstream_dns.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Returns the socket for this client source port, creating (and binding
    /// to that same port) on first use. Falls back to an ephemeral port when
    /// the preferred one is taken.
    pub async fn get_or_create(&self, client_src_port: u16) -> Result<Arc<UdpSocket>, String> {
        {
            let mut mappings = self.lock();
            if let Some(m) = mappings.get_mut(&client_src_port) {
                m.last_used = Instant::now();
                return Ok(m.socket.clone());
            }
            if mappings.len() >= MAX_UDP_MAPPINGS_PER_CLIENT {
                self.sweep_oldest(&mut mappings);
            }
        }

        let bind_addr = SocketAddr::from(([0, 0, 0, 0], client_src_port));
        let socket = match UdpSocket::bind(bind_addr).await {
            Ok(s) => s,
            Err(_) => UdpSocket::bind("0.0.0.0:0")
                .await
                .map_err(|e| format!("udp bind failed: {e}"))?,
        };
        let actual_port = socket.local_addr().map_err(|e| e.to_string())?.port();
        let socket = Arc::new(socket);

        let mut mappings = self.lock();
        // Another task may have raced us; keep theirs if present.
        if let Some(existing) = mappings.get(&client_src_port) {
            return Ok(existing.socket.clone());
        }
        mappings.insert(
            actual_port,
            Mapping { socket: socket.clone(), last_used: Instant::now() },
        );
        Ok(socket)
    }

    /// Resolves the effective destination — applies DNS redirect.
    pub fn resolve_dst(&self, dst: SocketAddr) -> SocketAddr {
        if dst.port() == 53 {
            if let Some(upstream) = self.dns_upstream() {
                return upstream;
            }
        }
        dst
    }

    /// Drops mappings idle longer than the spec timeout. Returns removed count.
    pub fn sweep_idle(&self) -> usize {
        let mut mappings = self.lock();
        let cutoff = Instant::now() - Duration::from_secs(UDP_NAT_IDLE_TIMEOUT_SECS);
        let before = mappings.len();
        mappings.retain(|_, m| m.last_used > cutoff);
        before - mappings.len()
    }

    fn sweep_oldest(&self, mappings: &mut HashMap<u16, Mapping>) {
        if let Some(oldest) = mappings
            .iter()
            .min_by_key(|(_, m)| m.last_used)
            .map(|(k, _)| *k)
        {
            mappings.remove(&oldest);
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<u16, Mapping>> {
        self.mappings.lock().unwrap_or_else(|p| p.into_inner())
    }
}

impl Default for UdpNat {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn preserves_client_source_port_when_free() {
        let nat = UdpNat::new();
        let sock = nat.get_or_create(45123).await.unwrap();
        assert_eq!(sock.local_addr().unwrap().port(), 45123);
        // Same port reuses the same mapping.
        let again = nat.get_or_create(45123).await.unwrap();
        assert_eq!(again.local_addr().unwrap(), sock.local_addr().unwrap());
    }

    #[tokio::test]
    async fn dns_redirect_applies_only_to_port_53() {
        let nat = UdpNat::new();
        let upstream: SocketAddr = "127.0.0.1:15353".parse().unwrap();
        nat.set_dns_upstream(upstream);

        let redirected = nat.resolve_dst("8.8.8.8:53".parse().unwrap());
        assert_eq!(redirected, upstream);

        let untouched = nat.resolve_dst("1.1.1.1:9999".parse().unwrap());
        assert_eq!(untouched, "1.1.1.1:9999".parse().unwrap());
    }
}
