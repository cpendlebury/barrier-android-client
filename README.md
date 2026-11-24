# barrier-android-client

An Android **client** for the [Barrier](https://github.com/debauchee/barrier) keyboard and mouse sharing server. Your computer keeps its keyboard and mouse. The phone joins the computer's Barrier layout as a secondary screen and takes input from it.

- The computer runs the Barrier **server** and owns the real keyboard and mouse.
- The phone runs this **client**. Move the pointer onto the phone's edge and it takes over.
- The phone does not display the computer's screen. Video is out of scope.
- The clipboard is shared in both directions.

## Current status

Version 0.1.2 works with a real Barrier server on a personal phone. Confirmed on the phone:

- Connection and handshake with Barrier 2.4.0 (protocol 1.6), over TLS with the server's certificate fingerprint pinned.
- A visible cursor that follows the host pointer, and left click.
- Mouse wheel scrolling in both directions.
- Right click and the mouse back button go back.
- Host shortcuts: Home, recent apps, notification shade, quick settings, lock screen, and screenshot.
- Keystrokes into a focused text field through the Barrier keyboard, and the clipboard in both directions.

Known gaps, and what is not verified on other devices, are listed in [Status and limitations](docs/limitations.md). The main ones: no real system pointer (taps, drags, and scrolls are accessibility gestures), a fixed scroll distance per wheel notch, no forward button, and screen wake that depends on the device.

## Features

- **Protocol client**: Barrier protocol 1.6 handshake, keepalive, input events, clipboard, screensaver, options, and reconnect with backoff.
- **Transport**: plain socket, or TLS with the server pinned by the SHA-256 fingerprint of its self-signed certificate (Barrier's `ENCRYPTED_AUTHENTICATED`). The app shows the fingerprint it observed.
- **Pointer**: a cursor overlay follows the host pointer. Clicks and drags are accessibility gestures. Movement alone sends nothing.
- **Scrolling**: wheel events become swipes at the cursor position, in both directions.
- **Buttons**: right click and the back button go back. The forward button is declined.
- **Keyboard**: a Barrier input method (IME) delivers keystrokes into focused fields, with modifiers. Host shortcuts (Home, recent apps, notifications, quick settings, lock screen, screenshot) run as system actions without a focused field.
- **Clipboard**: server clipboard is stored on the phone; host Ctrl+C, Ctrl+X, and Ctrl+V copy, cut, and paste.
- **Foreground service**: keeps the session running while the app is in the background, with a notification, a CPU wake lock, and a Wi-Fi lock. Host input wakes the screen.
- **Debug logging**: an opt-in log file for reporting problems. Off by default.

## Requirements

- Android 5.1 or newer declared (`minSdk` 22). Tested on one personal phone; see [installation](docs/installation.md).
- A Barrier server, v2.4.0 or newer (protocol 1.6), reachable on the network.
- The accessibility service and the Barrier keyboard enabled by the user. On Android 13+, allow restricted settings for the app first.

## Quick start

### Install the 0.1.2 release APK

1. On your Android device, open the browser and download [`barrier-android-0.1.2.apk`](https://github.com/cpendlebury/barrier-android-client/releases/download/0.1.2/barrier-android-0.1.2.apk) from the [GitHub release](https://github.com/cpendlebury/barrier-android-client/releases/tag/0.1.2).
2. When the download completes, open the notification shade or Downloads and tap the APK, then confirm installation.
3. If Android prompts, allow installing unknown apps from the browser; on Android 11+ you may need to grant that permission.
4. Open **Settings → Accessibility → BarrierAndroid** and enable the service (grant restricted settings on Android 13+).
5. Enable the Barrier keyboard in **Settings → Keyboard**.
6. Launch BarrierAndroid, enter the server host and port, and tap **Connect**.
7. On the Barrier server, add a screen named `AndroidBarrierClient` (or your chosen name) and position it in the layout.

See [Usage](docs/usage.md) for the full walkthrough.

### Build and install a debug APK from source

1. Build the APK: `./gradlew :app:assembleDebug`
2. Install it on the Android device.
3. Complete steps 4 to 7 above.

## Documentation

| Document | Description |
|----------|-------------|
| [Installation](docs/installation.md) | Install the 0.1.2 release APK or build from source, prerequisites |
| [Usage](docs/usage.md) | Setup, connecting, pointer, scrolling, typing, shortcuts, clipboard |
| [Configuration](docs/configuration.md) | Server setup, fields, options, timers, TLS |
| [Troubleshooting](docs/troubleshooting.md) | Common problems and fixes, with the debug log |
| [Architecture](docs/architecture.md) | Codebase structure, components, data flow |
| [Protocol Reference](docs/protocol-reference.md) | The Barrier protocol framing this client implements |
| [Status and limitations](docs/limitations.md) | What is confirmed, what is not, and why |

## Known limitations

- **No real system pointer.** Taps, drags, and scrolls are injected as accessibility gestures. The framework cannot hold a button down across calls, so a drag is approximated.
- **Fixed scroll distance.** Each wheel notch is a 200 px swipe. Some apps ignore injected swipes.
- **No forward button.** Android has no public forward action.
- **Keyboard needs the Barrier keyboard.** Keystrokes are delivered through the Barrier input method. App shortcuts such as Ctrl+L need a focused text field.
- **Some chords are unmapped.** Meta alone (app drawer), Meta+A (assistant), and Meta+/ (keyboard shortcuts).
- **Screen wake is device-dependent.** It uses a deprecated wake-lock flag, and a secure lock screen still needs unlocking.
- **TLS is always requested.** There is no plaintext toggle in the app.
- **Stock Files** does not open folders from a tap. Use another file manager.
- **Options**: `MDLT` (relative mouse moves) is not read, `SSVR` is stored but does not lock the phone, and file transfer and drag packets are ignored.

## License

This project is licensed under the [GNU General Public License v2.0](LICENSE), matching the license of the [Barrier](https://github.com/debauchee/barrier) project.
