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

### 2. `startForeground` on Android 10–13 — DIAGNOSIS RETRACTED (`9bab7f9`, harmless)

**This section previously claimed a crash loop. That claim was wrong; an emulator test
disproved it. Do not repeat the reasoning.**

The original claim was: both services passed `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` whenever
`SDK_INT >= Q`, `specialUse` only exists from API 34, so on 29–33 the manifest attribute
parses to `0x0` and the platform throws `IllegalArgumentException: foregroundServiceType
0x40000000 is not a subset of ... 0x0`.

Why it is false: `android:foregroundServiceType="specialUse"` is compiled by **AAPT2 against
compileSdk 36**, so the binary manifest stores the *integer* `0x40000000`. Old platforms read
that int verbatim (`PackageParser` does not validate it against types it knows), so the
declared mask is `0x40000000` on API 29 too and the type **is** a subset. Nothing throws.

Proof, not reasoning: on branch `verify/gates-fail-on-old-bugs` the old behaviour was
reintroduced (`forSdk` returning the type from `Q`) and run `32687459491` was dispatched.
On the **API 29** emulator `host_service_reaches_the_foreground_on_this_api_level` **passed**.
Had the exception been real the service would have caught it and called `stopSelf()`, and the
test would have failed.

Consequences to keep in mind:
- `ForegroundServiceType.kt` and its unit test are still correct and are kept (the 2-arg call
  below API 34 is the documented approach), but they **fixed no user-visible defect**.
- The *decreasing* counters on the Galaxy M11 were therefore **not** a crash loop. See §12.

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

## 12. M7.9 — why it "stopped connecting automatically", and the approach change

The tester's report was "not auto connecting; entered the password manually; still no
internet". Three distinct causes were found. Only the first was ever a Rust bug.

### 1. Unbound probe sockets (fixed, `2ec0dba`) — the likely cause of no auto-connect

`probeHost()` used a bare `Socket()`. Android routes an unbound socket over the **default**
network. A Wi-Fi Direct group is flagged "no internet", so on any phone with mobile data the
default is **cellular**, where the host's `192.168.49.x` address does not exist. The probe
times out although the host is one hop away, `probeDetails()` returns null, no link is made,
and the VPN consent dialog is never shown.

`PeerNetVpnService.bindProcessToLink()` does bind the whole process — but only *after* the
tunnel starts, which is after linking. Discovery and auto-link run unprotected.

Fix: `probeNetwork()` prefers the linked/P2P network and `openProbeSocket()` builds every
host-facing socket from `network.socketFactory`, independent of any process-wide binding.
Failures now name the interface they used ("tried over the default network" vs
"tried over p2p-p2p0-6"), which is what distinguishes this from a host that is simply off.

### 2. The link-flap loop (fixed, `2ec0dba`) — explains the decreasing counters

`startLiveness()` cleared the link after 2 missed probes whenever
`joinedAsClient == false`. A join made through the **Wi-Fi picker never sets that flag**
(no Wi-Fi Direct callback fires), so for the tester it was always false. With cause 1 making
every probe miss, the sequence was: link -> VPN starts -> 10 s -> link cleared -> VPN
stopped -> legacy poll re-links -> VPN restarts. Each restart calls `startTunCapture`, which
**resets the engine counters** — the `tun=3` then `tun=1` then `tun=5` readings were
successive short-lived tunnels, not a crash loop.

Fix: a missed probe alone can no longer drop the link — `tunnelDelivering()` keeps it while
`in=` is still climbing. Every drop and every `onStartCommand`/`stopTunnel`/`onDestroy`/
`onRevoke` is now written to the diagnostics buffer, so a flap is unmistakable in the report.

### 3. Corrupted status strings (fixed, `2ec0dba`)

Five on-screen strings rendered as "Searching this network for a PeerNet host?" because an
earlier edit was written in the shell's ANSI codepage, turning every `…` and `—` into U+FFFD.
It compiles, lint is silent, and only a human reading the screen notices.
`SourceEncodingTest` now fails the build on any U+FFFD in Kotlin sources.

### Approach change: gates that run on Android, not just on the JVM

Two of these three bugs are invisible to JVM unit tests, and one of my earlier "fixes" was
based on reasoning that turned out to be false. So the workflow gained a `device` job
(`reactivecircus/android-emulator-runner`, API **29** and **34**, `x86_64`) running real
instrumentation tests, and every gate is validated by **reintroducing the bug and watching it
fail** before being trusted:

| Gate | Bug reintroduced | Result |
| --- | --- | --- |
| `capture_keeps_reading_across_idle_gaps` | reader `Err(_) => break` | **failed on API 29 and 34** — real gate |
| `host_service_reaches_the_foreground_on_this_api_level` | `forSdk` from `Q` | **passed** — bug is not real (see §11) |

Rule going forward: a gate that has never been seen to fail is not evidence.

### Retest script (M7.9, CI run `32691790001` / `2ec0dba` or later)

1. Uninstall on both phones, install the new APK.
2. Host: SHARE. The card must show "Clients probed: 0" and no red line.
3. Client: join `DIRECT-...` from Wi-Fi settings, open PeerNet, **leave mobile data ON**
   (that is the configuration that used to fail).
4. Expect the link within ~5 s and a VPN consent dialog. If not, the red line now says which
   interface was tried.
5. On any failure tap **SHARE DIAGNOSTICS** and send the whole report: it contains the probe
   attempts with interfaces, every VPN lifecycle event, and every link drop with its reason.


---

## 13. Build #105 host/client lifecycle post-mortem (M7.10, `0700af9`)

Four defects reported from build #105. All four were reproduced by reading the code paths,
not guessed; the port bug is now covered by a **validated** JVM gate (see the table below).

### 1. `LinkServer` :4434 `BindException: EADDRINUSE`, permanently

`HostRuntime.init` started the responder from inside a `wifiDirect.state` collector, so
`start()` ran **once per state emission** - and the framework emits several times while a
group forms. `start()` began with an unconditional `stop()` and then bound the socket
**asynchronously, inside the accept thread**. Two overlapping starts therefore raced:

* start A calls `stop()` (nothing bound yet), spawns thread A, returns;
* start B calls `stop()` - `serverSocket` is still `null` because thread A has not published
  it - so it closes nothing, spawns thread B;
* one thread binds, the other throws `EADDRINUSE`, and its `catch` sets `serverSocket = null`,
  discarding the **winner's** reference.

The winner is left parked in `accept()` on a socket no one can reach: the port stays bound,
`listening` is false forever, and the host card reads "port 4434 unavailable" for the rest of
the process's life. `SO_REUSEADDR` was already set and does not help - it applies to
`TIME_WAIT`, not to a live listener - and using `SO_REUSEPORT`-style tricks would only hide
the leak.

Fix (`LinkServer.kt`, rewritten): one lock guards `start`/`stop`; the **bind happens on the
caller's thread** so a concurrent `stop()` can always see the socket; `start()` is idempotent
(`LINKSERVER_ALREADY_RUNNING`); each socket carries a `generation` and only the current
generation may publish state; the accept loop closes its own socket in `finally`, so a socket
can never outlive its loop; `stop()` closes, interrupts and joins (bounded, 500 ms) and clears
everything. `HostRuntime` now starts it exactly once per share.

### 2. Client auto-connect broken

`WifiDirectManager.refreshGroupInfo()` set `hosting = true` whenever `requestGroupInfo`
returned a group. That call returns the group to **both** members, so a phone that had just
joined a host reported `hosting = true` *and* `joinedAsClient = true`. Everything on the
client is gated on NOT hosting:

* `ClientViewModel`'s join edge is `s.joinedAsClient && !s.hosting` - never true, so
  `autoLink()` never ran;
* the legacy poll bails at `if (state.value.hosting) continue` - forever;
* and the client started a `LinkServer` and an mDNS advertisement of its own.

So *joining the group* was precisely what disabled auto-connect. Fix: `classifyGroup()`, a
pure function returning `OWNER` / `CLIENT` / `STALE_OWNER` from the group-owner flag plus the
user's intent, unit-tested in `GroupRoleTest`.

### 3. STOP SHARE left the `DIRECT-...` network alive

