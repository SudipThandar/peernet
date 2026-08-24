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
    PeerNetVpnService            TUN establishment (specialUse FGS, no protect() on the
                                 fd), fd handoff; reads
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
.github/workflows/android.yml   CI: rust test -> .so for 3 ABIs -> 16 KB alignment check
                                -> assembleDebug -> artifact; unit tests + lint gate
app/src/test/.../ManifestContractTest.kt  Manifest/FGS-permission contracts (see §10)
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
- **Retest after the M7.7 audit fixes** (APK from commit `be79ae2` or later) — see §9 for
  the retest script and §10 for what each fix changed on screen.
- Wi-Fi label may still read "connected without internet" while browsing works — that is
  inherent to the VPN-overlay design (internet rides the VPN, not that network). Success
  criterion = pages load, not the label.
- Two-name confusion polish: Wi-Fi settings shows group SSID (`DIRECT-xx-PeerNet-…`),
  app shows host device name — same thing twice; unify display later.
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
3. **Never call `protect()` on the TUN fd.** `protect()` excludes a *socket* from the VPN;
   on a TUN descriptor it fails (`ENOTSOCK`) and used to abort every tunnel. The
   routing-loop guard is `addDisallowedApplication(packageName)` on the Builder. Sockets
   the app opens outside the tunnel are the only legitimate `protect()` targets.
4. **No panics across FFI**; JNI entry points return flags/null-safe strings; poison-tolerant
   lock access via `.unwrap_or_else(|p| p.into_inner())` everywhere.
5. **Generation guard:** `ENGINE_GEN` bumps on stop/new-start; handshake tasks and the UDP reply
   pump check it and exit when superseded.
5b. **Runtime context on JNI threads:** quinn resolves its async runtime *while constructing*
   endpoints (`Handle::try_current()`), and JNI threads have none. Build any quinn/tokio
   object inside `runtime().enter()` or `runtime().block_on(..)` — `bind_host_server()` is
   the reference. `#[tokio::test]` hides this class of bug entirely; guard it with a test
   that runs on a bare `std::thread`.
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
- **The tester has no adb.** Nothing may fail only into logcat: every failure path must
  end up in `lastError()` / the red status line / the on-screen counters.
- Device matrix for later: Galaxy M11, J4, Pixel; Android 8/10/12/14+. RTC apps to test:
  Discord, WhatsApp, Meet, Zoom, Telegram, browser, Speedtest.

## 7. Suggested Plan Going Forward

1. **Retest (next): full internet path with the M7.7 audit fixes.**
   - Install APK from commit `be79ae2` (or later) on both phones; uninstall the old build
     first, since permission and foreground-service declarations changed.
   - Host: SHARE. Client: join via OS Wi-Fi picker + password (the preferred flow), open
     the app, wait ~4 s for auto-link, allow the VPN prompt.
   - Success = pages load and `in=` climbs in the on-screen counters. The tester has no
     adb, so every failure must name itself on screen (§9 retest script).
2. **M8-ish: resilience.** Tunnel reconnect integration with ClientLinkManager backoff;
   stop-tunnel on P2P disconnect; battery-optimization exemption prompt for long hosting
   sessions (the notification-disappearing bug itself was a state-replay defect, §10.6).
3. **UX polish pass:** unify naming (one canonical label for the host network in app +
   a hint showing the actual SSID to look for in Wi-Fi settings); explain the
   "without internet" label behavior in-app so it never looks broken.
4. **M9-ish: hardening.** MTU review, IPv6 decision, UDP flow LRU/timeout (table never evicts),
   battery/doze behavior, NAT port-collision fallback when port preservation fails,
   TCP idle-timeout tuning vs long-lived connections.
5. M10: foreground-service ownership refinements. M11: TESTING.md + device matrix passes.
   M12: PLAY_STORE_NOTES.md, signing, listing. Backlog: Windows client (Phase 2).

