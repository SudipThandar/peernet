//! PNTP relay harness (Milestone 5 gate).
//!
//! Covers the mandatory pre-device-testing cases:
//! - TCP connect + bidirectional data + clean close
//! - UDP datagram roundtrip with preserved source port (endpoint-preserving NAT)
//! - DNS query/response through the redirect
//! - Concurrent 10 TCP + 10 UDP sessions

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};

use peernet_client::{ClientOptions, TunnelClient};
use peernet_host::HostServer;

async fn spawn_host() -> HostServer {
    let addr: std::net::SocketAddr = "127.0.0.1:0".parse().unwrap();
    let server = HostServer::bind(addr, "relay-host").expect("host bind");
    tokio::spawn({
        let server = server.clone();
        async move { server.run().await }
    });
    server
}

fn client_opts(server: &HostServer, name: &str) -> ClientOptions {
    ClientOptions::new(
        server.local_addr(),
        "localhost",
        server.fingerprint_hex(),
        name,
    )
}

async fn spawn_tcp_echo() -> std::net::SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let Ok((mut sock, _)) = listener.accept().await else { return };
            tokio::spawn(async move {
                let mut buf = [0u8; 4096];
                loop {
                    match sock.read(&mut buf).await {
                        Ok(0) | Err(_) => return,
                        Ok(n) => {
                            if sock.write_all(&buf[..n]).await.is_err() {
                                return;
                            }
                        }
                    }
                }
            });
        }
    });
    addr
}

async fn spawn_udp_echo_with_peer_port_prefix() -> std::net::SocketAddr {
    let sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let addr = sock.local_addr().unwrap();
    tokio::spawn(async move {
        let mut buf = [0u8; 65536];
        loop {
            let (n, peer) = match sock.recv_from(&mut buf).await {
                Ok(x) => x,
                Err(_) => return,
            };
            let mut reply = peer.port().to_be_bytes().to_vec();
            reply.extend_from_slice(&buf[..n]);
            if sock.send_to(&reply, peer).await.is_err() {
                return;
            }
        }
    });
    addr
}

#[tokio::test(flavor = "multi_thread")]
async fn tcp_relay_echo_and_clean_close() {
    let run = tokio::time::timeout(Duration::from_secs(30), async {
        let server = spawn_host().await;
        let echo_addr = spawn_tcp_echo().await;
        let client =
            TunnelClient::connect(client_opts(&server, "tcp-client")).await.expect("connect");

        let echoed = client
            .tcp_relay(echo_addr, b"hello-through-tunnel")
            .await
            .expect("tcp relay");
        assert_eq!(echoed, b"hello-through-tunnel");

        // Second connection on the same tunnel must also work cleanly.
        let echoed = client.tcp_relay(echo_addr, b"second").await.expect("tcp relay 2");
        assert_eq!(echoed, b"second");

        client.shutdown();
    })
    .await;
    assert!(run.is_ok(), "tcp relay test timed out: {run:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn udp_roundtrip_preserves_source_port() {
    let run = tokio::time::timeout(Duration::from_secs(30), async {
        let server = spawn_host().await;
        let echo_addr = spawn_udp_echo_with_peer_port_prefix().await;
        let client =
            TunnelClient::connect(client_opts(&server, "udp-client")).await.expect("connect");

        let chosen_port = 45987u16;
        let payload = b"ping-via-nat";
        let reply = client
            .udp_exchange_via_stream(chosen_port, echo_addr, payload)
            .await
            .expect("udp exchange");

        assert_eq!(&reply[..2], &chosen_port.to_be_bytes(), "source port preserved");
        assert_eq!(&reply[2..], payload, "payload intact");

        client.shutdown();
    })
    .await;
    assert!(run.is_ok(), "udp test timed out: {run:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn dns_redirect_to_custom_upstream() {
    let run = tokio::time::timeout(Duration::from_secs(30), async {
        let server = spawn_host().await;

        // Fake upstream resolver on a high port.
        let fake = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let fake_addr = fake.local_addr().unwrap();
        server.set_dns_upstream(fake_addr);
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, peer) = match fake.recv_from(&mut buf).await {
                    Ok(x) => x,
                    Err(_) => return,
                };
                let mut reply = b"PN-DNS-OK".to_vec();
                reply.extend_from_slice(&buf[..n]);
                if fake.send_to(&reply, peer).await.is_err() {
                    return;
                }
            }
        });

        let client =
            TunnelClient::connect(client_opts(&server, "dns-client")).await.expect("connect");

        // Query aimed at a public resolver; the host redirects it upstream.
        let query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01";
        let response = client
            .udp_exchange_via_stream(5353, "8.8.8.8:53".parse().unwrap(), query)
            .await
            .expect("dns exchange");

        assert_eq!(&response[..9], b"PN-DNS-OK");
        assert_eq!(&response[9..], &query[..]);

        client.shutdown();
    })
    .await;
    assert!(run.is_ok(), "dns test timed out: {run:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn concurrent_ten_tcp_plus_ten_udp_relays() {
    let run = tokio::time::timeout(Duration::from_secs(45), async {
        let server = spawn_host().await;
        let tcp_addr = spawn_tcp_echo().await;
        let udp_addr = spawn_udp_echo_with_peer_port_prefix().await;

        let client = std::sync::Arc::new(
            TunnelClient::connect(client_opts(&server, "mixed-client")).await.expect("connect"),
        );

        let mut tasks = Vec::new();
        for i in 0..10u16 {
            // TCP
            let c = client.clone();
            tasks.push(tokio::spawn(async move {
                let msg = format!("tcp-{i}").into_bytes();
                let echoed = c.tcp_relay(tcp_addr, &msg).await.expect("tcp relay");
                assert_eq!(echoed, msg);
            }));
            // UDP
            let c = client.clone();
            tasks.push(tokio::spawn(async move {
                let msg = format!("udp-{i}").into_bytes();
                let port = 46000u16 + i;
                let reply = c.udp_exchange_via_stream(port, udp_addr, &msg).await.expect("udp relay");
                assert_eq!(&reply[..2], &port.to_be_bytes());
                assert_eq!(&reply[2..], &msg[..]);
            }));
        }

        for t in tasks {
            t.await.expect("task join");
        }

        client.shutdown();
    })
    .await;
    assert!(run.is_ok(), "concurrent relay test timed out: {run:?}");
}
