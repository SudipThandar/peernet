//! Loopback coverage for the M7 part-2 data path without a real TUN fd:
//!
//! outbound helper -> QUIC relay datagram -> host NAT -> local UDP echo ->
//! host reply pump -> client reply pump -> rebuilt IPv4/UDP packet.

use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use peernet_client::{ClientOptions, TunnelClient};
use peernet_core::{
    build_udp_packet, engine_generation, install_reply_channel, internet_checksum,
    pump_udp_replies, register_flow, send_udp_relay,
};
use peernet_host::HostServer;

const CLIENT_TUN_IP: [u8; 4] = [10, 215, 17, 2];
const CLIENT_SRC_PORT: u16 = 54321;

fn start_echo() -> SocketAddr {
    let sock = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind echo");
    let addr = sock.local_addr().unwrap();
    std::thread::spawn(move || {
        let mut buf = [0u8; 2048];
        while let Ok((n, peer)) = sock.recv_from(&mut buf) {
            let _ = sock.send_to(&buf[..n], peer);
        }
    });
    addr
}

#[tokio::test(flavor = "multi_thread")]
async fn udp_roundtrip_through_host_nat() {
    let echo_addr = start_echo();

    let server = HostServer::bind("127.0.0.1:0".parse().unwrap(), "test-host").unwrap();
    let fp = server.fingerprint_hex().to_string();
    let server_addr = server.local_addr();
    let server = Arc::new(server);
    tokio::spawn({
        let s = server.clone();
        async move { s.run().await }
    });

    let opts = ClientOptions::new(server_addr, "peernet-host", fp, "test-client");
    let client =
        Arc::new(TunnelClient::connect(opts).await.expect("client handshake"));

    // Publish the write-back channel and start the reverse-path pump with
    // the current engine generation so it stays valid.
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Vec<u8>>();
    install_reply_channel(tx);
    let gen = engine_generation();
    tokio::spawn(pump_udp_replies(client.clone(), gen));

    // Register the fake TUN flow, then push a request through the tunnel.
    register_flow(
        CLIENT_SRC_PORT,
        CLIENT_TUN_IP,
        echo_addr.ip().octets(),
        echo_addr.port(),
    );
    send_udp_relay(
        &client,
        CLIENT_SRC_PORT,
        Ipv4Addr::LOCALHOST,
        echo_addr.port(),
        b"hello-pntp",
    );

    let packet = tokio::time::timeout(Duration::from_secs(10), rx.recv())
        .await
        .expect("reply within timeout")
        .expect("channel alive");

    // Decode the rebuilt packet: Ethernet-less IPv4 + UDP + payload.
    assert!(packet.len() >= 28);
    assert_eq!(&packet[12..16], &echo_addr.ip()); // source = remote peer
    assert_eq!(&packet[16..20], &CLIENT_TUN_IP); // destination = phone
    let src_port = u16::from_be_bytes([packet[20], packet[21]]);
    let dst_port = u16::from_be_bytes([packet[22], packet[23]]);
    assert_eq!(src_port, echo_addr.port());
    assert_eq!(dst_port, CLIENT_SRC_PORT);

    // Header checksum must validate against a recomputation.
    let mut header = packet[..20].to_vec();
    let stored = u16::from_be_bytes([header[10], header[11]]);
    header[10] = 0;
    header[11] = 0;
    assert_eq!(stored, internet_checksum(&header));

    assert_eq!(&packet[28..], b"hello-pntp");
}

#[test]
fn rebuild_packet_is_self_consistent() {
    let payload = vec![7u8; 100];
    let p = build_udp_packet(
        (Ipv4Addr::new(8, 8, 8, 8), 53),
        (Ipv4Addr::from(CLIENT_TUN_IP), 40000),
        &payload,
    );
    assert_eq!(p.len(), 28 + payload.len());
    assert_eq!(u16::from_be_bytes([p[2], p[3]]), p.len() as u16); // total_len
    assert_eq!(p[9], 17); // proto UDP
    let declared = u16::from_be_bytes([p[24], p[25]]) as usize;
    assert_eq!(declared, 8 + payload.len());

    let mut header = p[..20].to_vec();
    let stored = u16::from_be_bytes([header[10], header[11]]);
    header[10] = 0;
    header[11] = 0;
    assert_eq!(stored, internet_checksum(&header));
    assert_ne!(stored, 0); // checksum actually computed
}

#[test]
fn checksum_matches_rfc1071_example() {
    // Ones'-complement sum of 0x0001 + 0xf203 + 0xf4f5 + 0xf6f7 folds to a
    // checksum of 0x220d.
    let bytes = [0x00, 0x01, 0xf2, 0x03, 0xf4, 0xf5, 0xf6, 0xf7];
    assert_eq!(internet_checksum(&bytes), 0x220d);
}
