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

#[tokio::test(flavor = "multi_thread")]
async fn handshake_heartbeat_and_stats() {
    // Hard ceiling so a regression can never hang CI for 25 minutes.
    tokio::time::timeout(Duration::from_secs(30), async {
        let server = spawn_host("loopback-host").await;
        let client = TunnelClient::connect(client_opts(&server, "loopback-client"))
            .await
            .expect("client connect");

        assert!(client.session_id() != 0, "host must assign a session id");
        assert_eq!(client.state(), ClientState::Connected);

        tokio::time::sleep(Duration::from_millis(700)).await;
        assert_eq!(client.state(), ClientState::Connected);

        let payload = b"pn-loopback-data".to_vec();
        let echoed = client.data_roundtrip(payload.clone()).await.expect("data roundtrip");
        assert_eq!(echoed, payload);

        // UDP relay roundtrip through the host NAT to a local echo socket.
        let echo = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let echo_addr = echo.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 2048];
            loop {
                let (n, peer) = match echo.recv_from(&mut buf).await {
                    Ok(x) => x,
                    Err(_) => return,
                };
                let _ = echo.send_to(&buf[..n], peer).await;
            }
        });
        let echoed = client
            .udp_exchange_via_stream(45987, echo_addr, &vec![7u8; 1024])
            .await
            .expect("udp relay roundtrip");
        if echoed.len() != 1024 {
            eprintln!(
                "[diag] echoed.len={} prefix={:?} suffix={:?}",
                echoed.len(),
                &echoed[..16.min(echoed.len())],
                &echoed[echoed.len().saturating_sub(16)..]
            );
        }
        assert_eq!(echoed.len(), 1024);

        let host_stats = server.stats_snapshot();
        assert!(host_stats.bytes_up > 0, "host saw bytes");
        assert!(host_stats.sessions_started >= 1);

        let client_stats = client.request_stats().await.expect("stats response");
        assert!(client_stats.sessions_started >= 1);
        assert!(client_stats.bytes_down > 0);

        assert_eq!(server.session_count(), 1);
        client.shutdown();
    })
    .await
    .expect("handshake test timed out");
}

#[tokio::test(flavor = "multi_thread")]
async fn concurrent_sessions() {
    tokio::time::timeout(Duration::from_secs(30), async {
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
    })
    .await
    .expect("concurrent test timed out");
}

#[tokio::test(flavor = "multi_thread")]
async fn fingerprint_mismatch_is_rejected() {
    let server = spawn_host("pin-host").await;
    let mut opts = client_opts(&server, "impostor-client");
    opts.expected_fingerprint_hex = "00".repeat(32);
    let result = TunnelClient::connect(opts).await;
    assert!(result.is_err(), "wrong fingerprint must fail the handshake");
}

#[tokio::test(flavor = "multi_thread")]
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