Three separate leaks: a late `CONNECTION_CHANGED` broadcast re-entered `refreshGroupInfo()`
and resurrected `hosting = true`; the `createGroup` failure path could retry **after** stop,
recreating the group; and `clearGroupState()` never reset `joinedAsClient` /
`joinedGroupOwnerAddress`, so a client kept a link to a host that no longer existed. Fix: a
`@Volatile hostingRequested` intent gates every `createGroup` path, a group that arrives after
stop is removed immediately, removal is **verified** with `requestGroupInfo` instead of
trusting the callback (bounded to 3 attempts), and `clearGroupState()` clears the client
fields too.

One case is not fixable from an app: a client that joined by typing the passphrase in Android's
Wi-Fi picker has the SSID saved as a *user-added network*, which no app may remove. The report
now distinguishes that from a live P2P group rather than pretending the network is gone.

### 4. `SecurityException` in `acquireMulticast()`

`CHANGE_WIFI_MULTICAST_STATE` was genuinely missing from the manifest. Added. The catch is now
specific, records `MULTICAST_LOCK_DENIED` once, and `releaseMulticast()` nulls the lock so a
failed release cannot make every later acquire return a stale `isHeld`.

### Gate validation (rule from 12: a gate that has never failed is not evidence)

Bug reintroduced on throwaway branch `verify/linkserver-gate`, run `32697481344`:

| Assertion | With bug restored |
| --- | --- |
| `binds and answers a probe with the versioned banner` | **failed** (async bind: probe arrives before the socket exists) |
| `repeated start is idempotent and keeps serving` | **failed** |
| `stop releases the port so the same instance can rebind it` | **failed** |
| `concurrent starts never orphan the port` | **failed** |
| `a taken port is reported as a failure instead of looking healthy` | **failed** (old code returned success optimistically) |
| `probe counter resets per session and counts answers` | **failed** |
| `stop actually frees the port for an unrelated listener` | passed - single start/stop does not race |

6 of 7 catch it; the branch is deleted. Green run on `main`: `32697004993`.

### Diagnostics tags added

Host: `SHARE_START_REQUESTED`, `SHARE_ALREADY_ACTIVE`, `SHARE_ABORTED`, `ENGINE_STARTED`,
`ENGINE_START_FAILED`, `HOST_READY`, `ADVERT_REPUBLISH`, `LINK_RESPONDER_RESTART`,
`SHARE_STOP_REQUESTED`, `SHARE_STOP_COMPLETED`; `LINKSERVER_START_REQUESTED`,
`LINKSERVER_ALREADY_RUNNING`, `LINKSERVER_BOUND`, `LINKSERVER_BIND_FAILED`,
`LINKSERVER_SOCKET_CLOSED`, `LINKSERVER_LOOP_ENDED`, `LINKSERVER_STOP_REQUESTED`,
`LINKSERVER_STOP_COMPLETED`; `WIFI_DIRECT_CREATE_REQUESTED`, `WIFI_DIRECT_GROUP_CREATED`,
`WIFI_DIRECT_CREATE_ABORTED`, `WIFI_DIRECT_STOP_REQUESTED`,
`WIFI_DIRECT_REMOVE_GROUP_REQUESTED`, `WIFI_DIRECT_GROUP_REMOVED`,
`WIFI_DIRECT_GROUP_STILL_PRESENT`, `WIFI_DIRECT_GROUP_STUCK`, `WIFI_DIRECT_SESSION_CLEARED`,
`MULTICAST_LOCK_DENIED`.

Client (all stamped `s=<session>`): `AUTOCONNECT_START`, `AUTOCONNECT_STOP`,
`NETWORK_DETECTED`, `DIRECT_NETWORK_DETECTED`, `HOST_IP_DETECTED`, `LINK_ATTEMPT`,
`LINK_SUCCESS`, `LINK_FAILED`, `HOST_LOST`, `CLIENT_CLEANUP_COMPLETED`.

### Deliberately NOT changed

QUIC/`peernet-core` protocol, UDP framing, TUN setup, `VpnService` architecture, NAT,
forwarding, DNS, routing, encryption, the Windows client, and all Rust networking. None of the
four defects reached those layers - all four are Android-side lifecycle. Port 4434 was kept.
No `SO_REUSEADDR` band-aid. No state-machine framework: the existing collector plus an explicit
intent flag was enough.

### Retest script (M7.10, run `32697004993` / `0700af9` or later)

Internet is **not** expected to work yet - this build is about lifecycle stability.

* **A. Fresh share.** Host: SHARE. Expect no red line and "Clients probed: 0".
* **B. Auto-connect.** Client: join `DIRECT-PeerNet-...` from Wi-Fi settings, open PeerNet.
  Expect a link within ~5 s and a VPN consent dialog, with mobile data left ON.
* **C. Stop/restart (the port bug).** Host: STOP SHARE, then SHARE again, twice.
  Every share must come up clean - any "port 4434 unavailable" is a regression.
* **D. Teardown.** Host: STOP SHARE. On the client the `DIRECT-...` network must disappear
  from Wi-Fi settings (if the client joined via the picker, the *saved entry* remains - that is
  a system limitation; the group itself must be gone).
* **E. Double tap.** Host: tap SHARE twice quickly - the second must log `SHARE_ALREADY_ACTIVE`
  and must not drop a connected client.

On any failure tap **SHARE DIAGNOSTICS** and send the whole report; every tag above carries a
session/generation id, so overlapping attempts can be told apart.

---

## 14. Build #106 client-link lifecycle post-mortem (M7.11, `599965a`)

Build #106 established a working tunnel (the user opened YouTube through it) but five
lifecycle defects made it unusable in practice. All five were found by reading the code
paths; the client-side rules are now covered by **validated** JVM gates (table below).

### 1. False `HOST_LOST` every ~25 s while the tunnel was working

`startLiveness()` guarded the teardown with
`p2pBacked = wifiDirect.state.value.joinedAsClient`. That flag is only set by Wi-Fi Direct
**client callbacks**, which never fire when the user joins `DIRECT-...` by typing the
passphrase in Android's Wi-Fi picker - the documented, intended flow. The device log shows
exactly that: the group is on `wlan0` at `192.168.49.213/24`, no `p2p-` interface, so
`joinedAsClient` was false and the guard meant to protect P2P links never applied to the
commonest way of joining one.

Two further errors compounded it: `tunnelDelivering()` compares the engine's `in=` counter
against the previous sample, so an **idle** tunnel (user not loading anything) is
indistinguishable from a dead one; and a single 3 s TCP-connect timeout to :4434 counted as
a loss, which a power-saving host radio produces routinely.

Fix: `LinkPolicy.shouldDropLink()`. The QUIC tunnel state is the authority - `CONNECTED` or
data arriving keeps the link regardless of probes. The evidence that a session is Wi-Fi
Direct is now the **host's address** (`192.168.49.x`), not a callback flag. The signal that
genuinely ends a session is `hostNetworkPresent`: when the host stops sharing its group
disappears and the client's route into that /24 goes with it, which no sleeping radio can
fake. Direct sessions keep a bounded extra grace period (`DIRECT_MISS_FACTOR`) so a host
that never returns cannot strand the UI in a permanent "connected" state.

### 2. VPN, `tun0` and the Android VPN key outlived the session

VPN start **and stop** lived in `HomeScreen.kt`:
`LaunchedEffect(client.connectedHost?.hostId) { if (null) stopVpn() }`, over state read with
`collectAsStateWithLifecycle()`. Both stop when the Activity stops. With the screen off or
the app backgrounded, nothing observed the link clearing, so `stopVpn()` was never called:
the tunnel, the TUN interface and the VPN key survived a host that had stopped sharing.
(The Rust side was correct all along - `stopTunCapture` closes the fds via
`close_capture_fds()` - it was simply never asked to.)

Fix: `PeerNetVpnService` owns its own lifetime. It collects `ClientLinkManager.linkedHost`
from `onCreate()` on its own scope and tears down when the link clears. Teardown is a single
idempotent `teardown(reason)`. The UI keeps only the consent prompt, which genuinely needs
an Activity.

### 3. Auto-connect unreliable until app data was cleared

`HostRuntime.sharingActive` was a latch, added in #106 to stop double-SHARE from recreating
the group. If a share ended **without** `stopSharing()` - process killed, app swiped,
service stopped by the system, group dropped by the platform - the flag stayed `true`, and
every later SHARE returned early with `SHARE_ALREADY_ACTIVE`. The only escape was clearing
app data. #106 introduced this trap.

