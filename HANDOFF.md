# Android Barrier Client — Handoff

## Project
- **Path**: `/home/chris/bin/opencodeexperiments/barrierandroid`
- **Goal**: Android phone as a secondary screen for a computer running the Barrier server (primary); server → client input delivery; no video (permanently out of scope); clipboard in scope (not optional).
- **Plan**: `/home/chris/.opencode/plan/barrier-android-client.md`

## Current phase status
- Phase 1 (client protocol core) — **COMPLETE AND GREEN**.
- Phase 2 (Accessibility Service injection + clipboard + foreground service) — **SUBSTANTIALLY COMPLETE**. Connection handshake works on real device. Input injection partially functional (gestures, not real pointer events).
- Phase 3 (hardening) — **IN PROGRESS**: reconnect, screensaver/options, UI wiring, wake lock, battery optimization, background survival, settings persistence, network change detection all implemented; **visible pointer cursor overlay implemented** via accessibility-hosted translucent window drawing a standard arrow (Red Moon pattern); **instant-crash-on-connect fixed** (added missing `ACCESS_NETWORK_STATE` permission for the network callback); **in-app debug log sink added** (`barrier_debug.log` in external files dir; cursor lifecycle + inject logging; cursor overlay show lifecycle log entries added in `showCursor`).

## Implemented (Phase 1)
- Protocol layer (`app/src/main/java/com/crrpendlebury/barrierandroid/protocol/`): BarrierClient, BarrierPacket, PacketCodec, BarrierTransport, SocketBarrierTransport, BarrierModels, BarrierCommand, BarrierConstants.
- Android UI layer: MainActivity, AndroidManifest, layout, strings, theme.
- Tests: PacketCodecTest (16), BarrierClientTest (11), FakeBarrierTransport.

## Implemented (Phase 2 — this session series)
1. **Foreground service** (`BarrierForegroundService.kt`): owns BarrierClient + coroutine scope, startForeground with FOREGROUND_SERVICE_TYPE_SPECIAL_USE, notification channel, PendingConnection handoff, wake lock, WiFi lock, stopSelf removed on DISCONNECTED (reconnect survives), network change detection, reconnect from saved settings on restart.
2. **Manifest**: FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE permissions; FGS registered with foregroundServiceType="specialUse" + PROPERTY_SPECIAL_USE_FGS_SUBTYPE; accessibility service registered with meta-data config (canRetrieveWindowContent, canPerformGestures).
3. **Key injection** (`BarrierAccessibilityService.kt`): injectKey maps KEYCODE_BACK/HOME/APP_SWITCH → global actions; injectGlobalAction via performGlobalAction. Arbitrary keys deferred (no public API).
4. **Text injection**: injectText via ACTION_SET_TEXT on focused editable node. Wired from clipboard handler (DCLP). Requires canRetrieveWindowContent (now declared).
5. **Pointer injection**: injectPointer now consults motion action (tap/stroke/scroll), throttled to 8 px, mouse movement toggle, 20 ms stroke duration. Tracks lastX/lastY for move origin.

   **Visible cursor overlay (implemented)**: `showCursor`/`hideCursor` in `BarrierAccessibilityService` add/remove a translucent overlay window (`WindowManager.addView`/`removeView`) via inner `CursorOverlayView` drawing a standard arrow; `injectPointer` mirrors position (down/move/scroll show, up hide). Window: `TYPE_ACCESSIBILITY_OVERLAY` (fallback `TYPE_SYSTEM_OVERLAY` < API 23), flags NOT_TOUCHABLE/NOT_FOCUSABLE/LAYOUT_NO_LIMITS/LAYOUT_IN_SCREEN, `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` on API 23+, translucent format, display-plus-margin geometry. Overlay is a render surface only; dispatchGesture remains sole gesture path. Hidden on disconnect / screensaver; injected input re-shows. Released in service onDestroy. Build + unit tests green.
6. **Clipboard**: CCLP → onClipboardGrab provider → DCLP reply; DCLP → store in ClipboardManager + injectText. CLPS option respected.
7. **Accessibility service registration**: meta-data XML + manifest entry. isEnabled() checks secure settings. openSettings() launches system settings.
8. **UI wiring**: accessibility status surfacing (on create + resume), error display (TextView + Toast), open settings button, mouse movement CheckBox, battery optimization prompt.
9. **Barrier logo**: actual Barrier SVG logo as app icon (manifest) and in-app ImageView.

