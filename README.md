# PeerNet – WiFi Extender

Local network sharing bridge: an Android phone shares its active internet
connection with nearby devices over Wi-Fi Direct using an encrypted
QUIC/UDP tunnel (PNTP). No root, no manual proxy. Real-time apps
(Discord, WhatsApp, Google Meet, Zoom) work because UDP is relayed
natively, not through an HTTP/SOCKS proxy.

- Package: `com.peernet.wifiextender`
- Min SDK 26 / Target SDK 36
- Host + Client modes in one APK

## Building

The APK is built by GitHub Actions on every push/PR to `main`
(`.github/workflows/android.yml`). Download the artifact
`peernet-debug-apk` from the workflow run page.

Local builds are intentionally not used for this project.
