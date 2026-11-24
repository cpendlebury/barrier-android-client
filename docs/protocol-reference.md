# Protocol notes

This describes the framing **this client implements** in `BarrierPacket` and `PacketCodec`. It is not a copy of the Barrier specification. The handshake and input packets have been exercised against a live Barrier 2.4.0 server on a phone. Where the code and a typical Barrier description disagree, the code is what is documented here.

Wire constants live in `BarrierCommand` and `BarrierConstants`. Protocol version advertised by the client is 1.6 (`PROTOCOL_MAJOR_VERSION` / `PROTOCOL_MINOR_VERSION`).

## Framing

`PacketCodec` prefixes every payload with a 4-byte big-endian length. The reader drops the connection if the length is greater than 4 MiB, if the stream ends early, or if `BarrierPacket.decode` returns null.

Integers in payloads are big-endian. Most helpers read them as unsigned bit patterns (`readInt16` / `readInt32`). The wheel and relative-move deltas (`DMWM` and `DMRM`) are Barrier `%2i` fields, two's complement, and decode with `readSInt16`. Reading them unsigned made every wheel-back event look like a forward event before 0.1.2. Strings, where present, are a big-endian 32-bit byte length plus bytes. HelloBack uses US-ASCII for the name on decode and UTF-8 on encode.

## Greeting

The greeting command is the 7-byte ASCII string `Barrier`, not a 4-byte command.

| Payload length after the 7-byte command | Decoded as | Direction this client uses |
|------------------------------------------|------------|----------------------------|
| Exactly 4 more bytes (11 total): major u16, minor u16 | `Hello` | Server → client. The client does not send this; it waits for the server's. |
| Longer: major u16, minor u16, name length u32 at offset 11, name at offset 15 | `HelloBack` | Client → server, in reply to a compatible `Hello` |

Decode does not check that the buffer is long enough for the name. A short hello-back will throw rather than return null.

The client accepts a server hello only when major is 1 and minor is at least 6. Otherwise it reports `IncompatibleVersion` and closes. It does not send `EICV`.

## Commands the codec knows

Unknown 4-byte commands return null, and the socket reader then stops. These commands are decoded:

| Command | Class | Body, as this codec reads it |
|---------|--------|------------------------------|
| `QINF` | `QueryInfoPacket` | empty |
| `CIAK` | `InfoAcknowledgedPacket` | empty |
| `CNOP` | `NoOpPacket` | empty |
| `CBYE` | `ClosePacket` | empty |
| `CSEC` | `ScreenSaverPacket` | u8, non-zero means started |
| `CROP` | `ResetOptionsPacket` | empty |
| `CALV` | `KeepAlivePacket` | empty |
| `CCLP` | `ClipboardGrabPacket` | u8 id, u32 seq |
| `EICV` | `IncompatibleVersionPacket` | u16 major, u16 minor |
| `EBSY` | `BusyPacket` | empty |
| `EUNK` | `UnknownPacket` | empty |
| `EBAD` | `BadPacket` | empty |
| `CINN` | `ScreenEnterPacket` | u16 x, u16 y, u32 seq, u16 modifier mask |
| `COUT` | `ScreenLeftPacket` | empty. Server → client. This client does not send it |
| `DKDN` | `KeyDownPacket` | u16 key id, u16 modifier mask, u16 button |
| `DKRP` | `KeyRepeatPacket` | u16 key id, u16 modifier mask, u16 repeat, u16 button |
| `DKUP` | `KeyUpPacket` | u16 key id, u16 modifier mask, u16 button |
| `DMMV` | `MouseMovePacket` | u16 x, u16 y |
| `DMRM` | `MouseRelativeMovePacket` | s16 dx, s16 dy (signed, two's complement) |
| `DMWM` | `MouseWheelPacket` | s16 x delta, s16 y delta (signed; +120 wheel forward, -120 wheel back) |
| `DMDN` | `MouseButtonDownPacket` | u8 button |
| `DMUP` | `MouseButtonUpPacket` | u8 button |
| `DINF` | `ScreenInfoPacket` | seven u16 fields: x origin, y origin, width, height, warp zone, mouse x, mouse y |
| `DCLP` | `ClipboardDataPacket` | u8 id, u32 seq, u8 mark, u32 size, then that many bytes taken from the payload at offset 14 |
| `DSOP` | `SetOptionsPacket` | repeating u32 id, u32 value, while 8 bytes remain |
| `DFTR` | `FileTransferPacket` | u8 mark, u32 size, bytes from payload offset 9. Decoded and ignored |
| `DDRG` | `DragInfoPacket` | u16 count, u32 size, bytes from payload offset 10. Decoded and ignored |

`DINF` encode writes 14 bytes of parameters (7 × u16) after the command. Width and height are now set from display metrics in the foreground service.

## What the client sends

| When | Packet |
|------|--------|
| `performConnect` (connect / reconnect) | nothing; waits for the server's `Hello` |
| Compatible server `Hello` | `HelloBack` (versions, then UTF-8 name) |
| `QINF` | `DINF` with origins 0, configured size, warp 0, mouse 0,0 |
| `CALV`, and the keepalive timer | `CALV` |
| `CCLP` | `DCLP` using the grab id and seq, mark set to that same seq, data from `onClipboardGrab` or empty |
| `CINN`, `COUT`, and every key, pointer, wheel, and button packet | `CNOP` |

Sending `CNOP` after each input event is this client's behaviour. Do not assume a Barrier server requires it.

`DCLP` encode writes `mark` as one byte. The grab reply sets mark equal to seq, so any seq above 255 is truncated on the wire.

## Session behaviour in `BarrierClient`

After `DSOP`, state becomes `CONNECTED` and keepalive starts. `CIAK` does not change state by itself.

`CINN` stores `currentScreenSeq`. `COUT` emits `ScreenLeft` with that seq. Incoming clipboard packets are not checked against the seq.

Keepalive: every 3000 ms the client sends `CALV` if still connected. A parallel watchdog closes the transport if `lastServerActivityNanos` is at least 9000 ms old. Both the timer and inbound packets update that timestamp, so the watchdog does not measure "missed server keepalives" independently of the client's own sends.

Reconnect: `closeTransport` while state was `CONNECTED` starts a loop that waits `INITIAL_RECONNECT_BACKOFF_MILLIS`, then doubles up to `MAX_RECONNECT_BACKOFF_MILLIS`, and calls `performConnect`. `disconnect()` sets `userInitiatedClose` and does not schedule that loop. The service's client listener forwards the `DISCONNECTED` state without stopping the service, so this loop can run inside the service scope; the service is stopped by the activity's Disconnect button. See [architecture](architecture.md).

Option ids this client names are listed in [configuration](configuration.md). `CLPS` and `HART` are now honoured by the client (clipboard gating and keepalive interval); `SSVR` is recorded; `MDLT` is not read. Storing an option does not change pointer handling.
