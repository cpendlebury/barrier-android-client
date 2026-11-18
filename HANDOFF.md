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

   **Visible cursor overlay (implemented)**: `showCursor`/`hideCursor` in `BarrierAccessibilityService` add/remove a translucent overlay window (`WindowManager.addView`/`removeView`) via inner `CursorOverlayView` drawing a standard arrow; `injectPointer` mirrors position (down/move/scroll show, up hide). Window: `TYPE_ACCESSIBILITY_OVERLAY` (fallback `TYPE_SYSTEM_OVERLAY` < API 23), flags NOT_TOUCHABLE/NOT_FOCUSABLE/LAYOUT_NO_LIMITS/LAYOUT_IN_SCREEN, `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` on API 23+, translucent format, display-plus-margin geometry. Overlay is a render surface only; dispatchGesture remains sole gesture path. Hidden on disconnect / screensaver; injected input re-shows. Released in service onDestroy. Build + unit tests green. Overlay hosted on the service context for the window token (BadToken with application context); visibility set `View.VISIBLE` on show.
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
- **Cursor visibility (current debugging)**: overlay implementation landed (commits `2f51539` → `13e08c7` → `446cada` → `515cfbf`); window-add defect fixed in `2cc63c4` (no-arg `LayoutParams` + field assignment; convenience constructors shift args); latest device log shows `cursor overlay addView ok attached=false type=2032 flags=16778008` — addView now succeeds via valid `TYPE_ACCESSIBILITY_OVERLAY`, draw path runs. Final overlay defect fixed: arrow paint was opaque black, invisible over the display dimmed by the screen filter during a session, so `cursorPaint` is now white (high contrast). Overlay view and window manager were then re-hosted on the application context (Red Moon `Overlay(applicationContext)` pattern) with `View.VISIBLE` set on show, and the `addView ok` log line now carries `visibility=…`; after the next retest surfaced `BadTokenException: token null is not valid` with the application context, the overlay was reverted to service-context hosting (the service context carries the valid window token for the accessibility overlay). The user has never seen anything on screen, so a diagnostic was added: `DEBUG_OVERLAY_FILL = true` fills the overlay window with translucent red on every draw (turn off once the cursor works); the draw log now includes `screenX`, `screenY`, `windowVisibility`, `shown`; a `cursor overlay size w=… h=…` line is logged on layout; the arrow is sized in dp (24×20 dp). **Result of that diagnostic**: the red fill appeared, so the window reaches the screen. The logs showed the window at `screenX=-3000 screenY=-3000` instead of -1000, because the default centre gravity shifts the window by `(display - window) / 2`; the arrow was therefore drawn off-screen. Fix: `gravity = Gravity.TOP or Gravity.START` so `x`/`y` are absolute. Retest: `screenX`/`screenY` should now read -1000, and the white arrow should follow the mouse. `DEBUG_OVERLAY_FILL` is still true for this retest; turn it off once the arrow is confirmed.

  **Double press (2026-11-19)**: after the zero-duration fix, every click was a double press (9 became 99, checkboxes toggled twice). `ACTION_DOWN` dispatched a tap and `ACTION_UP` dispatched a second tap at the same spot. `ACTION_UP` now sends no gesture and only hides the cursor. Retest: one press should produce one tap.

  **Click disconnect: ROOT CAUSE FOUND (2026-11-19)**: the uncaught-exception log showed `IllegalArgumentException: Duration must be positive` from `GestureDescription.StrokeDescription` in `tapGesture`. The tap used a 0 ms stroke, which the framework rejects. Every click (ACTION_DOWN) threw on a coroutine thread, which killed the process and dropped the connection. Fixed by `TAP_DURATION_MS = 10L`. The earlier checkbox difference was only the order of gestures. Retest: a click should keep the connection and show the cursor's click. The `addView` rethrow fix from `a8ac093` stays; it is still correct.

  **Click disconnects the app (2026-11-19, superseded by the root cause above)**: the cursor now appears with the checkbox unchecked, but clicking still closes the app and disconnects it. The earlier rethrow fix did not change this. Diagnostics added so the next run is decisive: `BarrierApplication` logs `app process started` and any `uncaught exception on <thread>` with its stack trace to `barrier_debug.log`; ACTION_DOWN and ACTION_UP are always logged with coordinates; `disconnect button pressed` and `foreground service destroyed` mark deliberate shutdowns. Interpretation: a `process started` line after the click means the process died; an `uncaught exception` line names the crash; `disconnect button pressed` means a tap landed on the Disconnect button; none of these means the disconnect came from the network side.

  **Click-without-checkbox crash (2026-11-19)**: clicking with the movement checkbox unchecked closed the app and disconnected. Cause: `showCursor` rethrew an `addView` failure inside `handler.post`, which runs on the main looper, so the process died. With the checkbox unchecked the first overlay add happens on the first click, so that is where it surfaced. The rethrow is removed; a failed add is logged as `cursor overlay addView failed` and retried on the next event. Not yet confirmed which exception the add throws; a retest with the log will show it. Open question: a click can also land on the Barrier app's own Disconnect button if its window is under the pointer. Not addressed.

  **Cursor shape (2026-11-19)**: the plain white triangle was hard to see on white backgrounds. The cursor is now a classic pointer outline (white fill, black 1.5 dp outline, scaled 1.3× density) drawn from `CURSOR_OUTLINE_POINTS`. Needs an on-device check on light and dark content.

  **Logging trimmed (2026-11-19)**: per-event lines (every injected motion, every overlay show, every draw) now go through `logVerbose`, gated by `VERBOSE_CURSOR_LOG = false` because each call opens and appends to `barrier_debug.log` on the calling thread. Lifecycle lines stay on: `addView ok` / `addView failed`, `attached to window`, `detached from window`, `size`, `hide`, and foreground-service and client-state lines. Set `VERBOSE_CURSOR_LOG = true` for a diagnostic run.

  **Cursor confirmed (2026-11-19)**: the white arrow appears on screen, but only with the movement checkbox checked. `injectPointer` returned before updating the cursor when movement was disabled. The cursor now follows movement regardless; the checkbox only suppresses movement gestures. `DEBUG_OVERLAY_FILL` is now false, so the red test fill is off. Remaining: validate button hold/release, disconnect/screensaver hide, and the cursor's size and position on the real screen.

  **Retest result (no tint, no draw lines)**: the log showed `addView ok` followed by `cursor overlay show` lines but no `cursor overlay size` or `cursor overlay draw` lines, so the view was never laid out or drawn. Attach and detach logging (`cursor overlay attached to window` / `cursor overlay detached from window`) was added to show whether the system attached and then removed the window. If the APK is not the latest commit, reinstall first.

  **2026-11-18 update**: the `createWindowContext(TYPE_ACCESSIBILITY_OVERLAY)` implementation crashed with `UnsupportedOperationException` (context not associated with a display) and was reverted to the plain service-context `WindowManager`. Current route under investigation: **route 2** — align the overlay with Red Moon's known-working pattern (full-screen translucent layout via `LayoutInflater`, `WRAP_CONTENT` + `gravity = TOP`, `NOT_TOUCHABLE`/`NOT_FOCUSABLE`, `TRANSLUCENT`, `TYPE_ACCESSIBILITY_OVERLAY`, plain-context add). Instrumentation added (`cursor overlay addView ok attached=… type=… flags=… visibility=…`, `cursor overlay draw x=… y=… w=… h=…`) is committed and pushed (build green). Overlay later moved to application-context hosting per Red Moon `Overlay(applicationContext)`.
