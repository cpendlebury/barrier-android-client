# BarrierAndroid

**Android phone as an external keyboard/mouse for a computer running the [Barrier](https://github.com/debauchee/barrier) server.**

BarrierAndroid is a native Android client for the Barrier KVM protocol. It receives keyboard, mouse, and clipboard events from a Barrier server and injects them into the Android device via an Accessibility Service. No video is displayed — the phone acts purely as an input target.

## Features

- **Pointer injection** — absolute/relative mouse motion, button down/up, scroll wheel via `AccessibilityService.dispatchGesture`
- **Text injection** — typed text delivered into the focused editable field via `AccessibilityNodeInfo.ACTION_SET_TEXT`
- **Navigation keys** — Back, Home, Recents via framework global actions
- **Clipboard sync** — server-to-client clipboard data stored into the device clipboard and injected into the focused field; client-to-server clipboard provided on server grab
- **Foreground service** — keeps the connection and injection alive while the app is backgrounded (Android 8+ compliant)
- **Reconnect with backoff** — exponential backoff (1s → 30s) on server-initiated disconnect
- **Screensaver/options handling** — reacts to server screensaver state and option updates (clipboard sharing, heartbeat rate, etc.)
- **TLS support** — TLS expected with plaintext fallback

## Requirements

- Android 5.0+ (minSdk 22)
- A Barrier server (v2.4.0, protocol v1.6) reachable on the network
- The accessibility service must be enabled by the user (Settings → Accessibility)

## Quick start

1. Build the APK (see [Installation](docs/installation.md)) or download a release.
2. Install on the Android device.
3. Open **Settings → Accessibility → BarrierAndroid** and enable the service.
4. Launch BarrierAndroid, enter the server host/port, tap **Connect**.
5. On the Barrier server, add this device as a client screen.

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

## Status

Phase 1 (protocol core) and Phase 2 (injection + clipboard + foreground service) are complete. Phase 3 (hardening) is in progress: reconnect/backoff, screensaver/options handling, and UI wiring are done; arbitrary-keycode injection remains an open limitation (no public API in compileSdk 35).

## License

See [LICENSE](LICENSE) (if present). BarrierAndroid is an independent client implementation of the Barrier protocol.