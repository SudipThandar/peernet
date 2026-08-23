//! JNI bridge between the Android app and the Rust engine.
//!
//! Loaded from Kotlin via `System.loadLibrary("peernet_core")`.
//! Symbol names must match `com.peernet.wifiextender.core.NativeCore` exactly.
//!
//! Constraints honored here:
//! - no panics cross the FFI boundary
//! - TUN fd is received as i32, wrapped in `OwnedFd`, and driven exclusively
//!   through tokio async I/O (`AsyncFd`) â€” never blocking reads
//! - ownership transfers to Rust on start; Kotlin never closes the same fd,
//!   which rules out double-close UB (stop path closes it exactly once)

use std::os::fd::{FromRawFd, OwnedFd, RawFd};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use jni::objects::{JClass, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use peernet_client::{ClientOptions, ClientState, TunnelClient};
use peernet_core::SessionId;
use peernet_host::HostServer;
use peernet_proto::UdpRelayHeader;
use tokio::io::unix::AsyncFd;
use std::io::Read as _;

/// Dedicated engine runtime â€” JNI has no ambient tokio context.
fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_io()
            .enable_time()
            .build()
            .expect("tokio runtime")
    })
}

// ---------- TUN capture state ----------

static TUN_FD: AtomicI32 = AtomicI32::new(-1);
static TUN_STOP: AtomicBool = AtomicBool::new(false);
/// True while a capture session owns the TUN; lets the reply writer bail
/// out promptly when the reader half dies.
static CAPTURE_ALIVE: AtomicBool = AtomicBool::new(false);
static PACKETS: AtomicU64 = AtomicU64::new(0);
static BYTES: AtomicU64 = AtomicU64::new(0);

/// TUN flow table for the UDP reverse path. Keyed by the phone-side source
/// port (the host preserves ports on its NAT sockets). We remember the
/// ORIGINAL destination so replies can be rebuilt claiming to come from
/// exactly where the phone sent them - required for the virtual DNS IP,
/// whose replies physically originate from a different resolver address.
/// Entries live for the capture session (no LRU in Phase 1).
#[derive(Clone, Copy)]
struct UdpFlow {
    local_ip: [u8; 4],
    dst_ip: [u8; 4],
    dst_port: u16,
}

type UdpFlows = HashMap<u16, UdpFlow>;

fn udp_flows() -> &'static Mutex<UdpFlows> {
    static S: OnceLock<Mutex<UdpFlows>> = OnceLock::new();
    S.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Write-back channel: the reply pump produces fully formed IPv4/UDP
/// packets; the single capture task owns the TUN fd and writes them.
/// Swapped per capture session so restarts never reuse a dead receiver.
type TunTx = tokio::sync::mpsc::UnboundedSender<Vec<u8>>;

static TUN_TX: std::sync::RwLock<Option<TunTx>> = std::sync::RwLock::new(None);

fn tun_tx() -> Option<TunTx> {
    TUN_TX
        .read()
        .unwrap_or_else(|p| p.into_inner())
        .as_ref()
        .map(std::sync::Clone::clone)
}

/// Outbound TCP is deferred (needs local termination); count instead of
/// silently dropping so the UI can surface it later.
static TCP_DROPPED: AtomicU64 = AtomicU64::new(0);
static UDP_FORWARDED: AtomicU64 = AtomicU64::new(0);

// ---------- PNTP engine state (Milestone 7) ----------

/// Mirrors peernet_client::ClientState for cheap JNI polling:
/// 0 Disconnected, 1 Connecting, 2 Connected, 3 Backoff.
static TUNNEL_STATE: AtomicI32 = AtomicI32::new(0);
static CLIENT_STARTING: AtomicBool = AtomicBool::new(false);
/// Invalidates in-flight connects when a stop/new-start supersedes them.
static ENGINE_GEN: AtomicU64 = AtomicU64::new(0);

type HostSlot = Mutex<Option<std::sync::Arc<HostServer>>>;
type ClientSlot = Mutex<Option<std::sync::Arc<TunnelClient>>>;

fn host_slot() -> &'static HostSlot {
    static S: OnceLock<HostSlot> = OnceLock::new();
    S.get_or_init(|| Mutex::new(None))
}

