# Using the app

The screen is a small form. It does not join a Barrier server. This page describes what the current build actually does, not the intended KVM session.

## Screen

| Control | Id | Behaviour |
|---------|----|-----------|
| Barrier Server Host | `serverHostEditText` | Free text. Hint `e.g. 192.168.1.50`. Not validated |
| Barrier Server Port | `serverPortEditText` | Number. If empty or not an integer, the code uses `24800` |
| Client Name | `clientNameEditText` | If empty after trim, the code uses `AndroidBarrierClient` |
| Connect | `connectButton` | Starts the foreground service. Does not open a socket. See below |
| Disconnect | `disconnectButton` | `stopService` on `BarrierForegroundService` |
| Accessibility service status | `accessibilityStatusTextView` | `enabled` or `disabled`, from secure settings |
| Open Accessibility Settings | `openAccessibilitySettingsButton` | Hidden only when `isEnabled()` is true. Opens the system accessibility screen |
| Mouse movement gestures | `mouseMovementCheckBox` | Toggles whether pure movement creates gestures. Disable to avoid phantom keystrokes on on-screen keyboards |
| Error line | `errorTextView` | Red text for the cases listed below |
| Status line | `statusTextView` | `Barrier state: …` or `Barrier error: …` |

The status line is set to `DISCONNECTED` when the activity is created. Connect does not change it, because no client is started and `CONNECTING` / `HANDSHAKE` are not reported to the listener anyway.

## Connect

1. If `BarrierAccessibilityService.isEnabled()` is false, the error line is set to `Accessibility service not enabled; input injection will not work.` and a toast says `Enable the accessibility service first.` The tap still continues.
2. A `PendingConnection` is stored on the service **companion**.
3. `startService` is called inside `runCatching`. A failure is ignored. There is no success message.

The service's `onStartCommand` now reads the companion property. It assigns the activity listener, copies clipboard handlers, sets screen dimensions from display metrics, builds a `BarrierClient`, and launches `connect()` on the service scope. A TCP connection is now attempted, but the socket implementation does not open a working Barrier socket (see [limitations](limitations.md)). The status line now receives `CONNECTED` on a successful handshake.

On Android 5.1 through 9, that `startForeground` / `NotificationChannel` path crashes instead of showing the notification. See [limitations](limitations.md).

## Disconnect

**Disconnect** stops the service. So does leaving the activity (`onDestroy` calls `stopService`). There is no separate "keep running in the background" control. The notification goes away when the service is destroyed.

If a client were running, service destruction would call `disconnect()`, which suppresses reconnect. That path is not reached from Connect today.

## Accessibility button

**Open Accessibility Settings** starts `Settings.ACTION_ACCESSIBILITY_SETTINGS`. BarrierAndroid is not registered with the meta-data Android requires for an accessibility service, so it will not appear in that list. Enabling "the service" is not possible with this build.

The status text is refreshed in `onCreate` and `onResume`.

## Clipboard and typing

Nothing in the running app reads or writes the clipboard, and nothing injects text or pointer events. Those methods exist on `BarrierAccessibilityService` and `MainActivity`, but they run only when a `BarrierClient` listener fires. Connect never creates that client.

**Clipboard-based typing path**: When the server pushes clipboard data (DCLP), the app stores it into the device clipboard and injects it into the focused editable field via `ACTION_SET_TEXT`. To type from the server, copy text on the server — it will be pushed to the phone and injected. Keystroke-by-keystroke typing is not possible (no public key injection API).

If that path is fixed later, the clipboard handler as written will:

- On a server grab (`CCLP`), read the first text item of the primary clip, or send empty bytes.
- On server clipboard data (`DCLP`), decode the payload as UTF-8, set the primary clip to a plain-text clip labeled `barrier`, then try `injectText`, which replaces a focused editable field rather than typing keystrokes.

## What a real session would still need

Even after the service reads the pending connection, a usable session still needs a real socket (plaintext and Barrier TLS), non-zero screen size in `DINF`, a properly registered accessibility service, and injection that matches Barrier events. None of that is in place. Details are in [limitations](limitations.md) and [configuration](configuration.md).
