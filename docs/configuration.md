# Configuration

## Server configuration

BarrierAndroid speaks the Barrier v1.6 protocol (v2.4.0 server). Configure the server as you would for any Barrier client:

- **Port**: default `24800` (configurable in the app).
- **TLS**: expected by default; the app falls back to plaintext if TLS negotiation fails.
- **Screen name**: must match the client name entered in the app (default `AndroidBarrierClient`), case-sensitive.

## Client options (server → client)

The server sends options via `DSOP` (SetOptions). The client applies the following:

| Option ID | Code | Value | Effect |
|-----------|------|-------|--------|
| Clipboard sharing | `CLPS` | 0 = disabled, non-zero = enabled | Toggles clipboard sync |
| Heartbeat | `HART` | seconds | Requested keepalive interval (stored; default 3s) |
| Screensaver sync | `SSVR` | 0 = disabled, non-zero = enabled | Screensaver state tracking |
| Relative mouse moves | `MDLT` | 0 = disabled, non-zero = enabled | Relative motion mode |

Other options are received and stored in `appliedOptions` but not acted upon.

## Reset options

The server may send `CROP` (ResetOptions), which resets all options to defaults and restarts the keepalive loop.

## Screensaver

The server sends `CSEC` (ScreenSaver) with a started/stopped flag. The client tracks `screenSaverActive` and notifies via `onScreenSaverChanged`. No automatic action is taken beyond tracking.

## Keepalive

- Client sends keepalive every 3 seconds.
- Watchdog drops the connection after 3 missed keepalives (9s).
- Server keepalive requests are answered in kind.

## TLS vs plaintext

- TLS is attempted by default (`useTls = true`).
- If `SSLSocketFactory.createSSLSocket` is unavailable (as on Android 35), the app falls back to plaintext.
- For a plaintext server, TLS negotiation simply fails and falls back.

## Client name and identity

The client name is sent in the `HelloBack` handshake packet and must match a configured screen on the server. Change it in the app's "Client Name" field before connecting.