Fix, two layers, neither of which touches persistent identity: `startSharing()` now
**reconciles** - if the flag is set but no group is live or forming, it logs
`SHARE_STALE_STATE_RECOVERED`, resets session-scoped state and proceeds; and
`HostForegroundService` tells the runtime when hosting ends for any reason
(`HOST_GROUP_ENDED` from the state collector, plus `onDestroy`), so the flag is cleared at
the source. Host id and saved credentials are untouched, so a client that joined once still
auto-rejoins.

### 4. Client internet stopped when the **host's** screen turned off

Nothing in the app stops on screen-off: the foreground service stays foreground,
`HostRuntime` is a `@Singleton` on an app-scoped `CoroutineScope`, `LinkServer` runs a daemon
thread, and the QUIC engine lives in Rust. That leaves the radio. A Wi-Fi Direct group owner
is an access point plus a router; with the screen off the driver enters power save, stops
servicing the group promptly, and both the tunnel and the plain :4434 probes start timing out.

Fix: a `WifiManager.WifiLock` in `WIFI_MODE_FULL_LOW_LATENCY` (API 29+) /
`WIFI_MODE_FULL_HIGH_PERF`, held for the duration of a share. This is **not** a
`PowerManager` wake lock: the CPU and screen still sleep normally, and the screen is never
kept awake. `WAKE_LOCK` was already in the manifest. A failure to acquire is reported
(`WIFI_LOCK_FAILED`) rather than left to look healthy.

This one **cannot be proven in CI** - it needs the two phones. `HOST_SCREEN_OFF` /
`HOST_SCREEN_ON` and a 15 s `LINKSERVER_ALIVE` tick were added so the shared report
distinguishes "process frozen" (gap in ticks) from "radio asleep" (ticks continue while the
client loses internet).

### 5. The client probed the user's own router as if it were a host

`gatewayCandidate()` returned `gateways.firstOrNull()`, so a phone on `AirFiber21` probed
`192.168.31.1:4434` every few seconds forever. Fix: `LinkPolicy.rankGateways()` scores
candidates and **rejects** the unqualified ones. Ordinary Wi-Fi is probed only with
corroborating evidence - a remembered host at that exact address, a `DIRECT-` SSID, a `p2p-`
interface, or an explicit CONNECT tap. With none of those the client stays idle and logs
`AUTOCONNECT_IDLE` with what it saw. The VPN transport is excluded from candidate discovery,
so the tunnel can never be mistaken for a route to the host.

### Gate validation (rule from 12: a gate that has never failed is not evidence)

All three bugs reintroduced on throwaway branch `verify/link-policy-gate`, run
`32736848934`: **Unit tests failed, 10 assertions**, each naming its symptom.

| Assertion | With bug restored |
| --- | --- |
| `ordinary router is never probed on its own` | **failed** |
| `group owner outranks the router when both are present` | **failed** |
| `explicit connect tap allows probing the current network` | **failed** |
| `connected tunnel is never dropped over missed probes` | **failed** |
| `idle tunnel with no inbound packets is not treated as dead` | **failed** |
| `picker joined direct session survives probe loss without p2p callbacks` | **failed** |
| `host network disappearing ends the session immediately` | **failed** |
| `dead direct session is eventually dropped, not stranded forever` | **failed** |
| `clearing the link also advances the generation` | **failed** |
| `clearing drops the pinned network and the status text` | **failed** |

The remaining 11 assertions guard behaviour the restored bugs did not change (e.g.
`isWifiDirectAddress`, `keepReason`, generation advance on a real link). Branch deleted.
Green run on `main`: `32735933171`.

### Session identity

`ClientLinkManager.setLinked()` now returns a **generation** and increments on every call
**including clears**. Liveness jobs and VPN bring-up carry theirs and abandon themselves once
it moves on, so a probe or handshake from a session the user ended cannot tear down - or
resurrect - the session that replaced it. `PeerNetVpnService` also re-checks the generation
after the QUIC handshake: installing a default-route TUN for a dead session takes the phone
offline entirely.

`CLIENT_CLEANUP_COMPLETED` is now gated on `ClientLinkManager.tunnelActive` going false, with
a 5 s bound and a distinct `CLIENT_CLEANUP_INCOMPLETE`. #106 logged completion immediately,
while `tun0` was still up - the report claimed a cleanup that had not happened.

### Diagnostics tags added

Host: `SHARE_SESSION_CREATED id=<id>`, `SHARE_STALE_STATE_RECOVERED`, `HOST_GROUP_ENDED`,
`HOST_SCREEN_OFF`, `HOST_SCREEN_ON`, `LINKSERVER_ALIVE`, `LINKSERVER_STOPPED reason=<r>`,
`WIFI_LOCK_ACQUIRED`, `WIFI_LOCK_FAILED`, `WIFI_LOCK_RELEASED`.

Client: `P2P_NETWORK_SELECTED`, `LIVENESS_PROBE network=<n> interface=<i>`,
`LIVENESS_PROBE_SUCCESS`, `LIVENESS_PROBE_TIMEOUT` (carries session, generation, miss count,
destination, interface, `p2p=`, `directHost=`, `quic=`, `tun=`, `routed=`),
`AUTOCONNECT_RESET`, `AUTOCONNECT_RETRY`, `AUTOCONNECT_IDLE`, `CLIENT_CLEANUP_INCOMPLETE`,
`VPN_STOP_REQUESTED`, `TUN_CLOSED`, `VPN_SERVICE_STOPPED`. All client tags carry `s=<session>`
and link tags carry `gen=<generation>`.

### Deliberately NOT changed

`LinkServer` is untouched - the #106 port-ownership fix and its `LINKSERVER_*` tags are
preserved verbatim, and its validated gate still passes. Also unchanged: QUIC, UDP framing,
the packet format, TUN setup, NAT, TCP relay, DNS, routing, encryption, the Windows client and
all Rust networking. No liveness timeout or retry count was raised as a "fix" - the rules
changed, not the numbers. No `PowerManager` wake lock and nothing keeps the screen awake. No
SharedPreferences or app-data clearing: persistent device identity survives every path above.

### Retest script (M7.11, run `32735933171` / `599965a` or later)

* **A. Fresh connect.** Host SHARE; client joins `DIRECT-PeerNet-...` from Wi-Fi settings,
  opens PeerNet. Expect a link and VPN consent, then `LINK_SUCCESS`.
* **B. Host screen off.** With a client connected, turn the **host's** screen off for 60 s
  while browsing on the client. Internet must keep working. The report must show
  `HOST_SCREEN_OFF` followed by continuing `LINKSERVER_ALIVE` ticks, and **no** `HOST_LOST`.
* **C. Host STOP.** Host taps STOP SHARING. On the client the VPN key must disappear from the
  status bar. Expect `HOST_LOST ... host network gone`, `TUN_CLOSED`, `VPN_SERVICE_STOPPED`,
  `CLIENT_CLEANUP_COMPLETED ... tun=closed`.
* **D. STOP then SHARE again.** Must auto-connect with **no** clearing of app data. Any
  `SHARE_ALREADY_ACTIVE` that blocks a share is a regression; `SHARE_STALE_STATE_RECOVERED`
  is the recovery working.
* **E. Three cycles.** Repeat SHARE / connect / STOP three times. Session ids must increment
  and nothing may carry over.
* **F. Network transition.** Client: `DIRECT-...` -> normal Wi-Fi -> `DIRECT-...`. On the
  ordinary network expect `AUTOCONNECT_IDLE` and **no** probes to the router's gateway.

On any failure tap **SHARE DIAGNOSTICS** and send the whole report.

---

## 15. Build #108 regression: "tapping SHARE but not sharing" (run #110, `490cf8b`)

Build #108's own fix for the stale `sharingActive` latch broke SHARE outright. Worth
recording because the mistake was a category error, not a typo.

### What I did wrong

To clear the latch when a share ended without `stopSharing()`, I made
`HostForegroundService` call `hostRuntime.stopSharing()` whenever its state collector saw
`sawHosting && !hosting && !creating`. I treated "hosting ended" as a fact. It is not:

1. **A successful group formation publishes it.** `groupListener.onSuccess()` sets
   `pendingCreate = false` and then calls `refreshGroupInfo()`. `requestGroupInfo` can
   return null for the brand-new group before the framework has registered it, and
   `refreshGroupInfo()`'s null branch is `if (!pendingCreate) clearGroupState()` - which
   publishes `hosting=false, creating=false` **while the group is fine**. #108 removed the
   group on that signal, so SHARE created a group and immediately destroyed it.