fn client_slot() -> &'static ClientSlot {
    static S: OnceLock<ClientSlot> = OnceLock::new();
    S.get_or_init(|| Mutex::new(None))
}

// ---------- Core info ----------

/// NativeCore.version() -> String
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_version<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let text = concat!("peernet-core ", env!("CARGO_PKG_VERSION"));
    create_string(env, text)
}

/// NativeCore.newSessionId() -> String (32-char hex)
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_newSessionId<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    create_string(env, &SessionId::generate().to_hex())
}

// ---------- TUN capture (Milestone 6) ----------

/// NativeCore.startTunCapture(fd: Int, mtu: Int) -> Boolean
///
/// Takes ownership of `fd`. Returns false when a capture is already running.
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_startTunCapture<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    mtu: jint,
) -> jboolean {
    if fd < 0 {
        return 0;
    }
    if TUN_FD.swap(fd as i32, Ordering::SeqCst) != -1 {
        // Already capturing; hand the fd back untouched by leaving it open â€”
        // caller treats false as "abort" and will not close it either.
        return 0;
    }
    TUN_STOP.store(false, Ordering::SeqCst);
    PACKETS.store(0, Ordering::Relaxed);
    BYTES.store(0, Ordering::Relaxed);

    let owned = unsafe { OwnedFd::from_raw_fd(fd as RawFd) };
    let mtu = mtu.clamp(1200, 1500) as usize;

    runtime().spawn(async move { run_capture(owned, mtu).await });
    1
}

/// NativeCore.stopTunCapture() -> Boolean
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_stopTunCapture<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    TUN_STOP.store(true, Ordering::SeqCst);
    let fd = TUN_FD.swap(-1, Ordering::SeqCst);
    if fd >= 0 {
        // Closing the tracked fds wakes pending reads/writes with EBADF.
        // The capture task also runs close_capture_fds() on exit; both
        // paths are safe because the tracker swaps to -1 first.
        close_capture_fds();
        return 1;
    }
    0
}

/// NativeCore.tunPacketCount() -> Long
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_tunPacketCount<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    PACKETS.load(Ordering::Relaxed) as jlong
}

// ---------- PNTP engine lifecycle (Milestone 7) ----------

/// NativeCore.startHost(port: Int, deviceName: String) -> String
///
/// Binds the QUIC host server on 0.0.0.0:port and runs its accept loop on the
/// engine runtime. Returns the SHA-256 certificate fingerprint (lowercase
/// hex) for advertisement, or "" when a server is already running or the
/// bind failed.
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_startHost<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    port: jint,
    device_name: JString<'local>,
) -> JString<'local> {
    let name = get_string(&mut env, &device_name);
    let mut guard = host_slot().lock().unwrap_or_else(|p| p.into_inner());
    if guard.is_some() {
        return create_string(env, "");
    }
    let addr = SocketAddr::from(([0, 0, 0, 0], port.clamp(1, 65535) as u16));
    match HostServer::bind(addr, &name) {
        Ok(server) => {
            let fingerprint = server.fingerprint_hex().to_string();
            let server = std::sync::Arc::new(server);
            runtime().spawn({
                let server = server.clone();
                async move { server.run().await }
            });
            *guard = Some(server);
            create_string(env, &fingerprint)
        }
        Err(e) => {
            crate::jni_log(&format!("[host] bind failed: {e}"));
            create_string(env, "")
        }
    }
}

/// NativeCore.stopHost() -> Boolean
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_stopHost<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    let mut guard = host_slot().lock().unwrap_or_else(|p| p.into_inner());
    match guard.take() {
        Some(server) => {
            server.shutdown();
            1
        }
        None => 0,
    }
}

/// NativeCore.hostSessionCount() -> Int
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_hostSessionCount<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    let guard = host_slot().lock().unwrap_or_else(|p| p.into_inner());
    guard.as_ref().map(|s| s.session_count()).unwrap_or(0) as jint
}

