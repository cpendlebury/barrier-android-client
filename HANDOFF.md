# Android Barrier Client — Handoff

## Project
- **Path**: `/home/chris/bin/opencodeexperiments/barrierandroid`
- **Goal**: Android phone as an external keyboard/mouse for a computer running the Barrier server (primary); server → client input delivery; no video (permanently out of scope); clipboard in scope (not optional).
- **Plan**: `/home/chris/.opencode/plan/barrier-android-client.md`

## Current phase status
- Phase 1 (client protocol core) — **COMPLETE AND GREEN** (was failing/wip at handoff; fixed this session).
- Phase 2 (Accessibility Service injection + clipboard) — **IN PROGRESS**, compiles + builds green.

## Implemented (Phase 1)
- Protocol layer (`app/src/main/java/com/crrpendlebury/barrierandroid/protocol/`): BarrierClient, BarrierPacket, PacketCodec, BarrierTransport, SocketBarrierTransport, BarrierModels, BarrierCommand, BarrierConstants.
- Android UI layer: MainActivity, AndroidManifest, layout, strings, theme.
- Tests: PacketCodecTest (16), BarrierClientTest (5), FakeBarrierTransport.

## Implemented (Phase 2 — this session)
- `accessibility/BarrierAccessibilityService.kt` (new): AccessibilityService with gesture-based injection via `dispatchGesture(GestureDescription, GestureResultCallback, Handler)`; public `injectPointer(x, y, action)`; companion `instance` set in `onServiceConnected`.
- `AndroidManifest.xml`: registered `BarrierAccessibilityService` with `BIND_ACCESSIBILITY_SERVICE` permission + `AccessibilityService` intent action.
- `MainActivity.kt`: routes `onInputEvent` → accessibility service injection (mouse move/relative/wheel/button down/up via `injectPointer`); starts service on connect; stops in `onDestroy`; clipboard grab handler wiring (`client.onClipboardGrab`).
- `BarrierClient.kt`: clipboard handling — CCLP (`ClipboardGrabPacket`) triggers registered `onClipboardGrab` provider → replies with DCLP (`ClipboardDataPacket`); DCLP (`ClipboardDataPacket`) received → retained in `receivedClipboardData`.

## Protocol facts encoded (Barrier v2.4.0 / protocol v1.6)
- Frame: u32 **BE** length + payload; command (4 chars; `"Barrier"` greeting = 7 chars), BE int16/int32 params, BE32 string length + UTF-8.
- Greeting decode: payload size 11 → `Hello(maj, min)`; size > 11 → `HelloBack(maj, min, name)` (BE32 name length at offset 11, name at 15).
- HelloBack encode: "Barrier"(7) + BE16 maj + BE16 min + BE32 namelen + UTF-8 name.

## Build / test status
- `:app:compileDebugKotlin` — **green**.
- `:app:compileDebugUnitTestKotlin` — **green**.
- `:app:testDebugUnitTest` — **GREEN: 21 tests, 0 failures** (PacketCodecTest 16/16, BarrierClientTest 5/5). Last run after Phase 2 edits: **BUILD SUCCESSFUL**.
- `:app:assembleDebug` — **GREEN / SUCCESSFUL** (full APK built).

## Fixes applied this session (Phase 1 wrap-up + codec)
1. Upgraded `kotlinx-coroutines-test` and `kotlinx-coroutines-android` **1.6.4 → 1.7.3** (offline-cached).
2. Removed invalid `import kotlinx.coroutines.test.backgroundScope` — it is a **member property of `TestScope`** (resolved via `runTest` receiver), not an importable top-level symbol.
3. Tests use `backgroundScope.launch { client.connect(...) }` + `runCurrent()` + `advanceUntilIdle()` (plain `advanceUntilIdle()` alone did not dispatch backgroundScope jobs in coroutines-test 1.7.3 — `runCurrent()` needed).
4. Client under test uses `scope = backgroundScope` (keepalive jobs live there, not the test scope, so `runTest` can finish).
5. `BarrierPacket.decode` greeting branch fixed: size == 11 → `Hello`; else → `HelloBack(maj, min, name)`.
6. BarrierClientTest disconnect expectation fixed: state history is `[CONNECTED, DISCONNECTED]`.

## Known issues / gotchas
- Environment's `android.jar` (`platforms/android-35`) **lacks** `SSLSocketFactory.createSSLSocket` → TLS socket creation uses reflection.
- Offline builds: use `--offline`; `androidx.appcompat` not cached.
- `ScreenEnterPacket.encode()` buffer size corrected (14 bytes).
- Kotlin nested-class instantiation requires parens: `BarrierPacket.KeepAlivePacket()`, etc.
- **Framework API finding (Phase 2)**: public `AccessibilityService` dispatch surface in compileSdk 35 exposes only `dispatchGesture(GestureDescription, GestureResultCallback?, Handler?): Boolean` (final). `dispatchKeyEvent(KeyEvent)` does **not** exist in this SDK → discrete key injection via accessibility service is **not yet possible**; pointer injection implemented via `dispatchGesture` (`injectPointer(x, y, action)`).
- `onGesture(int)` override is deprecated → warning only.

## Immediate next steps
1. Re-run `./gradlew :app:testDebugUnitTest --offline` → confirm green (done this session, green).
2. Re-run `./gradlew :app:assembleDebug --offline` → full APK build (done this session, green).
3. Debug remaining `helloBackPacketEncodesVersionAndName` codec test failure (fixed this session — greeting decode).
4. **Phase 2 follow-ups**:
   - Key injection path once framework/API allows (or alternative injection mechanism).
   - Foreground service (Android 8+ background start restrictions) for keepalive/injection while not in foreground.
   - Clipboard: wire DCLP-received data into actual paste/injection use; clipboard provider reads via ClipboardManager.
   - UI/service wiring hardening (service enable status, error surfacing).
5. Proceed to **Phase 3** hardening (reconnect/backoff, screensaver/options events, etc.).
