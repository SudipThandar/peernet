//! JNI bridge between the Android app and the Rust engine.
//!
//! Loaded from Kotlin via `System.loadLibrary("peernet_core")`.
//! Symbol names must match `com.peernet.wifiextender.core.NativeCore` exactly.
//!
//! Constraint: no panics may cross the FFI boundary. Every fallible operation
//! degrades to a safe default and logs via the Android logging side later;
//! Kotlin wraps calls in Result-style guards anyway.

use jni::objects::{JClass, JString};
use jni::JNIEnv;
use peernet_core::SessionId;

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

/// Panic-free string handoff; returns an empty string rather than unwinding.
fn create_string<'local>(env: JNIEnv<'local>, value: &str) -> JString<'local> {
    match std::panic::catch_unwind(|| value.to_owned())
        .ok()
        .and_then(|v| env.new_string(v).ok())
    {
        Some(s) => s,
        None => match env.new_string("") {
            Ok(s) => s,
            // Last resort: nothing sane left; a null handle is still safer
            // than panicking into JNI.
            Err(_) => unsafe { JString::from_raw(std::ptr::null_mut()) },
        },
    }
}
