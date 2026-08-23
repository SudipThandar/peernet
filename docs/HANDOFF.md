# Session Handoff — PeerNet WiFi Extender

Handoff written 2026-08-23 @ commit `be04a87` (`main`, CI green, pushed to `origin/main`).
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
- Windows client (Wintun tray app) explicitly deferred ("Phase 2", `windows/README.md`).

## 2. Repository Layout

```
app/                     Android app (Compose UI, Hilt DI, services)
  .../wifi/
    WifiDirectManager    Wi-Fi Direct hosting/joining; stable branded group credentials;
                         joinByCredentials (API 33+) + peer-name invitation fallback
    LinkServer           Host-side TCP responder on port 4434 validating link probes
                         (moved off 4433 in M7 - QUIC engine owns 4433 now)
  .../discovery/         NSD advertise/discover, HostIdentity; TXT carries hid/fp/tp
  .../host/HostRuntime   Starts/stops the QUIC HostServer with sharing; passes engine
                         fingerprint into mDNS advertisement
  .../service/
    HostForegroundService        Keeps hosting alive in background (specialUse FGS)
    PeerNetVpnService            TUN establishment, protect(), fd handoff; reads
                                 EXTRA_HOST_ADDR / EXTRA_HOST_FP and starts tunnel after capture
  .../core/NativeCore.kt         Raw JNI surface; RustCoreBridge = safe wrapper (use this)
  .../ui/client/ClientViewModel  Connect flow, auto-link watcher, liveness watchdog, profile save
  .../ui/home/HomeScreen.kt      Single screen; permission prompt at first launch; VPN intent
                                 builder with host extras; QUIC status line
core/                    Rust workspace (see core/README.md)
  peernet-proto          Frame codec, message kinds, TCP/UDP relay headers, ALPN, constants
  peernet-core           SessionId, TunnelStats, cert helpers, backoff
  peernet-host           QUIC server: Hello/heartbeat, TCP relay, UDP NAT (port-preserving), DNS fwd
  peernet-client         QUIC client: pinned-cert TLS verify, handshake, keepalive pump,
                         connection() accessor for the TUN forwarder (M7 part 2)
  peernet-host/tests     Loopback harness - THE quality gate (must pass before device testing)
  peernet-ffi/src/lib.rs JNI bridge: TUN capture + forwarder, engine lifecycle statics,
                         #[cfg(test)] loopback data-path tests (do NOT move to tests/ dir)
.github/workflows/android.yml   CI: rust test -> .so for 3 ABIs -> assembleDebug -> artifact
scripts/                 build_rust_android.sh, run_tests.sh, format.sh
docs/                    HANDOFF.md only real doc; ARCHITECTURE/TESTING/PLAY_STORE placeholders
```

**Note:** The project specification ("Sections 5-17", milestone list) referenced in code comments
is NOT in the repo — it lives outside. Docs only reveal M10/M11/M12 themes.

## 3. Milestone Status

| Milestone | Scope | Status |
|---|---|---|
| M1 | Android skeleton: Compose/Hilt/nav + CI APK build | Done |
| M2 | Rust workspace skeleton: framing, session ids, stats, backoff, JNI bridge | Done |
| M3 | Wi-Fi Direct hosting/join/link/auto-reconnect, FGS, single screen | Done |
| M4 | QUIC loopback: quinn server+client, pinned certs, heartbeat, tests | Done |
| M5 | TCP relay, port-preserving UDP NAT, DNS redirect + relay tests | Done |
| M6 | VpnService TUN + Rust async capture (fd ownership, protect-first) | Done |
| **M7 part 1** | **Engine lifecycle via FFI + permission-at-launch + port split** | **Done** |
| **M7 part 2** | **UDP/DNS data path end-to-end through TUN** | **Done** |
| **M7 part 3+3b** | **TCP data path: smoltcp terminator + QUIC relay-stream wiring** | **Code done; device retest pending** |
| **M7.5 fixes** | **Legacy-join detection, network pinning, logcat logging (first device test findings)** | **Done — awaiting retest** |

