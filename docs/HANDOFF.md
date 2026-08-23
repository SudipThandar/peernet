# Session Handoff — PeerNet WiFi Extender

Handoff written 2026-08-23 @ commit `640b813` (`main`, clean tree, pushed to `origin/main`).
Feed this file to a new agent session to resume work.

---

## 1. Project Overview

**PeerNet – WiFi Extender** (`com.peernet.wifiextender`): an Android app that shares a
phone's internet connection with nearby devices over **Wi-Fi Direct**, tunneling traffic via an
encrypted **QUIC/UDP protocol ("PNTP")** implemented in Rust. Client devices route all traffic
through a **VpnService TUN**; the host does userspace NAT/forwarding. No root, no HTTP/SOCKS
proxy — UDP is relayed natively so real-time apps (Discord, Meet, Zoom) work.

- Host + Client modes in one APK. Min SDK 26 / Target 36.
- Kotlin + Jetpack Compose + Hilt app layer; Rust core (`core/`) compiled to `.so` via cargo-ndk,
  bridged through raw JNI (`peernet-ffi`).
- Windows client (Wintun tray app) is explicitly deferred ("Phase 2", `windows/README.md`).

## 2. Repository Layout

```
app/                     Android app (Compose UI, Hilt DI, services)
  .../wifi/
    WifiDirectManager    Wi-Fi Direct group owner/client, mDNS-backed discovery state
    LinkServer           Host-side TCP responder on PNTP port validating link probes
  .../discovery/         NSD advertise (host) / discover (client), HostIdentity, fingerprint TXT
  .../host/HostRuntime   App-lifetime host runtime: mDNS adv + LinkServer while group exists
  .../service/
    HostForegroundService        Keeps hosting alive in background (specialUse FGS)
    PeerNetVpnService            M6: VpnService TUN establishment, protect(), fd handoff
  .../core/NativeCore.kt         Raw JNI surface; RustCoreBridge = safe wrapper (use this)
  .../ui/home/HomeScreen.kt      Single-screen UX; VPN consent launcher + TUN packet counter
core/                    Rust workspace (see core/README.md)
  peernet-proto          Frame codec, message kinds, TCP/UDP relay headers, ALPN, constants
  peernet-core           SessionId, TunnelStats, cert helpers, backoff
  peernet-host           QUIC server: Hello/heartbeat, TCP relay, UDP NAT (port-preserving), DNS fwd
  peernet-client         QUIC client: pinned-cert TLS verify, handshake, keepalive pump
  peernet-host/tests     Loopback harness — THE quality gate (must pass before device testing)
  peernet-ffi/src/lib.rs JNI bridge incl. M6 TUN capture task
.github/workflows/android.yml   CI: rust test → .so for 3 ABIs → assembleDebug → artifact
scripts/                 build_rust_android.sh, run_tests.sh, format.sh
docs/                    ARCHITECTURE.md / TESTING.md / PLAY_STORE_NOTES.md are placeholders
```

**Note:** The project specification ("Sections 5–17", milestone list) referenced in code comments
is NOT in the repo — it lives outside. Docs only reveal: M4 = loopback harness expansion,
M10 = FGS ownership refinement, M11 = testing expansion, M12 = Play Store notes.

## 3. Milestone Status (from git history, oldest → newest)

| Milestone | Scope | Status |
|---|---|---|
| M1 | Android skeleton: Compose/Hilt/nav + CI APK build | Done |
| M2 | Rust workspace skeleton: framing, session ids, stats, backoff, JNI bridge, panic-free FFI | Done |
| M3 | Wi-Fi Direct: hosting, real mDNS discovery, join/link, auto-reconnect, foreground service, NetShare-style single screen | Done |
| M4 | QUIC loopback: quinn 0.11 server+client, pinned-cert verify, Hello/HelloAck, heartbeat pump, loopback tests | Done |
| M5 | TCP relay, endpoint/port-preserving UDP NAT, DNS redirect + relay tests | Done |
| **M6** | **VpnService TUN + Rust async capture (fd ownership, protect-first) — JUST LANDED, current HEAD** | Done (capture-only proof) |

M6 commit series (last 7 commits) hardened: `protect(fd)` before `detachFd()`, single-owner fd
(Rust closes it exactly once), `AsyncFd` non-blocking read loop with `readable_mut`/`try_io`,
VPN consent via `StartIntentSenderForResult`, UI shows live "TUN: N packets" counter.

## 4. Current State — What Works vs What's Stubbed

### Working end-to-end
1. Wi-Fi Direct group formation both directions; branded `PeerNet-xxxx` identity while hosting.
2. mDNS advertise/discover with certificate-fingerprint TXT record; link probe validated by
   host's LinkServer on PNTP port.
3. Rust QUIC engine fully working **in pure-Rust loopback tests**: handshake, heartbeats, stats,
   pinned-cert rejection, concurrent sessions, TCP echo relay, UDP roundtrip preserving source
   port, DNS redirect, 10×TCP + 10×UDP concurrency.
