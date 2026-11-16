# Android Barrier Client — Handoff

## Project
- **Path**: `/home/chris/bin/opencodeexperiments/barrierandroid`
- **Goal**: Android phone as an external keyboard/mouse for a computer running the Barrier server (primary); server → client input delivery; no video (permanently out of scope); clipboard in scope (not optional).
- **Plan**: `/home/chris/.opencode/plan/barrier-android-client.md`

## Current phase status
- Phase 1 (client protocol core) — **COMPLETE AND GREEN**.
- Phase 2 (Accessibility Service injection + clipboard + foreground service) — **NEAR COMPLETE**, compiles + builds + tests green. Input delivery via accessibility service implemented; discrete-key limitation addressed via global actions; FGS hosting added.
- Phase 3 (hardening) — **IN PROGRESS**: reconnect with exponential backoff implemented; service stopSelf on unexpected disconnect; screensaver/options handling stubbed.

## Implemented (Phase 1)
- Protocol layer (`app/src/main/java/com/crrpendlebury/barrierandroid/protocol/`): BarrierClient, BarrierPacket, PacketCodec, BarrierTransport, SocketBarrierTransport, BarrierModels, BarrierCommand, BarrierConstants.
- Android UI layer: MainActivity, AndroidManifest, layout, strings, theme.

## Implemented (Phase 1 wrap-up + Phase 2 this session)
- Coroutines: upgraded `kotlinx-coroutines-test` and `kotlinx-coroutines-android` **1.6.4 → 1.7.3** (offline-cached).
- Tests use `backgroundScope.launch { client.connect(...) }` + `runCurrent()` + `advanceUntilIdle()` (plain `advanceUntilIdle()` alone did not dispatch backgroundScope jobs in coroutines-test 1.7.3 — `runCurrent()` needed).
- Client under test uses `scope = backgroundScope` (keepalive jobs live there, not the test scope, so `runTest` can finish).
- `BarrierPacket.decode` greeting branch fixed: size == 11 → `Hello`; else → `HelloBack(maj, min, name)`.
- BarrierClientTest disconnect expectation fixed: state history is `[CONNECTED, DISCONNECTED]`.

## Implemented (Phase 2 — this continuation session)
1. **Foreground service** (`app/src/main/java/com/crrpendlebury/barrierandroid/BarrierForegroundService.kt`, new):
   - Owns the `BarrierClient` + `CoroutineScope(SupervisorJob() + Dispatchers.IO)` so keepalive/injection survive backgrounding.
   - `onStartCommand`: consumes `BarrierForegroundService.pendingConnection` (host/port/clientName/listener/clipboard handlers set by MainActivity) → starts client connection; calls `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`; returns `START_STICKY`.
   - `onDestroy`: stop client + cancel scope.
   - Companion holds `pendingConnection` + notification constants; notification via `NotificationCompat` + `NotificationChannel` (`ic_barrier_notification` drawable).
2. **Manifest** (`AndroidManifest.xml`): added `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` permissions; registered `BarrierForegroundService` with `android:foregroundServiceType="specialUse"`.
3. **Key injection** (`BarrierAccessibilityService.kt`): added `injectKey(keyCode, action)` and `injectGlobalAction(globalAction)`. Mapping: `KEYCODE_BACK → GLOBAL_ACTION_BACK`, `KEYCODE_HOME → GLOBAL_ACTION_HOME`, `KEYCODE_APP_SWITCH → GLOBAL_ACTION_RECENTS`; unmapped keycodes return false. Implemented via public `AccessibilityService.performGlobalAction` (the only outbound dispatch path available; discrete key dispatch does not exist in compileSdk 35 public API).
   - `MainActivity` key branches (KeyDown/KeyRepeat → `ACTION_DOWN`, KeyUp → `ACTION_UP`) now route through `service.injectKey`.
4. **Clipboard** (`BarrierClient.kt`, `MainActivity.kt`):
   - `BarrierClient.receivedClipboardData` is now public; new `onClipboardData` callback invoked when a `ClipboardDataPacket` (DCLP) arrives.
   - MainActivity passes `onClipboardData` through `PendingConnection` → `handleClipboardData` stores received text into the device `ClipboardManager` (via `ClipData.newPlainText`) on the UI thread.
   - CCLP (`ClipboardGrabPacket`) → `onClipboardGrab` provider → `ClipboardDataPacket` reply (existing) still reads device clipboard via ClipboardManager (`readClipboardBytes`).
5. **Tests**: added `client notifies clipboard data received from server` (DCLP → callback + state retention). BarrierClientTest now **7/7**, PacketCodecTest **16/16** → **23 tests total, 0 failures**.
6. **Text injection** (this continuation, per plan update): added `BarrierAccessibilityService.injectText(text)` using public `AccessibilityNodeInfo.ACTION_SET_TEXT` + `performAction(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ...)` on the focused editable node resolved via `getRootInActiveWindow()` + recursive editable/focused search (depth-capped). MainActivity `handleClipboardData` now calls `service.injectText(text)` in addition to storing the DCLP text into the ClipboardManager.

## Implemented (Phase 3 — this session)
1. **Reconnect with exponential backoff** (`BarrierClient.kt`):
   - `connect()` stores host/port/useTls and delegates to `performConnect()`.
   - `closeTransport()` (server-initiated close) now schedules a reconnect when the client was `CONNECTED`.
   - `scheduleReconnect()` launches a job with `INITIAL_RECONNECT_BACKOFF_MILLIS` (1s) → `MAX_RECONNECT_BACKOFF_MILLIS` (30s), doubling each attempt, skipping retries when `userInitiatedClose` is set.
   - `disconnect()` is user-initiated: sets `userInitiatedClose`, cancels the reconnect job, does not reschedule.
   - `performConnect()` re-opens transport + re-sends Hello, restoring `CONNECTING`/`HANDSHAKE`.