This session's commits (oldest to newest):
- `75b22e2` M6 fixes: stable branded SSID/passphrase, multicast locks both sides,
  auto-relink on join rising edge, NSD resolve serialization, VPN fd-leak fix,
  stale-link cleanup watcher, retry-round discovery.
- `f98122d` CONNECT performs a REAL P2P join by derived credentials (API 33+
  `WifiP2pConfig.Builder`), peer-name invitation fallback, liveness watchdog.
- `01ffeb6`+`ed218ee` M7 part 1: JNI startHost/stopHost/hostSessionCount/startTunnel/
  stopTunnel/tunnelState; fingerprint advertised via NSD TXT; client starts tunnel when
  VPN comes up; LinkServer moved to 4434; location/nearby permission requested at first
  launch (pendingStartSharing flag keeps SHARE semantics).
- `450c135`..`be04a87` M7 part 2: outbound IPv4/UDP parse+forward from TUN, relay send via
  `connection().send_datagram`, reverse-path pump rebuilding packets into TUN, orig-dst
  source rewrite (virtual DNS), split read/write fd halves, CAPTURE_FDS close-once tracking,
  loopback roundtrip tests inside lib.rs.
- `8f0a11e`..`6d74598` M7 part 3: new `peernet-tcp` crate — smoltcp-based transparent TCP
  terminator (per-SYN listeners keyed on full 4-tuple), channel seam (`TcpStack::channels`)
  for upstream, simulated-phone loopback tests (handshake/echo/close, concurrent flows,
  256KB bulk). Key fixes: AnyIP + whitequark `/0` local-prefix trick for arbitrary
  destinations; Arc/Mutex queues so the stack is Send; downstream backpressure
  (`PENDING_CAP`) instead of flow-killing.
- `541e613` M7 part 3b: FFI wiring — `spawn_tcp_termination()` builds the engine per
  tunnel generation; forward_outbound feeds proto==6 packets in; orchestrator task maps
  ToUpstream::Open/Data/Eof onto one QUIC bi-stream per flow (framed TcpRelayHeader then
  raw bytes), reader halves pump internet->engine; Eof = half-close; teardown drops intake
  on stop/link-death.
- `f19af26` HANDOFF refresh for M7 completion.

