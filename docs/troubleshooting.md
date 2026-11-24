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

## Keys do nothing on the phone

- Ordinary keystrokes need the Barrier keyboard. Enable it in **Settings → Keyboard**, then check the `Keyboard (IME)` line on the main screen. It reads `enabled` once the keyboard is on. The app switches to it when the connection starts; if the keyboard picker shows another keyboard, select Barrier from it.
- Keystrokes only reach a focused text field. Tap into a field first.
- Escape goes Back, and the host shortcuts (Home, recent apps, shade, and so on) do not need a field. If those work but typing does not, the keyboard is the cause.

## Ctrl+L, Alt+Left, or another app shortcut does nothing

App shortcuts are delivered as key events to the focused text field, so they need a field with focus. Not every app honours synthetic key events with Ctrl or Alt held. Those limits are in [limitations](limitations.md#keyboard).

## Scrolling does nothing in one app

Scroll is an injected swipe. Apps that ignore injected gestures will not scroll. Try the same wheel in another app to confirm the connection is fine.

## Meta+N, Meta+A, Meta+/, or the app drawer does nothing

Meta+N, Meta+S, Meta+L, Meta+H, Meta+Tab, Alt+Tab, and Print Screen are mapped. Meta alone (app drawer), Meta+A (assistant), and Meta+/ are not, because Android has no public action for them. If a mapped chord does nothing, turn on Debug logging, press the chord, and look for `handleBarrierKey keyId=… mask=…` followed by `host shortcut … accepted=…`. No `handleBarrierKey` line means the host or server did not send the key; `accepted=false` means Android refused the action.

## Right click clicks instead of going back

Right click is Barrier button 3 and maps to Back. A build older than 0.1.2 mapped Back to button 2, which is the middle button. Update to 0.1.2 or later.

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
- Scroll direction comes from the host wheel deltas: wheel forward (`yDelta` positive) scrolls the page up, wheel back scrolls it down, and `xDelta` drives horizontal scroll. Each event is a fixed 200 px swipe over 100 ms because `GestureDescription` carries no axis or delta data. Host right click (3) and the back button (4) fire the BACK global action; the forward button (5) is declined (no public forward action). Host Meta/Alt chords map to Home, recents, notifications, quick settings, lock screen, and screenshot (see [usage](usage.md#keyboard-shortcuts)).

### Screen does not wake

Symptom: the screen is off or locked, host input (mouse movement) arrives, but the display does not wake.

Diagnostic: with the app's Debug logging enabled, each inbound input event is logged to the debug sink (`barrier_debug.log` in the external files dir), for example `input event: MouseMove; screenWake wasHeld=false; held=true; interactive=false; keyguardLocked=true; keyguardSecure=true`. The screen-wake lock is `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`, acquired with a 5 s timeout on every event. Builds before 0.1.2 used `PARTIAL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`. PowerManager does not allow that combination, so those builds held a lock that never woke the screen.

Interpretation:

- If no `input event:` lines appear at all, input delivery is the problem (the events never reach the accessibility service).
- If the lines show `held=true` but `interactive=false` stays false and the display remains dark, this device or ROM does not honour the wake lock. Report the device model and Android version.
- If `interactive=true` appears after an event but the display is still dark, check the physical display state and whether the phone's own power settings override the wake.
- `keyguardLocked=true` means the screen can wake to the lock screen, but the user still has to unlock it. The wake lock does not dismiss the keyguard.

The debug log is written only while Debug logging is checked. Unchecking it deletes the existing `barrier_debug.log`, so no stale log is left behind.

Workaround: on a secure lock screen the display wakes but the phone still needs unlocking. Check the debug log as described above, and report the device model and Android version if the display stays dark while `interactive` remains false.

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

The 48 unit tests drive `BarrierClient` through `FakeBarrierTransport`, and the packet and shortcut tests run pure Kotlin. None of them start `BarrierForegroundService`, open a socket, or enable an accessibility service. A passing test suite does not show that the app works on a device; the device checks in [limitations](limitations.md) do.