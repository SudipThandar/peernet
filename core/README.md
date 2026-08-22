# Rust Core

Performance-critical PeerNet tunnel engine. Implemented in Milestone 2.

Workspace layout (Section 11.2):

- `peernet-proto/` – shared protocol structs, serialization, errors
- `peernet-core/` – logging, config, crypto helpers, session IDs, stats
- `peernet-host/` – QUIC server, session manager, TCP relay, UDP NAT, DNS forwarder
- `peernet-client/` – QUIC client, TUN read/write, classifier, reconnect
- `peernet-ffi/` – UniFFI bindings for Kotlin

CI builds `.so` files automatically when `core/Cargo.toml` exists.
