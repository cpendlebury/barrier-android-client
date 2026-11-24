# Architecture

The computer runs the Barrier server and owns the keyboard and mouse. The phone runs this client. Everything below describes the phone side: input arrives from the server and the app makes the phone act on it.

Package `com.crrpendlebury.barrierandroid`. Protocol code is in `protocol`, the accessibility service and its drag helper in `accessibility`, and the input method and key mapping in `ime`.

## Layout

```text
app/src/main/java/com/crrpendlebury/barrierandroid/
├── MainActivity.kt                    # Connection form, input routing, clipboard handlers
├── BarrierApplication.kt              # Process start and uncaught-exception logging
├── BarrierForegroundService.kt        # Owns BarrierClient, notification, wake locks, Wi-Fi lock, network callback
├── accessibility/
│   ├── BarrierAccessibilityService.kt # Gestures, global actions, cursor overlay, debug log sink
│   └── DragCoalescer.kt               # Chains drag segments into one continuing stroke
├── ime/
│   ├── BarrierIME.kt                  # Input method: commitText, sendKey, paste
│   ├── BarrierKeyMap.kt               # Barrier key ids to Android key codes and characters
│   ├── HostShortcuts.kt               # Host chord to phone system action
│   └── ImeSwitcher.kt                 # Makes BarrierIME the active input method and restores the previous one
└── protocol/
    ├── BarrierClient.kt               # Handshake, keepalive, reconnect, clipboard, options
    ├── BarrierPacket.kt               # Encode and decode of payload bodies
    ├── PacketCodec.kt                 # 4-byte big-endian length prefix
    ├── BarrierTransport.kt
    ├── SocketBarrierTransport.kt      # Plain socket, or TLS pinned by server certificate fingerprint
    ├── BarrierServerTrustManager.kt   # Fingerprint pinning, trust on first use
    ├── BarrierModels.kt               # State, errors, input events, listener
    ├── BarrierCommand.kt
    └── BarrierConstants.kt

app/src/main/res/                      # activity_main.xml (XML views), accessibility and IME configs, strings
app/src/test/java/.../                 # PacketCodecTest, BarrierClientTest, BarrierServerTrustManagerTest,
                                       # HostShortcutsTest, BarrierKeyMapTest, FakeBarrierTransport
```

The UI is XML views in `res/layout/activity_main.xml`. Compose is on the classpath but unused. Retrofit and Gson have been removed.

## Connect

```text
MainActivity.connectToBarrierServer()
  stores settings in SharedPreferences
  writes BarrierForegroundService.pendingConnection
  startService(BarrierForegroundService)

BarrierForegroundService.onStartCommand()
  takes pendingConnection, assigns the activity listener
  acquires the CPU wake lock and the Wi-Fi lock
  registers the network callback
  startConnection(): builds SocketBarrierTransport and BarrierClient on serviceScope
  startForeground(...) with the notification
```

`BarrierClient.connect()` runs on the service scope. The server sends `Hello`, the client answers `HelloBack`, then the server sends `QINF`, the client replies `DINF`, the server sends `CIAK` and `DSOP`, and the state becomes `CONNECTED`. The activity then calls `ImeSwitcher.useOurs` so the Barrier keyboard becomes active.

## Input path

```text
Barrier server (TCP, port 24800 by default)
  SocketBarrierTransport.readPackets
    PacketCodec.decodePayload -> BarrierPacket.decode
      BarrierClient.handlePacket            # sends CNOP after input packets
        BarrierForegroundService.clientListener.onInputEvent
          wake lock: timed screen-bright lock, logged per event
          MainActivity.onInputEvent
            pointer  -> BarrierAccessibilityService.injectPointer
            buttons  -> injectBack / injectForward / tap path
            wheel    -> injectPointer(ACTION_SCROLL, xDelta, yDelta)
            keys     -> HostShortcuts.match
                        -> global action (Home, recents, shade, ...)
                        -> BarrierIME commitText / sendKey, when a field has focus
                        -> copy / cut / paste chords
```

Pointer injection has three parts:

- `injectPointer` mirrors the position onto a cursor overlay window (`showCursor` / `hideCursor`, a translucent `TYPE_ACCESSIBILITY_OVERLAY` window that draws an arrow). The overlay is a display only; it never produces gestures.
- `ACTION_DOWN`, `ACTION_MOVE`, and `ACTION_UP` drive a drag. `DragCoalescer` keeps one segment in flight, and each segment is dispatched with `dispatchGesture` and chained with `StrokeDescription.continueStroke`. Movement with no button held sends no gesture.
- `ACTION_SCROLL` builds one swipe per wheel event. The swipe direction comes from the sign of the wheel delta and the start is clamped to the screen (`scrollGesture`).