/// NativeCore.startTunnel(serverAddr: String, fingerprintHex: String, deviceName: String) -> Boolean
///
/// Connects to the pinned-fingerprint QUIC host. The handshake runs on the
/// engine runtime; progress is observable via tunnelState(). Returns false
/// only when a connect attempt is already in flight or arguments are bad —
/// handshake failure surfaces as tunnelState() == 0.
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_startTunnel<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    server_addr: JString<'local>,
    fingerprint_hex: JString<'local>,
    device_name: JString<'local>,
) -> jboolean {
    let addr = get_string(&mut env, &server_addr);
    let fp = get_string(&mut env, &fingerprint_hex);
    let name = get_string(&mut env, &device_name);

    let Ok(parsed) = SocketAddr::from_str(&addr) else {
        return 0;
    };
    if CLIENT_STARTING.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_err() {
        return 0;
    }

    TUNNEL_STATE.store(1, Ordering::SeqCst); // Connecting
    let gen = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    runtime().spawn(async move {
        let opts = ClientOptions::new(parsed, "peernet-host", fp, name);
        match TunnelClient::connect(opts).await {
            Ok(client) => {
                if ENGINE_GEN.load(Ordering::SeqCst) != gen {
                    // Superseded by stop/new start during the handshake.
                    client.shutdown();
                    return;
                }
                let client = std::sync::Arc::new(client);
                *client_slot().lock().unwrap_or_else(|p| p.into_inner()) = Some(client.clone());
                CLIENT_STARTING.store(false, Ordering::SeqCst);
                // Reverse path: rebuild relay datagrams into TUN packets
                // until this generation is superseded or the link dies.
                tokio::spawn(pump_udp_replies(client.clone(), gen));
                // Mirror watch-channel state into the atomic until the link dies.
                loop {
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    if ENGINE_GEN.load(Ordering::SeqCst) != gen {
                        break;
                    }
                    let s = client.state();
                    TUNNEL_STATE.store(map_state(s), Ordering::SeqCst);
                    if matches!(s, ClientState::Backoff | ClientState::Disconnected) {
                        break;
                    }
                }
                *client_slot().lock().unwrap_or_else(|p| p.into_inner()) = None;
            }
            Err(e) => {
                crate::jni_log(&format!("[client] connect failed: {e}"));
                if ENGINE_GEN.load(Ordering::SeqCst) == gen {
                    TUNNEL_STATE.store(0, Ordering::SeqCst);
                    CLIENT_STARTING.store(false, Ordering::SeqCst);
                }
            }
        }
    });
    1
}

fn map_state(state: ClientState) -> i32 {
    match state {
        ClientState::Disconnected => 0,
        ClientState::Connecting => 1,
        ClientState::Connected => 2,
        ClientState::Backoff => 3,
    }
}

/// NativeCore.stopTunnel() -> Boolean
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_stopTunnel<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    CLIENT_STARTING.store(false, Ordering::SeqCst);
    TUNNEL_STATE.store(0, Ordering::SeqCst);
    // Invalidate any handshake still in flight.
    ENGINE_GEN.fetch_add(1, Ordering::SeqCst);
    udp_flows()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clear();
    // Clone the handle out before touching the slot again; shutdown() is
    // synchronous so it is safe outside the lock.
    let client = {
        let guard = client_slot().lock().unwrap_or_else(|p| p.into_inner());
        guard.as_ref().map(std::sync::Arc::clone)
    };
    match client {
        Some(client) => {
            client.shutdown();
            *client_slot().lock().unwrap_or_else(|p| p.into_inner()) = None;
            1
        }
        None => 0,
    }
}

/// NativeCore.tunnelState() -> Int (see TUNNEL_STATE mapping)
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_tunnelState<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    TUNNEL_STATE.load(Ordering::SeqCst)
}

/// All fds backing an active capture session (original + read/write
/// duplicates). Closed exactly once by close_capture_fds(); registered
/// before the async halves are built so every exit path cleans up.
static CAPTURE_FDS: Mutex<[i32; 3]> = Mutex::new([-1, -1, -1]);

fn register_capture_fds(original: i32, read_fd: i32, write_fd: i32) {
    *CAPTURE_FDS.lock().unwrap_or_else(|p| p.into_inner()) = [original, read_fd, write_fd];
}

fn close_capture_fds() {
    let fds = std::mem::replace(
        &mut *CAPTURE_FDS.lock().unwrap_or_else(|p| p.into_inner()),
        [-1, -1, -1],
    );
    for fd in fds.iter().filter(|f| **f >= 0) {
        unsafe {
            libc::close(*fd);
        }
    }
}