- **Text injection**: ACTION_SET_TEXT replaces field contents (no append). Only fires from clipboard path.
- **Options**: CLPS and HART now applied; MDLT and SSVR stored but not acted upon.
- **File transfer / drag**: DFTR/DDRG decoded and ignored.
- **Screen dimensions**: now set from displayMetrics.
- **Verbose comments policy**: plan + docs/comments.md. All source files commented.
- **Git email**: chris@christopherpendlebury.com (verified on GitHub).

## Immediate next steps
1. On-device validation: test connection, injection, clipboard, screensaver, options, reconnect, background survival on a real device with a real Barrier server. The visible cursor overlay now needs real-device validation (movement shows/moves cursor, button hold/release, disconnect/screensaver hide). Current blocker: cursor not visible despite working injection; using in-app `barrier_debug.log` (`Android/data/com.crrpendlebury.barrierandroid/files/barrier_debug.log`) to trace the overlay show path. Since `865f6de`: overlay hosted on app context, visibility `View.VISIBLE` on show, arrow white, draw log line now `cursor overlay draw x=… y=… w=… h=…`; retest with latest APK, movement checkbox on, move mouse, read log, expect `addView ok` → `draw` → cursor visible.

   **2026-11-18 update — route 2**: after the `createWindowContext` crash + revert, selected **route 2** as the path to a working cursor (Red Moon-aligned overlay: full-screen translucent layout, `WRAP_CONTENT` + `gravity = TOP`, flags, `TYPE_ACCESSIBILITY_OVERLAY`, plain-context add). Instrumentation added and pushed (build green). Next: implement route 2 overlay layout, then real-device validation of the cursor.
2. Remaining key injection: arbitrary keycodes require hidden InputManager reflection — evaluate feasibility.
3. File transfer/drag info handling (deferred stubs).
4. UI improvements: update notification text with connection state, add plaintext TLS toggle.

## Handy commands
- Tests: `./gradlew :app:testDebugUnitTest --offline`
- Build: `./gradlew :app:assembleDebug --offline`
- SDK API inspection: `javap -classpath /home/chris/Android/Sdk/platforms/android-35/android.jar android.accessibilityservice.AccessibilityService`