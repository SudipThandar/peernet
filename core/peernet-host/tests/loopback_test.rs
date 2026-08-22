//! PNTP loopback harness (Milestone 4 gate).
//!
//! Real QUIC over localhost UDP: host server + tunnel clients.
//! Per the project constraints, this harness must stay green before any
//! Android device testing. Relay cases (TCP/UDP/DNS) extend it in M5/M6.

use std::time::Duration;

use peernet_client::{ClientOptions, ClientState, TunnelClient};
use peernet_host::HostServer;

async fn spawn_host(device: &str) -> HostServer {
    let addr: std::net::SocketAddr = "127.0.0.1:0".parse().unwrap();
    let server = HostServer::bind(addr, device).expect("host bind");
    tokio::spawn({
        let server = server.clone();
        async move { server.run().await }
    });
    server
}

fn client_opts(server: &HostServer, name: &str) -> ClientOptions {
    let mut opts = ClientOptions::new(
        server.local_addr(),
        "localhost",
        server.fingerprint_hex(),
        name,
    );
    // Tight intervals so heartbeat-failure tests finish fast.
    opts.keepalive_interval = Duration::from_millis(150);
    opts.response_timeout = Duration::from_millis(500);
    opts
}

#[tokio::test]
async fn handshake_heartbeat_and_stats() {
    let server = spawn_host("loopback-host").await;
    let client = TunnelClient::connect(client_opts(&server, "loopback-client"))
        .await
        .expect("client connect");

    assert!(client.session_id() != 0, "host must assign a session id");
    assert_eq!(client.state(), ClientState::Connected);

    // Heartbeat pump is running; state must hold Connected across several beats.
    tokio::time::sleep(Duration::from_millis(700)).await;
    assert_eq!(client.state(), ClientState::Connected);

    // Framed data echo roundtrip.
    let payload = b"pn-loopback-data".to_vec();
    let echoed = client.data_roundtrip(payload.clone()).await.expect("data roundtrip");
    assert_eq!(echoed, payload);

    // Unreliable datagram roundtrip (64KB buffer path).
    let dgram = vec![7u8; 1024];
    let echoed = client.datagram_roundtrip(dgram).await.expect("datagram roundtrip");
    assert_eq!(echoed.len(), 1024);

    // Stats readable on both ends.
    let host_stats = server.stats_snapshot();
    assert!(host_stats.bytes_up > 0, "host saw bytes");
    assert!(host_stats.sessions_started >= 1);

    let client_stats = client.request_stats().await.expect("stats response");
    assert!(client_stats.sessions_started >= 1);
    assert!(client_stats.bytes_down > 0);

    assert_eq!(server.session_count(), 1);
    client.shutdown();
}

#[tokio::test]
async fn concurrent_sessions() {
    let server = spawn_host("concurrent-host").await;

    let mut clients = Vec::new();
    for i in 0..10 {
        let opts = client_opts(&server, &format!("client-{i}"));
        clients.push(TunnelClient::connect(opts).await.expect("connect"));
    }

    for (i, c) in clients.iter().enumerate() {
        let echoed = c.data_roundtrip(vec![i as u8; 256]).await.expect("roundtrip");
        assert_eq!(echoed[0], i as u8);
    }

    let ids: std::collections::HashSet<u64> =
        clients.iter().map(|c| c.session_id()).collect();
    assert_eq!(ids.len(), 10, "session ids must be unique");

    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(server.session_count(), 10);

    for c in clients {
        c.shutdown();
    }
}

#[tokio::test]
async fn fingerprint_mismatch_is_rejected() {
    let server = spawn_host("pin-host").await;
    let mut opts = client_opts(&server, "impostor-client");
    opts.expected_fingerprint_hex = "00".repeat(32);
    let result = TunnelClient::connect(opts).await;
    assert!(result.is_err(), "wrong fingerprint must fail the handshake");
}

#[tokio::test]
async fn heartbeat_timeout_detected_when_host_dies() {
    let server = spawn_host("dying-host").await;
    let client = TunnelClient::connect(client_opts(&server, "watchdog-client"))
        .await
        .expect("connect");

    assert_eq!(client.state(), ClientState::Connected);

    // Kill the accept loop + session tasks; connection drops.
    server.shutdown();

    // The heartbeat pump should notice within interval + response timeout.
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    loop {
        if client.state() == ClientState::Backoff {
            break;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "heartbeat failure was not detected in time"
        );
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}