async fn run_capture(file: OwnedFd, mtu: usize) {
    use std::mem::ManuallyDrop;
    use std::os::fd::AsFd;
    use std::os::unix::io::AsRawFd;

    // Split the TUN into independent read/write halves so the outbound
    // reader and reply writer never contend on one handle. Duplicated fds
    // share one open description; tokio registers level-triggered
    // readiness, which stays correct across duplicates. All three raw fds
    // are tracked and closed exactly once at session end (closing any one
    // wakes pending epoll waits, which is how stop interrupts reads).
    let owned = ManuallyDrop::new(std::fs::File::from(file));
    // Track the original immediately so even early failure paths close it
    // exactly once.
    register_capture_fds(owned.as_raw_fd(), -1, -1);
    let read_half = match owned.try_clone() {
        Ok(f) => f,
        Err(_) => {
            close_capture_fds();
            return;
        }
    };
    let write_half = match owned.try_clone() {
        Ok(f) => f,
        Err(_) => {
            close_capture_fds();
            return;
        }
    };
    register_capture_fds(
        owned.as_raw_fd(),
        read_half.as_raw_fd(),
        write_half.as_raw_fd(),
    );
    let _ = set_nonblocking(read_half.as_fd().as_raw_fd());
    let _ = set_nonblocking(write_half.as_fd().as_raw_fd());

    let reader_fd = match AsyncFd::new(read_half) {
        Ok(a) => a,
        Err(_) => {
            close_capture_fds();
            return;
        }
    };
    let writer_fd = match AsyncFd::new(write_half) {
        Ok(a) => a,
        Err(_) => {
            close_capture_fds();
            return;
        }
    };

    CAPTURE_ALIVE.store(true, Ordering::SeqCst);

    // Publish the write-back channel for the reply pump (replacing any
    // stale sender from a previous session; its receiver gets dropped,
    // which makes old pumps exit on their next send).
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel::<Vec<u8>>();
    install_reply_channel(tx);

    // Reply writer: rebuilt packets -> TUN. Exits when the pump side goes
    // away (channel closed) or the capture session ends.
    let writer = tokio::spawn(async move {
        let mut async_fd = writer_fd;
        loop {
            let pkt = tokio::select! {
                p = rx.recv() => match p {
                    Some(p) => p,
                    None => break,
                },
                _ = tokio::time::sleep(Duration::from_millis(250)) => {
                    if !CAPTURE_ALIVE.load(Ordering::SeqCst) {
                        break;
                    }
                    continue;
                }
            };
            let mut wrote = false;
            while !wrote {
                let guard = match async_fd.writable_mut().await {
                    Ok(g) => g,
                    Err(_) => break,
                };
                match guard.try_io(|inner| inner.get_mut().write(&pkt)) {
                    Ok(Ok(_)) => wrote = true,
                    Ok(Err(e)) if e.kind() == std::io::ErrorKind::WouldBlock => continue,
                    Ok(Err(_)) | Err(_) => break,
                }
            }
            if !wrote {
                break;
            }
        }
    });

    // Outbound reader: phone -> tunnel.
    let mut async_fd = reader_fd;
    let mut buf = vec![0u8; mtu.max(1500)];
    let mut logged = 0u64;
    loop {
        if TUN_STOP.load(Ordering::SeqCst) {
            break;
        }
        let guard = match async_fd.readable_mut().await {
            Ok(g) => g,
            Err(_) => break,
        };
        let result = guard.try_io(|inner| inner.get_mut().read(&mut buf));
        match result {
            Ok(Ok(0)) => break, // EOF: interface closed
            Ok(Ok(n)) => {
                PACKETS.fetch_add(1, Ordering::Relaxed);
                BYTES.fetch_add(n as u64, Ordering::Relaxed);
                forward_outbound(&buf[..n]);
                if logged < 5 {
                    logged += 1;
                    crate::jni_log(&format!(
                        "[tun] pkt#{} {}B {}",
                        PACKETS.load(Ordering::Relaxed),
                        n,
                        describe(&buf[..n])
                    ));
                }
            }
            Ok(Err(e)) if e.kind() == std::io::ErrorKind::WouldBlock => continue,
            Ok(Err(_)) | Err(_) => break,
        }
    }

    CAPTURE_ALIVE.store(false, Ordering::SeqCst);
    close_capture_fds();
    let _ = writer.await;
}

