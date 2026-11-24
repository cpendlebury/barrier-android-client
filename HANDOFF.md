# Android Barrier Client — Handoff

## Project

- **Path**: `/home/chris/bin/opencodeexperiments/barrierandroid`
- **Goal**: an Android phone as a secondary screen for a computer running the Barrier server. The computer owns the keyboard and mouse; the phone takes input from it. Clipboard is shared in both directions. Video is out of scope.
- **Repository**: `github.com/cpendlebury/barrier-android-client` (branch `master`).
- **Documentation**: `README.md`, and `docs/` (usage, limitations, architecture, configuration, troubleshooting, installation, protocol reference). `docs/limitations.md` is the source of truth for what works and what doesn't.
- **Working plan** (outside the repo): `/home/chris/.opencode/plan/barrier-android-client.md`. Its phase notes are partly out of date; the repository docs take precedence.

## Current state (2026-11-25)

- **Release 0.1.2** is published: tag `0.1.2`, `versionCode` 4, signed APK SHA-256 `89ee11579d7cf977ede8a1b4f45ab72af0a39f1c814bd5ae21af0c1f5eb97cb0`. Release notes are on the GitHub release page.
- **Tested device**: Google Pixel 8a, GrapheneOS, Android 17 (build 2026100201). It is the only device tested.
- **Confirmed on device** (0.1.2 session): cursor overlay follows the host pointer; left click; right click and the back button go back; wheel scrolls in both directions; host input wakes the screen; Print Screen and Meta+Ctrl+S screenshot; Meta+L lock; Meta+H Home; Alt+Tab and Meta+Tab recents; Meta+S quick settings; Meta+N notification shade. Earlier sessions confirmed the handshake over TLS with fingerprint pinning, clipboard copy and paste chords, and keystrokes through the Barrier keyboard.
- **Not yet validated on device**: horizontal wheel scroll; Ctrl+L, Ctrl+PageDown, and Alt+Left in a browser with a focused field; reconnect after a server drop; screensaver hide; wake on any other device or ROM.
- **Known gaps** (see `docs/limitations.md`): no real system pointer, so taps, drags, and scrolls are accessibility gestures; scroll is a fixed 200 px swipe per notch; the forward button is declined (no public API); Meta alone, Meta+A, and Meta+/ are unmapped; keystrokes need the Barrier keyboard; wake uses a deprecated flag; the stock Files app does not open folders from a tap on the test device.

## Build, test, release

```bash
# Debug build and unit tests (offline works on this machine)
./gradlew :app:testDebugUnitTest :app:assembleDebug --offline

# Release APK, lint excluded because lint-gradle is not cached offline
./gradlew :app:assembleRelease --offline -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease
```

- Unit tests: 48, all passing. `PacketCodecTest` 18, `BarrierClientTest` 12, `BarrierServerTrustManagerTest` 7, `HostShortcutsTest` 8, `BarrierKeyMapTest` 3.
- Signing: release builds use the keystore at `/home/chris/Android/keystore/barrier-release.jks` (alias `barrier`), which lives outside the repo. Without it, the release build is unsigned, which is what F-Droid expects. Passwords are kept outside the repo.
- Check the signer with `apksigner verify --print-certs`. It should show `CN=BarrierAndroid`.

### Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update the 0.1.x references in `README.md`, `docs/installation.md` (APK link, SHA-256, `versionCode`), and `docs/limitations.md` (version line).
3. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
4. Build the release APK, copy it to `/tmp/opencode/barrier-android-<version>.apk`, and record its SHA-256.
5. Commit ("Release: <version>"), tag `<version>` with an annotated tag, push `master` and the tag.
6. `gh release create <version> <apk> --repo cpendlebury/barrier-android-client --title "Barrier Android Client <version>" --notes-file <notes>`, then confirm the asset digest matches the docs.

## Architecture in one paragraph

`MainActivity` is the connection form and the input router. `BarrierForegroundService` owns the `BarrierClient`, the notification, the wake locks, and the network callback. `BarrierClient` (in `protocol/`) runs the Barrier handshake, keepalive, and reconnect, and emits input events. Pointer, button, wheel, and global-action injection go through `accessibility/BarrierAccessibilityService`. Keystrokes go through `ime/BarrierIME`, selected by `ImeSwitcher`. Host shortcut chords are matched by `ime/HostShortcuts` (pure Kotlin, tested). The debug log sink is `BarrierAccessibilityService.log`, which writes `barrier_debug.log` only while Debug logging is on. `docs/architecture.md` has the full flow.

