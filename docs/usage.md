# Using the app

The screen is a small form. The app now connects to a real Barrier server: the handshake completes and the client reaches `CONNECTED`. Pointer movement, clipboard sync, and text injection are partially functional. Typing individual keystrokes is not yet possible. See [Status and limitations](limitations.md) for details.

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

**Clipboard-based typing path**: When the server pushes clipboard data (DCLP), the app stores it into the device clipboard and injects it into the focused editable field via `ACTION_SET_TEXT`. To type from the server, copy text on the server — it will be pushed to the phone and injected. Keystroke-by-keystroke typing is not possible (no public key injection API).

## Mouse movement

Mouse movement does not send any gesture to the phone. The injected position is mirrored onto a visible cursor overlay (a classic pointer drawn in a translucent window hosted in the accessibility service). The cursor follows movement, hides when a button is released, on disconnect, or on screensaver start, and injected input re-shows it. Clicks and scrolls land at the tracked position. Movement strokes were removed because they caused phantom keystrokes on on-screen keyboards and accidental scrolls.

## What a real session still needs

Even with the connection working, a fully usable session still needs: proper injection (real pointer events, not gestures), and on-device validation. See [limitations](limitations.md) and [configuration](configuration.md).