/// Parse one outbound packet and route it through the tunnel when possible.
/// IPv4 + UDP goes via relay datagrams; TCP is counted (local termination
/// lands in a follow-up); everything else is dropped.
fn forward_outbound(packet: &[u8]) {
    if packet.len() < 20 || packet[0] >> 4 != 4 {
        return;
    }
    let ihl = ((packet[0] & 0x0F) as usize) * 4;
    if ihl < 20 || packet.len() < ihl {
        return;
    }
    let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    let end = total_len.min(packet.len());
    if end < ihl {
        return;
    }
    let proto = packet[9];
    if proto == 6 {
        // TCP deferred: needs a local userspace termination to keep the
        // phone's kernel TCP state consistent. Count for visibility.
        let dropped = TCP_DROPPED.fetch_add(1, Ordering::Relaxed) + 1;
        if dropped == 1 {
            crate::jni_log("[tun] tcp flows pending local termination (deferred)");
        }
        return;
    }
    if proto != 17 || end < ihl + 8 {
        return;
    }

    let src_ip = Ipv4Addr::new(packet[12], packet[13], packet[14], packet[15]);
    let dst_ip = Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
    let l4 = ihl;
    let sport = u16::from_be_bytes([packet[l4], packet[l4 + 1]]);
    let dport = u16::from_be_bytes([packet[l4 + 2], packet[l4 + 3]]);
    let declared = u16::from_be_bytes([packet[l4 + 4], packet[l4 + 5]]) as usize;
    let payload_end = if declared >= 8 && l4 + declared <= end {
        l4 + declared
    } else {
        end
    };
    if payload_end < l4 + 8 {
        return;
    }
    let payload = &packet[l4 + 8..payload_end];

    udp_flows()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .insert(
            sport,
            UdpFlow {
                local_ip: src_ip.octets(),
                dst_ip: dst_ip.octets(),
                dst_port: dport,
            },
        );

    let client = current_client();
    if let Some(client) = client {
        send_udp_relay(&client, sport, dst_ip, dport, payload);
        UDP_FORWARDED.fetch_add(1, Ordering::Relaxed);
    }
}

fn current_client() -> Option<std::sync::Arc<TunnelClient>> {
    client_slot()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .as_ref()
        .map(std::sync::Arc::clone)
}

pub fn send_udp_relay(
    client: &TunnelClient,
    src_port: u16,
    dst_ip: Ipv4Addr,
    dst_port: u16,
    payload: &[u8],
) {
    let hdr = UdpRelayHeader {
        session_id: client.session_id() as u32,
        src_port,
        dst_ip: IpAddr::V4(dst_ip),
        dst_port,
    };
    let mut frame = hdr.encode(0);
    frame.extend_from_slice(payload);
    let _ = client.connection().send_datagram(frame.into());
}

/// Test/advanced hook: publish a reply channel before starting a tunnel so
/// rebuilt packets land somewhere inspectable.
pub fn install_reply_channel(tx: TunTx) {
    *TUN_TX.write().unwrap_or_else(|p| p.into_inner()) = Some(tx);
}

/// Test/advanced hooks for flow-table and generation introspection.
pub fn register_flow(src_port: u16, local_ip: [u8; 4], dst_ip: [u8; 4], dst_port: u16) {
    udp_flows()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .insert(
            src_port,
            UdpFlow {
                local_ip,
                dst_ip,
                dst_port,
            },
        );
}

pub fn engine_generation() -> u64 {
    ENGINE_GEN.load(Ordering::SeqCst)
}

