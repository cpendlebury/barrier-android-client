# Status and limitations

This is an experimental codebase. Treat the unit tests as the supported surface. Do not expect a phone to join a Barrier server.

## Intended product

A computer running an unmodified Barrier server (protocol 1.6, as spoken by Barrier 2.4.0) should be able to treat the phone as another screen: move the pointer onto it, type, and share the clipboard. The phone does not show the computer's display.

That product is not what the current app does.

## What you can rely on

`BarrierClient`, `BarrierPacket`, and `PacketCodec` are exercised by 27 local unit tests (`PacketCodecTest` 16, `BarrierClientTest` 11) using `FakeBarrierTransport`. Those tests do not open a socket and do not run on a device.

Covered there:

- Hello / HelloBack framing, including the 11-byte hello versus a longer hello-back
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

1. Shows a toast if the accessibility service is not enabled, then continues anyway.
2. Writes a `PendingConnection` onto `BarrierForegroundService`'s companion object.
3. Starts `BarrierForegroundService`.

`onStartCommand` reads a **different** `pendingConnection`, an instance field that nothing ever sets. The companion value is ignored. The service therefore never constructs a `BarrierClient` and never opens a socket. It only calls `startForeground` and shows the notification "Waiting for Barrier server".

The activity listener, clipboard callbacks, and accessibility instance are never attached to a running client. Input injection code is not on the path of a real session.

Tapping **Disconnect** stops the service. Leaving the activity also stops it (`onDestroy`).

The status line stays `Barrier state: DISCONNECTED` unless a listener callback runs. Connect does not notify `CONNECTING` or `HANDSHAKE` to the listener, so those states are not shown during a connection attempt even after the wiring bug is fixed.

## Accessibility service

The service class exists, but the manifest entry has no `android.accessibilityservice` meta-data and there is no service-config XML under `res/xml/`. Android will not list it under Settings → Accessibility, so the in-app **Open Accessibility Settings** button has nothing to enable.

`isEnabled()` only checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. With the current manifest that check stays false.

Even if the service were enabled, the injection methods are not a working input path:

- `injectPointer` ignores the motion action. Button down, button up, move, and scroll all build the same zero-duration stroke from a point to itself. `dispatchGesture` is likely to reject a 0 ms stroke. Wheel events are sent at `(0, 0)`.
- `injectKey` compares the Barrier key id with `KeyEvent.KEYCODE_BACK`, `KEYCODE_HOME`, and `KEYCODE_APP_SWITCH`. Barrier key ids are not Android key codes, so those branches do not fire for real server traffic. The `action` argument is ignored. There is no public API in this SDK for injecting arbitrary keys.
- `injectText` looks for a focused editable node and performs `ACTION_SET_TEXT`, which replaces the field contents. It is only called from the clipboard handler, not from key events. Window content cannot be read unless the service config sets `canRetrieveWindowContent`, which is not declared. The node walk stops at depth 8 and does not recycle nodes.

## Network

The UI always asks for TLS (`useTls = true`). There is no toggle.

`SocketBarrierTransport.createSocket()` looks up `SSLSocketFactory.createSSLSocket(String, int)`. That method is not part of the platform API, so the lookup fails. The catch block then calls `SSLSocketFactory.createSocket(host, port)`, which is still an SSL socket, and `connect()` calls `Socket.connect` on it again. Neither branch constructs a plain `java.net.Socket`. This is not Barrier's TLS (self-signed certificate and fingerprint check). A normal Barrier server, with or without encryption, will not complete a handshake through this helper.

Socket read errors are swallowed. They do not become `BarrierError.ConnectionFailed`. That error is only raised for `EBSY`, `EUNK`, and `EBAD`. A failed `connect()` in the service is not caught, so a later fix that actually calls it would still fail the coroutine quietly unless a handler is added.

`screenWidth` and `screenHeight` stay 0. Nothing in the app sets them, so a `DINF` reply would advertise a 0×0 screen.

## Options and reconnect do less than the names suggest

`applyOptions` stores:

- `CLPS` → `clipboardSharingEnabled` (default true)
- `HART` → `heartbeatSeconds` (default 0)
- `SSVR` → `screenSaverSyncEnabled` (default false)

Clipboard grab and clipboard data ignore `clipboardSharingEnabled`. The keepalive interval stays 3000 ms and ignores `heartbeatSeconds`. `MDLT` is defined and never read. Screensaver sync only stores a boolean; it does not lock the device or dismiss a dream.

`BarrierClient` can schedule reconnect after a drop from `CONNECTED`. The foreground service stops itself on every `DISCONNECTED` callback, and `onDestroy` cancels the service scope. If the service ever ran the client, that would cancel the reconnect job immediately. Reconnect is therefore not a feature of the app.

## Foreground service on real devices

`onStartCommand` always calls `NotificationChannel` (API 26) and `startForeground(int, Notification, int)` (API 29). `minSdk` is 22. On Android 5.1–9 this crashes as soon as the service starts.

The manifest type is `specialUse`, but there is no `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property. Android 14 and later can reject that foreground start.

The notification text is fixed: "Waiting for Barrier server". It is not updated when a connection would succeed.

## Other leftovers

- `settings.gradle.kts` still sets `rootProject.name = "FoodDiary"`. That is not the application id or the launcher name.
- Compose, Retrofit, and Gson are on the classpath. The UI is XML views. Nothing calls Retrofit or Gson.
- Version in the manifest is the template `versionName` 1.0 / `versionCode` 1. That is not a release.
- File-transfer (`DFTR`) and drag (`DDRG`) packets are decoded and ignored.
