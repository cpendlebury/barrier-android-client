# Status and limitations

This is an experimental codebase. Treat the unit tests as the supported surface.

The connection path is implemented end to end and is now expected to work: the client waits for the server's `Hello`, answers with `HelloBack`, completes the `QINF` → `DINF` → `CIAK` → `DSOP` → `CONNECTED` handshake, and opens a plain or TLS socket. A phone should now be able to join a Barrier server that speaks protocol v1.6 (Barrier 2.4.0 and newer). The handshake has been validated on device, and the TLS transport was rewritten afterwards; the visible cursor overlay was then visually confirmed on a real device over a live server connection, which exercises that rewritten path end to end.

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

However, the socket it uses (`SocketBarrierTransport`) now implements the transport: `connect` opens a plain `java.net.Socket` for plaintext or an `SSLSocket` built through an `SSLContext` for TLS, performs the handshake, and no longer double-connects. The TLS path now authenticates the server the way Barrier's own client does: by the
SHA-256 fingerprint of the server's leaf certificate, compared against a previously
accepted (pinned) fingerprint (Barrier: `ConnectionSecurityLevel.ENCRYPTED_AUTHENTICATED`).
Hostname verification is disabled, matching Barrier's client for self-signed LAN
certificates. Trust on first use: the first server fingerprint observed is accepted and
stored, then enforced on subsequent connects (Barrier's accept-and-store flow). Socket
failures are surfaced via `BarrierError.ConnectionFailed` (from `performConnect`) and
via `onConnectionLost` → `closeTransport`.

The status line receives `CONNECTING`, `HANDSHAKE`, and `CONNECTED` callbacks now that the listener is wired. `CONNECTING` and `HANDSHAKE` are not notified by `BarrierClient.connect()` (only `CONNECTED` is, via `handleSetOptions`), so the status jumps from `DISCONNECTED` to `CONNECTED` on a successful handshake.

Tapping **Disconnect** stops the service. Leaving the activity no longer stops the service (`onDestroy` does not call `stopService`), so the connection survives backgrounding. The service is stopped only by explicit Disconnect or force-stop.

If the client reaches `DISCONNECTED` (server close), the service's `clientListener` wrapper no longer calls `stopSelf()`. The reconnect scope survives. The service stays alive for reconnect attempts.

## Accessibility service

The service class exists and is now registered in the manifest with a meta-data element pointing at `res/xml/accessibility_service_config.xml`. That config enables `canRetrieveWindowContent` and `canPerformGestures`. Android should now list the service under Settings → Accessibility, and the in-app **Open Accessibility Settings** button opens the system screen.

`isEnabled()` checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. It returns true only after the user toggles the service on.

The injection methods are still not a working input path, but they are less wrong than before (the Phase 2/3 injection hardening applied):

- `injectPointer` now consults the motion action. `ACTION_DOWN` starts a continuing drag stroke via the `DragCoalescer` (a tap is a press and release at the same point). `ACTION_MOVE` appends a segment (moves update the target and jump ahead); `ACTION_UP` ends the drag via the closing segment. `ACTION_SCROLL` is a downward swipe of 200 px over 100 ms. `dispatchGesture` still cannot hold a real system button down across calls; the coalesced stroke chain approximates it. Scroll has no axis data (the caller passes a point, not deltas). Wheel events from the server (`MouseWheelPacket`) are sent as `ACTION_SCROLL` at `(0, 0)` by `MainActivity`, so scroll direction and amount are lost.

  The injected position is mirrored onto a visible cursor overlay: `showCursor` / `hideCursor` in the accessibility service add or remove a translucent window that draws a standard arrow, and `injectPointer` drives show / move / hide. The overlay is a render surface only and is not part of the gesture path. The cursor is hidden when a button is released, when the client disconnects, or when the screensaver starts; injected input re-shows it. The overlay window is anchored top-left (`Gravity.TOP or Gravity.START`) so its `x`/`y` offsets are absolute; the default centre gravity moved the window and hid the arrow. The arrow is a classic pointer shape with a white fill and a black outline, scaled by density, so it stays visible on both light and dark content. The cursor follows movement regardless of any movement toggle; movement gestures are never dispatched. The overlay was visually confirmed on a real device before the 2026-11-20 drag rework; since the drag rework the drag-driven path needs a fresh retest; the remaining validation is button hold/release, the disconnect/screensaver hide path, and cursor size and position.
- Pure movement sends no gesture. The movement checkbox was removed: movement strokes caused phantom keystrokes on on-screen keyboards and accidental scrolls, and clicks already use the tracked position, so the stroke added nothing.
- `injectKey` (accessibility path) compares the Barrier key id with `KeyEvent.KEYCODE_BACK`, `KEYCODE_HOME`, and `KeyEvent.KEYCODE_APP_SWITCH`. Barrier key ids are not Android key codes, so those branches do not fire for real server traffic; the accessibility path is therefore limited to global actions (Back/Home/Recents). Arbitrary keystrokes are now delivered through the Barrier input method (IME): `BarrierIME` commits printable characters via `InputConnection.commitText` and special keys via `sendKeyEvent`, with modifier state tracked from the Barrier modifier mask. The IME must be enabled by the user in system settings and selected as the active keyboard; when it is not active, key events fall back to the accessibility path above.
- **Stock Files app does not open folders from a tap.** In the stock Android Files app (`com.android.documentsui`), a tapped folder shows the press feedback and the logged accessibility `TYPE_VIEW_CLICKED` event on its card, but the folder does not open. The same tap opens folders in Fossify File Manager, so the input path is delivering clicks correctly and the behaviour is specific to Files. Experiments that changed press duration (30 ms, 100 ms), node-click on release, and a Files-only `ACTION_CLICK` did not fix it, and some caused regressions. Selection mode was ruled out. Use another file manager until this is resolved. See [troubleshooting](troubleshooting.md#stock-files-app-does-not-open-folders).
- `injectText` looks for a focused editable node and performs `ACTION_SET_TEXT`, which replaces the field contents. There is no public "insert text at cursor" action, so appending would require reading the current text, concatenating, and setting the whole string — this method does not do that. It is not called from the clipboard handler — received clipboard data is stored, not injected — and not from key events. With `canRetrieveWindowContent` now declared, `getRootInActiveWindow()` can return a tree. The node walk stops at depth 8 and does not recycle nodes.

## Network

The UI always asks for TLS (`useTls = true`). There is no toggle. `BarrierClient.connect`
receives the flag but `SocketBarrierTransport` now uses it: plain `java.net.Socket` for
`useTls == false`, an `SSLSocket` whose server is pinned by SHA-256 fingerprint (with
hostname verification disabled) for `useTls == true`. `connect()` no longer
double-connects.

The TLS path is now Barrier's TLS: it pins the server's self-signed certificate by its
SHA-256 fingerprint (trust on first use, then enforced), matching Barrier's
`ENCRYPTED_AUTHENTICATED` mode. Plaintext remains available via `useTls = false`.

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

The foreground service notification text reflects connection state: it reads "Waiting for Barrier server" while no client is connected, is updated to "Barrier: connected" on `CONNECTED`, and moves to reconnecting text after `DISCONNECTED`. It is updated via the client listener.

## Other leftovers

- `settings.gradle.kts` sets `rootProject.name = "BarrierAndroid"`. The application id and launcher name are unchanged by that string.
- Compose is still on the classpath and unused. The UI is XML views. Retrofit and Gson were removed.
- Version in the manifest is now `versionName` 0.1.1 / `versionCode` 3, wired to a release signing config (`barrier-release.jks`, alias `barrier`). That is the 0.1.1 release.
- File-transfer (`DFTR`) and drag (`DDRG`) packets are decoded and ignored.
