# Architecture

Package `com.crrpendlebury.barrierandroid`. UI and service code sit in that package. Protocol types sit in `protocol`. Injection helpers sit in `accessibility`.

The protocol layer can run without Android, apart from `SocketBarrierTransport`. The unit tests use `FakeBarrierTransport` and never touch the service or the activity.

## Layout

```text
app/src/main/java/com/crrpendlebury/barrierandroid/
├── MainActivity.kt                  # XML form, clipboard helpers, listener
├── BarrierForegroundService.kt      # Foreground notification; does not start the client
├── accessibility/
│   └── BarrierAccessibilityService.kt
└── protocol/
    ├── BarrierClient.kt             # Handshake, keepalive, reconnect, clipboard, options
    ├── BarrierPacket.kt             # Encode/decode of payload bodies
    ├── PacketCodec.kt               # 4-byte big-endian length prefix
    ├── BarrierTransport.kt
    ├── SocketBarrierTransport.kt    # Plain + TLS socket; TLS is trust-all, not Barrier fingerprint TLS
    ├── BarrierModels.kt             # State, errors, input events, listener
    ├── BarrierCommand.kt
    └── BarrierConstants.kt

app/src/test/java/.../protocol/
├── BarrierClientTest.kt
├── PacketCodecTest.kt
└── FakeBarrierTransport.kt
```

The activity layout is `res/layout/activity_main.xml`. It is not Compose, even though the Compose plugin and libraries are applied. Retrofit and Gson are dependencies and are unused.

## What a tap on Connect does

```text
MainActivity.connectToBarrierServer()
  writes BarrierForegroundService.Companion.pendingConnection
  startService(BarrierForegroundService)

BarrierForegroundService.onStartCommand()
  reads Companion.pendingConnection      # now wired
  assigns connectionListener from pending.listener
  startConnection(pending)              # builds client + transport
  startForeground(...)                  # notification
```

`startConnection()` now builds a `SocketBarrierTransport` and a `BarrierClient` on `SupervisorJob() + Dispatchers.IO`. It copies the clipboard lambdas and `onScreenSaverChanged` from the pending connection, and sets `screenWidth`/`screenHeight` from display metrics. The client's `connect()` is launched on the service scope.

`clientListener` forwards state, input, and errors to `connectionListener` (now assigned). Input events reach the activity listener, which routes them to `BarrierAccessibilityService`. Clipboard data reaches `handleClipboardData`.

The break is no longer at the service wiring step. It is now at the socket (see below) and at the accessibility service registration (fixed in the manifest, but injection still does not match real Barrier events).

## Intended path, not the one that runs

```text
Barrier server
  TCP :24800
    SocketBarrierTransport.readPackets
      PacketCodec.decodePayload → BarrierPacket.decode
        BarrierClient.handlePacket
          BarrierClientListener in the activity
            BarrierAccessibilityService.injectPointer / injectKey
            clipboard lambdas in MainActivity
```

Breaks on that path, in order:

1. ~~Socket transport~~ — now opens a plain `java.net.Socket` for plaintext and an `SSLSocket` with a trust-all `TrustManager` for TLS. `connect()` no longer double-connects. TLS is still not Barrier's fingerprint-verified TLS. Socket errors now surface via `onConnectionLost` → `closeTransport` and `BarrierError.ConnectionFailed`.
2. The accessibility service is now declared with meta-data. `injectPointer` now consults the motion action (tap, stroke, scroll) but `dispatchGesture` creates discrete gestures, not continuous pointer tracking. `injectKey` still does not match real server key ids. `injectText` replaces field contents. See [limitations](limitations.md).
3. The service's `stopSelf()` on `DISCONNECTED` cancels the reconnect scope.

## Client state machine

`BarrierClient` is the piece the tests cover.

- `connect` records host, port, and TLS flag, then `performConnect`: set transport callback, state `CONNECTING`, `transport.connect`, send `Hello`, state `HANDSHAKE`. It does not notify the listener for those two states.
- A compatible server `Hello` (major 1, minor ≥ 6) is answered with `HelloBack`. Otherwise `IncompatibleVersion` and close.
- `QINF` is answered with `DINF` using `screenWidth` / `screenHeight` (0 unless the caller sets them).
- `CIAK` is ignored. `DSOP` applies options, sets `CONNECTED`, notifies, and starts keepalive plus a watchdog.
- Input packets notify the listener and send `CNOP`.
- `CALV` is answered with `CALV`.
- `CCLP` calls `onClipboardGrab` and sends `DCLP`. `DCLP` stores the packet and calls `onClipboardData`.
- `CSEC` stores `screenSaverActive` and calls `onScreenSaverChanged`.
- `CBYE`, version errors, and `EBSY` / `EUNK` / `EBAD` call `closeTransport`. If the previous state was `CONNECTED`, that schedules reconnect unless `disconnect()` set `userInitiatedClose`.
- `DFTR` and `DDRG` are ignored. `CINN` / `COUT` update screen sequence and notify `ScreenEntered` / `ScreenLeft`, then send `CNOP`. The activity ignores those two events.

Keepalive and reconnect jobs use the `CoroutineScope` passed into the client. In the service that would be `serviceScope`. `onDestroy` cancels that scope. The service no longer calls `stopSelf()` on `DISCONNECTED`; reconnect survives a server drop. The service is stopped by the activity's Disconnect button or `onDestroy`.

## Accessibility service

`BarrierAccessibilityService` is a `AccessibilityService` with empty `onAccessibilityEvent` / `onInterrupt`. `onServiceConnected` publishes `instance`. The manifest now declares the `android.accessibilityservice` meta-data pointing at `res/xml/accessibility_service_config.xml`, so Android should list it in Settings. The system starts it only after the user enables it; the app does not start it itself.

`injectGlobalAction` is a one-line wrapper around `performGlobalAction` and has no caller. `injectPointer`, `injectKey`, and `injectText` are called from `MainActivity` only when a client listener fires. With the service wiring fixed, those calls now happen on real input, but the implementations do not match the events. See [limitations](limitations.md).

## Tests

`FakeBarrierTransport` records `send` and lets a test `emit` a packet into `onPacket`. Client tests launch `connect` on `backgroundScope`, then `runCurrent()` and `advanceUntilIdle()`. The reconnect test also `advanceTimeBy(INITIAL_RECONNECT_BACKOFF_MILLIS)` because `advanceUntilIdle()` does not fire that delay on its own.

These tests do not construct `MainActivity`, `BarrierForegroundService`, or `SocketBarrierTransport`.