2. **`stopService()` is asynchronous.** A previous service instance's `onDestroy` can be
   delivered after the user has started a new share, and #108 had `onDestroy` call
   `stopSharing()` unconditionally - tearing down the new session.

In #106 the same collector branch only called `stopForeground` + `stopSelf()`. That was
harmless: the notification vanished but `HostRuntime` kept hosting, so the bug was invisible.
Making the path destructive is what exposed it.

### Fix

`HostSessionPolicy` (new, pure, unit-tested). A service instance may release the sharing
latch only if **all** of: it still belongs to the current session; no group is live or
forming; and the user's hosting intent (`WifiDirectManager.hostingIntended`, newly exposed)
is cleared. `HostRuntime.noteHostingEnded()` replaces the `stopSharing()` call and **returns
whether hosting really ended**, so the service only stops itself when the runtime agrees.

The stale-latch fix stays in `startSharing()` via `HostSessionPolicy.shouldStartFresh()`,
which is where it belongs: evaluated against live group state at the moment of the tap, so it
cannot fire spuriously the way a background signal can. The service call was always redundant.

### Gate validation

Bug reintroduced on throwaway branch `verify/host-session-gate`, run #111
(`32742909395`): **Unit tests failed**.

| Assertion | With bug restored |
| --- | --- |
| `transient no-group during formation must not end the share` | **failed** |
| `a dying service instance cannot end a newer share` | **failed** |

Branch deleted. Green run on `main`: #110 (`32742208703`).

### Lesson for this codebase

Wi-Fi Direct state from `WifiP2pManager` is **eventually** consistent. A single emission is
never proof a session ended. Anything destructive must be driven by explicit user intent
(`hostingRequested` / `sharingActive`) plus a session id, never by an observed state edge.
Where a signal is genuinely ambiguous, prefer reconciling at the next user action over acting
on a guess in the background.

### Build numbering

GitHub run numbers are authoritative from here on. Earlier notes used internal build numbers
that drifted: the "#106 post-mortem" in section 14 shipped as run **#108**, and its
gate-validation failure was run **#109**.

## 16. WhatsApp voice calls failing: one UDP port, many peers (run #112, `805f1ef`)

Reported symptom: on run #110 everything worked - YouTube, Discord voice, WhatsApp
messaging - **except WhatsApp voice calls**, which never connected. Diagnostics showed a
completely healthy tunnel: `quicState=2 tunPackets=38479 udp=6875 tcp=31570 in=71980894
lost=0 cap=up eng=up`. No losses, no errors, nothing to point at.

### Root cause

`peernet-ffi`'s TUN flow table was keyed **only by the phone's UDP source port**:

```rust
type UdpFlows = HashMap<u16, UdpFlow>;   // u16 = src_port
struct UdpFlow { local_ip, dst_ip, dst_port }
```

Every outbound packet did `insert(sport, ...)`, overwriting the entry with its newest
destination. On the way back, `pump_udp_replies` **discarded** the real peer that the host
reported in `hdr.dst_ip`/`hdr.dst_port` and rebuilt the reply from `flow.dst_ip`/
`flow.dst_port` - i.e. from whichever destination that port had touched last.

For one port talking to one destination this is invisible. It is fatal for the opposite
shape, which is exactly what a WebRTC/ICE agent does: from a **single** local port it fires
STUN/TURN/peer connectivity checks at **many** candidates in parallel. Each reply was
rebuilt with the wrong source address, and ICE requires a response to arrive from the
address the request was sent to (RFC 8445 symmetry check), so the phone's ICE agent
discarded every single one. No candidate pair ever validated, so the call never established.

Why the control tests all passed and hid it:

| Test | Shape | Result |
| --- | --- | --- |
| YouTube, WhatsApp messaging | TCP, terminated locally by smoltcp | unaffected |
| Discord voice | UDP to a **single** media server | last-destination is always correct |
| WhatsApp voice | UDP, **one port to many ICE candidates** | every reply misattributed |

This was never WhatsApp-specific. Any ICE/WebRTC caller (Meet, FaceTime, Signal) was broken.

### Fix

Flows are keyed by the full tuple `(src_port, dst_ip, dst_port)`, and a reply is attributed
to the peer the host actually reports. The host side already did the right thing: its NAT
socket is unconnected and `pump_udp_replies` uses `recv_from`, so the true peer was in the
datagram all along - the client was throwing it away.

The **per-port fallback is retained deliberately**, for the two cases where no exact tuple
can match:

- **virtual DNS.** The phone queries `10.215.17.1:53`; the host rewrites the destination to
  a real resolver, so the answer arrives from an address the phone never addressed. The
  reply must still appear to come from `10.215.17.1:53` or the phone's stack drops it.
- **the stream fallback** (`udp_exchange_via_stream`), which relays only
  `(src_port, payload)` and carries no peer address at all.

Tuple keying makes the table grow with ICE fan-out, so it is now bounded: an idle sweep
(reusing `UDP_NAT_IDLE_TIMEOUT_SECS`, so both ends forget a flow together) plus a cap that
trims to 3/4 rather than exactly to the limit - trimming to the limit would re-sort the
whole table on every packet once full, turning a bounded table into a per-packet cost.

Second, independent defect fixed in `peernet-host`: the per-port reply-pump registration in
`udp_readers` was never removed. Once the NAT swept an idle mapping, the same local port
came back (`get_or_create` preserves the client source port), `insert()` returned `Some`,
`is_new` was false, and **no reply pump was spawned** - that flow went silently one-way.

### Diagnostics added

`engineStats()` gained payload-free aggregates, because "UDP works" and "UDP works only for
single-destination flows" were indistinguishable from the old counters:

`flows=open/ports fnew=.. fexp=.. fanout=.. rok=.. rmap=.. rmiss=..`

`fanout>0` proves ICE-style multiplexing is present; `rok` vs `rmap` separates exact matches
from fallback remaps; `rmiss>0` means replies arrived that no flow could claim. Metadata and
counters only - no payloads, no per-packet addresses logged.

### Gate validation

Bugs reintroduced on throwaway branch `verify/udp-flow-gate`, run #113 (`32759201074`):
**Build APK (cargo test) failed**.

| Assertion | With bug restored |
| --- | --- |
| `multi_destination_replies_keep_their_own_source` - "reply source IP must be the peer that answered, not the last dest" | **failed** |
| `idle_flows_are_swept_under_pressure` - "table must be bounded after sweep, was 2149" | **failed** |

The DNS/stream fallback tests kept **passing** under sabotage, which is the point: they
confirm the fix preserves the virtual-DNS contract instead of trading one break for another.

Branch and red run deleted. Green run on `main`: #112 (`32758614656`).

### Lesson for this codebase

A UDP "flow" is not identified by a port. Keying reverse-path state by source port alone
silently corrupts any protocol that multiplexes destinations over one socket, and the
corruption is invisible to every aggregate counter - the tunnel reports zero loss because
nothing was lost, only misaddressed. When rewriting addresses on a return path, carry the
real peer end-to-end and treat rewriting as the exception that must justify itself.

### Not verified

Only the user two phones can confirm WhatsApp calling now works. CI proves the flow-table
and bounding logic; it cannot prove ICE succeeds on a real network. The host `udp_readers`
leak fix has **no dedicated gate** - it is a small "remove your own registration on exit"
cleanup, but it is untested and should get a test if that path is ever touched again.
## 17. Screen-off drops and the stale VPN icon (run #114, `bb1449d`)

Two user reports, one root cause:

