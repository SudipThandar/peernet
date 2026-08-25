# PeerNet – WiFi Extender

One Android phone shares its internet with another over **Wi-Fi Direct**, through
an encrypted **QUIC/UDP** tunnel. No root, no manual proxy settings, no tethering
plan.

The point of the design is that real-time apps keep working. Voice and video
calls (WhatsApp, Discord, Meet, Zoom) break on ordinary sharing apps because
those relay traffic through an HTTP or SOCKS proxy, which cannot carry UDP.
PeerNet relays UDP natively and terminates TCP in userspace, so the client phone
behaves as if it had its own connection.

- Package `com.peernet.wifiextender` · min SDK 26 · target SDK 36
- Host and client are the **same APK**, decided at runtime by which button you tap

---

## How it works

```
   HOST phone (has internet)                    CLIENT phone (no internet)
  ┌───────────────────────────┐                ┌───────────────────────────┐
  │ mobile data / Wi-Fi       │                │  apps                     │
  │        ▲                  │                │        │                  │
  │        │ userspace NAT    │                │        ▼                  │
  │  ┌─────┴──────┐           │                │  ┌───────────┐            │
  │  │ UDP relay  │           │                │  │ VpnService│  TUN, /0   │
  │  │ TCP (smol- │           │                │  │  tun0     │  route     │
  │  │ tcp) relay │           │                │  └─────┬─────┘            │
  │  └─────┬──────┘           │                │        │ raw IP packets   │
  │   ┌────┴─────┐            │                │   ┌────┴─────┐            │
  │   │ QUIC srv │ :4433 ─────┼── Wi-Fi Direct ┼── │ QUIC cli │            │
  │   └──────────┘            │   (group)      │   └──────────┘            │
  └───────────────────────────┘                └───────────────────────────┘
```

1. **Host** taps SHARE. It creates a Wi-Fi Direct group and starts a QUIC server
   on port `4433`, plus a small plaintext banner server on `4434` used for
   discovery (`PN-LINK-2 <hostId> <fingerprint> <tunnelPort>`).
2. **Client** joins the `DIRECT-…` network from Android's Wi-Fi settings, typing
   the password once. Android remembers it; later sessions link automatically.
3. **Client** opens the app, which finds the host, grants VPN consent once, and
   installs a TUN interface with a default route.
4. Every IP packet from the client is read off the TUN, framed, and sent over
   QUIC. The host performs NAT and forwards it to the real internet. Replies
   travel back the same way.

### Why these choices

| Decision | Reason |
| --- | --- |
| **Wi-Fi Direct** | Works without an access point, without the two phones sharing a network, and without the hotspot APIs that most OEMs restrict or that disable mobile data when used. |
| **QUIC (quinn) over UDP** | One encrypted, multiplexed, congestion-controlled connection with a stream per TCP flow. TLS 1.3 is built in, so there is no separate handshake to design or get wrong. Runs entirely in userspace - no kernel modules, no root. |
| **VpnService TUN on the client** | The only way to capture *all* traffic from *all* apps without root. Alternatives (proxy settings, per-app config) miss UDP and need manual setup in every app. |
| **Userspace NAT on the host** | The host cannot use kernel NAT without root, so the relay maintains its own translation tables. |
| **smoltcp for TCP termination** | The host has no raw-socket privilege, so client TCP flows are terminated in userspace and re-originated as ordinary Android sockets. |
| **Native UDP relay** | This is the whole reason the project exists: WebRTC, QUIC and DNS need UDP end-to-end. A proxy-based extender cannot carry them. |
| **Rust core, thin Kotlin shell** | The packet path must be allocation-light and predictable; the Android layer only handles lifecycle, permissions and UI. |
| **One screen, two buttons** | No host lists, no settings, no tuning. Everything that can be detected is detected. |

### Known constraint

Wi-Fi Direct puts the client's Wi-Fi radio into a group that has **no internet of
its own**, so sockets must be pinned to the right network or Android routes them
to cellular where the host is unreachable. Both phones hold a
`WifiManager.WifiLock` to stop the radio power-saving mid-session, and the app
asks once for a battery-optimization exemption so the system does not suspend it
with the screen off. It never takes a `PowerManager` wake lock and never keeps
the screen on.

---

## Layout

```
app/                     Android app (Kotlin, Compose, Hilt)
  wifi/                  Wi-Fi Direct group control, link banner server
  client/                link selection + lifecycle policy
  host/                  hosting runtime, session policy
  service/               VpnService (client) and foreground service (host)
  power/                 Doze exemption prompt
  diag/                  on-device diagnostics ring buffer
core/                    Rust workspace (edition 2021)
  peernet-proto/         wire format, relay headers, shared constants
  peernet-core/          TLS identity, certificate generation
  peernet-host/          QUIC server, userspace NAT, relays
  peernet-client/        QUIC client
  peernet-tcp/           smoltcp TCP terminator
  peernet-ffi/           JNI bridge, TUN packet pump, flow table
docs/HANDOFF.md          engineering log: every defect, cause and fix
windows/                 experimental Windows client
```

## Tech stack

**Android** — Kotlin, Jetpack Compose (Material 3), Hilt, coroutines/Flow,
`VpnService`, `WifiP2pManager`, NSD, Timber. Java 17, `compileSdk` 36.

**Native** — Rust 2021 built with `cargo-ndk` for `arm64-v8a`, `armeabi-v7a` and
`x86_64`; `quinn` (QUIC), `rustls` + `ring` (TLS 1.3), `rcgen` (self-signed
identity), `tokio`, `smoltcp`, `jni`. Release profile is size-optimised
(`opt-level = "z"`, LTO).

## Building

**There are no local builds in this project.** Everything is compiled and tested
by GitHub Actions (`.github/workflows/android.yml`) on every push to `main`:

| Job | What it gates |
| --- | --- |
| `Build APK` | `cargo test --release`, then `cargo ndk` and the APK |
| `Unit tests` | JVM policy tests and `lintDebug` |
| `On-device tests (API 29)` | instrumented tests on an emulator |
| `On-device tests (API 34)` | instrumented tests on an emulator |

Download the `peernet-debug-apk` artifact from the run page.

Because the app has no debug UI and the target users have no `adb`, every failure
must name itself on the single app screen; **SHARE DIAGNOSTICS** exports a full
snapshot to any share target.

### Testing rules that matter here

- **No Robolectric.** JVM tests cannot touch Android APIs, so every real decision
  is extracted into a pure policy object (`LinkPolicy`, `HostSessionPolicy`,
  `TunnelSupervisorPolicy`, `DozeExemptionPolicy`, `classifyGroup`) and tested
  there. The Android classes keep only the plumbing.
- **A gate that has never been seen to fail is not evidence.** Every new test is
  validated by reintroducing the bug on a throwaway branch and confirming the
  test goes red, before the branch is deleted.

## Status

The tunnel works end to end: TCP browsing, DNS and UDP are relayed, hosting and
auto-reconnect are stable. `docs/HANDOFF.md` is the authoritative log of what is
proven, what was fixed and why - including the defects that were subtle enough to
be worth reading before changing anything.

No release build is published yet: ads and a premium tier (monthly or one-time
lifetime, lifting a session limit) are planned first, and release signing is not
configured.

## License

MIT (Rust workspace declares `license = "MIT"`).