## Protocol facts this client relies on (Barrier 2.4.0, protocol 1.6)

- Frame: u32 big-endian length, then a payload with a 4-character command. The greeting is the 7-character `Barrier` followed by version fields.
- Handshake: server `Hello` → client `HelloBack` → server `QINF` → client `DINF` → server `CIAK` and `DSOP` → `CONNECTED`. The client does not send `Hello` first.
- Keepalive: `CALV` every 3 s. Nine seconds of silence closes the transport. `HART` can change the interval.
- Mouse buttons (`DMDN`/`DMUP`, one unsigned byte): 1 left, 2 middle, 3 right, 4 and 5 extra (back and forward). Source: Barrier `ButtonTypes`.
- Wheel (`DMWM`) and relative move (`DMRM`): signed 16-bit deltas (`%2i`). +120 is wheel forward. Use `readSInt16`, not `readInt16`.
- Modifier mask bits: Shift `0x1`, Control `0x2`, Alt `0x4`, Meta `0x8`, Super `0x10`. Print Screen is key id `0xEF61`, and Escape is `0xEF1B`. Source: Barrier `KeyTypes.h` (keysym minus `0x1000`).
- Key packets: `DKDN`, `DKRP`, `DKUP`. Key ids are Barrier ids, not Android key codes.
- Clipboard: `CCLP` (grab) → `DCLP` (reply); server `DCLP` (push) is stored in the device clipboard, not typed.
- Options: `CLPS` and `HART` are applied. `SSVR` is stored only. `MDLT` is not read.
- TLS: the server's self-signed certificate is pinned by SHA-256 fingerprint, trust on first use (Barrier `ENCRYPTED_AUTHENTICATED`). Hostname verification is off.

## Gotchas

- **Unsigned vs signed reads.** `readInt16` is unsigned. Signed Barrier fields must use `readSInt16`. A regression in this area produced a one-way scroll bug in 0.1.1.
- **`ACQUIRE_CAUSES_WAKEUP` does not combine with `PARTIAL_WAKE_LOCK`.** The screen-wake lock is `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` with a 5 s timeout. Both constants are deprecated since API 33.
- **`GestureDescription` durations must be positive.** A zero-length stroke throws `IllegalArgumentException` and kills the process. `TAP_DURATION_MS` is 100 ms.
- **Keep gesture endpoints on screen.** Scroll start points are clamped so both ends stay inside the display.
- **Overlay window gravity** must be `TOP or START`. With the default centre gravity the cursor is drawn off screen.
- **The overlay must not throw.** A failed `addView` is logged and retried on the next event. A rethrow on the main looper kills the app.
- **Public API limits on compileSdk 35.** `AccessibilityService` has `dispatchGesture` and `performGlobalAction` only; there is no `dispatchKeyEvent`, so keystrokes go through the IME. There is no public forward-navigation global action, and no public force-wake API.
- **Kotlin nested classes** need parentheses at construction, e.g. `BarrierPacket.KeepAlivePacket()`.
- **`android.jar` for android-35** lacks `SSLSocketFactory.createSSLSocket`, so TLS uses a plain `SSLSocket` with a fingerprint-pinning trust manager.
- **`androidx.appcompat` is not cached.** Do not depend on it, or build online.
- **The debug log is gated.** Every writer goes through `BarrierAccessibilityService.log`. Unchecking Debug logging deletes the file.

## Open work

1. Validate on device: horizontal wheel scroll; app shortcuts in a browser with a focused field; reconnect after a server drop; screensaver hide.
2. Wake: if `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` stops working on a future Android release, the replacement is an activity using `setTurnScreenOn` launched from a full-screen-intent notification.
3. Forward button: needs real input injection (a hidden `InputManager` route with system privileges). Not planned until a route is confirmed.
4. Plaintext TLS toggle in the UI. The transport supports plaintext; the UI always passes `useTls = true`.
5. F-Droid: the recipe `metadata/com.crrpendlebury.barrierandroid.yml` still targets 0.1.1. The earlier submission (`fdroid/fdroiddata!58615`) was closed by the reviewer because the template was not followed and the app did not meet the three-month maintenance expectation. Resubmit only when the app has a maintenance history and the MR follows the template.
6. Phone screenshots for F-Droid and the store listing.