- **A**: with the screen off, the client loses internet (the host `WifiLock` added in
  #108 was not enough).
- **B**: after disconnecting Wi-Fi on the client, the Android VPN key and the
  "Internet is arriving through the host phone" notification stay up - and the phone
  has no internet at all.

### Root cause

All client link supervision lived in `ClientViewModel.startLiveness(host, gen)`
(`ClientViewModel.kt:636`), i.e. in **`viewModelScope`**. That scope is cancelled when the
Activity/ViewModel is destroyed - screen off long enough, app swiped away, memory pressure.
When it died, `livenessJob` died with it and **nothing ever called `clearLink(...)`**.

The consequence is worse than a stale icon. `PeerNetVpnService` installs a
**default-route TUN**. Once QUIC is dead but the TUN is still installed, every packet on
the device is routed into a tunnel with no far end - so the phone is not merely showing a
wrong icon, it has *no internet*, and the only visible symptom is the icon the user
reported. Bug B was Bug A wearing a different hat.

`PeerNetVpnService.watchLink()` only reacted to `linkedHost == null`. Nothing in the
service observed **its own underlying network dying** - there was no
`ConnectivityManager.NetworkCallback` anywhere in the client.

This is the same ownership mistake fixed in #108, left half-done: #108 moved the *stop*
path out of the UI (`HomeScreen` no longer stops the VPN) but left *liveness* UI-scoped.

### Why the WifiLock asymmetry mattered

`WifiLock` existed **only** in `HostRuntime.kt` (86-136). The client had none. Wi-Fi Direct
power-saves aggressively with the screen off, so a screen-off stall could originate at
either end and there was **no way to tell them apart** from a diagnostics dump.

### Fix

New `app/src/main/java/com/peernet/wifiextender/service/TunnelSupervisorPolicy.kt`, pure and
JVM-testable (no Robolectric in this project), following `service/ForegroundServiceType.kt`:

| Function | Rule |
| --- | --- |
| `shouldTeardownOnLoss(lost, underlying, tunInstalled)` | only the tunnel's **own** network ends it; an unattributable loss (`underlying == null`) never does |
| `shouldHoldWifiLock(tunInstalled)` | client holds the lock exactly while it owns a TUN |
| `canReachHost(hostAddress, isVpn, addresses)` | same `/24` as the host, port suffix tolerated, **VPN always rejected** |

In `PeerNetVpnService`:

1. `watchUnderlyingNetwork()` registers a `NetworkCallback`. The `NetworkRequest` uses
   **`clearCapabilities()`** - a Wi-Fi Direct network advertises no
   `NET_CAPABILITY_INTERNET`, so a default request would never match it and the one loss
   that matters would never be delivered. `onLost` mirrors `onRevoke` exactly:
   `linkManager.setLinked(null)` then `stopTunnel(...)`. This runs on the service
   lifetime, so it is immune to UI death.
2. A client `WifiManager.WifiLock` (`"peernet-client"`, `WIFI_MODE_FULL_LOW_LATENCY` on
   API 29+, `setReferenceCounted(false)`) acquired when the TUN is installed and released
   in `teardown()`. Never fails tunnel bring-up; logs `WIFI_LOCK_ACQUIRED` /
   `WIFI_LOCK_FAILED` / `WIFI_LOCK_RELEASED`. This is a `WifiManager` lock, **not** a
   `PowerManager` wake lock, and it does not keep the screen on.
3. `resolveUnderlyingFromHost()`: `EXTRA_NETWORK` comes from `linkedNetwork()`, which can
   be **null**. Without a known underlying network the new watch would silently no-op -
   the exact failure mode this change exists to remove - so the service recovers it from
   the host's own subnet. This also repairs socket pinning on that path.

### Gate validation (two rounds, both red runs deleted)

Round 1 sabotaged three rules; **3 of 3** caught:

| Assertion | With bug restored |
| --- | --- |
| `losing the tunnel's own network ends the tunnel` | **failed** |
| `the client holds a wifi lock exactly while it owns a TUN` | **failed** |
| `subnet matching is not a loose prefix match` | **failed** |

The fourth sabotage was **ineffective** - `if (isVpn) return false.also { }` still returns
`false`, so the VPN guard was never actually broken and its gate was unvalidated. Round 2
removed the `isVpn` guard outright: exactly **1** test failed,
`the VPN itself is never the route to the host`. Recording this because a sabotage that
does not change behaviour proves nothing, and it is easy to miss when the run is already
red for other reasons.

Green run on `main`: #114 (`32810879266`), all four jobs. Unit tests: 64.

### Lesson for this codebase

Supervision must be owned by the component whose lifetime matches the thing supervised. A
watchdog in `viewModelScope` guards the UI, not the tunnel. And when a default-route TUN is
involved, "stale VPN icon" is never cosmetic - it means the device is blackholing all
traffic, so treat any stale-indicator report as a total-connectivity bug.

### Not verified

Screen-off behaviour under Doze **cannot be tested in CI** - it needs the user's two
phones. What is provably fixed: the icon/notification now clear on network loss regardless
of UI state, and the client now holds a WifiLock at all.

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared in `AndroidManifest.xml` but is
**never used anywhere in code** (verified by search). With `PowerManager` wake locks ruled
out by design, a user-granted Doze exemption is the only remaining lever if the client
WifiLock alone does not fix screen-off. It needs a one-time prompt, which touches the
"one screen, two buttons, no settings" mandate - so it was deliberately **not** added here
and is an open question for the user.
## 18. The last screen-off lever: a one-time Doze exemption (run #117, `fa31636`)

Follow-up to section 17. After both phones held a `WifiManager.WifiLock`, one gap
remained: **a lock on the radio is not a lock on the app**. `WifiLock` keeps the Wi-Fi
chip serviceable, but the system can still suspend the process with the screen off, and
once the process stops running the QUIC keepalive (20 s) misses its deadline and the 90 s
idle timeout kills the connection.

`PowerManager` wake locks are ruled out by design here, so the only remaining lever is a
**user-granted battery-optimization exemption**. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
was already declared in `AndroidManifest.xml` but was **never referenced in code** - a dead
permission, flagged at the end of section 17 and now actually wired up.

### The rule

New `app/src/main/java/com/peernet/wifiextender/power/DozeExemptionPolicy.kt`:

`shouldPrompt(sessionActive, alreadyExempt, alreadyAsked)` = `sessionActive && !alreadyExempt && !alreadyAsked`

Trivial-looking, but each clause is load-bearing in an app whose entire UX rule is "one
screen, two buttons, no settings":

- **`sessionActive`** - a dialog on a cold start would be the first thing a new user sees,
  with no context for why the app wants it, and there is nothing to protect yet.
- **`!alreadyExempt`** - never ask for what we already hold.
- **`!alreadyAsked`** - a user who declined has answered. Re-asking every session turns a
  two-button app into a nag screen. Persisted in `peernet_power` prefs.

`DozeExemption` holds the Android side separately (`PowerManager.isIgnoringBatteryOptimizations`,
the prefs flag, and `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) so the rule stays
JVM-testable. `requestExemption()` **records the attempt before launching**, so a build with
no activity for that action cannot produce a prompt loop; it returns false and the session
continues degraded, logged as `DOZE_EXEMPTION_PROMPT_UNAVAILABLE`.

### Why the trigger is not the button tap

The prompt fires from a `LaunchedEffect` on "a session is genuinely up" - `HostState.READY`,
or client `quicState == STATE_CONNECTED` - never on the SHARE/CONNECT tap. Two concrete
reasons, both of which would have been bugs:

1. At tap time the client still has the **VPN consent dialog** pending. A second system
   dialog would cover it, and a user who dismissed the wrong one would end up with no
   tunnel and no explanation.
2. Starting an activity moves the app off-screen, and on **Android 12+ a foreground service
   cannot be started from the background** - the exact `ForegroundServiceStartNotAllowedException`
   the code at `HomeScreen.kt:137` already exists to report. Waiting until the service is
   running sidesteps it, and an app with a running FGS is not subject to the restriction
   anyway.

### Gate validation (red run deleted)

Sabotage: `sessionActive || !alreadyExempt` - i.e. prompt eagerly and ignore prior refusal.
**4 of 5** tests failed:

| Assertion | With bug restored |
| --- | --- |
| `never prompts before a session is running` | **failed** |
| `never prompts when already exempt` | **failed** |
| `never asks a second time` | **failed** |
| `an exempt user who was asked before is still never prompted` | **failed** |

`prompts once when a session is running and the exemption is missing` correctly kept
passing - the sabotage still returns true for that case, which is what a well-aimed
sabotage should leave alone.

Green run on `main`: #117 (`32813697111`), all four jobs. Unit tests: 69.

### Not verified

Whether this actually fixes screen-off needs the user's two phones. Three independent
mechanisms are now in place - host WifiLock (#108), client WifiLock (#114), Doze exemption
(#117) - and if the stall persists after all three, the next suspect is the QUIC idle
timeout versus real suspend duration, **not** more locks.

### Also in this run

The root `README.md` was rewritten: how the tunnel works, why Wi-Fi Direct + QUIC + TUN +
userspace NAT were chosen over a proxy (each with the reason it beats the alternative), the
repository layout, tech stack, the CI-only build process, and the two testing rules that
govern this codebase (no Robolectric -> pure policy objects; a gate that has never failed is
not evidence).

No release build is published: **ads and a premium tier** (monthly, or one-time lifetime,
lifting a session limit) are planned first, and release signing is not configured. The
README says so rather than leaving the absence unexplained.
## 19. "It asks for the password every time": the wrong API gate (run #119, `003f15b`)

User report: joining works and internet flows, but after the client toggles Wi-Fi off and
on, the network is not remembered and the password must be typed again - and only in one
direction (M11 as client failed, J4 as client did not).

### Root cause

`WifiDirectManager.createGroup` gated its custom `WifiP2pConfig` behind
**`Build.VERSION_CODES.TIRAMISU` (33)**. But `WifiP2pConfig.Builder`,
`setNetworkName`, `setPassphrase` and `enablePersistentMode` are all **API 29 (Q)**.

So on every phone below API 33, `HostRuntime`'s carefully chosen stable credentials were
built, passed in, and **silently discarded** - the code fell through to the legacy
`createGroup(ch, listener)`, which lets the framework generate a fresh SSID and passphrase
**per share**. The client had saved credentials that no longer matched anything.

The comment at the call site even said "honored on API 33+", so the limitation was known
and written down; what was wrong was the belief that 33 was the minimum. Nothing failed,
nothing logged, and the only symptom was a user retyping a password.

The same wrong gate sat on `joinByCredentials` (line 271), so clients that were perfectly
capable of joining programmatically were forced through Android's Wi-Fi picker by hand.

A second, independent defect: the group was **temporary**. `enablePersistentMode(true)` was
never called, and a temporary group discards its credentials when it ends, so even a
correct passphrase would look like a new network next time.

### Why the asymmetry between the two phones

Both phones are below 33, so both regenerated credentials as hosts. What differed is
whether the *client* OS kept a saved entry for a `DIRECT-` SSID whose credentials had
changed underneath it. That is OEM/version behaviour we do not control - which is the
point: the fix is to stop the credentials changing at all, not to argue with the client's
Wi-Fi settings.

### Fix

- Both gates lowered **33 -> 29**, and `enablePersistentMode(true)` added to the group
  config and to `joinByCredentials`.
- `createGroup` now degrades **one capability at a time**: persistent + custom -> custom
  only -> system credentials. Dropping straight to system credentials on the first failure
  would have thrown away the stable passphrase - the entire point - merely because a build
  refused persistence. Each step logs `WIFI_DIRECT_CREATE_RETRY ... dropping=`, and the
  final fallback says outright that the password will now change every share.
- `requestedPersistent` is re-armed by every `startHosting`, so one stubborn attempt cannot
  permanently downgrade later shares on that phone.

### New `wifi/GroupCredentialsPolicy.kt`

The network name and passphrase were **duplicated string literals** in two files:
`HostRuntime` built `"DIRECT-PeerNet-$shortId"` / `"pn-$hostId"`, and `ClientViewModel`
rebuilt both to auto-join. Nothing connected them, so editing either would have broken
auto-join silently. They now live in one policy object with a test asserting host and
client agree.

**The default passphrase is derived, not random.** A client cannot be *told* a passphrase
before it joins - the banner server (4434) and NSD are both inside the group - so the only
passphrase it can present unaided is one it computes from the host id it already
discovered. The trade-off is explicit and unchanged from the previous `"pn-$hostId"`
default: the default group is joinable by anyone nearby who knows the scheme, and the
tunnel is still authenticated by certificate fingerprint over TLS 1.3, so joining the group
grants no access to traffic. A host wanting a private group sets a custom passphrase, which
by definition cannot be derived and must be typed once.

The derived alphabet excludes `0/O`, `1/l/I` and `i`. This string is read off one screen and
typed into another phone; a character the user cannot distinguish is a failed connection
that looks like an app bug.

### Editable password

`HostCredentials` persists a user-chosen passphrase in `peernet_group`. A stored value that
fails validation is **ignored rather than trusted** - otherwise one bad write would make
every future share fail with an opaque framework error, recoverable only by clearing app
data (which this project forbids as a fix).

The field on screen shows the group's **actual** passphrase, not the stored preference. When
the framework refused our config those differ, and showing the preference would have the
user typing a password that cannot work. Saved on IME Done: persisting each keystroke would
store half-typed passwords, and a separate button was explicitly not wanted.

### Client notification Stop

The client's tunnel notification is `setOngoing(true)` and cannot be swiped, so there was no
way to end a tunnel without opening the app - poor for something that owns the default
route. It now has a Stop action mirroring the host's, delivered by a manifest receiver
(`VpnNotificationActionReceiver`) for the same reason as the host's: independent of process
state and OEM service-intent handling.

The receiver does **not** simply clear the link. `ClientViewModel` owns the visible client
state and does not observe `ClientLinkManager.linkedHost`, so clearing the singleton would
have torn the tunnel down while the screen still read "Connected to …" - a faked UI state,
which this project forbids. Instead the receiver raises `ClientLinkManager.requestStop()`,
the ViewModel runs its own tested `disconnect()`, and the receiver additionally clears the
link, leaves the group and pokes the service so a stop still happens when **no ViewModel is
alive** - which with the screen off is the normal case, not the exception.

### New `client/AutoConnectPolicy.kt`

A Stop that reconnects itself is worse than no Stop. `clearLink` deliberately re-arms
auto-connect (`pollMisses = 0`, `AUTOCONNECT_RESET`) so a link lost to an error retries at
once; applied to a stop the user asked for, that re-arms within one poll.

Leaving the group is not sufficient either: below API 29 the user associates through
Android's Wi-Fi picker, and `removeGroup()` does not drop a plain Wi-Fi association, so the
host stays reachable and the client re-links. So an explicit stop is now remembered against
the network it happened on, and forgotten when the user taps CONNECT or joins a different
network - never latched forever.

### Gate validation (red run deleted)

Three distinct sabotages, each mapping to its own assertion:

| Sabotage | Test that failed |
| --- | --- |
| `MIN_LENGTH = 7` | `exactly eight characters is accepted`, `too short is rejected with a reason the user can act on` |
| `ALPHABET = "01"` | `the derived passphrase avoids characters a user cannot tell apart` |
| `shouldAutoLink` returns true always | `a user stop is honoured while still on the same network` |

The ambiguous-alphabet sabotage was chosen as `"01"` rather than a realistic alphabet
because a 10-character sample from a 36-character set only *probably* contains an ambiguous
character - a gate that fails 84% of the time is not a gate.

Green run on `main`: #119 (`32827404244`), all four jobs. Unit tests: 83.

### Lesson for this codebase

An API level written from memory is a silent feature switch. The comment said "honored on
API 33+" and was believed for three builds; the real minimum was 29, and the cost was the
single most visible annoyance in the app. When gating on `SDK_INT`, the version belongs to
the *API being called*, and it is worth checking rather than recalling - especially for a
capability whose absence degrades silently instead of throwing.

### Still open

- **Host screen-off stops sharing.** Reported after run #117. The screen receiver in
  `HostForegroundService` only writes diagnostics, so it is not the direct cause. The
  candidate is `observeState()`: a transient "no group" reading while the radio power-saves
  reaches `noteHostingEnded`, or the OEM genuinely tears the group down on screen off. The
  diagnostics distinguish these - `HOST_GROUP_ENDED (service stopping)` means our code
  agreed the group was gone; `WIFI_LOCK_*` and a gap in `tick` entries mean the process was
  frozen instead. Not guessed at pending that dump.
- **Intermittent missing tunnel** and a **crash**, both awaiting a diagnostics export.

## 20. "TCP connection refused": the group was cleared right after it was created (run #122, `b540304`)

Client SM-M115F (Android 31) reported `TCP CONNECTION REFUSED` against `192.168.49.1:4434`
while the host SM-J400F (Android 29) reported `probes=0`. Both statements were true, and
together they ruled out most of the obvious suspects: `ECONNREFUSED` is an RST or an ICMP
port-unreachable, so a listener was genuinely absent at that instant - not a wrong-network
problem (that is a timeout) and not a bind-address problem (`LinkServer` binds `0.0.0.0`).
`probes=0` confirmed no client TCP connection had ever been accepted.

### Root cause

`WifiP2pManager.createGroup` reports success through its `ActionListener` **before**
`requestGroupInfo` will return the new group. For a short window after the success callback
the framework still answers `requestGroupInfo` with `null`.

`groupListener.onSuccess()` cleared its `pendingCreate` guard at the top of the callback and
then called `refreshGroupInfo()`. That first report was the transient null, `pendingCreate`
was already false, and the null branch treats a missing group as the authoritative "session
is over" signal - so the just-created, perfectly healthy group was wiped:

```
WIFI_DIRECT_GROUP_CREATED
WIFI_DIRECT_SESSION_CLEARED (was hosting=true)
...HOST_READY only later, once a broadcast re-read the group
```

The contradictory `group=false` then `group=true` in the host diagnostics was this bug, not
a benign transitional state.

### Why a false teardown caused a refused connection

`HostViewModel.uiState` derives `HostState` purely from `WifiDirectManager.state`, so
`hosting=false` moved the host to `IDLE`: the button flipped from STOP SHARING back to
SHARE and the details card vanished, though hosting was fine. That invited a second tap,
which ran `stopSharing()` then `startSharing()` - closing port 4434 and removing and
recreating the group. `LinkServer` bumps its generation three times per such cycle, which is
exactly the `2 -> 5 -> 8 -> 11` in the logs: four sharing sessions where the user intended
one. The client's 4s-to-15s poll kept landing in those stop windows, and each landing was a
refused connection.

So the failure was never in the socket code. The chain was: wrong ordering in the create
callback -> false IDLE -> user-driven churn -> 4434 flapping -> refused.

### The fix

`pendingCreate` is no longer cleared when `createGroup` succeeds; it is cleared only when a
real group has actually been observed. A create is "done" when the group exists, not when
the framework says it accepted the request. The decision moved into a pure
`GroupLifecyclePolicy.onGroupReport(groupPresent, createInFlight)` so the ordering invariant
is unit-testable - the module has no Robolectric, so anything touching `WifiP2pManager`
directly cannot be tested at all.

A null report while a create is in flight now writes
`WIFI_DIRECT_GROUP_PENDING (transient null during create - keeping session)` instead of
silently clearing, so the window is visible in diagnostics rather than inferred.

The `!hostingRequested` path still clears `pendingCreate` immediately: a STOP that arrives
while the group is forming must not leave the flag set, or a later null could never end the
session.

### Gate validation (red run deleted)

Reverting the policy to the old "clear on any null" behaviour failed exactly two of the five
new tests:

| Sabotage | Tests that failed |
| --- | --- |
| `clearSession = true` regardless of `createInFlight` | `transient null right after create keeps the live session` (:36), `full create sequence never clears the session until the real teardown` (:88) |

The other three tests cover the group-present and genuine-teardown paths, which the sabotage
does not touch, so they correctly kept passing - the blast radius matched the change.

Because the workflow only triggers on `main` pushes and PRs, the sabotage branch was run via
`workflow_dispatch --ref`; the red run and the branch were both deleted afterwards.

Green run on `main`: #122 (`32854744234`), all four jobs. Unit tests: 91.

### Lesson for this codebase

An asynchronous success callback is not proof that the thing it created is observable yet.
`createGroup` succeeding and the group existing are two different events, and the code
treated them as one. Where a guard exists specifically to cover that gap, the guard must be
released by the *confirming observation*, never by the callback that opens the gap - clearing
it early converts the guard into a no-op while leaving it in the source, which reads as
protection during review.

It is also worth noting how far the symptom was from the cause: a networking-looking error
(`connection refused`, a port, an IP) was produced by a state-ordering bug in Wi-Fi Direct
lifecycle handling, and amplified by a UI that derives its button state from that state.
Chasing the socket would have found nothing wrong with it.

### Deliberately not changed in this run

The audit found four further issues, left for a follow-up so this fix can be verified alone:

- **`bindProcessToNetwork` is process-wide and sticky** (`PeerNetVpnService`): a phone that
  was a client and then becomes a host can leave the process bound to the old network, which
  would misroute the host's own accept path. Presents as a timeout rather than a refusal, so
  it is not the cause of this report.
- **Client is poll-only** - no `registerNetworkCallback` anywhere in `ClientViewModel`; a
  callback will be added as an accelerator with the proven poll kept as the fallback.
- **`groupOwnerAddress` is only populated when we are the GO** (`go=?` in `HOST_READY`), and
  the client infers `.1` from the subnet instead of preferring the discovered address.
- **`probeHost` swallows the exception** - `ConnectException` becomes a friendly string
  without logging the cause, and `NoRouteToHost`/`ENETUNREACH` fold into a generic
  `IOException`. Classifying `TCP_REACHABLE/REFUSED/TIMEOUT/NO_ROUTE` would have identified
  this failure from the first diagnostics export instead of requiring a full audit.
## 21. The host screen said IDLE while the share was alive (run #124, `b38841e`)

**Symptom.** During a healthy share the host screen went back to showing
`SHARE`, and the network-details card vanished. Nothing had actually stopped:
the group, the QUIC engine and the :4434 responder were all up.

**Why it was not cosmetic.** IDLE is an invitation. The tester tapped `SHARE`
again, which ran a full stop/start: the group was recreated and :4434 was
closed and rebound. That is the sequence that produced `TCP connection refused`
on the client. The screen manufactured the failure it appeared to report.

**Cause.** `HostViewModel.uiState` derived `HostState` purely from
`WifiDirectManager.state`. That flow is an *observation* of the platform, and
the platform reports `hosting = false` for a moment even while a share is
healthy. Intent lived in `HostRuntime.sharingActive` and was never consulted, so
any transient outranked the user's standing request to share.

Run #122 removed one producer of the transient (`refreshGroupInfo` clearing the
session on a null group). This removed the fragility: no other transient, from
any source, can flip the screen to IDLE while intent stands.

**Fix.** `HostRuntime.sharingIntended` exposes intent. New pure
`HostStatePolicy.evaluate(sharingIntended, groupLive, error)` owns the decision.
The ordering is load-bearing in two places:

- A **live group outranks an error**. The staged credential fallback records an
  error on a failed attempt and then succeeds; reporting `ERROR` over a working
  share hid the details card and contradicted the headline.
- An **error outranks intent**, because `startSharing` aborts *before* setting
  its intent latch when location services are off. Checking intent first showed
  IDLE and swallowed the one message naming what to fix.

Intent with no group and no error is `CREATING_GROUP`, which `HomeScreen` already
treats as hosting, so the button reads `STOP SHARING` through a transient.

`error` is surfaced in `HostUiState` only when the state is `ERROR`, so a live
group cannot display "Sharing internet" and an error message at once.

**Gate.** `HostStatePolicyTest`, 7 tests. Sabotage: restoring the
observation-only `else -> IDLE` turned `Unit tests` green to red.

**Not fixed by this.** A screen-off failure where the *client actually loses
internet* is a different defect at a different layer. A derivation error cannot
take a client's internet away. See section 22.

## 22. The diagnostics buffer was erasing the screen-off evidence (run #126, `6e2f9a0`)

**Context.** The tester reported, on run #122 with the host display off, that
the host reverted to not-sharing **and** the client lost internet. The second
half rules out section 21 as the cause.

`HostForegroundService` was built to settle exactly this question. It ticks every
15s while sharing and documents the rule at `aliveTick()`: a **gap** in those
entries means the process was frozen by Doze or the OEM's standby, whereas ticks
that continue while the client is dead point at the radio or the group. Those two
causes need opposite fixes.

**The defect.** `Diagnostics.note` collapsed consecutive identical lines into
`(xN)` and rewrote the line with **only the newest stamp**. The tick's text is
constant while sharing (same session, same counters), so it hit the collapsing
path on every emission. A ten-minute freeze and ten minutes of healthy ticking
therefore rendered identically: one line, one timestamp.

The buffer was destroying the evidence it had been added to capture. No number of
screen-off reproductions could have settled the cause, which is why earlier
attempts at this symptom (sections 17 and 18) shipped mitigations - `WifiLock`,
the Doze exemption - without ever confirming which mechanism was at fault.

**Fix.** Buffer logic moved into `DiagLog` with an injectable clock so the rules
are testable. Collapsed runs now carry `first..last` stamp plus the count:

```
+120.000s..+600.000s host: LINKSERVER_ALIVE (tick) id=3 listening=true ... (x8)
```

A freeze becomes arithmetic - a 480s span holding 8 ticks of a 15s loop is 24
missing ticks. Collapsing is retained, so a 4s poll loop still cannot flood a
400-line buffer and evict the failure being investigated.

`Diagnostics` keeps its public API; every call site is unchanged.

**Gate.** `DiagLogTest`, 8 tests, including two dumps over the same 300s window
that must differ by count. Sabotage: discarding the span turned `Unit tests`
green to red.

**Still open.** The screen-off cause itself is *not* diagnosed. Required next:
reproduce on run #126 or later with the host display off, then `SHARE
DIAGNOSTICS` from the host and read, in order - `HOST_SCREEN_OFF`, then the
`LINKSERVER_ALIVE (tick)` spans across the dark period, then whether
`HOST_GROUP_ENDED (service stopping)` or a `noteHostingEnded` reason appears.
Tick count far below span/15s means the process was frozen; ticks continuing with
`group=true engine=true listening=true` while the client is dead means the data
plane or the radio, not the lifecycle. Do not ship a fix before this is read.
## 23. "Connection refused" was never proven to be a refusal (run #128, `de2b044`)

**Context.** Sections 20 and 21 both treated the client's `TCP connection
refused` as fact and worked backwards from it to the host's group and :4434
lifecycle. That premise was never verified.

**The defect.** `ClientViewModel.probeHost` used three catch blocks:

```kotlin
} catch (e: java.net.ConnectException) {
    ProbeOutcome.Failed("refused the connection - is SHARE on, on the host phone?")
} catch (e: java.net.SocketTimeoutException) {
    ProbeOutcome.Failed("did not answer within 3s - wrong network, or the host app is asleep")
```

On Android, `IoBridge` maps `EHOSTUNREACH` to `NoRouteToHostException` and
`EACCES` to `SocketException`, but **everything else, including both
`ECONNREFUSED` and `ENETUNREACH`, becomes `ConnectException`**. So a client with
no route to `192.168.49.1` was told, in confident prose, that the host had
stopped sharing. `e.message` - carrying the errno, the only thing that separates
them - was discarded on that line.

`ENETUNREACH` is not hypothetical here: it is what a process-wide
`bindProcessToNetwork` left pointing at the wrong network produces, which is an
open defect (`fix-process-bind-leak`).

The timeout branch was wrong in the other direction. A read timeout was reported
as "wrong network", but by then TCP had connected - so the network is fine and the
*host's* responder accepted and never wrote. The two messages sent the tester to
opposite phones.

**Fix.** New pure `LinkProbePolicy.classify(phase, throwable)` returning
`ProbeDiagnosis(kind, message, detail, tcpEstablished)`:

- classification is driven by **errno text**, then by the **phase** the probe was
  in (`CONNECTING` vs `READING_BANNER`), not by exception type alone
- `tcpEstablished` states plainly whether TCP came up, which is the bit that
  decides which phone to look at next
- the OS's errno string always reaches the diagnostics `detail`, so the report
  survives an unrecognised failure
- a **bare `ConnectException` is not called a refusal**. That assumption is the
  defect; with no errno there is no evidence, so it reports `OTHER` with the raw
  text

`ProbeOutcome.Failed` now carries `reason` (shown) and `detail` (recorded), so the
buffer gets `ENETUNREACH`, not a friendly sentence. Control flow is untouched:
every failure still yields `Failed` and a null host.

**Gate.** `LinkProbePolicyTest`, 12 tests, including two `ConnectException`s that
must not produce the same message and one `SocketTimeoutException` that must
classify differently by phase. Sabotage: restoring `e is ConnectException ->
REFUSED` turned `Unit tests` green to red.

**Consequence for the open investigation.** Every earlier conclusion that rested
on the word "refused" is now unproven, not wrong. The next client dump will say
which it was. Do not re-reason about the group lifecycle until it does.
---

## 24. The client screen outlived the link it described (run #130, `d94a74b`)

**Symptom, as reported.** The host taps STOP. The client's Wi-Fi drops. The app
goes on saying "Connected to PeerNet-4f93".

**Cause.** `ClientLinkManager.linkedHost` is the authority for "am I linked", and
`setLinked(null)` has four callers - `ClientViewModel.clearLink`,
`PeerNetVpnService` twice, and `VpnNotificationActionReceiver`. The three outside
the view model never touched the view model's own `connectedHost`, which is what
`HomeScreen` renders (`HomeScreen.kt:246`, `:285`). The view model never observed
the manager. So there was no path by which an external teardown could reach the
screen.

The watcher that was supposed to cover this compared `joinedAsClient` falling
edges (`ClientViewModel.kt:141-145`). That flag is documented twelve lines below
it (`:149-156`) as never becoming true when the user joins by typing the
passphrase in Android's Wi-Fi settings - which is this app's only documented
client flow. It starts false, stays false, and its falling edge never happens.
`clearLink("Host disconnected.")` was unreachable code in practice.

That left the liveness loop's `clearLink` (`:755`) as the only working clearing
path, and it has a silent exit at `:691-694`: `break` on
`!linkManager.isCurrent(gen)` with no clear. Generation bumps are routine with
four writers, so the loop could vanish leaving the screen populated.

**Fix.** `LinkPolicy.shouldClearStaleUi(hadManagerLink, hasManagerLink,
uiShowsLink)` plus a watcher on `linkManager.linkedHost`. Deliberately an edge,
not a level: `linkedHost` is null before a link exists too, so a level check
would clear the screen mid-connection and fight the view model while it sets the
link up. `clearLink` is split into `clearLink` (nulls the manager, then delegates)
and `clearLocalLinkState`, so the watcher can clear the screen without spending a
second generation on top of the one the external writer already spent.

**Gate.** `LinkPolicyStaleUiTest`, 6 tests, including an exhaustive check that
exactly one of the eight input combinations may clear the screen. Sabotage:
replacing the edge with the level check `!hasManagerLink && uiShowsLink` turned
`Unit tests` green to red.

**Not fixed by this.** The stale VPN notification of section 17 is a different
object. This only fixes the app's own screen.

---

## 25. SHARE destroyed the client session with no warning (run #132, `c11e9a1`)

**Symptom, as reported.** "Client cannot share while connected as client - tapping
SHARE silently disconnects from the host and reconnects to JioAirFiber and starts
sharing."

**What is the radio and what was the app.** A device gets one P2P group from
`WifiP2pManager`, and neither the SM-J400F nor the SM-M115F supports P2P
concurrency. Creating a group as host therefore destroys the group joined as a
client. This is not something the app can code around, and it means the
daisy-chained extender - phone B receiving from A while serving C - is not
implementable on this hardware. Do not plan features that assume it.

The app bug was narrower and real: `HostRuntime.startSharing` (`:300-331`) had no
guard of any kind. Nothing checked for an active client link, so Android tore the
client group down underneath the user with no warning, no teardown, and no
explanation for the internet stopping. The user read that as the app secretly
switching networks.

**Fix.** `RoleConflictPolicy.evaluateShareRequest(clientLinkActive, tunnelActive)`
returns `PROCEED` or `CONFIRM_REPLACING_CLIENT_LINK`. `HomeScreen` asks before
switching roles and, on confirmation, ends the client session deliberately -
`stopVpn()`, `disconnect()`, settle, then share - mirroring what the CONNECT
button already did in the opposite direction (`HomeScreen.kt:374-378`). Both the
link and `tunnelActive` are checked because they disagree while the VPN service is
tearing down; `tunnelActive` is now exposed on `ClientViewModel`.

**Gate.** `RoleConflictPolicyTest`, 5 tests, including an exhaustive check that
only a fully idle phone shares without asking. Sabotage: returning `PROCEED`
unconditionally - the pre-fix behaviour - turned `Unit tests` green to red.

**Still open, and unchanged by either fix.** The host stopping by itself after
5-10 minutes, and whether the client's working internet is the tunnel or its own
mobile data. Both need a `SHARE DIAGNOSTICS` dump from a run #132 build; neither
is diagnosable from CI. Read the host dump in the order given in section 22.