2. **Constants** (`BarrierConstants.kt`): `INITIAL_RECONNECT_BACKOFF_MILLIS`, `MAX_RECONNECT_BACKOFF_MILLIS`.
3. **Service stopSelf on unexpected disconnect** (`BarrierForegroundService.kt`): `clientListener` wrapper forwards lifecycle to the hosting activity and calls `stopSelf()` (main-thread posted) when the client reaches `DISCONNECTED`.
4. **Screensaver + options handling** (`BarrierClient.kt`, `BarrierConstants.kt`):
   - `handleScreenSaver` now stores `screenSaverActive` and invokes `onScreenSaverChanged`.
   - `applyOptions` consumes known option IDs (`OPTION_CLIPBOARD_SHARING`/`CLPS`, `OPTION_HEARTBEAT`/`HART`, `OPTION_SCREEN_SAVER_SYNC`/`SSVR`, `OPTION_RELATIVE_MOUSE_MOVES`/`MDLT`) into state fields and invokes `onOptionsChanged`.
   - `handleResetOptions` resets applied options + derived state to defaults and restarts keepalive.
   - New state: `screenSaverActive`, `appliedOptions`, `clipboardSharingEnabled`, `heartbeatSeconds`, `screenSaverSyncEnabled`; callbacks `onScreenSaverChanged`, `onOptionsChanged`.
   - Option ID constants added to `BarrierConstants.kt` (4-char codes packed big-endian).
5. **Tests**: screensaver notification, options application, options reset. BarrierClientTest **11/11**, PacketCodecTest **16/16** → **27 tests total, 0 failures**.

## Protocol facts encoded (Barrier v2.4.0 / protocol v1.6)
- Frame: u32 **BE** length + payload; command (4 chars; `"Barrier"` greeting = 7 chars), BE int16/int32 params, BE32 string length + UTF-8.
- Greeting decode: payload size 11 → `Hello(maj, min)`; size > 11 → `HelloBack(maj, min, name)` (BE32 name-len at offset 11, name at 15).
- HelloBack encode: `"Barrier"(7) + BE16 maj + BE16 min + BE32 namelen + UTF-8 name`.

## Build / test status
- `./gradlew :app:testDebugUnitTest --offline` → **GREEN: 23/23 tests** (PacketCodecTest 16/16, BarrierClientTest 7/7). Includes new DCLP callback test.
- `./gradlew :app:assembleDebug --offline` → **GREEN / SUCCESSFUL** (full APK built, incl. new FGS + notification icon).

## Fixes applied this session (continuation)
1. Added `BarrierForegroundService` (foreground host for BarrierClient; pending-connection handoff; notification; START_STICKY).
2. Manifest: FGS + `FOREGROUND_SERVICE_SPECIAL_USE` permission/type.
3. Key injection via `performGlobalAction` (global actions); `injectKey` seam added.
4. Clipboard: `onClipboardData` callback + DCLP → ClipboardManager storage.
5. New unit test for DCLP notification.

## Known issues / gotchas
- Environment's `android.jar` (`platforms/android-35`) **lacks** `SSLSocketFactory.createSSLSocket` → TLS socket creation uses reflection.
- Offline builds: use `--offline`; `androidx.appcompat` not cached.
- `ScreenEnterPacket.encode()` buffer size corrected (14 bytes).
- Kotlin nested-class instantiation requires parens: `BarrierPacket.KeepAlivePacket()`, etc.
- **Framework API finding (Phase 2)**: public `AccessibilityService` dispatch surface in compileSdk 35 exposes only `dispatchGesture(GestureDescription, GestureResultCallback?, Handler?)` (final) and `performGlobalAction(int)` for outbound input. `dispatchKeyEvent(KeyEvent)` does **not** exist in this SDK → discrete key injection via accessibility not possible; implemented via global actions (`injectKey` maps `KEYCODE_BACK/HOME/APP_SWITCH` → global actions, returns false otherwise).
- Text injection (implemented this session): `AccessibilityNodeInfo.ACTION_SET_TEXT` + `performAction(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)` on focused editable node (`getRootInActiveWindow` + recursive search) — **public API, chosen simplest approach for typing data into Android apps**.
- `onGesture(int)` override is deprecated → warning only.
- FGS uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` — requires the `FOREGROUND_SERVICE_SPECIAL_USE` permission + manifest type (added); runtime start restrictions apply on Android 14+ (FGS-from-background blocked — start from activity is compliant).

## Immediate next steps
1. UI/service wiring hardening: accessibility service enabled status surfacing, error surfacing.
2. Remaining key injection: arbitrary keycodes (letters, modifiers) still require a non-public injection path (hidden `InputManager`/reflection) — evaluate feasibility vs. physical-keyboard/IME approach.
3. On-device validation of `ACTION_SET_TEXT` injection and screensaver/options handling.
4. File transfer/drag info handling (deferred stubs).

## Handy commands
- Tests: `./gradlew :app:testDebugUnitTest --offline`
- Build: `./gradlew :app:assembleDebug --offline`
- SDK API inspection (offline): `javap -classpath /home/chris/Android/Sdk/platforms/android-35/android.jar android.accessibilityservice.AccessibilityService`