Immediate concrete next step: retest with the M7.7 APK (commit `be79ae2` or later) using
the OS-picker join flow — full script in §9. The on-screen red status line and the
`tun=/udp=/tcp=/in=` counters replace logcat for a tester without adb.

## 8. Verification Checklist for Any Change

- [ ] Push -> CI green. The pipeline is: rust `cargo test --release` gate, 3-ABI build,
      **16 KB page-alignment check on every `.so`**, `assembleDebug`, unit tests
      (incl. `ManifestContractTest`), **`:app:lintDebug` platform-contract gate**.
- [ ] Manifest touched? The lint gate and `ManifestContractTest` must both still pass —
      every declared `foregroundServiceType` needs its matching `uses-permission`, and the
      VPN service must stay exported with its `android.net.VpnService` intent filter.
- [ ] New JNI symbols: update BOTH `NativeCore.kt` and `lib.rs` in the same commit.
- [ ] Never close the TUN fd from Kotlin after successful capture, and never `protect()` it.
- [ ] New platform API call? Assume it can throw or no-op silently; make the failure
      visible on screen (the tester has no adb).
- [ ] Loopback tests still pass without a device (they are the gate).

## 9. M7.6 — "linked but no internet" root causes and fixes

Device test on the M7.5 APK: host shared, client joined `DIRECT-…` from Wi-Fi settings,
app showed "PeerNet-432b connected" — but **zero internet in every app**, and sometimes
"no PeerNet network found" while actually joined. Six independent defects, each alone
enough to blackhole traffic:

1. **DNS was never forwarded anywhere.** The client's TUN advertises virtual DNS
   `10.215.17.1`; the host's NAT redirects `:53` to `dns_upstream`, but nothing ever
   called `set_dns_upstream`, so it stayed `None` and every query was dropped — no name
   resolution means no internet even with a perfect tunnel.
   Fix: `HostServer::bind` defaults to `1.1.1.1:53`, `startHost(port, name, dnsUpstream)`
   takes the real resolver, and `HostRuntime.systemDnsUpstream()` reads
   `LinkProperties.dnsServers` from the host's internet-carrying network.
2. **UDP NAT mapping keyed by the wrong port.** `get_or_create` inserted under
   `actual_port` (the bound port) instead of `client_src_port`, so every packet created a
   fresh socket and replies never matched a flow. Fix: key by `client_src_port`.
3. **Reply source port was the host's local port.** `pump_udp_replies` echoed
   `socket.local_addr().port()`; whenever port preservation failed the client's flow table
   lookup missed and the reply was discarded. Fix: pass and echo `client_src_port`.
4. **The certificate pin arrived late/empty.** It rode only in an mDNS TXT record, so
   `startTunnel` often got `""`, failed silently — while the default-route TUN stayed
   installed (fail-closed = total blackout). Fix: `LinkServer` banner is now
   `PN-LINK-2 <hostId> <fp|-> <tunnelPort>` built per connection from live engine state;
   the client reads the pin from the banner; mDNS re-registers when the fingerprint
   changes; Rust validates 64 hex chars and records a readable error.
5. **QUIC was routed over the wrong network.** `setUnderlyingNetworks` only *declares*
   the underlying network; it does not bind sockets, so the Rust UDP socket followed the
   default route (cellular) and never reached `192.168.49.1`.
   Fix: `bindProcessToNetwork(link)` while the tunnel runs, unbound on teardown.
6. **TUN came up before QUIC.** Order is now: bind process -> `startTunnel` -> wait for
   Connected (20 s, 250 ms poll) -> `establish()` -> `startTunCapture`. A failure now
   reports a reason and stops instead of leaving a blackhole interface.

Plus visibility, because the tester has no adb: `lastError()` and `engineStats()`
("tun=N udp=N tcp=N") over JNI, `ClientLinkManager.tunnelStatus`, both rendered on the
one screen; host card warns "Tunnel engine not running" **and names the engine's own
reason**; a stuck engine holding the port is recycled (`stopHost` + retry) on Share.

