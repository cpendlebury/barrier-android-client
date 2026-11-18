# Status and limitations

This is an experimental codebase. Treat the unit tests as the supported surface. Do not expect a phone to join a Barrier server.

## Intended product

A computer running an unmodified Barrier server (protocol 1.6, as spoken by Barrier 2.4.0) should be able to treat the phone as another screen: move the pointer onto it, type, and share the clipboard. The phone does not show the computer's display.

That product is not what the current app does.

## What you can rely on

`BarrierClient`, `BarrierPacket`, and `PacketCodec` are exercised by 27 local unit tests (`PacketCodecTest` 16, `BarrierClientTest` 11) using `FakeBarrierTransport`. Those tests do not open a socket and do not run on a device.

Covered there:

- Hello / HelloBack framing, including the 11-byte hello versus a longer hello-back (client waits for server's Hello, does not send Hello first)
- Query-info reply, info-ack, and transition to `CONNECTED` after `DSOP`
- Key, pointer, wheel, and button events delivered to a listener, each answered with `CNOP`
- Keepalive reply
- Clipboard grab answered with a `DCLP` (empty unless a provider is set)
- Incoming `DCLP` retained and passed to `onClipboardData`
- Screensaver flag and `onScreenSaverChanged`
- A few option ids stored from `DSOP`, and cleared on `CROP`
- Incompatible hello closes the client
- A server `CBYE` while connected schedules a reconnect on the client; `disconnect()` does not

## What the installed app does

Tapping **Connect**:

1. Checks whether the accessibility service is enabled. If not, shows a toast and an error line, then continues.
2. Writes a `PendingConnection` onto `BarrierForegroundService`'s companion object, including the activity listener, clipboard handlers, and a screensaver callback.
3. Starts `BarrierForegroundService`.

`onStartCommand` now reads the companion property (previously it read an instance field that was never set, so nothing happened). It assigns the activity listener to `connectionListener`, copies the clipboard lambdas, sets screen dimensions from display metrics, and calls `startConnection`, which builds a `SocketBarrierTransport` and a `BarrierClient` on the service scope. The client's `connect()` is launched on the service scope.

So a `BarrierClient` is now constructed and its handshake coroutine runs. The client no longer sends `Hello` first; it waits for the server's `Hello` and responds with `HelloBack`. This matches Barrier's protocol. Previously the client sent `Hello` first, causing the server to reply with `EBAD` (protocol error).

However, the socket it uses (`SocketBarrierTransport`) does not implement a working Barrier connection — see [Network](#network). The client will attempt `transport.connect`, which now opens a plain or TLS socket. The TLS path uses a trust-all manager and may or may not handshake with a real Barrier server. Socket failures are surfaced via `BarrierError.ConnectionFailed` (from `performConnect`) and via `onConnectionLost` → `closeTransport`.

The status line receives `CONNECTING`, `HANDSHAKE`, and `CONNECTED` callbacks now that the listener is wired. `CONNECTING` and `HANDSHAKE` are not notified by `BarrierClient.connect()` (only `CONNECTED` is, via `handleSetOptions`), so the status jumps from `DISCONNECTED` to `CONNECTED` on a successful handshake.

Tapping **Disconnect** stops the service. Leaving the activity no longer stops the service (`onDestroy` does not call `stopService`), so the connection survives backgrounding. The service is stopped only by explicit Disconnect or force-stop.

If the client reaches `DISCONNECTED` (server close), the service's `clientListener` wrapper no longer calls `stopSelf()`. The reconnect scope survives. The service stays alive for reconnect attempts.

## Accessibility service

The service class exists and is now registered in the manifest with a meta-data element pointing at `res/xml/accessibility_service_config.xml`. That config enables `canRetrieveWindowContent` and `canPerformGestures`. Android should now list the service under Settings → Accessibility, and the in-app **Open Accessibility Settings** button opens the system screen.

`isEnabled()` checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. It returns true only after the user toggles the service on.

The injection methods are still not a working input path, but they are less wrong than before (Phase C improvements applied):

- `injectPointer` now consults the motion action. `ACTION_DOWN` is a tap at (x, y). `ACTION_UP` is a tap at the last position. `ACTION_MOVE` is a short stroke (20 ms) from the last position to (x, y), throttled to 8 px minimum movement to reduce phantom interactions. `ACTION_SCROLL` is a downward swipe of 200 px over 100 ms. `dispatchGesture` still creates discrete, fire-and-forget gestures; it cannot hold a button down or track a continuous drag the way a real mouse does. Scroll has no axis data (the caller passes a point, not deltas). Wheel events from the server (`MouseWheelPacket`) are sent as `ACTION_SCROLL` at `(0, 0)` by `MainActivity`, so scroll direction and amount are lost.

  The injected position is mirrored onto a visible cursor overlay: `showCursor` / `hideCursor` in the accessibility service add or remove a translucent window that draws a standard arrow, and `injectPointer` drives show / move / hide. The overlay is a render surface only and is not part of the gesture path. The cursor is hidden when a button is released, when the client disconnects, or when the screensaver starts; injected input re-shows it. The arrow is drawn in a high-contrast white so it stays visible over the dimmed display.
- A "Inject mouse movement gestures" checkbox in the UI toggles whether pure movement creates gestures. When disabled, only button presses and scrolls inject. This eliminates phantom keystrokes on on-screen keyboards caused by movement strokes passing over key areas. The toggle is applied when the service connects.
- `injectKey` compares the Barrier key id with `KeyEvent.KEYCODE_BACK`, `KEYCODE_HOME`, and `KeyEvent.KEYCODE_APP_SWITCH`. Barrier key ids are not Android key codes, so those branches do not fire for real server traffic. The `action` argument is ignored: down, repeat, and up would fire the same global action if a code ever matched. There is no public discrete-key injection API on this compileSdk. Arbitrary key injection would require reflection on the hidden `InputManager` / `IInputManager` binder, which is not implemented.
- `injectText` looks for a focused editable node and performs `ACTION_SET_TEXT`, which replaces the field contents. There is no public "insert text at cursor" action, so appending would require reading the current text, concatenating, and setting the whole string — this method does not do that. It is only called from the clipboard handler, not from key events. With `canRetrieveWindowContent` now declared, `getRootInActiveWindow()` can return a tree. The node walk stops at depth 8 and does not recycle nodes.

## Network

The UI always asks for TLS (`useTls = true`). There is no toggle. `BarrierClient.connect` receives the flag but `SocketBarrierTransport` now uses it: plain `java.net.Socket` for `useTls == false`, an `SSLSocket` with a trust-all `TrustManager` and hostname verification disabled for `useTls == true`. `connect()` no longer double-connects.

The TLS path is still not Barrier's TLS. Barrier uses a self-signed certificate with fingerprint verification. This client trusts every certificate, so it is not secure against a MITM on an untrusted network. A future version should verify the server fingerprint the way Barrier's GUI does.

Socket read errors now invoke `onConnectionLost`, which calls `BarrierClient.closeTransport()`. That surfaces as a `DISCONNECTED` state and schedules reconnect (if the client was connected). `BarrierClient.connect` and `performConnect` catch transport failures and call `notifyError(BarrierError.ConnectionFailed(...))`, so a failed `connect()` is no longer silent. The foreground service wraps `connect()` in `runCatching`.

`screenWidth` and `screenHeight` are set from `resources.displayMetrics` in the service before connect, so `DINF` advertises the real device resolution.

## Options and reconnect

`applyOptions` now stores and applies:

- `CLPS` → `clipboardSharingEnabled` (default true). `handleClipboardGrab` returns empty data when disabled; `handleClipboardData` skips storing.
- `HART` → `heartbeatSeconds` and `keepAliveIntervalMillis` (if > 0). The keepalive timer now uses the requested interval.
- `SSVR` → `screenSaverSyncEnabled` (default false). Still only stored; screensaver sync does not lock the device.
- `MDLT` → declared but still not read.

`BarrierClient` can schedule reconnect after a drop from `CONNECTED`. The foreground service's `clientListener` no longer calls `stopSelf()` on `DISCONNECTED`, so the reconnect scope survives. The service is still stopped by the activity's Disconnect button or `onDestroy`.

## Foreground service on real devices

`onStartCommand` now guards `NotificationChannel` creation (API 26) and the three-argument `startForeground` (API 29) with `SDK_INT` checks, so the service no longer crashes on Android 5.1–9. The manifest declares `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` for Android 14+.

A partial wake lock and a WiFi lock are now acquired in `onStartCommand` and released in `onDestroy`, keeping the CPU and WiFi awake for keepalive and socket reads while the screen is off. The app requests battery optimization exemption on resume. A `ConnectivityManager` callback detects network changes and closes the transport so the reconnect loop can retry. Settings are persisted to SharedPreferences on every text change and on pause, using synchronous `commit()`, so they survive process death. The activity re-attaches to a running service on recreation. The service attempts to reconnect from saved settings on restart.

The notification text is still fixed: "Waiting for Barrier server". It is not updated when a connection would succeed.

## Other leftovers

- `settings.gradle.kts` sets `rootProject.name = "BarrierAndroid"`. The application id and launcher name are unchanged by that string.
- Compose is still on the classpath and unused. The UI is XML views. Retrofit and Gson were removed.
- Version in the manifest is the template `versionName` 1.0 / `versionCode` 1. That is not a release.
- File-transfer (`DFTR`) and drag (`DDRG`) packets are decoded and ignored.
