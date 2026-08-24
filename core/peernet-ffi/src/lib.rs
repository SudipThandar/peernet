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
use std::time::{Duration, Instant};

use jni::objects::{JClass, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use peernet_client::{ClientOptions, ClientState, TunnelClient};
use peernet_core::SessionId;
use peernet_host::HostServer;
use peernet_proto::{
    write_frame, MessageKind, PeerMessage, TcpRelayHeader, UdpRelayHeader,
};
use peernet_tcp::{FlowKey, FromUpstream, TcpStack, ToUpstream};
use tokio::io::unix::AsyncFd;
use std::io::{Read as _, Write as _};

/// Dedicated engine runtime — JNI has no ambient tokio context.
fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        init_logging();
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_io()
            .enable_time()
            .build()
            .expect("tokio runtime")
    })
}

/// Logcat bridge so on-device failures are diagnosable via
/// `adb logcat -s PeerNet`. No-op outside Android.
fn init_logging() {
    #[cfg(target_os = "android")]
    {
        let _ = android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("PeerNet"),
        );
        log::info!("engine logging online ({} v{})", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION"));
    }
    install_panic_hook();
}

/// A panic inside a spawned engine task only kills that task: the tunnel then
/// looks connected while its data path is dead, with nothing on screen. Route
/// panics into `lastError()` so the app can show them (the tester has no adb).
fn install_panic_hook() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let where_ = info
                .location()
                .map(|l| format!(" at {}:{}", l.file(), l.line()))
                .unwrap_or_default();
            let what = info
                .payload()
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| info.payload().downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic".to_string());
            set_last_error(&format!("engine panic: {what}{where_}"));
            jni_log(&format!("[panic] {what}{where_}"));
            previous(info);
        }));
    });
}

// ---------- TUN capture state ----------

static TUN_FD: AtomicI32 = AtomicI32::new(-1);
static TUN_STOP: AtomicBool = AtomicBool::new(false);
/// True while a capture session owns the TUN; lets the reply writer bail
/// out promptly when the reader half dies.
static CAPTURE_ALIVE: AtomicBool = AtomicBool::new(false);
static PACKETS: AtomicU64 = AtomicU64::new(0);
static BYTES: AtomicU64 = AtomicU64::new(0);

/// TUN flow table for the UDP reverse path.
///
/// Keyed by the FULL tuple (phone source port + the destination the app
/// addressed), *not* by source port alone. One UDP source port routinely fans
/// out to many destinations: that is precisely what ICE/WebRTC does when it
/// probes STUN, TURN and peer candidates in parallel from a single socket.
/// Keying by port alone let each new destination overwrite the previous one,
/// so every reply was rebuilt claiming to come from whichever candidate was
/// contacted last. ICE requires a response to arrive from the address the
/// request was sent to (RFC 8445 symmetry check), so every connectivity check
/// was discarded and calls never established - while single-destination UDP
/// (one media server, plain DNS) worked perfectly and hid the defect.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
struct UdpFlowKey {
    src_port: u16,
    dst_ip: [u8; 4],
    dst_port: u16,
}

#[derive(Clone, Copy)]
struct UdpFlow {
    local_ip: [u8; 4],
    /// Last packet seen on this flow, for idle expiry.
    last_seen: Instant,
}

/// Upper bound on tracked flows. A tuple-keyed table grows with ICE fan-out,
/// so it must be swept and capped instead of living for the whole session.
const MAX_UDP_FLOWS: usize = 2048;

/// Idle lifetime of a flow entry, matched to the host's NAT idle timeout so
/// both ends forget a flow at roughly the same time.
const UDP_FLOW_IDLE_SECS: u64 = peernet_proto::UDP_NAT_IDLE_TIMEOUT_SECS;

struct FlowTable {
    flows: HashMap<UdpFlowKey, UdpFlow>,
    /// Most recent destination per source port. Covers the two cases where a
    /// reply cannot be matched on the exact tuple:
    /// - the host rewrote the destination (virtual DNS -> real resolver), so
    ///   the peer that answers is not the address the phone addressed;
    /// - the stream fallback path, which carries no peer address at all.
    last_by_port: HashMap<u16, UdpFlowKey>,
}

fn flow_table() -> &'static Mutex<FlowTable> {
    static S: OnceLock<Mutex<FlowTable>> = OnceLock::new();
    S.get_or_init(|| {
        Mutex::new(FlowTable {
            flows: HashMap::new(),
            last_by_port: HashMap::new(),
        })
    })
}

/// Record an outbound UDP flow before the payload is relayed.
fn note_udp_flow(src_port: u16, local_ip: [u8; 4], dst_ip: [u8; 4], dst_port: u16) {
    let key = UdpFlowKey {
        src_port,
        dst_ip,
        dst_port,
    };
    let now = Instant::now();
    let mut table = flow_table().lock().unwrap_or_else(|p| p.into_inner());
    let fresh = table
        .flows
        .insert(
            key,
            UdpFlow {
                local_ip,
                last_seen: now,
            },
        )
        .is_none();
    let previous = table.last_by_port.insert(src_port, key);
    if fresh {
        UDP_FLOWS_OPENED.fetch_add(1, Ordering::Relaxed);
        // One source port reaching a second destination is the ICE fan-out
        // signature. Counted so the on-screen stats can prove the shape of
        // the traffic without logging any payload.
        if matches!(previous, Some(prev) if prev != key) {
            let n = UDP_FANOUT_PORTS.fetch_add(1, Ordering::Relaxed) + 1;
            if n == 1 {
                jni_log("[udp] source port fanning out to multiple destinations (ICE-style)");
            }
        }
    }
    if table.flows.len() > MAX_UDP_FLOWS {
        sweep_udp_flows(&mut table, now);
    }
}
/// Drop flows idle past `UDP_FLOW_IDLE_SECS`, then the oldest survivors if the
/// table is still over cap.
fn sweep_udp_flows(table: &mut FlowTable, now: Instant) {
    let FlowTable {
        flows,
        last_by_port,
    } = table;
    let idle = Duration::from_secs(UDP_FLOW_IDLE_SECS);
    let before = flows.len();
    flows.retain(|_, f| now.duration_since(f.last_seen) < idle);
    if flows.len() > MAX_UDP_FLOWS {
        // Trim below the cap rather than to it: trimming exactly to the cap
        // would re-sort the whole table on every subsequent packet once it is
        // full, turning a bounded table into a per-packet cost on the phone.
        let target = MAX_UDP_FLOWS * 3 / 4;
        let mut aged: Vec<(UdpFlowKey, Instant)> =
            flows.iter().map(|(k, f)| (*k, f.last_seen)).collect();
        aged.sort_by_key(|(_, seen)| *seen);
        let excess = flows.len().saturating_sub(target);
        for (key, _) in aged.into_iter().take(excess) {
            flows.remove(&key);
        }
    }
    let removed = before.saturating_sub(flows.len());
    if removed > 0 {
        UDP_FLOWS_EXPIRED.fetch_add(removed as u64, Ordering::Relaxed);
        // A remap pointing at an evicted flow would resurrect a dead address.
        last_by_port.retain(|_, key| flows.contains_key(&*key));
    }
}