Also fixed: "no PeerNet network found" while joined — discovery no longer depends on mDNS
or SSID text; `gatewayCandidate()` probes the link's gateway (route gateway, or `x.y.z.1`
derived from the interface prefix) first, and the legacy-join watcher polls it regardless
of what the SSID string says.

### 7. The dominant defect: the host engine never started at all

That new host warning immediately paid for itself — it exposed the real killer behind the
whole "no internet" report:

**`quinn::Endpoint::server()` resolves its async runtime while binding**
(`default_runtime()` → `Handle::try_current()`), and `startHost` runs on a **JNI thread
with no tokio context**. `HostServer::bind` therefore returned `no async runtime found` on
every device, always: no certificate, an empty pin in both mDNS and the banner, and
nothing listening on 4433. The client could never have tunnelled regardless of defects
1–6 — those were real, but latent behind this one.

Every `#[tokio::test]` passed because a test always supplies the runtime that production
never has; the CI gate was structurally blind to it.

Fix: `bind_host_server()` enters the engine runtime (`runtime().enter()`) before binding,
applies the DNS upstream, and is what JNI now calls. Regression guard
`tests::host_binds_from_a_thread_with_no_runtime` binds from a plain `std::thread`
(asserting `Handle::try_current().is_err()` first) and *also* asserts the bare
`HostServer::bind` still fails there, so the test cannot become vacuous.

**Rule added:** anything quinn/tokio constructed on a JNI thread must be built inside
`runtime().enter()` or `runtime().block_on(..)`. Client paths were already safe —
`Endpoint::client` runs inside the spawned async connect.

### Retest script (M7.6, CI run `32651274881` or later)

1. Install `peernet-debug-apk` on both phones.
2. Host: tap SHARE. The card must show Network / Password / Address and **no**
   "Tunnel engine not running" warning.
3. Client: Wi-Fi settings -> join `DIRECT-…` with that password -> open the app.
4. Expect: "PeerNet-xxxx connected", then a VPN consent dialog (first run only), then
   status "tunnel up" and counters `tun=/udp=/tcp=` climbing.
