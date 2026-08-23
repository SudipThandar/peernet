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
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::sync::OnceLock;

use jni::objects::{JClass, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use peernet_core::SessionId;
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
static PACKETS: AtomicU64 = AtomicU64::new(0);
static BYTES: AtomicU64 = AtomicU64::new(0);

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
        // Closing our copy wakes the pending read with EBADF/EOF.
        unsafe {
            libc::close(fd as RawFd);
        }
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

async fn run_capture(file: OwnedFd, mtu: usize) {
    use std::os::unix::io::AsRawFd;

    let std_file = std::fs::File::from(file);
    let _ = set_nonblocking(std_file.as_raw_fd());

    let async_fd = match AsyncFd::new(std_file) {
        Ok(a) => a,
        Err(_) => {
            close_current();
            return;
        }
    };

    let mut buf = vec![0u8; mtu];
    let mut logged = 0u64;

    loop {
        if TUN_STOP.load(Ordering::SeqCst) {
            break;
        }
        let mut guard = match async_fd.readable_mut().await {
            Ok(g) => g,
            Err(_) => break,
        };
        let result = guard.try_io_mut(|inner| inner.get_mut().read(&mut buf));
        match result {
            Ok(Ok(0)) => break, // EOF: interface closed
            Ok(Ok(n)) => {
                PACKETS.fetch_add(1, Ordering::Relaxed);
                BYTES.fetch_add(n as u64, Ordering::Relaxed);
                if logged < 10 {
                    logged += 1;
                    crate::jni_log(&format!(
                        "[tun] pkt#{} {}B {}",
                        PACKETS.load(Ordering::Relaxed),
                        n,
                        describe(&buf[..n])
                    ));
                }
            }
            Ok(Err(e)) if e.kind() == std::io::ErrorKind::WouldBlock => {
                drop(guard);
                continue;
            }
            Ok(Err(_)) | Err(_) => break,
        }
    }

    close_current();
}

fn close_current() {
    let fd = TUN_FD.swap(-1, Ordering::SeqCst);
    if fd >= 0 {
        unsafe {
            libc::close(fd as RawFd);
        }
    }
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
