//! JNI bridge between the Android app and the Rust engine.
//!
//! Loaded from Kotlin via `System.loadLibrary("peernet_core")`.
//! Symbol names must match `com.peernet.wifiextender.core.NativeCore` exactly.

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
    env.new_string(concat!("peernet-core ", env!("CARGO_PKG_VERSION")))
        .expect("JVM out of memory")
}

/// NativeCore.newSessionId() -> String (32-char hex)
#[no_mangle]
pub extern "system" fn Java_com_peernet_wifiextender_core_NativeCore_newSessionId<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let id = SessionId::generate();
    env.new_string(id.to_hex()).expect("JVM out of memory")
}