5. Browse. If it still fails, the red status line names the failing stage — report that
   line verbatim (it is the engine's own `lastError`).

## 10. M7.7 — Platform-contract audit (Android APIs that fail only on devices)

M7.6 was found by patching one report at a time, so before adding features the whole
"phone refuses to run this" surface was audited. Everything below was found by reading
code against platform contracts (no device involved) and is fixed unless marked.

Ranked by severity, with the user-visible symptom each one produced:

1. **VPN service declared `foregroundServiceType="systemExempted"` without
   `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`.** Android 14+ throws `SecurityException` from
   `startForeground()`, killing the service in `onCreate` — the tunnel could never start
   on a modern phone. Now `specialUse` (+ `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`), whose
   permission was already declared.
2. **VpnService had no `<intent-filter android:name="android.net.VpnService">` and was
   not exported.** The platform cannot bind an unadvertised VPN service, so
   `establish()` returned null forever: "Android refused to create the vpn interface".
3. **`protect()` was called on the TUN file descriptor.** `protect()` takes *sockets*;
   on a TUN fd it fails (`ENOTSOCK`) and aborted every tunnel. The routing-loop guard is
   `addDisallowedApplication(packageName)`, which was already in place.
4. **`POST_NOTIFICATIONS` sat in the required-permission gate.** Two denials left SHARE
   permanently disabled with no explanation. `Permissions` now splits
   `required()` (nearby-devices/location) from `optional()` (notifications); only
   `required()` gates hosting.
5. **`startForegroundService` was called from composition/background.** Android 12+
   throws `ForegroundServiceStartNotAllowedException`; a link event arriving while the
   app was backgrounded crashed instead of reporting. Now wrapped and surfaced.
6. **`HostForegroundService` self-stopped on the replayed `StateFlow` value**, so the
   notification appeared and instantly vanished. Guarded with `sawHosting`.
7. **Native libraries' page alignment was never checked.** A 4 KB-aligned `.so` will not
   load on Android 15+ 16 KB-page devices. `core/.cargo/config.toml` passes
   `-Wl,-z,max-page-size=16384`, and CI now *verifies* the LOAD alignment of every
   shipped `.so` (a config file alone proves nothing).
8. **Oversized UDP datagrams were dropped silently**
   (`let _ = connection().send_datagram(..)`). QUIC datagrams are capped by the peer's
   limit, so HTTP/3 and large DNS answers vanished and browsers looked "slow" while they
   timed out into TCP. Rejects now go over the stream relay
   (`udp_exchange_via_stream`) and the reply is rebuilt onto the TUN.
9. **Wi-Fi Direct calls could throw `SecurityException` unhandled** (permission revoked
   mid-session) and hosting silently did nothing. Every guarded call now catches it and
   reports "Allow Nearby devices". Note lint only credits a `catch` in the *same method*
   — an inline wrapper helper does not satisfy it.
10. **Location services being off was never checked.** Wi-Fi Direct needs the system
    location toggle on, not just the grant; without it group creation never completes.
    `startSharing()` now refuses with "Turn on Location in system settings".

Notification quality (also fixed): channels were `IMPORTANCE_LOW` with `setSilent(true)`,
so the "hosting"/"tunnel" bars were easy to miss; the tunnel now has its own
`CHANNEL_TUNNEL` at `IMPORTANCE_DEFAULT` with a Stop action.

### Why CI never caught these

`assembleDebug` + unit tests only prove the code compiles and the pure logic works —
every defect above is a *manifest or platform-contract* violation. Two gates were added:

- `./gradlew :app:lintDebug` with `abortOnError` and `checkOnly` limited to platform
  contracts (`ForegroundServicePermission`, `ForegroundServiceType`, `MissingPermission`,
  `ExportedService`, …), plus `textReport = true` so the log lists every error at once
  instead of "first failure".
- `app/src/test/.../ManifestContractTest.kt`: the VPN service stays bindable and
  exported, every declared `foregroundServiceType` has its matching `uses-permission`,
  and required permissions stay declared.

Verified sound during the audit (no change needed): `NEARBY_WIFI_DEVICES` declared and
requested, rustls ring provider installed on both host and client, arm64 ABI packaged,
`PendingIntent` uses `FLAG_IMMUTABLE`, R8 keeps the JNI symbols, and the virtual DNS
address is covered by the `0.0.0.0/0` route.

Still unproven on hardware (never executed once): a QUIC handshake accepted over
Wi-Fi Direct, the client's QUIC socket riding the p2p network via
`bindProcessToNetwork`, the smoltcp terminator against Android's real TCP stack, and the
host relaying to its own resolver.

## 11. M7.8 — the two defects that actually caused "tunnel up, no internet"

Both were invisible to CI and to reasoning about the code; both were found by reading the
on-screen counters from a real session (`tun=3/1/5 udp=1/5/3 in=89/96/92` — numbers that
went *down* between polls).

### 1. The capture loop died on the first EAGAIN (fixed, `32c7645`)

`AsyncFd::try_io` intercepts `WouldBlock` itself: when the closure returns `EAGAIN`, the
call reports `Err(TryIoError)` **after clearing readiness** — it does *not* surface as
`Ok(Err(WouldBlock))`. The reader matched `Ok(Err(_)) | Err(_) => break`, so the first
empty TUN read (normal, one poll after the first packet burst) ended the capture for the
rest of the session. Every later DNS retry and every TCP SYN then sat unread in the TUN
queue: `tun` froze at a handful of packets and `tcp` stayed `0` forever. The writer had the
identical bug, which stranded inbound replies.

Now `Err(_) => continue` (readiness was cleared, so the next `await` blocks until real
data), `Interrupted => continue`, and a single rejected packet no longer ends the loop —
`READ_FAILURE_LIMIT` / `WRITE_FAILURE_LIMIT` (16 consecutive) separate "one bad packet"
from "the interface is gone". Regression test:
`capture_survives_an_idle_gap_between_packets` pushes three packets through a real
`SOCK_SEQPACKET` socketpair with idle gaps in between, which fails on the old code.

### 2. `startForeground` crashed the client on Android 10–13 (fixed, `9bab7f9`)

Both services passed `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` whenever `SDK_INT >= Q` (29).
`specialUse` — the constant `1 shl 30` and the manifest value — **only exists from API 34**.
On 29–33 the manifest attribute parses to `0x0`, so the platform threw
`IllegalArgumentException: foregroundServiceType 0x40000000 is not a subset of ... 0x0`.
The service is sticky, so Android restarted it, it crashed again, and the engine counters
reset on every restart — exactly the *decreasing* numbers seen on the Galaxy M11.

`service/ForegroundServiceType.kt` is now the single source of truth (`forSdk()` returns
the type only at API >= 34, else `null` -> 2-arg `startForeground`), gated by
`ForegroundServiceTypeTest`, which pins the behaviour for 26–33 vs 34–36 against the
literal `1 shl 30`. Both services also wrap the call: the VPN reports
"Android refused to start the tunnel service (...)" on screen instead of dying silently.

### Diagnostics added because the tester has no adb

- `engineStats()` is now `tun= udp= tcp= in= lost= cap= eng=`. `in=` counts replies only
  **after** the TUN write succeeds (it used to count arrivals, so it lied), `lost=`
  counts replies that arrived but could not be delivered, `cap=` is the capture loop and
  `eng=` the TCP terminator intake — with `eng=up` a stuck `tcp=0` means the phone sent no
  SYN, not a dead engine.
- Uncaught JVM exceptions are persisted to `SharedPreferences` and shown on the next
  launch as "Recovered from a crash: ...", so a crash loop names itself.
- Rust panics go through a hook into `lastError()` instead of vanishing into logcat.
- The TCP engine's device MTU now equals the VpnService MTU (1280) — `bulk_transfer_256k`
  asserts the largest packet handed to the TUN is `> 1000` and `<= 1280`, so the bound
  cannot pass vacuously.

### What is verified vs still unproven

Verified in CI: the rebuilt UDP reply keeps the flow's original source address (that is
what makes the virtual DNS IP work), swaps ports correctly, carries a correct IPv4 header
checksum and an intact payload (`udp_roundtrip_through_host_nat`,
`rebuild_packet_is_self_consistent`, `checksum_matches_rfc1071_example`).

Still unproven on hardware: TCP through the smoltcp terminator (`tcp=0` in every session
so far, fully explained by defect 1 — the phone was still waiting on DNS and never sent a
SYN), and sustained throughput.

### Retest script (M7.8, CI run `32682848115` / `0968b98` or later)

1. **Uninstall the old build on both phones** (the crash-looping service can survive an
   upgrade in a bad state).
2. Host: open PeerNet, tap SHARE. Expect no red line. Keep the host's own internet on.
3. Client: join the `DIRECT-...` network from Wi-Fi settings with the shown password,
   then open PeerNet and allow the VPN request.
4. Read the counter line. Expected: `cap=up eng=up`, `tun=` and `udp=` climbing while
   browsing, `in=` climbing, `lost=0`.
5. If it still fails, report the counter line, any red line, and any
   "Recovered from a crash: ..." line **verbatim** — each now points at a distinct cause:
   `cap=down` capture died, `lost>0` replies could not reach the phone, `in=0` with
   `udp>0` the host cannot reach the internet, `tcp=0` with DNS working the phone never
   sent a SYN.

