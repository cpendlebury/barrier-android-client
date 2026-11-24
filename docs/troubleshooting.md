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

## Clipboard receipt and text injection

When the server pushes clipboard data (DCLP), the app stores it into the device clipboard via `ClipboardManager` and does not inject it into the focused editable field, so receipt does not depend on a focused field and cannot fail there.

## Accessibility service not enabled

The accessibility service is disabled. Enable it via Settings → Accessibility. The service remains required for pointer injection and global actions; it is not consulted for clipboard receipt.

## Reconnect loop

After a server drop, the client reconnects with exponential backoff (1s → 2s → 4s → … → 30s). If the server is down, this continues indefinitely. Tap **Disconnect** to stop.

## Stock Files app does not open folders

In the stock Android Files app (`com.android.documentsui`), tapping a folder shows the press feedback, but the folder does not open. Other apps are not affected, and Fossify File Manager opens folders from the same tap.

This is a known limitation. The input path delivers the click to Files, so the cause appears to be inside Files. See [limitations](limitations.md).

Workaround: use another file manager, such as Fossify File Manager.

## Mouse movement issues

- Mouse movement sends no gesture to the phone; only clicks and scrolls do. The injected position is mirrored onto a visible cursor overlay that adds a translucent window hosting a standard arrow in the accessibility service; the arrow is a classic pointer with a white fill and black outline so it stays visible on light and dark content.
- Scroll direction is now taken from the host wheel deltas: a positive `yDelta` scrolls down, a negative `yDelta` scrolls up, and `xDelta` drives horizontal scroll; the scroll itself is a fixed 200 px swipe over 100 ms. The per-event distance is flat because `GestureDescription` carries no axis or delta data. Host mouse Back button presses fire the BACK global action via the accessibility service; Forward presses are declined (no public forward action).

### Screen does not wake

Symptom: the screen is off or locked, host input (mouse movement) arrives, but the display does not wake.

Diagnostic: with the app's Debug logging enabled, inbound input is logged per event to the debug sink (`barrier_debug.log` in the external files dir), e.g. `input event: MouseMove; wakeLock wasHeld=true; acquire=true; held=true; interactive=false; keyguardLocked=false; keyguardSecure=false`. The wake lock is re-armed (release + acquire, which re-applies `ACQUIRE_CAUSES_WAKEUP`) before the event is dispatched, so a healthy retest should show `acquire=true` and `held=true` on every event, alongside the device-interactive and keyguard state.

Interpretation:

- If no `input event:` lines appear at all, input delivery is the problem (the events never reach the accessibility service).
- If the lines appear with `acquire=true; held=true` but the display still does not wake, the re-arm is not the blocker; the wake mechanism or the keyguard is. A logged `keyguardLocked=true` alongside an apparently armed wake lock points at the keyguard swallowing the wake, and the next retest should confirm whether the display turns on and the keyguard then re-engages.
- The `interactive=` value is `PowerManager.isInteractive()`, which reports whether the device considers itself awake; it is logged per event so a retest can correlate the wake failure with the device state.

Workaround: none yet. Retest with the latest debug APK (`adb install -r app/build/outputs/apk/debug/app-debug.apk` on the real device, Debug logging checked first).

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