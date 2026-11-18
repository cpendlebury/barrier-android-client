# barrier-android-client

Experimental Android **client** for a [Barrier](https://github.com/debauchee/barrier) server.

The intended setup is the normal Barrier one, with the phone as a secondary screen:

- The computer runs the Barrier **server** and owns the real keyboard and mouse.
- The phone runs this **client**.
- The server sends input to the phone. The phone acts on that input.
- The phone does not display the computer's screen. Video is out of scope.
- Clipboard sharing is in scope.

This repository does **not** turn the phone into a keyboard or mouse for the computer. Control flows from the computer keyboard and mouse into the phone.

## Current status

The protocol layer is unit-tested (27 tests, all passing). The Android app now connects to a real Barrier server: the handshake completes and the client reaches `CONNECTED`. Pointer movement, clipboard sync, and text injection are partially functional. Typing individual keystrokes is not yet possible.

**Tested on device**: the connection handshake succeeds and mouse movement reaches the phone. The socket transport was rewritten after that handshake (plain socket / SSL socket with trust-all manager, no double-connect), so a retest against a live server is the current open item on the connection path. Injection uses discrete accessibility gestures (not a real system pointer). Injected pointer position is now mirrored onto a visible cursor overlay (standard arrow) hosted in the accessibility service; the overlay is a render surface, not a system pointer. The cursor overlay has since been visually confirmed on a real device. See [Status and limitations](docs/limitations.md) for details.

## Features

- **Protocol client** — full Barrier v1.6 handshake, keepalive, input events, clipboard, screensaver, options, reconnect
- **TCP/TLS transport** — plain socket or TLS (trust-all manager; not Barrier's fingerprint-verified TLS)
- **Pointer injection** — discrete gestures via `dispatchGesture` (tap and scroll only; movement sends no gesture)
- **Text injection** — `ACTION_SET_TEXT` on the focused editable field (clipboard push path)
- **Navigation keys** — Back, Home, Recents via framework global actions
- **Clipboard sync** — CCLP/DCLP round-trip via `ClipboardManager`
- **Foreground service** — keeps the connection alive while backgrounded (Android 8+ compliant, wake lock held)
- **Reconnect with backoff** — exponential backoff (1s → 30s) on server-initiated disconnect
- **Screensaver/options handling** — tracks server screensaver state and applies options (CLPS, HART, SSVR, MDLT)
- **UI wiring** — accessibility service status surfacing, error display, open-settings button, battery optimization prompt
- **Barrier logo** — actual Barrier project logo as app icon and in-app image

## Requirements

- Android 5.0+ (minSdk 22), tested on API 29+
- A Barrier server (v2.4.0, protocol v1.6) reachable on the network
- The accessibility service must be enabled by the user (Settings → Accessibility → "Allow restricted settings" on Android 13+)

## Quick start

1. Build the APK: `./gradlew :app:assembleDebug`
2. Install on the Android device.
3. Open **Settings → Accessibility → BarrierAndroid** and enable the service (grant restricted settings on Android 13+).
4. Launch BarrierAndroid, enter the server host/port, tap **Connect**.
5. On the Barrier server, add a screen named `AndroidBarrierClient` (or your chosen name) and position it in the grid.

See [Usage](docs/usage.md) for the full walkthrough.

## Documentation

| Document | Description |
|----------|-------------|
| [Installation](docs/installation.md) | Build from source, install APK, prerequisites |
| [Usage](docs/usage.md) | Connecting, enabling accessibility, injecting input |
| [Configuration](docs/configuration.md) | Server setup, options, clipboard, TLS |
| [Troubleshooting](docs/troubleshooting.md) | Common issues and fixes |
| [Architecture](docs/architecture.md) | Codebase structure, components, data flow |
| [Protocol Reference](docs/protocol-reference.md) | Barrier protocol v1.6 details |
| [Status and limitations](docs/limitations.md) | What works, what does not, and why |

## Known limitations

- **No real system cursor**: `dispatchGesture` creates discrete gestures, not a system pointer; the visible cursor is an overlay window rendering a standard arrow. There is no public API for moving a real system cursor.
- **No arbitrary key injection**: Barrier key IDs are not Android key codes. No public injection API exists on compileSdk 35.
- **TLS is not Barrier's TLS**: uses a trust-all manager. Barrier's self-signed cert fingerprint verification is not implemented.
- **No plaintext toggle**: TLS is always attempted. No UI switch for plaintext mode.
- **Options partially applied**: CLPS and HART are wired; SSVR is recorded; MDLT is stored but not read.
- **File transfer / drag**: `DFTR`/`DDRG` packets are decoded and ignored.
- **On-device validation**: the connection handshake has been tested on a real device and the visible cursor overlay has since been confirmed on a real device; the TLS transport was rewritten after the handshake test, so a retest against a live server is pending. Full input-injection behaviour (button hold/release, disconnect/screensaver hide, cursor size and position) still needs validation.

## License

This project is licensed under the [GNU General Public License v2.0](LICENSE), matching the license of the [Barrier](https://github.com/debauchee/barrier) project.