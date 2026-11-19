# Troubleshooting

## Connect does not reach CONNECTED

**Connect** now starts the foreground service, constructs a `BarrierClient`, and attempts a TCP/TLS connection. The status line should progress from `DISCONNECTED` to `CONNECTED` if the handshake succeeds. If it stays `DISCONNECTED` or shows an error:

- Verify the server host and port are correct.
- Ensure the server is running and reachable (firewall, same network).
- Check that the client name matches a configured screen on the server (case-sensitive).
- Check logcat: `adb logcat -s BarrierAndroid BarrierForegroundService BarrierClient`

## "incompatible server version"

The server speaks a protocol version older than 1.6. Upgrade the Barrier server to v2.4.0+.

## "server is busy" / "server does not know this client" / "protocol error reported by server"

- **Busy**: another client is connecting; retry.
- **Unknown client**: the client name does not match any configured screen. Fix the name on the server.
- **Protocol error**: version or framing mismatch; ensure both sides are v2.4.0. This was previously caused by the client sending `Hello` first (fixed).

## TLS handshake failure

The client authenticates the server by the SHA-256 fingerprint of its self-signed
certificate (Barrier's `ENCRYPTED_AUTHENTICATED` mode), with trust on first use.
If the TLS handshake fails:

- Check if the server is using TLS. If the server is in plaintext mode, the TLS handshake will fail because the server sends plaintext.
- There is no plaintext toggle in the UI yet. The server must use TLS for the connection to succeed.
- A fingerprint mismatch (for example after the server regenerated its certificate) is reported as a connection failure; clearing the stored fingerprint lets the client re-accept on next connect.
- Check logcat for SSL exceptions.

## The accessibility service is not in Settings

The service is now registered with meta-data. Android should list it under Settings → Accessibility. If it does not appear:

- Ensure the APK was rebuilt after the manifest change.
- On Android 13+, sideloaded apps need "Allow restricted settings" (Settings → Apps → BarrierAndroid → three-dot menu → Allow restricted settings) before the service can be enabled.

`Accessibility service: disabled` is the correct reading of secure settings until the user toggles it on.

## "App was denied access" (Android 13+)

When enabling the accessibility service on a sideloaded app, Android shows a warning about restricted permissions. To proceed:

1. Settings → Apps → BarrierAndroid
2. Tap the three-dot menu (⋮) → "Allow restricted settings"
3. Confirm the dialog
4. Go back to Settings → Accessibility → BarrierAndroid → toggle On

## "Text injection failed (no focused editable field)"

`ACTION_SET_TEXT` requires a focused, editable text field in the active window. If no such field is focused, injection fails.

**Fix**: Tap into a text field (e.g., a search bar or message input) on the phone before triggering text delivery from the server.

## "Accessibility service not enabled; text not injected."

The accessibility service is disabled. Enable it via Settings → Accessibility.

## Reconnect loop

After a server drop, the client reconnects with exponential backoff (1s → 2s → 4s → … → 30s). If the server is down, this continues indefinitely. Tap **Disconnect** to stop.

## Mouse movement issues

- Mouse movement sends no gesture to the phone; only clicks and scrolls do. The injected position is mirrored onto a visible cursor overlay that adds a translucent window hosting a standard arrow in the accessibility service; the arrow is a classic pointer with a white fill and black outline so it stays visible on light and dark content.
- Scroll direction and amount are not preserved (swipes down a fixed 200 px).

## Build issues

### `androidx.appcompat` not cached

The app does not depend on `appcompat`. If you add it, ensure it's cached or build online.

### TLS reflection warning

`SSLSocketFactory.createSSLSocket` is missing in `android-35`; the app uses reflection-free standard TLS now. This is expected and handled.

### Gradle offline

`--offline` is for a machine that already cached the dependencies. A fresh clone needs network. Remove `--offline` and retry.

## The Gradle project name

`settings.gradle.kts` sets `rootProject.name = "BarrierAndroid"`. The Gradle name is informational only; it is not the application id (`com.crrpendlebury.barrierandroid`) or the launcher label (`BarrierAndroid`).

## Tests pass but the phone does nothing

The 27 unit tests drive `BarrierClient` through `FakeBarrierTransport`. They never start `BarrierForegroundService`, never open a socket, and never enable an accessibility service. A passing test suite does not mean the app works on a device.