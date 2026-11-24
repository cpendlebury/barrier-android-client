# Status and limitations

This page states what the app does today, what has been confirmed on a real phone, and what it cannot do. The release notes for each version are on the [GitHub releases page](https://github.com/cpendlebury/barrier-android-client/releases).

## Status

The app is a working Barrier client for Android. It connects to a Barrier server (protocol 1.6, Barrier 2.4.0 and newer) and reaches `CONNECTED`. The phone then acts as a secondary input target for the computer's keyboard and mouse, and the clipboard is shared.

The phone does not show the computer's screen. Video is out of scope.

The app is tested on one device so far: a Google Pixel 8a running GrapheneOS. Behaviour on other phones, other Android versions, and other ROMs is not verified. Screen wake in particular depends on the device.

## Confirmed on a real phone

Confirmed by the maintainer on a Google Pixel 8a running GrapheneOS, over a live Barrier server, in the 0.1.2 testing session unless noted:

- Connection and handshake to a server speaking protocol 1.6 (confirmed in earlier sessions; the TLS path was rewritten after that and exercised on the same connection). Reconnect with backoff is implemented but not separately confirmed on device.
- Mouse movement shows a visible cursor overlay that follows the host pointer.
- Left click; right click and the back side button go back.
- Mouse wheel scrolling in both directions.
- Host input wakes the screen (with the caveats under Screen wake below).
- Host shortcuts: Print Screen and Meta+Ctrl+S (screenshot), Meta+L (lock), Meta+H (Home), Alt+Tab and Meta+Tab (recent apps), Meta+S (quick settings), and Meta+N (notification shade).
- Clipboard: server clipboard is stored on the phone; host Ctrl+C, Ctrl+X, and Ctrl+V work in a focused field (confirmed in the 0.1.0 session).
- Keystrokes reach a focused text field through the Barrier keyboard (IME) when it is enabled and selected (confirmed in earlier sessions).

## Known limitations

### Pointer and gestures

- **There is no real system pointer.** Taps, drags, and scrolls are accessibility gestures (`dispatchGesture`). The framework cannot hold a real button down across calls, so a drag is approximated with a chain of short continuing strokes.
- **Scroll distance is fixed.** Each wheel event is a 200 px swipe over 100 ms, whatever the size of the host's wheel delta. Horizontal wheel events (`xDelta`) use the same swipe and are not validated on device.
- **Some apps ignore injected scroll gestures**, so they may not scroll at all.
- **Stock Files does not open folders from a tap.** In the stock Android Files app (`com.android.documentsui`), tapping a folder shows press feedback, but the folder does not open. Fossify File Manager works from the same tap. Experiments with press duration, node-click on release, and a Files-only `ACTION_CLICK` did not fix it. See [troubleshooting](troubleshooting.md#stock-files-app-does-not-open-folders).

### Buttons

- **Forward (button 5) is declined.** Android has no public forward-navigation action on this SDK. A real forward button would need the hidden input-injection route, which is not implemented.
- **Middle button** is a plain tap at the tracked position.

### Keyboard

- **Keystrokes need the Barrier keyboard.** Keystrokes reach a focused field only while the Barrier input method (IME) is enabled and selected. Without it, ordinary key presses are dropped. Escape still goes Back, and the host shortcuts below still work.
- **Host shortcuts** (`ime/HostShortcuts.kt`) run as Android global actions and do not need a focused field: Meta+H or Meta+Enter (Home), Alt+Tab or Meta+Tab (recent apps), Meta+N (notification shade), Meta+S (quick settings), Meta+L (lock screen, Android 9+), and Meta+Ctrl+S or Print Screen (screenshot, Android 9+).
- **Unmapped chords:** the Meta key alone (app drawer), Meta+A (assistant), and Meta+/ (keyboard shortcuts). Android has no public action for them.
- **App shortcuts need a focused field.** Ctrl, Alt, and Meta chords with a letter or digit (Ctrl+L, Alt+Left) are delivered as key events through the IME's `InputConnection`. With no focused text field there is no connection, so they are not delivered. Whether a given app honours synthetic key events with Ctrl or Alt state is app-specific and not verified.

### Clipboard and text

- **Server clipboard is stored, not pasted.** When the server pushes clipboard data, the phone stores it in the device clipboard. Nothing is typed into a field. A host Ctrl+V pastes it into the focused field.
- **Host Ctrl+C and Ctrl+X** read or clear the focused editable field through the accessibility service. They do nothing when no editable field has focus.
- `injectText` replaces a field's whole contents (`ACTION_SET_TEXT`). It is not used by the clipboard path. Appending would need the current text read and rewritten.

### Screen wake

- **The wake lock is deprecated.** Waking the screen uses `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`, deprecated since API 33. Android says the flag will require the `TURN_SCREEN_ON` permission in future releases. A later Android version may stop waking the screen this way.
- **Secure lock screens still need unlocking.** The wake lock turns the screen on but does not dismiss the keyguard.
- **Each input event holds the screen on for 5 seconds.** The lock is timed, so it releases on its own, and a new event re-acquires it.

### Accessibility service

- The accessibility service must be enabled by the user. On Android 13+, sideloaded apps need **Allow restricted settings** first (see [troubleshooting](troubleshooting.md)).
- Pointer, scroll, button, and global-action injection depend on it. Keystrokes do not (they use the IME).

### Options and protocol

- `CLPS` (clipboard sharing) is honoured. `HART` (heartbeat) is honoured. `SSVR` (screensaver sync) is stored but does not lock or unlock the phone. `MDLT` (relative mouse moves) is declared but not read.
- The screensaver callback (`CSEC`) updates the status line and hides the cursor. It does not lock the device.
- File transfer (`DFTR`) and drag information (`DDRG`) packets are decoded and ignored.

### Network and TLS

- The UI always requests TLS. There is no plaintext toggle in the app, although `SocketBarrierTransport` supports plaintext.
- TLS uses Barrier's `ENCRYPTED_AUTHENTICATED` model: the server's self-signed certificate is pinned by SHA-256 fingerprint, trust on first use. A regenerated server certificate is reported as a connection failure until the stored fingerprint is cleared.
- Hostname verification is disabled, matching Barrier's client for self-signed LAN certificates.

### Foreground service

- The service runs as a foreground service with a notification and a partial wake lock while a session is active. Disconnect stops it.
- Battery optimization exemption is requested on resume. The app does not require it to keep running, but it helps on aggressive battery managers.

## Tests

The JVM unit tests are the tested surface. There are 48 of them:

| Suite | Tests | Covers |
|-------|-------|--------|
| `PacketCodecTest` | 18 | Framing, packet encode and decode, including signed wheel and relative-move deltas |
| `BarrierClientTest` | 12 | Handshake, keepalive, input and clipboard events, options, reconnect, using `FakeBarrierTransport` |
| `BarrierServerTrustManagerTest` | 7 | Fingerprint pinning and trust on first use |
| `HostShortcutsTest` | 8 | Host chord matching for system actions |
| `BarrierKeyMapTest` | 3 | Printable key id to Android key code |

The unit tests do not open a socket, start the service, or enable an accessibility service. They do not prove the app works on a device. The device checks above do.

## Intended product

A computer running an unmodified Barrier server should be able to treat the phone as another screen: move the pointer onto it, scroll, type, use the phone's navigation keys and shortcuts, and share the clipboard. The phone is the client; it does not turn into a keyboard or mouse for the computer.