4. M6 TUN capture: VpnService establishes TUN (MTU 1280, v4 `0.0.0.0/0` route, virtual DNS
   `10.215.17.1`, own package disallowed), protects fd, hands to Rust which counts packets and
   logs first 10 IP summaries. UI surfaces counter when linked to a host.

### NOT yet wired (the actual gap)
- **FFI exposes no start/stop for the QUIC engine.** `NativeCore.kt` has only `version`,
  `newSessionId`, `startTunCapture`, `stopTunCapture`, `tunPacketCount`. The proven
  `TunnelClient`/`HostServer` are never invoked from the app.
- **TUN capture doesn't forward anything.** `run_capture` in `peernet-ffi/src/lib.rs` reads
  packets, counts them, drops them. No IP parse→classify→relay-over-QUIC pipeline yet.
- **Kotlin "connection" today is only the link probe** (ClientLinkManager), not a PNTP session.
  VPN starts whenever `connectedHost != null`.
- No classifier (the `peernet-client` module description promises one), no reconnect integration,
  no stats surfaced from Rust beyond packet count.
- `jni_log()` is a no-op on device (comment says accepted-for-M6; relies on counters instead).
- IPv6: TUN config is v4-only; capture's `describe()` handles v6 incidentally.
- docs/ARCHITECTURE.md, TESTING.md, PLAY_STORE_NOTES.md still placeholders (M11/M12 work).

## 5. Key Design Rules (do not break these)

1. **fd ownership:** after `startTunCapture(fd)` returns true, Rust owns the fd exclusively;
   Kotlin never closes it. Stop path closes exactly once (`close_current` swaps TUN_FD to -1
   first). If Rust refuses (already capturing), fd is left open and caller aborts without closing.
2. **protect-before-detach:** `VpnService.protect(pfd.fd)` runs BEFORE `detachFd()`; failure
   aborts cleanly with pfd still owned by Kotlin.
3. **No panics across FFI**; all JNI entry points return success flags / null-safe strings.
4. **JNI runtime:** dedicated tokio multi-thread runtime (2 workers) behind `OnceLock` — JNI has
   no ambient context.
5. **Symbol names** must match between `NativeCore.kt` and `peernet-ffi/src/lib.rs`
   (`Java_com_peernet_wifiextender_core_NativeCore_*`).
6. **Loopback gate:** `cargo test --release` in `core/` must pass before any device testing.
7. Local builds intentionally unused — CI (GitHub Actions on push/PR to main) builds everything;
   artifact name `peernet-debug-apk`.

## 6. Environment Notes

- Windows dev box: **no local Rust toolchain** (`cargo` not found) — don't try `cargo test`
  locally; push and let CI verify, or install Rust if the user wants local gates.
- Device matrix for later: Galaxy M11, J4, Pixel; Android 8/10/12/14+. RTC apps to test:
  Discord, WhatsApp, Meet, Zoom, Telegram, browser, Speedtest.

## 7. Suggested Plan Going Forward (M7+, inferred — confirm against user's spec)

1. **M7 (likely next): wire the real tunnel through FFI.**
   - Add JNI: `startHost(config)`, `stopHost()`, `startClient(opts)`, `stopClient()`,
     `tunnelStats()` mirroring existing patterns (state atomics + watch channel).
   - Extend `run_capture`: read packet → minimal IP parse (v4 first) → classify
     (TCP/UDP/DNS by dst port 53) → write into `TunnelClient` relay streams using
     `TcpRelayHeader`/`UdpRelayHeader` from peernet-proto; reverse path writes back to AsyncFd.
   - Host side: start `HostServer` when sharing begins; NAT/DNS already exist in peernet-host.
2. **M8-ish: lifecycle + resilience.** Reconnect/backoff integration with ClientLinkManager,
   stop-tunnel on P2P disconnect, stats in UI, replace no-op `jni_log` with real logcat
   (`__android_log_write` via `android_logger` crate or NDK binding).
3. **M9-ish: correctness hardening.** MTU/clamping review, IPv6 decision (add v6 route/address or
   explicitly drop v6 in classifier), DNS virtual-IP mapping to real upstream, battery/doze.
4. **M10: foreground-service ownership refinements** (per doc comment in HostRuntime).
5. **M11: fill docs/TESTING.md**, device matrix passes, RTC app validation.
6. **M12: release prep** — docs/PLAY_STORE_NOTES.md, signing, Play listing.
7. Backlog: Windows client (Phase 2), fill ARCHITECTURE.md as milestones land.

Immediate concrete next step: add `startClient`/`startHost` JNI surface and extend
`run_capture` to forward into `TunnelClient`; extend loopback tests to cover the
TUN-read → relay-write path with a fake fd (e.g., socketpair) so the gate stays device-free.

## 8. Verification Checklist for Any Change

- [ ] `git status` clean before starting (was clean at handoff).
- [ ] Push → CI green (rust test gate + 3-ABI build + assembleDebug + unit tests).
- [ ] New JNI symbols: update BOTH `NativeCore.kt` and `lib.rs` in same commit.
- [ ] Never close the TUN fd from Kotlin after successful capture.