## Implemented (Phase 3 — this session series)
1. **Protocol handshake fix**: client no longer sends Hello first; waits for server's Hello, responds with HelloBack. Fixed EBAD error on real server.
2. **Reconnect with exponential backoff**: 1s → 30s, user-initiated disconnect suppresses reconnect, service no longer cancels reconnect scope.
3. **Screensaver + options handling**: CSEC → screenSaverActive + callback; DSOP → applyOptions (CLPS, HART, SSVR, MDLT) + callback; CROP → reset to defaults.
4. **Options applied**: CLPS gates clipboard grab/data; HART sets keepalive interval; MDLT/SSVR stored.
5. **Socket transport rewrite**: plain Socket for plaintext, SSLSocket with trust-all TrustManager for TLS. No double-connect. useTls parameter respected. onConnectionLost callback surfaces drops. Socket errors → BarrierError.ConnectionFailed.
6. **Error surfacing**: transport failures → BarrierError.ConnectionFailed via notifyError. Foreground service wraps connect in runCatching.
7. **Foreground service compat**: SDK_INT guards for NotificationChannel (API 26) and startForeground 3-arg (API 29). PROPERTY_SPECIAL_USE_FGS_SUBTYPE for Android 14. Wake lock + WiFi lock. Battery optimization prompt.
8. **Background survival**: removed stopService from onDestroy. Settings saved/restored via SharedPreferences (commit() + TextWatcher). Activity re-attaches listener to running service via BarrierForegroundService.instance.
9. **Network change detection**: ConnectivityManager callback detects network loss, closes transport to trigger reconnect.
10. **Service restart reconnection**: On START_STICKY restart without pending connection, reads saved settings from SharedPreferences and reconnects.
11. **Verbose comments policy**: plan + docs/comments.md. File-level and symbol-level KDoc across all source files documenting defects, protocol facts, API limits.
12. **GNU GPLv2 license**: LICENSE file + README reference.
13. **Documentation suite**: README + 7 docs files, rewritten to reflect actual (non-working) state.
14. **Git email fix**: Rewrote all 29 commits with chris@christopherpendlebury.com (was chris@cpendlebury.com). Force-pushed. Git config updated.
15. **Gradle project name**: Fixed FoodDiary → BarrierAndroid.

## Protocol facts encoded (Barrier v2.4.0 / protocol v1.6)
- Frame: u32 BE length + payload; command (4 chars; "Barrier" greeting = 7 chars), BE int16/int32 params, BE32 string length + UTF-8.
- Handshake: server sends Hello → client sends HelloBack → server QINF → client DINF → server CIAK → server DSOP → CONNECTED.
- Greeting decode: payload size 11 → Hello(maj, min); size > 11 → HelloBack(maj, min, name).
- HelloBack encode: "Barrier"(7) + BE16 maj + BE16 min + BE32 namelen + UTF-8 name.
- Keepalive: 3s interval, 3 missed (9s) watchdog. HART option adjusts interval.
- Clipboard: CCLP (grab) → DCLP (reply); DCLP (push) → store + inject.
- Options: CLPS, HART, SSVR, MDLT (4-char codes packed big-endian).
- Reconnect: exponential backoff 1s → 30s. User-initiated disconnect suppresses.

## Build / test status
- `./gradlew :app:testDebugUnitTest --offline` → **GREEN: 27/27 tests** (PacketCodecTest 16/16, BarrierClientTest 11/11).
- `./gradlew :app:assembleDebug --offline` → **GREEN / SUCCESSFUL** (full APK built).

## Known issues / gotchas
- Environment's android.jar (platforms/android-35) lacks SSLSocketFactory.createSSLSocket → TLS uses standard SSLSocket with trust-all manager (not Barrier's fingerprint-verified TLS).
- Offline builds: use --offline; androidx.appcompat not cached.
- Kotlin nested-class instantiation requires parens: `BarrierPacket.KeepAlivePacket()`, etc.
- **Framework API finding**: public AccessibilityService dispatch surface in compileSdk 35 exposes only dispatchGesture and performGlobalAction. dispatchKeyEvent does not exist → discrete key injection not possible; global actions for back/home/recents only.
- **Pointer injection**: dispatchGesture creates discrete gestures, not continuous pointer tracking. Visible cursor now rendered via accessibility-hosted overlay (standard arrow); injection still discrete. Throttled to reduce phantom keystrokes.
- **Cursor visibility (current debugging)**: overlay implementation landed (commits `2f51539` → `13e08c7` → `446cada` → `515cfbf`), but the cursor did **not** appear on device even though injection works (debug log shows `injectPointer ACTION_MOVE` firing). In-app log sink added (`barrier_debug.log`, external files dir) + show lifecycle logging (`cursor overlay show` at runnable start and after position update). Awaits: reinstall latest APK, move mouse during connected session, read `barrier_debug.log`, confirm show-path execution and overlay add/draw.
- **Text injection**: ACTION_SET_TEXT replaces field contents (no append). Only fires from clipboard path.
- **Options**: CLPS and HART now applied; MDLT and SSVR stored but not acted upon.
- **File transfer / drag**: DFTR/DDRG decoded and ignored.
- **Screen dimensions**: now set from displayMetrics.
- **Verbose comments policy**: plan + docs/comments.md. All source files commented.
- **Git email**: chris@christopherpendlebury.com (verified on GitHub).

## Immediate next steps
1. On-device validation: test connection, injection, clipboard, screensaver, options, reconnect, background survival on a real device with a real Barrier server. The visible cursor overlay now needs real-device validation (movement shows/moves cursor, button hold/release, disconnect/screensaver hide). Current blocker: cursor not visible despite working injection; using in-app `barrier_debug.log` (`Android/data/com.crrpendlebury.barrierandroid/files/barrier_debug.log`) to trace the overlay show path.
2. Remaining key injection: arbitrary keycodes require hidden InputManager reflection — evaluate feasibility.
3. File transfer/drag info handling (deferred stubs).
4. UI improvements: update notification text with connection state, add plaintext TLS toggle.

## Handy commands
- Tests: `./gradlew :app:testDebugUnitTest --offline`
- Build: `./gradlew :app:assembleDebug --offline`
- SDK API inspection: `javap -classpath /home/chris/Android/Sdk/platforms/android-35/android.jar android.accessibilityservice.AccessibilityService`