/// Resolve the source address a reply must appear to come from, refreshing the
/// flow's idle timer. Returns `(reply_src_ip, reply_src_port, phone_ip)`.
///
/// `peer` is the real remote that produced the data, as reported by the host.
/// Matching the exact tuple first keeps per-destination identity intact, which
/// is what ICE verifies. Only when no such flow exists do we fall back to the
/// most recent destination on that port - that is how a host-rewritten
/// destination (virtual DNS -> real resolver) is mapped back to the address the
/// phone actually addressed.
fn resolve_reply_source(
    src_port: u16,
    peer: Option<([u8; 4], u16)>,
) -> Option<(Ipv4Addr, u16, Ipv4Addr)> {
    let now = Instant::now();
    let mut table = flow_table().lock().unwrap_or_else(|p| p.into_inner());
    if let Some((ip, port)) = peer {
        let key = UdpFlowKey {
            src_port,
            dst_ip: ip,
            dst_port: port,
        };
        if let Some(flow) = table.flows.get_mut(&key) {
            flow.last_seen = now;
            let local = Ipv4Addr::from(flow.local_ip);
            UDP_REPLY_EXACT.fetch_add(1, Ordering::Relaxed);
            return Some((Ipv4Addr::from(ip), port, local));
        }
    }
    let key = *table.last_by_port.get(&src_port)?;
    let flow = table.flows.get_mut(&key)?;
    flow.last_seen = now;
    let local = Ipv4Addr::from(flow.local_ip);
    UDP_REPLY_REMAPPED.fetch_add(1, Ordering::Relaxed);
    Some((Ipv4Addr::from(key.dst_ip), key.dst_port, local))
}

fn clear_udp_flows() {
    let mut table = flow_table().lock().unwrap_or_else(|p| p.into_inner());
    table.flows.clear();
    table.last_by_port.clear();
}

/// Open flows and distinct source ports, for the diagnostics line.
fn udp_flow_gauges() -> (usize, usize) {
    let table = flow_table().lock().unwrap_or_else(|p| p.into_inner());
    (table.flows.len(), table.last_by_port.len())
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
        .map(|t| t.clone())
}

/// Outbound TCP is terminated locally by the peernet-tcp engine; count the
/// packets handed to it for visibility.
static TCP_TERMINATED: AtomicU64 = AtomicU64::new(0);
static UDP_FORWARDED: AtomicU64 = AtomicU64::new(0);

/// Payloads that came *back* from the host (UDP replies + TCP stream data)
/// **and reached the TUN**. The one number that distinguishes "tunnel works"
/// from "tunnel connected to a host that cannot reach the internet".
static INBOUND: AtomicU64 = AtomicU64::new(0);

/// Replies that arrived from the host but could not be handed to the phone
/// (unknown flow, or the TUN writer is gone). Nonzero here means the tunnel
/// is healthy and the *local* delivery path is broken — the two failures look
/// identical on screen otherwise.
static UNDELIVERED: AtomicU64 = AtomicU64::new(0);

/// UDP reverse-path shape, all aggregate and payload-free. These exist because
/// "UDP works" and "UDP works only for single-destination flows" are
/// indistinguishable from the older counters, and the difference is exactly
/// what decides whether a WebRTC call can establish.
///
/// - `UDP_FLOWS_OPENED`  distinct (port, destination) pairs ever tracked
/// - `UDP_FLOWS_EXPIRED` entries dropped by the idle sweep / cap
/// - `UDP_FANOUT_PORTS`  times a port reached an additional destination
///   (nonzero = ICE-style fan-out is present)
/// - `UDP_REPLY_EXACT`   replies matched on the full tuple (correct source)
/// - `UDP_REPLY_REMAPPED` replies matched only by port, so the source had to
///   be remapped (expected for the virtual DNS, and for the stream fallback)
/// - `UDP_REPLY_UNMATCHED` replies with no flow at all: silently lost before
static UDP_FLOWS_OPENED: AtomicU64 = AtomicU64::new(0);
static UDP_FLOWS_EXPIRED: AtomicU64 = AtomicU64::new(0);
static UDP_FANOUT_PORTS: AtomicU64 = AtomicU64::new(0);
static UDP_REPLY_EXACT: AtomicU64 = AtomicU64::new(0);
static UDP_REPLY_REMAPPED: AtomicU64 = AtomicU64::new(0);
static UDP_REPLY_UNMATCHED: AtomicU64 = AtomicU64::new(0);

/// How long the engine's packet forwarder waits for a TUN write channel
/// (50 ms per try) before giving up on a packet.
const TUN_TX_WAIT_TRIES: usize = 40;

/// Consecutive failed TUN writes tolerated before the writer gives up. One
/// rejected packet is normal; a wall of them means the interface is gone.
const WRITE_FAILURE_LIMIT: u32 = 16;

/// Consecutive failed TUN reads tolerated before the capture ends, for the
/// same reason.
const READ_FAILURE_LIMIT: u32 = 16;

/// Intake into the active session's TCP terminator engine. Present only
/// while a tunnel owns an engine; teardown drops it, which lets the engine
/// thread wind down.
type TcpPktTx = std::sync::mpsc::Sender<Vec<u8>>;

static TCP_PKT_TX: std::sync::RwLock<Option<TcpPktTx>> = std::sync::RwLock::new(None);