Global actions (`performGlobalAction`) back Home, Back, recents, the notification shade, quick settings, lock screen, and screenshot. They are used for the host shortcuts and the mouse back and right buttons. Keystrokes are not sent through the accessibility service, because the compile SDK exposes no key dispatch; they go through the IME instead.

## Key delivery

`BarrierIME` is an `InputMethodService`. When a text field has focus its `InputConnection` is available:

- Printable characters go through `commitText`.
- Special keys (Enter, Backspace, arrows, function keys, and so on) go through `sendKeyEvent` with the Barrier modifier state as the meta state.
- Ctrl, Alt, and Meta chords with a printable key are sent as key events so that apps see the modifiers.

Without an input connection, ordinary keys are dropped. `ImeSwitcher` switches the active input method when the connection starts and restores the user's previous method on disconnect.

## Clipboard

- `CCLP` (server asks for the phone's clipboard) calls `readClipboardBytes` in `MainActivity` and replies `DCLP`.
- `DCLP` (server pushes clipboard) stores the text in the device clipboard with `ClipboardManager`. Nothing is typed into a field.
- Host Ctrl+C and Ctrl+X read or clear the focused field through `copyFocusedEditableText` and `cutFocusedEditableText`. Host Ctrl+V calls `BarrierIME.paste`, which commits the device clipboard into the focused field.

## Client state machine

`BarrierClient` is the protocol core, and the unit tests cover it.

- `connect` records host, port, and the TLS flag, then runs `performConnect`: set the transport callback, state `CONNECTING`, `transport.connect`, state `HANDSHAKE`. It does not send `Hello`; the server speaks first.
- A compatible server `Hello` (major 1, minor at least 6) is answered with `HelloBack`. Otherwise the client reports `IncompatibleVersion` and closes.
- `QINF` is answered with `DINF` using `screenWidth` and `screenHeight`, which the service sets from display metrics.
- `CIAK` is ignored. `DSOP` applies options, sets `CONNECTED`, and starts keepalive and the watchdog.
- Input packets notify the listener and reply `CNOP`. `CALV` is answered with `CALV`.
- `CCLP` calls `onClipboardGrab` and replies `DCLP`. `DCLP` calls `onClipboardData`.
- `CSEC` sets `screenSaverActive` and calls `onScreenSaverChanged`.
- `CBYE`, the version errors, and `EBSY`, `EUNK`, and `EBAD` close the transport. If the previous state was `CONNECTED`, a reconnect is scheduled with exponential backoff (1 s doubling to 30 s), unless `disconnect()` set `userInitiatedClose`.
- `DFTR` and `DDRG` are decoded and ignored.

## Service lifetime

The service does not stop itself on `DISCONNECTED`, so the reconnect loop survives a server drop. The service stops only when the user taps **Disconnect** (which calls `stopService`) or the process is killed. Leaving the activity does not stop it, so the connection survives backgrounding. The activity re-attaches its listener to a running service on recreation through `BarrierForegroundService.instance`.

On `DISCONNECTED` the service also hides the cursor overlay.

## Wake and power

- The partial wake lock keeps the CPU awake while the session runs. It does not wake the screen, so it carries no wake flag.
- Each inbound input event acquires a separate `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` lock with a 5 s timeout. The per-event log line records whether the lock was held and the device state (`interactive`, keyguard).
- A Wi-Fi lock keeps the radio awake, and a `ConnectivityManager` callback closes the transport on network loss so the reconnect loop can retry on the new network.

## Debug logging

`BarrierAccessibilityService.log` is the single debug sink. It appends to `barrier_debug.log` in the app's external files directory, and returns without writing unless the **Debug logging** checkbox (`debug_logging_enabled`) is on. Unchecking the box deletes the file. `BarrierApplication` routes uncaught exceptions through the same sink.

## Tests

`FakeBarrierTransport` records `send` and lets a test `emit` a packet into `onPacket`. Client tests launch `connect` on `backgroundScope`, then use `runCurrent()` and `advanceUntilIdle()`. The reconnect test also calls `advanceTimeBy(INITIAL_RECONNECT_BACKOFF_MILLIS)`, because `advanceUntilIdle()` does not run that delay on its own.

`HostShortcuts` and `BarrierKeyMap` are pure Kotlin and tested on the JVM. Nothing in the test suite constructs `MainActivity`, `BarrierForegroundService`, or the accessibility service. Device behaviour is checked by hand.
