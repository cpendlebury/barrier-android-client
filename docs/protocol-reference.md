# Protocol Reference

BarrierAndroid implements the **Barrier protocol v1.6** (Barrier server v2.4.0).

## Framing

Every message is a 4-byte big-endian length prefix followed by the payload:

```
┌──────────────┬─────────────────────┐
│ u32 BE len   │ payload (len bytes) │
└──────────────┴─────────────────────┘
```

- Maximum message length: 4 MiB.
- Payload starts with a 4-character command (except the greeting, which is 7 chars: `"Barrier"`).

## Greeting (handshake)

Server → client:
```
"Barrier" (7 bytes) + u16 major + u16 minor
```
- If payload size == 11: `Hello(major, minor)` (server hello).
- If payload size > 11: `HelloBack(major, minor, name)` (server hello-back, includes client name).

Client → server (reply to server hello):
```
"Barrier" (7) + u16 major + u16 minor + u32 nameLen + UTF-8 name
```

## Handshake sequence

1. Server sends `Hello(maj, min)`.
2. Client validates version (major == 1, minor >= 6). If incompatible → `EICV` + close.
3. Client sends `HelloBack(maj, min, clientName)`.
4. Server sends `QINF` (query info).
5. Client sends `DINF` (screen info: xOrigin, yOrigin, width, height, warpZone, mouseX, mouseY).
6. Server sends `CIAK` (info acknowledged).
7. Server sends `DSOP` (set options) → handshake complete, client enters `CONNECTED`.

## Screen events

- `CINN` (screen enter): `x, y, seq, modifierMask` — server delivers input to this client.
- `COUT` (screen leave): client acknowledges with `CNOP`.

## Input events (server → client)

| Command | Packet | Fields |
|---------|--------|--------|
| `DKDN` | KeyDown | keyId (u16), modifierMask (u16), button (u16) |
| `DKRP` | KeyRepeat | keyId, modifierMask, repeat (u16), button (u16) |
| `DKUP` | KeyUp | keyId, modifierMask, button (u16) |
| `DMMV` | MouseMove | x (u16), y (u16) |
| `DMRM` | MouseRelativeMove | dx (u16), dy (u16) |
| `DMWM` | MouseWheel | xDelta (u16), yDelta (u16) |
| `DMDN` | MouseButtonDown | button (u8) |
| `DMUP` | MouseButtonUp | button (u8) |

Client acknowledges each input event with `CNOP`.

## Keepalive

- `CALV` (keepalive): no params. Client replies with `CALV`.
- Interval: 3s. Watchdog drops connection after 3 missed (9s).

## Clipboard

- `CCLP` (clipboard grab): `clipboardId (u8), seq (u32)` → server asks client for clipboard. Client replies `DCLP`.
- `DCLP` (clipboard data): `clipboardId (u8), seq (u32), mark (u8), dataSize (u32), data` → clipboard content.

## Options

- `DSOP` (set options): list of `(id: u32, value: u32)` pairs.
- `CROP` (reset options): no params → client resets to defaults.

Option IDs (4-char codes packed big-endian):
| Code | Name | Value |
|------|------|-------|
| `CLPS` | Clipboard sharing | 0 = disabled |
| `HART` | Heartbeat | seconds |
| `SSVR` | Screensaver sync | 0 = disabled |
| `MDLT` | Relative mouse moves | 0 = disabled |

## Screensaver

- `CSEC`: `started (u8)` — screensaver state change.

## Errors

| Command | Meaning |
|---------|---------|
| `EICV` | Incompatible version (u16 major, u16 minor) |
| `EBSY` | Server busy |
| `EUNK` | Unknown client |
| `EBAD` | Protocol error |
| `CBYE` | Close connection |

## Client → server packets

| Command | Packet | Fields |
|---------|--------|--------|
| `HELLO` | HelloBack | major (u16), minor (u16), nameLen (u32), name |
| `DINF` | ScreenInfo | xOrigin, yOrigin, width, height, warpZone, mouseX, mouseY (all u16) |
| `CNOP` | NoOp | — |
| `CALV` | KeepAlive | — |
| `DCLP` | ClipboardData | clipboardId (u8), seq (u32), mark (u8), dataSize (u32), data |
| `COUT` | ScreenLeft | — (sent by server; client sends CNOP in reply) |

## Sequence numbers

The server increments `seq` per screen switch (`CINN`). The client tracks `currentScreenSeq` and uses it for clipboard (`CCLP`/`DCLP`) traffic. Missequenced events are ignored by the server.