fn tcp_pkt_in() -> Option<TcpPktTx> {
    TCP_PKT_TX
        .read()
        .unwrap_or_else(|p| p.into_inner())
        .as_ref()
        .map(|t| t.clone())
}

fn tcp_teardown() {
    *TCP_PKT_TX.write().unwrap_or_else(|p| p.into_inner()) = None;
}

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

/// Last engine failure in human-readable form. Surfaced to the UI through
/// `lastError()` so on-device diagnosis never requires adb.
fn last_error_slot() -> &'static Mutex<String> {
    static S: OnceLock<Mutex<String>> = OnceLock::new();
    S.get_or_init(|| Mutex::new(String::new()))
}

fn set_last_error(msg: &str) {
    crate::jni_log(msg);
    *last_error_slot().lock().unwrap_or_else(|p| p.into_inner()) = msg.to_string();
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
    // Per-session counters: stale totals from a previous tunnel would make a
    // dead data path look alive on the diagnostics line.
    UDP_FORWARDED.store(0, Ordering::Relaxed);
    TCP_TERMINATED.store(0, Ordering::Relaxed);
    INBOUND.store(0, Ordering::Relaxed);
    UNDELIVERED.store(0, Ordering::Relaxed);
    UDP_FLOWS_OPENED.store(0, Ordering::Relaxed);
    UDP_FLOWS_EXPIRED.store(0, Ordering::Relaxed);
    UDP_FANOUT_PORTS.store(0, Ordering::Relaxed);
    UDP_REPLY_EXACT.store(0, Ordering::Relaxed);
    UDP_REPLY_REMAPPED.store(0, Ordering::Relaxed);
    UDP_REPLY_UNMATCHED.store(0, Ordering::Relaxed);

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

/// Binds the QUIC host server from *any* thread.
///
/// quinn registers the UDP socket with the ambient tokio reactor while
/// `Endpoint::server` runs, and a JNI thread has no runtime context: without
/// entering the engine runtime first this fails on-device with "no async
/// runtime found", the host has no certificate to advertise, and every client
/// silently loses its tunnel — while every `#[tokio::test]` still passes.
pub fn bind_host_server(addr: SocketAddr, device_name: &str, dns: &str) -> Result<HostServer, String> {
    let _guard = runtime().enter();
    let server = HostServer::bind(addr, device_name)?;
    match SocketAddr::from_str(dns.trim()) {
        Ok(resolver) => {
            server.set_dns_upstream(resolver);
            crate::jni_log(&format!("[host] DNS upstream {resolver}"));
        }
        Err(_) => {
            crate::jni_log("[host] DNS upstream unparsable; keeping built-in default");
        }
    }
    Ok(server)
}

/// NativeCore.startHost(port: Int, deviceName: String, dnsUpstream: String) -> String
///
/// Binds the QUIC host server on 0.0.0.0:port and runs its accept loop on the
/// engine runtime. `dnsUpstream` ("ip:port") is where client DNS queries aimed
/// at the tunnel's virtual resolver get redirected; an unparsable value falls
/// back to the server default. Returns the SHA-256 certificate fingerprint
/// (lowercase hex) for advertisement, or "" when a server is already running
/// or the bind failed (reason readable via lastError()).
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_startHost<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    port: jint,
    device_name: JString<'local>,
    dns_upstream: JString<'local>,
) -> JString<'local> {
    let name = get_string(&mut env, &device_name);
    let dns = get_string(&mut env, &dns_upstream);
    let mut guard = host_slot().lock().unwrap_or_else(|p| p.into_inner());
    if guard.is_some() {
        set_last_error("host engine already running on this port");
        return create_string(env, "");
    }
    let addr = SocketAddr::from(([0, 0, 0, 0], port.clamp(1, 65535) as u16));
    match bind_host_server(addr, &name, &dns) {
        Ok(server) => {
            let fingerprint = server.fingerprint_hex().to_string();
            let server = std::sync::Arc::new(server);
            runtime().spawn({
                let server = server.clone();
                async move { server.run().await }
            });
            *guard = Some(server);
            crate::jni_log(&format!("[host] engine up on {addr}, pin {fingerprint}"));
            create_string(env, &fingerprint)
        }
        Err(e) => {
            set_last_error(&format!("host bind failed: {e}"));
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
        set_last_error(&format!("bad host address '{addr}'"));
        return 0;
    };
    // A missing/short fingerprint can never authenticate the host: fail loud
    // here instead of letting the TLS layer reject it seconds later with the
    // TUN already installed (which looks like "connected, no internet").
    if fp.trim().len() != 64 || !fp.trim().chars().all(|c| c.is_ascii_hexdigit()) {
        set_last_error("host fingerprint missing or malformed (host engine not ready?)");
        return 0;
    }
    if CLIENT_STARTING.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_err() {
        return 0;
    }

    set_last_error("");
    TUNNEL_STATE.store(1, Ordering::SeqCst); // Connecting
    let gen = ENGINE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    runtime().spawn(async move {
        let opts = ClientOptions::new(parsed, "peernet-host", fp.trim().to_string(), name);
        match TunnelClient::connect(opts).await {
            Ok(client) => {
                if ENGINE_GEN.load(Ordering::SeqCst) != gen {
                    // Superseded by stop/new start during the handshake.
                    client.shutdown();
                    return;
                }
                let client = std::sync::Arc::new(client);
                crate::jni_log(&format!("[client] QUIC connected gen={gen}"));
                *client_slot().lock().unwrap_or_else(|p| p.into_inner()) = Some(client.clone());
                CLIENT_STARTING.store(false, Ordering::SeqCst);
                // Reverse path: rebuild relay datagrams into TUN packets
                // until this generation is superseded or the link dies.
                tokio::spawn(pump_udp_replies(client.clone(), gen));
                // Local TCP termination feeding QUIC relay streams.
                spawn_tcp_termination(client.clone(), gen);
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
                tcp_teardown();
                *client_slot().lock().unwrap_or_else(|p| p.into_inner()) = None;
            }
            Err(e) => {
                set_last_error(&format!("tunnel connect failed: {e}"));
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
    clear_udp_flows();
    tcp_teardown();
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

/// NativeCore.lastError() -> String ("" when nothing failed since the last
/// successful start). Lets the single-screen UI explain failures on the
/// device instead of requiring a logcat session.
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_lastError<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let msg = last_error_slot()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone();
    create_string(env, &msg)
}

/// NativeCore.engineStats() -> String, e.g.
/// "tun=1420 udp=310 tcp=88 in=402 lost=0 cap=up eng=up
///  flows=12/5 fnew=31 fexp=4 fanout=6 rok=280 rmap=25 rmiss=0".
///
/// Proves which half of the data path is moving when a user reports
/// "connected but nothing loads": `in=0` with the others rising means the
/// host is reachable but is not relaying (typically the host has no internet).
///
/// The flow gauges describe the UDP reverse path, which is what decides
/// whether real-time calls can establish:
/// - `flows=open/ports` currently tracked (port,destination) pairs / ports
/// - `fnew`/`fexp`      flows ever opened / swept
/// - `fanout`           times one port reached an extra destination; nonzero
///                      means ICE-style multiplexing is in play
/// - `rok`/`rmap`       replies matched exactly / remapped via the port
///                      fallback (expected for the virtual DNS)
/// - `rmiss`            replies no flow could claim - these never reach the app
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_engineStats<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let (flows_open, ports_open) = udp_flow_gauges();
    let stats = format!(
        "tun={} udp={} tcp={} in={} lost={} cap={} eng={} \
         flows={}/{} fnew={} fexp={} fanout={} rok={} rmap={} rmiss={}",
        PACKETS.load(Ordering::Relaxed),
        UDP_FORWARDED.load(Ordering::Relaxed),
        TCP_TERMINATED.load(Ordering::Relaxed),
        INBOUND.load(Ordering::Relaxed),
        UNDELIVERED.load(Ordering::Relaxed),
        // The capture loop dying is invisible otherwise: counters simply stop
        // moving, which reads as "the phone sent nothing".
        if CAPTURE_ALIVE.load(Ordering::SeqCst) { "up" } else { "down" },
        // Distinguishes "no TCP engine to feed" from "the phone sent no SYN"
        // when tcp= stays 0.
        if tcp_pkt_in().is_some() { "up" } else { "down" },
        // UDP reverse-path shape. `fanout>0` means at least one app is
        // multiplexing destinations over a single port (WebRTC/ICE); `rmiss>0`
        // means replies arrived that no flow could claim.
        flows_open,
        ports_open,
        UDP_FLOWS_OPENED.load(Ordering::Relaxed),
        UDP_FLOWS_EXPIRED.load(Ordering::Relaxed),
        UDP_FANOUT_PORTS.load(Ordering::Relaxed),
        UDP_REPLY_EXACT.load(Ordering::Relaxed),
        UDP_REPLY_REMAPPED.load(Ordering::Relaxed),
        UDP_REPLY_UNMATCHED.load(Ordering::Relaxed),
    );
    create_string(env, &stats)
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
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Vec<u8>>();
    install_reply_channel(tx);

    // Reply writer: rebuilt packets -> TUN. Exits when the pump side goes
    // away (channel closed) or the capture session ends.
    let writer = tokio::spawn(async move {
        let mut async_fd = writer_fd;
        let mut consecutive_failures = 0u32;
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
            let mut fd_dead = false;
            while !wrote {
                let mut guard = match async_fd.writable_mut().await {
                    Ok(g) => g,
                    Err(_) => {
                        fd_dead = true;
                        break;
                    }
                };
                match guard.try_io(|inner| inner.get_mut().write(&pkt)) {
                    Ok(Ok(_)) => wrote = true,
                    Ok(Err(e)) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                    // A single packet the TUN rejects (oversized, malformed)
                    // must not end inbound delivery for the whole session:
                    // drop it and keep serving the rest.
                    Ok(Err(_)) => break,
                    // Same trap as the reader: a full TUN queue arrives as
                    // Err(TryIoError) with readiness cleared. Retry after the
                    // next writability event instead of killing the writer,
                    // which would silently strand every inbound reply.
                    Err(_) => continue,
                }
            }
            if wrote {
                consecutive_failures = 0;
            } else {
                UNDELIVERED.fetch_add(1, Ordering::Relaxed);
                consecutive_failures += 1;
                // Only a genuinely dead fd (or relentless failure) ends the
                // writer; that is what stop uses to wake and exit.
                if fd_dead || consecutive_failures >= WRITE_FAILURE_LIMIT {
                    break;
                }
            }
        }
    });

    // Outbound reader: phone -> tunnel.
    let mut async_fd = reader_fd;
    let mut buf = vec![0u8; mtu.max(1500)];
    let mut logged = 0u64;
    let mut read_failures = 0u32;
    loop {
        if TUN_STOP.load(Ordering::SeqCst) {
            break;
        }
        let mut guard = match async_fd.readable_mut().await {
            Ok(g) => g,
            Err(_) => break,
        };
        let result = guard.try_io(|inner| inner.get_mut().read(&mut buf));
        match result {
            Ok(Ok(0)) => break, // EOF: interface closed
            Ok(Ok(n)) => {
                read_failures = 0;
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
            // A signal-interrupted read is not a dead interface.
            Ok(Err(e)) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            // One rejected read must not end the session either; a persistent
            // wall of them (closed fd) still exits.
            Ok(Err(_)) => {
                read_failures += 1;
                if read_failures >= READ_FAILURE_LIMIT {
                    break;
                }
                continue;
            }
            // `try_io` intercepts WouldBlock itself: the closure's EAGAIN is
            // reported as Err(TryIoError) after clearing readiness, NOT as
            // Ok(Err(WouldBlock)). Treating it as fatal used to end the whole
            // capture after the very first packet (tun=1 forever, so every DNS
            // retry and TCP SYN sat unread in the TUN queue). Readiness was
            // cleared, so the next await blocks until real data arrives.
            Err(_) => continue,
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
        // Local userspace termination: hand the packet to the smoltcp-based
        // engine, which turns flows into QUIC relay streams.
        if let Some(tx) = tcp_pkt_in() {
            if tx.send(packet[..end].to_vec()).is_ok() {
                let n = TCP_TERMINATED.fetch_add(1, Ordering::Relaxed) + 1;
                if n == 1 {
                    crate::jni_log("[tcp] first flow packet terminated locally");
                }
            }
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

    note_udp_flow(sport, src_ip.octets(), dst_ip.octets(), dport);

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

    // QUIC datagrams are capped by the peer's advertised limit and are
    // unreliable by design. Dropping the rejects silently made large UDP
    // (QUIC/HTTP3 handshakes, big DNS answers) disappear, which looks like
    // "some sites are slow" as browsers wait out a timeout and fall back to
    // TCP. Anything the datagram path refuses goes over a stream instead.
    if let Err(e) = client.connection().send_datagram(frame.clone().into()) {
        jni_log(&format!("[udp] datagram rejected ({e}); relaying over stream"));
        let Some(owned) = current_client() else { return };
        let payload = payload.to_vec();
        let dst = SocketAddr::new(IpAddr::V4(dst_ip), dst_port);
        runtime().spawn(async move {
            match owned.udp_exchange_via_stream(src_port, dst, &payload).await {
                Ok(reply) => deliver_udp_reply(src_port, &reply),
                Err(e) => jni_log(&format!("[udp] stream relay failed: {e}")),
            }
        });
    }
}

/// Rebuilds a UDP reply for the phone's stack and writes it to the TUN.
///
/// Used by the stream fallback, which relays only (src_port, payload) and so
/// carries no peer address - the flow table's per-port fallback is the only
/// information available here.
fn deliver_udp_reply(src_port: u16, payload: &[u8]) {
    let Some((src_ip, reply_port, local_ip)) = resolve_reply_source(src_port, None) else {
        UNDELIVERED.fetch_add(1, Ordering::Relaxed);
        UDP_REPLY_UNMATCHED.fetch_add(1, Ordering::Relaxed);
        return;
    };
    let Some(tx) = tun_tx() else {
        UNDELIVERED.fetch_add(1, Ordering::Relaxed);
        return;
    };
    let packet = build_udp_packet((src_ip, reply_port), (local_ip, src_port), payload);
    if tx.send(packet).is_err() {
        UNDELIVERED.fetch_add(1, Ordering::Relaxed);
        return;
    }
    INBOUND.fetch_add(payload.len() as u64, Ordering::Relaxed);
}

/// Test/advanced hook: publish a reply channel before starting a tunnel so
/// rebuilt packets land somewhere inspectable.
pub fn install_reply_channel(tx: TunTx) {
    *TUN_TX.write().unwrap_or_else(|p| p.into_inner()) = Some(tx);
}

/// Test/advanced hooks for flow-table and generation introspection.
pub fn register_flow(src_port: u16, local_ip: [u8; 4], dst_ip: [u8; 4], dst_port: u16) {
    note_udp_flow(src_port, local_ip, dst_ip, dst_port);
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
        // hdr.dst_* is the real remote peer that produced this data. Preserve
        // it: rebuilding the reply from a port-keyed guess breaks any app that
        // multiplexes destinations over one socket (ICE/WebRTC). The flow table
        // only supplies the phone-side address, and remaps the source when the
        // host rewrote the destination (virtual DNS).
        let peer = match hdr.dst_ip {
            IpAddr::V4(ip) => Some((ip.octets(), hdr.dst_port)),
            IpAddr::V6(_) => None,
        };
        let Some((src_ip, reply_port, local_ip)) = resolve_reply_source(hdr.src_port, peer) else {
            UNDELIVERED.fetch_add(1, Ordering::Relaxed);
            UDP_REPLY_UNMATCHED.fetch_add(1, Ordering::Relaxed);
            continue;
        };
        let Some(tx) = tun_tx() else {
            UNDELIVERED.fetch_add(1, Ordering::Relaxed);
            continue;
        };
        let packet = build_udp_packet(
            (src_ip, reply_port),
            (local_ip, hdr.src_port),
            &datagram[off..],
        );
        if tx.send(packet).is_err() {
            // The writer half is gone: nothing can reach the phone anymore.
            UNDELIVERED.fetch_add(1, Ordering::Relaxed);
            return;
        }
        // Counted only once the bytes are queued for the TUN. Counting them
        // on arrival made `in=` claim success while the reply was stranded.
        INBOUND.fetch_add((datagram.len() - off) as u64, Ordering::Relaxed);
    }
}

// ---------- Local TCP termination -> QUIC relay streams ----------
//
// Data path for one phone TCP flow:
//
//   TUN (proto 6) -> engine pkt_in -> smoltcp socket (per-SYN listener)
//   engine ToUpstream::Open/Data/Eof -> bridge thread -> orchestrator task
//     Open: open a QUIC bi-stream, write the framed TcpRelayHeader, spawn
//           a reader half that pumps internet bytes back into the engine
//     Data: raw payload onto that stream
//     Eof:  finish() = half-close toward the host
//   engine pkt_out (SYN-ACKs, data, ACKs) -> forwarder thread -> TUN write
//
/// Spawns the whole termination pipeline for this tunnel generation.
pub fn spawn_tcp_termination(client: std::sync::Arc<TunnelClient>, gen: u64) {
    crate::jni_log("[tcp] termination engine starting");
    let (pkt_out_tx, pkt_out_rx) = std::sync::mpsc::channel::<Vec<u8>>();
    let (to_up_tx, to_up_rx) = std::sync::mpsc::channel::<ToUpstream>();
    let (stack, pkt_in_tx, up_tx) = TcpStack::channels(pkt_out_tx, to_up_tx);

    // Engine loop; exits when any channel side hangs up.
    std::thread::spawn(move || stack.run());

    // Publish the intake so forward_outbound can feed it; teardown drops it.
    *TCP_PKT_TX.write().unwrap_or_else(|p| p.into_inner()) = Some(pkt_in_tx);

    // Engine-emitted packets back into the TUN writer. The TUN channel can
    // legitimately be absent for a moment (the tunnel is established BEFORE
    // the interface is installed, so failures never black-hole the phone):
    // wait for it instead of tearing the flow down forever.
    std::thread::spawn(move || {
        while let Ok(pkt) = pkt_out_rx.recv() {
            let mut delivered = false;
            for _ in 0..TUN_TX_WAIT_TRIES {
                match tun_tx() {
                    Some(tx) => {
                        if tx.send(pkt.clone()).is_ok() {
                            delivered = true;
                        }
                        break;
                    }
                    None => std::thread::sleep(Duration::from_millis(50)),
                }
            }
            if !delivered && tun_tx().is_none() {
                // Capture really is gone; the engine will be torn down.
                break;
            }
        }
    });

    // Bridge the std receiver into the async world.
    let (bridge_tx, bridge_rx) = tokio::sync::mpsc::unbounded_channel::<ToUpstream>();
    std::thread::spawn(move || {
        while let Ok(msg) = to_up_rx.recv() {
            if bridge_tx.send(msg).is_err() {
                break;
            }
        }
    });

    runtime().spawn(run_tcp_relays(client, gen, bridge_rx, up_tx));
}

/// Per-flow relay orchestration on the engine runtime. One QUIC bi-stream
/// carries one phone TCP flow; EOF in either direction propagates cleanly.
async fn run_tcp_relays(
    client: std::sync::Arc<TunnelClient>,
    gen: u64,
    mut upstream: tokio::sync::mpsc::UnboundedReceiver<ToUpstream>,
    from_up: std::sync::mpsc::Sender<FromUpstream>,
) {
    let mut flows: HashMap<FlowKey, quinn::SendStream> = HashMap::new();

    while ENGINE_GEN.load(Ordering::SeqCst) == gen {
        let msg = match upstream.recv().await {
            Some(m) => m,
            None => break,
        };
        match msg {
            ToUpstream::Open { flow } => {
                let dst_ip = Ipv4Addr::from(flow.dst_ip);
                let (mut tx, rx) = match client.connection().open_bi().await {
                    Ok(pair) => pair,
                    Err(e) => {
                        crate::jni_log(&format!("[tcp] open_bi failed for flow {flow:?}: {e}"));
                        let _ = from_up.send(FromUpstream::Eof { flow });
                        continue;
                    }
                };
                let frame = PeerMessage::new(
                    MessageKind::Data,
                    client.session_id(),
                    TcpRelayHeader {
                        src_port: flow.src_port,
                        dst_ip: IpAddr::V4(dst_ip),
                        dst_port: flow.dst_port,
                    }
                    .encode(),
                );
                if write_frame(&mut tx, &frame).await.is_err() {
                    crate::jni_log("[tcp] relay header send failed");
                    let _ = from_up.send(FromUpstream::Eof { flow });
                    continue;
                }
                crate::jni_log(&format!(
                    "[tcp] relay open -> {}:{} (local port {})",
                    dst_ip, flow.dst_port, flow.src_port
                ));
                // Internet -> phone reader half.
                let fu = from_up.clone();
                tokio::spawn(async move {
                    let mut rx = rx;
                    let mut buf = vec![0u8; 16 * 1024];
                    loop {
                        match rx.read(&mut buf).await {
                            Ok(Some(n)) if n > 0 => {
                                INBOUND.fetch_add(n as u64, Ordering::Relaxed);
                                let msg = FromUpstream::Data {
                                    flow,
                                    bytes: buf[..n].to_vec(),
                                };
                                if fu.send(msg).is_err() {
                                    break;
                                }
                            }
                            _ => break,
                        }
                    }
                    let _ = fu.send(FromUpstream::Eof { flow });
                });
                flows.insert(flow, tx);
            }
            ToUpstream::Data { flow, bytes } => {
                let wrote = match flows.get_mut(&flow) {
                    Some(tx) => tx.write_all(&bytes).await.is_ok(),
                    None => false,
                };
                if !wrote {
                    flows.remove(&flow);
                }
            }
            ToUpstream::Eof { flow } => {
                if let Some(mut tx) = flows.remove(&flow) {
                    // Half-close toward the host; its reply path stays open.
                    let _ = tx.finish();
                }
            }
        }
    }
    // Session over: tell the engine every remaining relay died so the
    // phone's sockets reset promptly instead of hanging until timeout.
    for (flow, _) in flows.drain() {
        let _ = from_up.send(FromUpstream::Eof { flow });
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
    #[cfg(target_os = "android")]
    log::info!("{}", message);
    #[cfg(not(target_os = "android"))]
    let _ = message;
}

#[cfg(test)]
mod tests {
    use super::*;

    const CLIENT_TUN_IP: [u8; 4] = [10, 215, 17, 2];
    const CLIENT_SRC_PORT: u16 = 54321;

    /// The engine's statics (counters, TUN_TX, CAPTURE_FDS, TUN_STOP) are
    /// process-global, so tests that drive a capture session or install a reply
    /// channel must not overlap — cargo runs tests in parallel by default.
    static ENGINE_LOCK: Mutex<()> = Mutex::new(());

    /// The host engine is started from a JNI thread, which has no tokio
    /// context. quinn needs one to register its UDP socket, so binding must
    /// enter the engine runtime itself. Regression guard for the defect that
    /// made every on-device share advertise an empty pin (no tunnel possible)
    /// while all `#[tokio::test]` cases passed, because they always supply a
    /// runtime that production never has.
    #[test]
    fn host_binds_from_a_thread_with_no_runtime() {
        std::thread::spawn(|| {
            assert!(
                tokio::runtime::Handle::try_current().is_err(),
                "test thread must mimic JNI: no ambient runtime"
            );
            let server = bind_host_server(
                SocketAddr::from(([127, 0, 0, 1], 0)),
                "runtime-less-host",
                "1.1.1.1:53",
            )
            .expect("host bind must not require an ambient runtime");
            assert_eq!(server.fingerprint_hex().len(), 64, "pin must be advertisable");
            server.shutdown();
        })
        .join()
        .expect("bind thread panicked");

        // Proof the guard above is not vacuous: quinn resolves its async
        // runtime while binding, so the bare call is what broke production.
        // If this ever starts succeeding, quinn changed and the wrapper's
        // comment (not the wrapper) is what needs updating.
        let bare = std::thread::spawn(|| {
            HostServer::bind(SocketAddr::from(([127, 0, 0, 1], 0)), "bare-host")
        })
        .join()
        .expect("bare bind thread panicked");
        assert!(
            bare.is_err(),
            "expected quinn to reject a runtime-less bind; got a live server"
        );
    }

    /// The capture loop must survive an idle gap between packets.
    ///
    /// `AsyncFd::try_io` reports the closure's EAGAIN as `Err(TryIoError)`
    /// (after clearing readiness), *not* as `Ok(Err(WouldBlock))`. Treating
    /// that as fatal ended the capture right after the first packet, so the
    /// phone's later DNS retries and TCP SYNs were never read: on-device
    /// counters froze at `tun=1` and the tunnel looked dead while QUIC was
    /// perfectly healthy.
    ///
    /// A socketpair stands in for the TUN: writing packet #2 only after the
    /// reader has already drained packet #1 forces the EAGAIN path in between.
    #[tokio::test(flavor = "multi_thread")]
    async fn capture_survives_an_idle_gap_between_packets() {
        use std::os::fd::{FromRawFd, OwnedFd};

        let _serial = ENGINE_LOCK.lock().unwrap_or_else(|p| p.into_inner());

        let mut fds = [0i32; 2];
        // SOCK_SEQPACKET keeps datagram boundaries like a TUN does.
        let rc = unsafe {
            libc::socketpair(libc::AF_UNIX, libc::SOCK_SEQPACKET, 0, fds.as_mut_ptr())
        };
        assert_eq!(rc, 0, "socketpair failed");
        let phone_side = fds[0];
        let tun_side = unsafe { OwnedFd::from_raw_fd(fds[1]) };

        PACKETS.store(0, Ordering::Relaxed);
        UDP_FORWARDED.store(0, Ordering::Relaxed);
        TUN_STOP.store(false, Ordering::SeqCst);

        tokio::spawn(async move { run_capture(tun_side, 1500).await });

        // No tunnel client is installed, so forwarding is a no-op; PACKETS is
        // what proves the loop kept reading.
        let packet = build_udp_packet(
            (Ipv4Addr::from(CLIENT_TUN_IP), CLIENT_SRC_PORT),
            (Ipv4Addr::new(10, 215, 17, 1), 53),
            b"query",
        );

        for i in 0..3 {
            let sent = unsafe {
                libc::send(
                    phone_side,
                    packet.as_ptr() as *const libc::c_void,
                    packet.len(),
                    0,
                )
            };
            assert!(sent > 0, "send #{i} failed");

            // Let the reader drain and hit EAGAIN before the next packet.
            let deadline = std::time::Instant::now() + Duration::from_secs(5);
            while PACKETS.load(Ordering::Relaxed) < (i + 1) as u64 {
                assert!(
                    std::time::Instant::now() < deadline,
                    "capture stopped reading after {} packet(s)",
                    PACKETS.load(Ordering::Relaxed)
                );
                tokio::time::sleep(Duration::from_millis(25)).await;
            }
            tokio::time::sleep(Duration::from_millis(60)).await;
        }

        assert_eq!(PACKETS.load(Ordering::Relaxed), 3, "all packets must be read");
        assert!(
            CAPTURE_ALIVE.load(Ordering::SeqCst),
            "capture must still be alive after idle gaps"
        );

        // Leave the globals as they were found: closing the peer makes the
        // reader see EOF and shut the session down cleanly.
        unsafe { libc::close(phone_side) };
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while CAPTURE_ALIVE.load(Ordering::SeqCst)
            && std::time::Instant::now() < deadline
        {
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
        TUN_STOP.store(false, Ordering::SeqCst);
    }

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

    /// Full UDP roundtrip without a TUN device: relay helper -> QUIC ->
    /// host NAT -> echo socket -> reply pump -> rebuilt IPv4/UDP packet.
    #[tokio::test(flavor = "multi_thread")]
    async fn udp_roundtrip_through_host_nat() {
        let _serial = ENGINE_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let echo_addr = start_echo();

        let server =
            HostServer::bind("127.0.0.1:0".parse().unwrap(), "test-host").unwrap();
        let fp = server.fingerprint_hex().to_string();
        let server_addr = server.local_addr();
        let server = std::sync::Arc::new(server);
        tokio::spawn({
            let s = server.clone();
            async move { s.run().await }
        });

        let opts = ClientOptions::new(server_addr, "peernet-host", fp, "test-client");
        let client = std::sync::Arc::new(
            TunnelClient::connect(opts).await.expect("client handshake"),
        );

        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Vec<u8>>();
        install_reply_channel(tx);
        let gen = engine_generation();
        tokio::spawn(pump_udp_replies(client.clone(), gen));

        register_flow(
            CLIENT_SRC_PORT,
            CLIENT_TUN_IP,
            Ipv4Addr::LOCALHOST.octets(),
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

        assert!(packet.len() >= 28);
        // Source must be the flow's ORIGINAL destination (echo here), not
        // whatever upstream actually answered - this is what makes the
        // virtual DNS IP work.
        assert_eq!(&packet[12..16], &Ipv4Addr::LOCALHOST.octets());
        assert_eq!(&packet[16..20], &CLIENT_TUN_IP);
        let src_port = u16::from_be_bytes([packet[20], packet[21]]);
        let dst_port = u16::from_be_bytes([packet[22], packet[23]]);
        assert_eq!(src_port, echo_addr.port());
        assert_eq!(dst_port, CLIENT_SRC_PORT);

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
        assert_eq!(u16::from_be_bytes([p[2], p[3]]), p.len() as u16);
        assert_eq!(p[9], 17); // proto UDP
        let declared = u16::from_be_bytes([p[24], p[25]]) as usize;
        assert_eq!(declared, 8 + payload.len());

        let mut header = p[..20].to_vec();
        let stored = u16::from_be_bytes([header[10], header[11]]);
        header[10] = 0;
        header[11] = 0;
        assert_eq!(stored, internet_checksum(&header));
        assert_ne!(stored, 0);
    }

    #[test]
    fn checksum_matches_rfc1071_example() {
        let bytes = [0x00, 0x01, 0xf2, 0x03, 0xf4, 0xf5, 0xf6, 0xf7];
        assert_eq!(internet_checksum(&bytes), 0x220d);
    }

    /// Isolate the flow table between the pure-logic tests below; the table is
    /// a process-global, like the counters.
    fn reset_flows() {
        clear_udp_flows();
    }

    /// The defect that broke WhatsApp calling: one source port fanning out to
    /// several destinations (ICE/STUN connectivity checks). A reply from each
    /// peer must be rebuilt with THAT peer as its source. Port-only keying
    /// rebuilt every reply from whichever destination was contacted last, so
    /// ICE's symmetry check (RFC 8445) dropped them all.
    #[test]
    fn multi_destination_replies_keep_their_own_source() {
        let _serial = ENGINE_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_flows();

        let port = 50000u16;
        let stun_a = ([203, 0, 113, 10], 3478u16);
        let stun_b = ([198, 51, 100, 7], 19302u16);
        let peer_c = ([192, 0, 2, 55], 55000u16);

        let fanout_before = UDP_FANOUT_PORTS.load(Ordering::Relaxed);
        // Same local port, three different destinations, sent in sequence -
        // exactly what a single ICE socket does.
        note_udp_flow(port, CLIENT_TUN_IP, stun_a.0, stun_a.1);
        note_udp_flow(port, CLIENT_TUN_IP, stun_b.0, stun_b.1);
        note_udp_flow(port, CLIENT_TUN_IP, peer_c.0, peer_c.1);

        // Each peer answers. The reply must be attributed to the peer that
        // actually sent it, regardless of arrival order.
        for (ip, prt) in [peer_c, stun_a, stun_b] {
            let (src_ip, src_port, local) =
                resolve_reply_source(port, Some((ip, prt))).expect("flow must resolve");
            assert_eq!(
                src_ip,
                Ipv4Addr::from(ip),
                "reply source IP must be the peer that answered, not the last dest"
            );
            assert_eq!(src_port, prt, "reply source port must be the peer's port");
            assert_eq!(local, Ipv4Addr::from(CLIENT_TUN_IP));
        }

        // Fan-out must have been observed twice (the 2nd and 3rd destinations).
        assert_eq!(
            UDP_FANOUT_PORTS.load(Ordering::Relaxed) - fanout_before,
            2,
            "each additional destination on a live port is one fan-out event"
        );
        reset_flows();
    }

    /// The virtual-DNS contract must survive tuple keying: the phone addresses
    /// 10.215.17.1:53, the host answers from a real resolver, and the reply
    /// (which carries the resolver as its peer, not the virtual IP) must still
    /// be rebuilt as coming from 10.215.17.1:53. This is the per-port fallback.
    #[test]
    fn host_rewritten_destination_falls_back_to_original() {
        let _serial = ENGINE_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_flows();

        let port = 41000u16;
        let virtual_dns = [10, 215, 17, 1];
        note_udp_flow(port, CLIENT_TUN_IP, virtual_dns, 53);

        // The real resolver (say 1.1.1.1) is what actually answered - a peer
        // the phone never addressed, so no exact tuple can match.
        let (src_ip, src_port, local) =
            resolve_reply_source(port, Some(([1, 1, 1, 1], 53))).expect("fallback must resolve");
        assert_eq!(
            src_ip,
            Ipv4Addr::from(virtual_dns),
            "DNS reply must appear from the virtual IP the phone queried"
        );
        assert_eq!(src_port, 53);
        assert_eq!(local, Ipv4Addr::from(CLIENT_TUN_IP));
        assert!(
            UDP_REPLY_REMAPPED.load(Ordering::Relaxed) >= 1,
            "a fallback match must be counted as a remap"
        );
        reset_flows();
    }

    /// The stream fallback path carries no peer address at all; it must still
    /// deliver via the per-port fallback.
    #[test]
    fn reply_with_no_peer_uses_last_destination() {
        let _serial = ENGINE_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_flows();

        let port = 42000u16;
        let dest = [93, 184, 216, 34];
        note_udp_flow(port, CLIENT_TUN_IP, dest, 443);

        let (src_ip, src_port, _local) =
            resolve_reply_source(port, None).expect("peerless reply must resolve");
        assert_eq!(src_ip, Ipv4Addr::from(dest));
        assert_eq!(src_port, 443);
        reset_flows();
    }

    /// A reply for a flow that was never opened must be reported, not
    /// silently attributed to some unrelated port.
    #[test]
    fn unmatched_reply_is_counted_and_dropped() {
        let _serial = ENGINE_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_flows();

        let before = UDP_REPLY_UNMATCHED.load(Ordering::Relaxed);
        assert!(resolve_reply_source(60000, Some(([8, 8, 8, 8], 53))).is_none());
        assert!(resolve_reply_source(60000, None).is_none());
        // resolve_reply_source itself does not count; the delivery sites do.
        // Drive one delivery site to prove the miss is surfaced.
        deliver_udp_reply(60000, b"orphan");
        assert!(
            UDP_REPLY_UNMATCHED.load(Ordering::Relaxed) > before,
            "an orphan reply must increment the unmatched gauge"
        );
        reset_flows();
    }

    /// Idle flows must be swept so a tuple-keyed table cannot grow without
    /// bound under sustained ICE fan-out.
    #[test]
    fn idle_flows_are_swept_under_pressure() {
        let _serial = ENGINE_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_flows();

        // Guard the monotonic clock: on a freshly booted machine the
        // subtraction can underflow, in which case the cap path still bounds
        // the table and the assertion below holds either way.
        let old = Instant::now()
            .checked_sub(Duration::from_secs(UDP_FLOW_IDLE_SECS + 5))
            .unwrap_or_else(Instant::now);
        {
            let mut table = flow_table().lock().unwrap_or_else(|p| p.into_inner());
            for i in 0..(MAX_UDP_FLOWS + 100) {
                let key = UdpFlowKey {
                    src_port: (i % 65535) as u16,
                    dst_ip: [10, 0, (i >> 8) as u8, (i & 0xff) as u8],
                    dst_port: 443,
                };
                table.flows.insert(
                    key,
                    UdpFlow {
                        local_ip: CLIENT_TUN_IP,
                        last_seen: old,
                    },
                );
            }
        }
        // A fresh packet triggers the sweep; every stale entry is idle.
        note_udp_flow(12345, CLIENT_TUN_IP, [1, 2, 3, 4], 443);
        let (open, _) = udp_flow_gauges();
        assert!(
            open <= MAX_UDP_FLOWS,
            "table must be bounded after sweep, was {open}"
        );
        reset_flows();
    }
}
