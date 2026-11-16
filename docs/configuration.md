# Configuration

There is no settings screen beyond the three fields on the main activity. Nothing is persisted. Host, port, and client name are read once, when **Connect** is tapped, and then dropped on the floor by the service bug described in [limitations](limitations.md).

## Fields

| Field | Default if blank | Notes |
|-------|------------------|--------|
| Host | none | Sent only if a client is actually constructed. Empty host is not rejected |
| Port | `24800` (`DEFAULT_BARRIER_PORT`) | Barrier's usual port. Non-numeric text falls back to 24800; `0` is kept |
| Client name | `AndroidBarrierClient` (`DEFAULT_CLIENT_NAME`) | Would be the HelloBack name and must match a screen name on the server, case-sensitive, if a handshake ever ran |

There is no TLS switch. The service hardcodes `useTls = true` in both the transport constructor and `connect()`. That flag does not select a working Barrier TLS or plaintext socket. See [limitations](limitations.md).

## Server side

A future working build would be configured like any other Barrier client:

- Server listens on the port entered in the app (24800 unless changed).
- A screen exists whose name matches the client name exactly.
- Encryption must match whatever the client actually speaks. This build speaks neither Barrier TLS nor plaintext TCP.

This client would also currently advertise a 0×0 screen (`screenWidth` / `screenHeight` are never set), which a Barrier server will not treat as a usable display.

## Options the client understands

Option ids are the Barrier 4-character codes packed big-endian, from `src/lib/barrier/option_types.h` in the Barrier tree. `applyOptions` only reads three of them:

| Code | Constant | Stored as | Changes behaviour? |
|------|----------|-----------|--------------------|
| `CLPS` | `OPTION_CLIPBOARD_SHARING` (`0x434C5053`) | `clipboardSharingEnabled` (`value != 0`) | No. Grab and data handlers do not check it |
| `HART` | `OPTION_HEARTBEAT` (`0x48415254`) | `heartbeatSeconds` | No. Keepalive stays 3000 ms |
| `SSVR` | `OPTION_SCREEN_SAVER_SYNC` (`0x53535652`) | `screenSaverSyncEnabled` (`value != 0`) | No. `CSEC` only updates `screenSaverActive` |

`OPTION_RELATIVE_MOUSE_MOVES` (`MDLT`, `0x4D444C54`) is declared and unused. Every other id is kept in `appliedOptions` and ignored.

`CROP` clears `appliedOptions` and resets the three flags above to their defaults (clipboard sharing true, heartbeat 0, screensaver sync false), then restarts the keepalive jobs. It does not tell the server anything.

## Timers

These are compile-time constants, not preferences:

| Constant | Value | Used? |
|----------|--------|--------|
| `KEEP_ALIVE_INTERVAL_MILLIS` | 3000 | Yes, while a client is running |
| `KEEP_ALIVES_UNTIL_DEATH` | 3 | Yes. No inbound activity for 9 s closes the transport |
| `INITIAL_RECONNECT_BACKOFF_MILLIS` | 1000 | Only inside `BarrierClient.scheduleReconnect` |
| `MAX_RECONNECT_BACKOFF_MILLIS` | 30000 | Same. Backoff doubles until this cap |
| `PROTOCOL_MAX_MESSAGE_LENGTH` | 4 MiB | Yes. Longer frames abort the read loop |

The app's service stops itself on `DISCONNECTED`, so the backoff loop would not survive in the current service even if a socket existed.
