# Troubleshooting

## "Accessibility service: disabled"

The accessibility service is not enabled. Input injection will not work.

**Fix**: Tap **Open Accessibility Settings** in the app, find **BarrierAndroid**, toggle it **On**, and accept the permission dialog. The status updates on return to the app (checked on resume).

## "Text injection failed (no focused editable field)"

`ACTION_SET_TEXT` requires a focused, editable text field in the active window. If no such field is focused, injection fails.

**Fix**: Tap into a text field (e.g., a search bar or message input) on the phone before triggering text delivery from the server.

## "Accessibility service not enabled; text not injected."

Same as above — the service is disabled. Enable it via Settings → Accessibility.

## Connection fails / "ConnectionFailed"

- Verify the server host and port are correct.
- Ensure the server is running and reachable (firewall, same network).
- Check that the client name matches a configured screen on the server.
- Check logs: `adb logcat -s BarrierAndroid`.

## "incompatible server version"

The server speaks a protocol version older than 1.6. Upgrade the Barrier server to v2.4.0+.

## "server is busy" / "server does not know this client" / "protocol error reported by server"

- **Busy**: another client is connecting; retry.
- **Unknown client**: the client name does not match any configured screen. Fix the name on the server.
- **Protocol error**: version or framing mismatch; ensure both sides are v2.4.0.

## Reconnect loop

After a server drop, the client reconnects with exponential backoff (1s → 30s). If the server is down, this continues indefinitely. Tap **Disconnect** to stop.

## Foreground service notification

A persistent "Barrier" notification is shown while connected. This is required on Android 8+ for background operation. It is removed on disconnect.

## Build issues

### `androidx.appcompat` not cached

The app does not depend on `appcompat`. If you add it, ensure it's cached or build online.

### TLS reflection warning

`SSLSocketFactory.createSSLSocket` is missing in `android-35`; the app uses reflection and falls back to plaintext. This is expected and handled.

### Gradle offline

Always use `--offline`. Dependencies are cached locally. If a dependency is missing, build online once to populate the cache.