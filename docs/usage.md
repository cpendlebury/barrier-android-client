# Using the app

The screen is a small form. The app now connects to a real Barrier server: the handshake completes and the client reaches `CONNECTED`. Pointer movement, clipboard sync, and text injection are partially functional (press/move/release drags via continuing gestures; keystrokes via the Barrier IME when enabled). Typing individual keystrokes is now possible through the Barrier IME when enabled and selected; otherwise keystrokes fall back to the accessibility service's limited global-action path. See [Status and limitations](limitations.md) for details.

## Screen

| Control | Id | Behaviour |
|---------|----|-----------|
| Barrier Server Host | `serverHostEditText` | Free text. Hint `e.g. 192.168.1.50`. Not validated |
| Barrier Server Port | `serverPortEditText` | Number. If empty or not an integer, the code uses `24800` |
| Client Name | `clientNameEditText` | If empty after trim, the code uses `AndroidBarrierClient` |
| Connect | `connectButton` | Starts the foreground service, constructs a `BarrierClient`, attempts connection |
| Disconnect | `disconnectButton` | `stopService` on `BarrierForegroundService` |
| Accessibility service status | `accessibilityStatusTextView` | `enabled` or `disabled`, from secure settings |
| Open Accessibility Settings | `openAccessibilitySettingsButton` | Hidden only when `isEnabled()` is true. Opens the system accessibility screen |
| Error line | `errorTextView` | Red text for connection errors, injection failures, disabled-service warnings |
| Status line | `statusTextView` | `Barrier state: …` or `Barrier error: …` |
| Server fingerprint | `serverFingerprintTextView` | SSL fingerprint of the server observed at handshake, rendered in Barrier's display form (uppercase, colon-separated pairs); `unavailable` before a TLS handshake completes, cleared on disconnect |

The status line starts at `DISCONNECTED`. On a successful connection it transitions to `CONNECTED`. On failure it shows an error message.

## Connect

1. If the accessibility service is not enabled, a toast and error line warn the user, but the tap continues.
2. A `PendingConnection` is stored on the service companion, including the activity listener, clipboard handlers, and a screensaver callback.
3. `startService` is called inside `runCatching`.

`onStartCommand` reads the companion property, assigns the activity listener, copies clipboard handlers, sets screen dimensions from display metrics, and calls `startConnection`, which builds a `SocketBarrierTransport` and a `BarrierClient` on the service scope. The client's `connect()` is launched on the service scope.

The client waits for the server's `Hello`, responds with `HelloBack`, then handles `QINF` → `DINF` → `CIAK` → `DSOP` → `CONNECTED`.

## Disconnect

**Disconnect** stops the service. Leaving the activity no longer stops the service, so the connection survives backgrounding. The notification persists while connected. There is no separate "keep running in the background" control — the foreground service is always active while connected.

## Accessibility button

**Open Accessibility Settings** starts `Settings.ACTION_ACCESSIBILITY_SETTINGS`. The service is registered with meta-data, so it appears in the list on Android. On Android 13+, sideloaded apps need "Allow restricted settings" before the service can be enabled.

The status text is refreshed in `onCreate` and `onResume`.

## Clipboard and typing

**Clipboard-based typing path**: When the server pushes clipboard data (DCLP), the app stores it into the device clipboard via `ClipboardManager` rather than injecting it into the focused editable field, so the content is available for an explicit user-initiated paste. To type from the server, copy text on the server — it will be pushed to the phone and stored.

**Host-driven paste**: when the host sends Ctrl+V, the client routes the key event (keyId `0x76`, CONTROL modifier bit `0x0002`) to the IME paste action, which commits the stored clipboard content into the focused editable field via `InputConnection.commitText`. This is the explicit paste path: copy on the host → stored on the phone; press Ctrl+V on the host → delivered into a focused field.

**Host copy and cut**: host Ctrl+C (keyId `0x43`) copies the focused editable field's text into the Android clipboard via the accessibility service; host Ctrl+X (keyId `0x58`) cuts (copies then clears the field). A subsequent host Ctrl+V (paste) delivers the stored content into the focused field.

**Keystroke typing**: individual keystrokes are now possible when the Barrier keyboard (IME) is enabled in system settings and selected as the active input method. In that mode, Barrier key events are committed into the focused field via `InputConnection` (printable characters via `commitText`, special keys via `sendKeyEvent`), with modifier state from the Barrier modifier mask. If the IME is not active, key events fall back to the accessibility service's limited global-action path (Back/Home/Recents only). Keystroke-by-keystroke injection into arbitrary fields without the IME is not possible (no public key injection API).

## Mouse movement

Known issue: in the stock Android Files app, tapping a folder does not open it. Use another file manager such as Fossify File Manager (see [troubleshooting](troubleshooting.md#stock-files-app-does-not-open-folders)).

Mouse movement does not send any gesture to the phone. The injected position is mirrored onto a visible cursor overlay (a classic pointer drawn in a translucent window hosted in the accessibility service). The cursor follows movement, hides when a button is released, on disconnect, or on screensaver start, and injected input re-shows it. Clicks and scrolls land at the tracked position. Movement strokes were removed because they caused phantom keystrokes on on-screen keyboards and accidental scrolls.

## Mouse buttons

Host mouse buttons arrive as `MouseButtonDown` / `MouseButtonUp` events carrying the `button` field, numbered as Barrier numbers them: 1 left, 2 middle, 3 right, 4 and 5 the extra back and forward buttons. Right click (3) and the back button (4) fire the BACK global action (`injectBack`), so the host's Back navigates back; the release is a no-op and does not fire a click. The forward button (5) is routed to `injectForward`, which declines because no public forward navigation action exists. Left and middle buttons keep the tap behaviour (press/release = click at the tracked position).

## Mouse wheel scrolling

Host wheel events (`DMWM`) arrive as `BarrierInputEvent.MouseWheel` with signed `xDelta` / `yDelta`. Barrier sends +120 for wheel forward (away from the user, page scrolls up) and -120 for wheel back. The client maps a positive `yDelta` to a finger-down swipe, so the page scrolls up, and a negative `yDelta` to a finger-up swipe, so the page scrolls down. Horizontal scroll follows the sign of `xDelta`. Each event is a 200 px swipe over 100 ms at the tracked pointer position, clamped to stay on screen.

The scroll is a synthetic swipe because `GestureDescription` cannot carry axis or delta data, so the signed delta becomes the scroll direction and the per-event distance stays a flat 200 px. Scrolling through browsers, social apps, email, and chat is implemented on this path but not yet validated on device.

## Keyboard shortcuts

Host chords that map to phone system actions run without the Barrier keyboard being active: Meta+H or Meta+Enter (Home), Alt+Tab or Meta+Tab (recent apps), Meta+N (notification shade), Meta+S (quick settings), Meta+L (lock screen, Android 9+), and Meta+Ctrl+S or Print Screen (screenshot, Android 9+). Escape is Back as before.

Ctrl, Alt, and Meta combined with a letter or digit (for example Ctrl+L) are delivered to the focused text field as key events, so an app's own shortcut can handle them. This needs a focused text field. The Meta key alone (app drawer), Meta+A (assistant), and Meta+/ (keyboard shortcuts) are not mapped; Android has no public action for them.

## What a real session still needs

Even with the connection working, a fully usable session still needs: proper injection (real pointer events, not gestures), and on-device validation. See [limitations](limitations.md) and [configuration](configuration.md).