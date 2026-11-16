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
    ├── SocketBarrierTransport.kt    # Not a working Barrier socket
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
  reads this.pendingConnection          # instance field, always null
  startForeground(...)                  # notification only
```

`startConnection()` exists and would build a `SocketBarrierTransport` plus a `BarrierClient` on `SupervisorJob() + Dispatchers.IO`. Nothing calls it.

`clientListener` forwards state, input, and errors to `connectionListener`. Nothing assigns `connectionListener`. The listener stored inside `PendingConnection` is unused. Clipboard lambdas on that same object are also unused, because `startConnection` is the only code that would copy them onto the client.

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

1. The service never constructs the client (companion vs instance `pendingConnection`).
2. Even then, the activity listener is not the listener passed to `BarrierClient`.
3. `SocketBarrierTransport.createSocket()` does not open a plain TCP socket and does not implement Barrier TLS. `connect()` is invoked on a socket that `createSocket(host, port)` has already connected.
4. The accessibility service is not declared correctly, so `instance` stays null and injection returns immediately.
5. `injectPointer` / `injectKey` do not implement the events they claim to. See [limitations](limitations.md).

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

Keepalive and reconnect jobs use the `CoroutineScope` passed into the client. In the service that would be `serviceScope`. `onDestroy` cancels that scope. The service also `stopSelf()`s on `DISCONNECTED`, which is the state `closeTransport` publishes before reconnect, so the service and the client reconnect logic contradict each other.

## Accessibility service

`BarrierAccessibilityService` is a `AccessibilityService` with empty `onAccessibilityEvent` / `onInterrupt`. `onServiceConnected` publishes `instance`. The system is what starts an accessibility service; the app does not, and with the current manifest the system will not either.

`injectGlobalAction` is a one-line wrapper around `performGlobalAction` and has no caller.

## Tests

`FakeBarrierTransport` records `send` and lets a test `emit` a packet into `onPacket`. Client tests launch `connect` on `backgroundScope`, then `runCurrent()` and `advanceUntilIdle()`. The reconnect test also `advanceTimeBy(INITIAL_RECONNECT_BACKOFF_MILLIS)` because `advanceUntilIdle()` does not fire that delay on its own.

These tests do not construct `MainActivity`, `BarrierForegroundService`, or `SocketBarrierTransport`.
