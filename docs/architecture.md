# Architecture

## Overview

BarrierAndroid is a native Android client for the Barrier KVM protocol. It consists of:

1. **Protocol layer** (`protocol/`) — pure Kotlin, no Android dependencies (except transport). Handles framing, handshake, keepalive, input events, clipboard, options, reconnect.
2. **Android layer** (`accessibility/`, `MainActivity`, `BarrierForegroundService`) — Accessibility Service for injection, foreground service for keepalive, UI for connection management.
3. **Tests** (`test/`) — JUnit + coroutines-test with `FakeBarrierTransport`.

## Package structure

```
com.crrpendlebury.barrierandroid/
├── MainActivity.kt                 # UI, connection orchestration, injection routing
├── BarrierForegroundService.kt     # Foreground service hosting BarrierClient
├── accessibility/
│   └── BarrierAccessibilityService.kt  # Accessibility-based input injection
└── protocol/
    ├── BarrierClient.kt            # Protocol state machine
    ├── BarrierPacket.kt            # Packet types + codec (encode/decode)
    ├── PacketCodec.kt              # Frame encode/decode (u32 BE length + payload)
    ├── BarrierTransport.kt         # Transport interface
    ├── SocketBarrierTransport.kt   # TCP/TLS transport
    ├── BarrierModels.kt            # States, errors, input events, listener
    ├── BarrierCommand.kt           # Command enum (4-char wire codes)
    └── BarrierConstants.kt         # Protocol constants, option IDs, backoff bounds
```

## Component interaction

```
┌─────────────────────────────────────────────────────┐
│  Barrier Server (remote)                            │
│  sends: Hello, QINF, CINN, DKDN, DMMV, DMWM, DCLP… │
└──────────────────────┬──────────────────────────────┘
                       │ TCP/TLS (port 24800)
                       ▼
┌─────────────────────────────────────────────────────┐
│  SocketBarrierTransport                             │
│  reads frames → PacketCodec.decode → onPacket       │
└──────────────────────┬──────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────┐
│  BarrierClient (in service scope)                   │
│  handlePacket() → state machine → notify listener   │
│  keepalive watchdog, reconnect backoff               │
└──────────┬──────────────────┬───────────────────────┘
           │ onInputEvent     │ onClipboardData / onScreenSaverChanged
           ▼                 ▼
┌─────────────────────────────────────────────────────┐
│  MainActivity (BarrierClientListener)               │
│  routes events → BarrierAccessibilityService        │
│  clipboard data → ClipboardManager + injectText     │
└──────────────────────┬──────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────┐
│  BarrierAccessibilityService                        │
│  injectPointer (dispatchGesture)                    │
│  injectText (ACTION_SET_TEXT)                      │
│  injectKey (global actions: back/home/recents)      │
└─────────────────────────────────────────────────────┘
```

## Data flow: input injection

1. Server sends `DMMV` (mouse move) → transport → `BarrierClient.handlePacket` → `notifyInput(MouseMove)` → listener → `service.injectPointer(x, y, ACTION_MOVE)` → `dispatchGesture`.
2. Server sends `DKDN` (key down) → … → `service.injectKey(keyId, ACTION_DOWN)` → `performGlobalAction` (if mapped) or no-op.
3. Server sends `DCLP` (clipboard data) → … → `onClipboardData` → `handleClipboardData` → `ClipboardManager.setPrimaryClip` + `service.injectText(text)` → `performAction(ACTION_SET_TEXT)`.

## Data flow: clipboard grab

1. Server sends `CCLP` (clipboard grab) → `handleClipboardGrab` → `onClipboardGrab` provider → reads device clipboard → replies `DCLP` with data.

## Foreground service lifecycle

- `MainActivity.connectToBarrierServer()` sets `BarrierForegroundService.pendingConnection` and calls `startService`.
- `BarrierForegroundService.onStartCommand` consumes pending connection, creates `BarrierClient`, calls `startForeground` (specialUse type), returns `START_STICKY`.
- Client lives in `serviceScope` (`SupervisorJob() + Dispatchers.IO`).
- On unexpected `DISCONNECTED`: `clientListener` wrapper posts `stopSelf()` → `onDestroy` → `stopClient()` → `disconnect()`.
- User disconnect: activity calls `stopService` → same cleanup path.

## Reconnect

- Server-initiated close (`closeTransport`) while `CONNECTED` → `scheduleReconnect()`.
- Exponential backoff: 1s, 2s, 4s, …, 30s cap.
- `userInitiatedClose` flag prevents reconnect after user disconnect.
- `performConnect()` re-establishes transport + handshake.

## Testing

- `FakeBarrierTransport` implements `BarrierTransport` in-memory.
- Tests use `TestScope` + `backgroundScope` for client coroutines.
- Pattern: `backgroundScope.launch { client.connect(...) }` → `runCurrent()` → `advanceUntilIdle()`.
- 27 tests: PacketCodec (16), BarrierClient (11).