First device test round (two Android phones, host on Wi-Fi) found two blockers, fixed in:
- `375507c`+`51682d0`+`c959bb2` M7.5 device-test fixes:
  1. **Legacy OS-picker joins were invisible.** Joining via Android's Wi-Fi settings
     (typing the passphrase) never fires Wi-Fi Direct callbacks client-side, so
     `joinedAsClient` stayed false and auto-link/tunnel never started ("stuck connecting").
     Fix: `ClientViewModel` polls WiFi SSID every 4 s; `DIRECT-*PeerNet*` triggers `autoLink()`.
  2. **Tunnel rode the cellular default route.** The P2P Wi-Fi is "no internet"-flagged,
     so Android's default network (cellular) swallowed QUIC packets to the host's private IP.
     Fix: capture the link `Network` at link time (prefers `p2p*` interface), pass via
     `EXTRA_NETWORK`, call `setUnderlyingNetworks` in `PeerNetVpnService`.
  3. **Real logcat logging**: `android_logger`, tag `PeerNet`; `jni_log` now writes to logcat.
     Debug with `adb logcat -s PeerNet`. Init happens inside `runtime()` so any engine path logs.
  - CI got `paths-ignore` for docs/** + **.md so handoff updates don't burn builds.

## 4. Current State — What Works vs What's Left

### Working end-to-end
1. Wi-Fi Direct group formation both directions; stable identity while hosting:
   SSID `DIRECT-PeerNet-<shortId>` (shortId = last 4 hex of host id), passphrase
   `"pn-"+<full 16-char hid>`. Both sides derive the same values from NSD TXT `hid`.
2. Client CONNECT flow priority: mDNS round to learn hid -> `joinByCredentials`
   (API 33+) -> awaitJoined(15s) -> P2P peer-name scan invitation -> current-network fallback.
   Liveness watchdog probes every 5s (2 misses clear link only when not P2P-backed).
3. Permission request happens at first app open (location/nearby), not mid-action.
4. Engine lifecycle: sharing boots `HostServer` on 0.0.0.0:4433 and advertises cert
   fingerprint (`fp`) + tunnel port (`tp`=4433); linking starts pinned-fingerprint
   `TunnelClient`; UI shows QUIC connecting/connected/reconnecting.
5. **UDP/DNS forwarding (M7 part 2)**: phone's UDP traffic flows TUN -> QUIC relay datagrams
   -> host NAT -> internet; replies rebuilt as valid IPv4/UDP into TUN. DNS via the virtual
   IP works because replies are rewritten to claim the ORIGINAL destination as source.
6. **TCP forwarding (M7 part 3+3b)**: phone TCP flows are terminated locally by the
   peernet-tcp engine and carried as one QUIC bi-stream per flow (TcpRelayHeader + raw
   bytes) to the host, which splices them to real sockets (idle timeout, FIN both ways).
   Full-duplex with backpressure. Loopback-verified; device retest in progress.
7. **Legacy-join auto-link (M7.5)**: manual joins via Android's Wi-Fi picker are detected
   by SSID polling (4 s cadence, `DIRECT-*PeerNet*` match) and trigger the normal
   auto-link -> VPN -> tunnel chain. Native P2P joins keep using the event-driven path.
8. **Network pinning**: `ClientLinkManager.linkedNetwork` (prefers `p2p*` interface,
   falls back to any TRANSPORT_WIFI) is passed to `PeerNetVpnService` via
   `EXTRA_NETWORK` and applied with `setUnderlyingNetworks`, keeping QUIC sockets off
   the cellular default route.
9. Pure-Rust gates all green: handshake, relays, NAT, DNS redirect, concurrency, data-path roundtrip.

### NOT yet done (the gap)
- **Retest after M7.5 fixes** (APK from commit `c959bb2` or later) — verify: OS-picker
  join auto-links within ~4 s, QUIC reaches Connected, browsing works.
- Wi-Fi label may still read "connected without internet" while browsing works — that is
  inherent to the VPN-overlay design (internet rides the VPN, not that network). Success
  criterion = pages load, not the label.
- Two-name confusion polish: Wi-Fi settings shows group SSID (`DIRECT-xx-PeerNet-…`),
  app shows host device name — same thing twice; unify display later.
- Host-side FGS notification reported flaky ("not everytime the bar shows up") — needs
  investigation (battery optimization exemption / START_STICKY behavior).
- No reconnect/backoff integration between tunnel drops and the Wi-Fi link state machine yet.
- IPv6 dropped silently (TUN config v4-only).
- UDP flow table never evicts (no LRU/timeout yet); engine `PENDING_CAP` is the only RAM guard.
- docs/ARCHITECTURE.md, TESTING.md, PLAY_STORE_NOTES.md still placeholders.

## 5. Key Design Rules (do not break these)

1. **fd ownership:** Rust owns TUN fds exclusively after `startTunCapture(fd)`; closed exactly
   once via `close_capture_fds()` (CAPTURE_FDS mutex tracks original + read/write duplicates).
   Stop closes them to wake pending epoll waits. Kotlin never closes after successful capture.
2. **Split halves:** run_capture duplicates the fd into read/write Files (level-triggered epoll
   is dup-safe). Reader task owns reads; writer task consumes an unbounded channel of rebuilt
   packets. Do NOT put both readiness waits in one tokio::select! whose handlers touch the same
   AsyncFd — branch futures hold conflicting mutable borrows (compile error).
3. **protect-before-detach** before handing the fd to Rust; failure aborts with Kotlin still owner.
4. **No panics across FFI**; JNI entry points return flags/null-safe strings; poison-tolerant
   lock access via `.unwrap_or_else(|p| p.into_inner())` everywhere.
5. **Generation guard:** `ENGINE_GEN` bumps on stop/new-start; handshake tasks and the UDP reply
   pump check it and exit when superseded.
6. **JNI specifics:** `JNIEnv::get_string(&mut self)` means helpers take `&mut JNIEnv` and
   callers need `mut env`. `compare_exchange` returns Result — never apply `!` to it. Symbols
   must match `NativeCore.kt`: `Java_com_peernet_wifiextender_core_NativeCore_*`.
7. **Test placement quirk:** peernet-ffi builds `crate-type = ["cdylib","rlib"]` with
   `doctest = false`. Integration tests in `tests/` fail to LINK (E0464 multiple rlib
   candidates) and doctests trip the same error. All ffi tests live in `#[cfg(test)] mod tests`
   inside lib.rs. Other crates can keep tests/ dirs.
8. **Loopback gate:** `cargo test --release` in `core/` must pass before any device testing.
9. Local builds intentionally unused — CI (GitHub Actions on push/PR to main) builds everything;
   artifact name `peernet-debug-apk`. Maven Central occasionally 429s during gradle dep
   download: just `gh run rerun <id> --failed`. Docs-only pushes (`docs/**`, `**.md`) skip CI
   via `paths-ignore`.
10. **Legacy joins are silent:** never rely on Wi-Fi Direct callbacks for clients that joined
    through the OS Wi-Fi picker — poll SSID instead.
11. **Pin tunnel sockets to the link network:** without `setUnderlyingNetworks`, a
    "no internet"-flagged P2P Wi-Fi loses the default route to cellular and the QUIC
    handshake can never reach the host's private IP.
12. **Android target-only Rust deps** (`android_logger`, `log`) live in a
    `[target.'cfg(target_os = "android")'.dependencies]` table inside
    `core/peernet-ffi/Cargo.toml` — the workspace-root manifest REJECTS target tables.
13. **Kotlin gotcha:** Hilt `@ApplicationContext context: Context` constructor params are
    NOT visible in member functions — capture as `private val appContext` at construction.

## 6. Environment Notes

- Windows dev box: **no local Rust toolchain**, invalid JAVA_HOME — don't build locally;
  push and let CI verify.
- Device matrix for later: Galaxy M11, J4, Pixel; Android 8/10/12/14+. RTC apps to test:
  Discord, WhatsApp, Meet, Zoom, Telegram, browser, Speedtest.

## 7. Suggested Plan Going Forward

1. **Retest (next): full internet path with M7.5 fixes.**
   - Install APK from commit `c959bb2` (or later) on both phones.
   - Host: Share as before. Client: join via OS Wi-Fi picker + password (the preferred
     flow), open the app, wait ~4 s for auto-link, confirm QUIC reaches Connected.
   - Verify browsing/Speedtest through the tunnel; `adb logcat -s PeerNet` if it fails.
2. **M8-ish: resilience.** Tunnel reconnect integration with ClientLinkManager backoff;
   stop-tunnel on P2P disconnect; tunnel stats surfaced in UI; host FGS notification
   reliability (battery-optimization exemption prompt).
3. **UX polish pass:** unify naming (one canonical label for the host network in app +
   a hint showing the actual SSID to look for in Wi-Fi settings); explain the
   "without internet" label behavior in-app so it never looks broken.
4. **M9-ish: hardening.** MTU review, IPv6 decision, UDP flow LRU/timeout (table never evicts),
   battery/doze behavior, NAT port-collision fallback when port preservation fails,
   TCP idle-timeout tuning vs long-lived connections.
5. M10: foreground-service ownership refinements. M11: TESTING.md + device matrix passes.
   M12: PLAY_STORE_NOTES.md, signing, listing. Backlog: Windows client (Phase 2).

Immediate concrete next step: retest with the `c959bb2` APK using the OS-picker join flow;
if anything still fails, capture `adb logcat -s PeerNet` from both phones.

## 8. Verification Checklist for Any Change

- [ ] Push -> CI green (rust test gate + 3-ABI build + assembleDebug + unit tests).
- [ ] New JNI symbols: update BOTH `NativeCore.kt` and `lib.rs` in the same commit.
- [ ] Never close the TUN fd from Kotlin after successful capture.
- [ ] Loopback tests still pass without a device (they are the gate).