/// Reverse path: relay datagrams from the host are rebuilt as IPv4/UDP
/// packets addressed back to the originating TUN flow. Runs until the
/// generation is superseded or the connection dies.
pub async fn pump_udp_replies(client: std::sync::Arc<TunnelClient>, gen: u64) {
    loop {
        if ENGINE_GEN.load(Ordering::SeqCst) != gen {
            return;
        }
        let datagram = match client.connection().read_datagram().await {
            Ok(d) => d,
            Err(_) => return,
        };
        let (hdr, off) = match UdpRelayHeader::decode(&datagram) {
            Ok(v) => v,
            Err(_) => continue,
        };
        // hdr.dst_* is the real remote peer that produced this data, but
        // the phone must see replies from its ORIGINAL destination (the
        // flow table records it) - critical for the virtual DNS IP.
        let local_src = udp_flows()
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&hdr.src_port)
            .copied();
        let Some(flow) = local_src else { continue };
        let Some(tx) = tun_tx() else { continue };
        let packet = build_udp_packet(
            (
                Ipv4Addr::from(flow.dst_ip),
                flow.dst_port,
            ),
            (Ipv4Addr::from(flow.local_ip), hdr.src_port),
            &datagram[off..],
        );
        if tx.send(packet).is_err() {
            return;
        }
    }
}

/// Build a complete IPv4/UDP packet. Header checksum is computed; the UDP
/// checksum is zero, which is valid for IPv4 and skips pseudo-header work.
pub fn build_udp_packet(
    src: (Ipv4Addr, u16),
    dst: (Ipv4Addr, u16),
    payload: &[u8],
) -> Vec<u8> {
    let total_len = (20 + 8 + payload.len()) as u16;
    let mut p = Vec::with_capacity(total_len as usize);
    p.extend_from_slice(&[0x45, 0x00]);
    p.extend_from_slice(&total_len.to_be_bytes());
    p.extend_from_slice(&[0x00, 0x00]); // identification
    p.extend_from_slice(&[0x40, 0x00]); // flags: DF
    p.push(64); // TTL
    p.push(17); // protocol: UDP
    p.extend_from_slice(&[0x00, 0x00]); // header checksum placeholder
    p.extend_from_slice(&src.0.octets());
    p.extend_from_slice(&dst.0.octets());
    p.extend_from_slice(&src.1.to_be_bytes());
    p.extend_from_slice(&dst.1.to_be_bytes());
    p.extend_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
    p.extend_from_slice(&[0x00, 0x00]); // UDP checksum: none (IPv4 allows it)
    p.extend_from_slice(payload);
    let csum = internet_checksum(&p[..20]);
    p[10..12].copy_from_slice(&csum.to_be_bytes());
    p
}

/// RFC 1071 ones'-complement checksum over big-endian 16-bit words.
pub fn internet_checksum(bytes: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut chunks = bytes.chunks_exact(2);
    for c in &mut chunks {
        sum += u16::from_be_bytes([c[0], c[1]]) as u32;
    }
    if let [hi] = chunks.remainder() {
        sum += u32::from(*hi) << 8;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

fn set_nonblocking(fd: RawFd) -> Result<(), i32> {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL, 0) };
    if flags < 0 {
        return Err(flags);
    }
    if unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(-1);
    }
    Ok(())
}

/// Minimal IPv4/IPv6 summary for the capture log.
fn describe(packet: &[u8]) -> String {
    if packet.len() >= 20 && packet[0] >> 4 == 4 {
        let proto = packet[9];
        let src = format!("{}.{}.{}.{}", packet[12], packet[13], packet[14], packet[15]);
        let dst = format!("{}.{}.{}.{}", packet[16], packet[17], packet[18], packet[19]);
        format!("v4 proto={proto} {src}->{dst}")
    } else if !packet.is_empty() && packet[0] >> 4 == 6 {
        format!("v6 proto={} ({}B)", packet[6], packet.len())
    } else {
        format!("non-ip ({}B)", packet.len())
    }
}

fn create_string<'local>(env: JNIEnv<'local>, value: &str) -> JString<'local> {
    match env.new_string(value) {
        Ok(s) => s,
        Err(_) => match env.new_string("") {
            Ok(s) => s,
            Err(_) => unsafe { JString::from_raw(std::ptr::null_mut()) },
        },
    }
}

fn get_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default()
}

// Keep JValue referenced so the import stays valid across cfg combinations.
#[allow(dead_code)]
const _: Option<JValue> = None;

/// Android logcat bridge used by the capture task.
pub(crate) fn jni_log(message: &str) {
    // Simple stdout logging is invisible on-device; route through Timber via
    // Kotlin instead is overkill here, so use the NDK-style logcat write.
    // For M6 we accept stderr-in-tests / silent-on-device behavior and rely on
    // packet counters surfaced through tunPacketCount().
    let _ = message;
}
