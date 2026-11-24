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

The protocol layer is unit-tested (27 tests, all passing). The Android app now connects to a real Barrier server: the handshake completes and the client reaches `CONNECTED`. Pointer movement, clipboard sync, and text injection are partially functional; the mouse-movement and keyboard-over-IME paths are now mostly usable on device (movement via the visible cursor overlay plus click/drag gestures; keystrokes via the Barrier IME when enabled, else the accessibility global-action fallback). Typing individual keystrokes is now possible when the Barrier keyboard (IME) is enabled and selected; otherwise keystrokes fall back to the accessibility service's limited global-action path.

**Tested on device**: the connection handshake succeeds and mouse movement reaches the phone; the visible cursor overlay was visually confirmed on a real device over a live server connection (red-fill diagnostic → gravity `TOP or START` → white arrow; hosting settled on the service context). The socket transport was rewritten after that handshake (plain socket / SSL socket with SHA-256 fingerprint pinning, no double-connect); the TLS path was then exercised end to end over that connection. Injection uses discrete accessibility gestures (not a real system pointer), including press/move/release drags via continuing strokes; the overlay is a render surface, not a system pointer. Full input-injection behaviour (button hold/release, disconnect/screensaver hide, cursor size and position) still needs validation. As of the latest device session, mouse movement and keyboard delivery over the Barrier IME are in a mostly usable state; see [Status and limitations](docs/limitations.md) for details.

## Features

- **Protocol client** — full Barrier v1.6 handshake, keepalive, input events, clipboard, screensaver, options, reconnect
- **TCP/TLS transport** — plain socket or TLS (server authenticated by SHA-256 fingerprint, Barrier's `ENCRYPTED_AUTHENTICATED`); the observed server fingerprint is displayed in the app
- **Pointer injection** — discrete gestures via `dispatchGesture` (tap, scroll, and press/move/release drags via continuing strokes; movement sends no gesture); visible cursor overlay mirrors injected position. Host wheel events become signed scroll gestures (positive `yDelta` scrolls down, negative scrolls up; flat 200 px per event). Host Back/Forward mouse buttons route to the BACK global action (Back) and are declined (Forward); unmapped buttons keep the tap behaviour.
- **Text injection** — clipboard push stores into the device clipboard; delivery into a focused field happens via explicit paste (`ACTION_SET_TEXT` retained as the field-level primitive)
- **Navigation keys** — Back, Home, Recents via framework global actions
- **Clipboard sync** — CCLP/DCLP round-trip via `ClipboardManager`
- **Foreground service** — keeps the connection alive while backgrounded (Android 8+ compliant, wake lock held)
- **Reconnect with backoff** — exponential backoff (1s → 30s) on server-initiated disconnect
- **Screensaver/options handling** — tracks server screensaver state and applies options (CLPS, HART applied; SSVR recorded; MDLT not read)
- **UI wiring** — accessibility service status surfacing, error display, open-settings button, battery optimization prompt
- **Barrier logo** — actual Barrier project logo as app icon and in-app image

## Requirements

- Android 5.0+ (minSdk 22), tested on API 29+
- A Barrier server (v2.4.0, protocol v1.6) reachable on the network
- The accessibility service must be enabled by the user (Settings → Accessibility → "Allow restricted settings" on Android 13+)

## Quick start

### Install the 0.1.1 release APK

1. On your Android device, open the browser and download [`barrier-android-0.1.1.apk`](https://github.com/cpendlebury/barrier-android-client/releases/download/0.1.1/barrier-android-0.1.1.apk) from the [GitHub release](https://github.com/cpendlebury/barrier-android-client/releases/tag/0.1.1).
2. When the download completes, open the notification shade or Downloads and tap the APK, then confirm installation.
3. If Android prompts, allow installing unknown apps from the browser; on Android 11+ you may need to grant that permission.
4. Open **Settings → Accessibility → BarrierAndroid** and enable the service (grant restricted settings on Android 13+).
5. Launch BarrierAndroid, enter the server host/port, tap **Connect**.
6. On the Barrier server, add a screen named `AndroidBarrierClient` (or your chosen name) and position it in the grid.

See [Usage](docs/usage.md) for the full walkthrough.

### Build and install a debug APK from source

1. Build the APK: `./gradlew :app:assembleDebug`
2. Install on the Android device.
3. Open **Settings → Accessibility → BarrierAndroid** and enable the service (grant restricted settings on Android 13+).
4. Launch BarrierAndroid, enter the server host/port, tap **Connect**.
5. On the Barrier server, add a screen named `AndroidBarrierClient` (or your chosen name) and position it in the grid.

See [Usage](docs/usage.md) for the full walkthrough.

## Documentation

| Document | Description |
|----------|-------------|
| [Installation](docs/installation.md) | Install the 0.1.1 release APK or build from source, prerequisites |
| [Usage](docs/usage.md) | Connecting, enabling accessibility, injecting input |
| [Configuration](docs/configuration.md) | Server setup, options, clipboard, TLS |
| [Troubleshooting](docs/troubleshooting.md) | Common issues and fixes |
| [Architecture](docs/architecture.md) | Codebase structure, components, data flow |
| [Protocol Reference](docs/protocol-reference.md) | Barrier protocol v1.6 details |
| [Status and limitations](docs/limitations.md) | What works, what does not, and why |

## Known limitations

- **No real system cursor**: `dispatchGesture` creates discrete gestures, not a system pointer; the visible cursor is an overlay window rendering a standard arrow. There is no public API for moving a real system cursor.
- **No arbitrary key injection via the accessibility path**: Barrier key IDs are not Android key codes and no public injection API exists on compileSdk 35; server keystrokes are delivered through the Barrier IME (`InputConnection.commitText` / `sendKey`).
- **TLS is Barrier's TLS**: the server is authenticated by the SHA-256 fingerprint of its self-signed certificate, with trust on first use (Barrier's `ENCRYPTED_AUTHENTICATED`).
- **No plaintext toggle**: TLS is always attempted. No UI switch for plaintext mode.
- **Options partially applied**: CLPS and HART are wired; SSVR is recorded; MDLT is declared but not read.
- **File transfer / drag**: `DFTR`/`DDRG` packets are decoded and ignored.
- **On-device validation**: the connection handshake, visible cursor overlay, and (as of the latest device session) mouse movement and keyboard-over-IME have been tested on a real device against a live Barrier server; the TLS transport rewrite predates that validation. Since the 2026-11-20 drag rework, the drag-driven overlay path needs a fresh retest; full input-injection behaviour (button hold/release, disconnect/screensaver hide, cursor size and position) still needs validation.
- **Scroll fidelity**: host wheel scrolling is injected as a synthetic accessibility scroll gesture — direction from the signed wheel delta, a flat 200 px swipe per event — because `GestureDescription` cannot carry axis or delta data; apps that ignore injected scroll gestures may not scroll. Real-input injection would lift this. Scroll behaviour itself is implemented (see [Usage](docs/usage.md)) and needs on-device validation.

## License

This project is licensed under the [GNU General Public License v2.0](LICENSE), matching the license of the [Barrier](https://github.com/debauchee/barrier) project.