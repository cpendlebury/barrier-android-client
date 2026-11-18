package com.crrpendlebury.barrierandroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream

/**
 * Accessibility service that is supposed to apply Barrier input on the phone.
 *
 * Direction of control: the computer's keyboard and mouse are the source.
 * This service is the sink. It does not capture the phone's own input to send
 * upstream. [onKeyEvent] and [onMotionEvent] return without consuming events.
 *
 * The system starts an accessibility service only after the user enables it.
 * The manifest registers this class with the `android.accessibilityservice`
 * meta-data resource pointing at `res/xml/accessibility_service_config.xml`
 * (canRetrieveWindowContent and canPerformGestures enabled), so Android lists
 * it in Settings → Accessibility. [isEnabled] only reads
 * `ENABLED_ACCESSIBILITY_SERVICES`; it cannot enable the service.
 *
 * Even if the system did bind it, the inject methods are not a real input path.
 * Read each method's KDoc before calling it from new code. The public compileSdk
 * 35 surface has `dispatchGesture` and `performGlobalAction`, and no
 * `dispatchKeyEvent`.
 *
 * Pointer injection renders a visible cursor. [showCursor] and [hideCursor]
 * add or remove a translucent overlay window that draws a standard arrow; the
 * overlay mirrors injected position but is a rendering surface only. [injectPointer]
 * remains the sole place that turns Barrier pointer traffic into framework
 * gestures. The cursor is hidden when the client disconnects or the screensaver
 * starts, and subsequent injected input re-shows it.
 */
class BarrierAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?): Unit = Unit

    override fun onInterrupt() = Unit

    override fun onGesture(gestureId: Int): Boolean = false

    override fun onKeyEvent(event: KeyEvent?): Boolean = false

    override fun onMotionEvent(event: MotionEvent): Unit { }

    override fun onDestroy() {
        // Release the cursor overlay window if it was attached.
        hideCursor()
        super.onDestroy()
    }

    override fun onServiceConnected() {
        instance = this
    }

    private val handler = Handler(Looper.getMainLooper())

    private val gestureResultCallback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) = Unit

        override fun onCancelled(gestureDescription: GestureDescription) = Unit
    }

    private var windowManager: WindowManager? = null

    private var cursorView: CursorOverlayView? = null

    /**
     * Tracks the last injected pointer position so relative moves and tap
     * gestures have a sensible origin. Updated on every call.
     */
    private var lastX = 0f
    private var lastY = 0f

    /**
     * Tracks the last position for which a gesture was actually dispatched.
     * Used to throttle move gestures: only send when the pointer has moved
     * more than [MOVE_THROTTLE_PX] from the last dispatched position.
     */
    private var lastDispatchedX = 0f
    private var lastDispatchedY = 0f

    /**
     * When false, pure movement (ACTION_MOVE) does not create a gesture.
     * Button presses, releases, and scrolls still do. This eliminates
     * phantom keystrokes on on-screen keyboards caused by movement
     * strokes passing over key areas. Toggle via [mouseMovementEnabled].
     */
    @Volatile
    var mouseMovementEnabled: Boolean = true

    /**
     * Dispatches a gesture for the requested pointer action.
     *
     * `dispatchGesture` creates discrete, fire-and-forget gestures. It cannot
     * hold a button down across calls or track a continuous drag the way a
     * real mouse does. What this does:
     *
     * - [android.view.MotionEvent.ACTION_DOWN]: a tap (0 ms stroke) at (x, y).
     * - [android.view.MotionEvent.ACTION_UP]: a tap at the last position.
     *   The framework has no "release held button" gesture, so this is a
     *   best-effort tap. A real hold needs [android.view.InputManager]
     *   injection, which is not public.
     * - [android.view.MotionEvent.ACTION_MOVE]: if [mouseMovementEnabled] is
     *   true and the pointer has moved more than [MOVE_THROTTLE_PX] from
     *   the last dispatched position, a short stroke (duration
     *   [POINTER_STROKE_DURATION_MS]) from the last position to (x, y).
     *   Otherwise returns false without dispatching (throttled / disabled).
     * - [android.view.MotionEvent.ACTION_SCROLL]: a vertical swipe. x/y deltas
     *   are not passed in (the caller only gives a point), so this swipes
     *   down a fixed amount. A real scroll needs axis data, which
     *   [GestureDescription] does not carry.
     * - anything else: treated as a tap at (x, y).
     *
     * [action] is now consulted. Earlier versions ignored it.
     *
     * @return whatever [dispatchGesture] returns, or false if throttled/disabled.
     * True means the framework accepted the gesture object, not that a
     * pointer event was delivered to an app.
     *
     * The injected motion is also mirrored onto the visible cursor overlay via
     * [showCursor] / [hideCursor]. The overlay is a render surface for position,
     * not a gesture source. Button-down, movement (when [mouseMovementEnabled]),
     * and scroll show the cursor; button-up hides it.
     */
    fun injectPointer(x: Float, y: Float, action: Int): Boolean {
        val gesture = when (action) {
            MotionEvent.ACTION_DOWN -> tapGesture(x, y)
            MotionEvent.ACTION_UP -> tapGesture(lastX, lastY)
            MotionEvent.ACTION_MOVE -> {
                if (!mouseMovementEnabled) {
                    lastX = x
                    lastY = y
                    return false
                }
                val dx = x - lastDispatchedX
                val dy = y - lastDispatchedY
                if (dx * dx + dy * dy < MOVE_THROTTLE_PX * MOVE_THROTTLE_PX) {
                    lastX = x
                    lastY = y
                    return false
                }
                strokeGesture(lastX, lastY, x, y)
            }
            MotionEvent.ACTION_SCROLL -> scrollGesture(x, y)
            else -> tapGesture(x, y)
        }
        lastX = x
        lastY = y
        if (action == MotionEvent.ACTION_MOVE) {
            lastDispatchedX = x
            lastDispatchedY = y
        }
        val dispatched = dispatchGesture(gesture, gestureResultCallback, handler)

        // Mirror injected pointer motion onto the visible cursor overlay. The
        // overlay is a render surface only; dispatchGesture above remains the
        // sole gesture-producing path. Button-down, movement, and scroll show
        // the cursor; button-up hides it. Movement is suppressed by
        // [mouseMovementEnabled], in which case only button actions act.
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                BarrierAccessibilityService.log(this, "injectPointer ACTION_DOWN x=$x y=$y")
                showCursor(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mouseMovementEnabled) {
                    BarrierAccessibilityService.log(this, "injectPointer ACTION_MOVE x=$x y=$y")
                    showCursor(x, y)
                }
            }
            MotionEvent.ACTION_SCROLL -> {
                BarrierAccessibilityService.log(this, "injectPointer ACTION_SCROLL x=$x y=$y")
                showCursor(x, y)
            }
            MotionEvent.ACTION_UP -> {
                BarrierAccessibilityService.log(this, "injectPointer ACTION_UP")
                hideCursor()
            }
            else -> Unit
        }
        return dispatched
    }

    private fun tapGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 0L))
            .setDisplayId(DISPLAY_ID)
            .build()
    }

    private fun strokeGesture(fromX: Float, fromY: Float, toX: Float, toY: Float): GestureDescription {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, POINTER_STROKE_DURATION_MS))
            .setDisplayId(DISPLAY_ID)
            .build()
    }

    private fun scrollGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y - SCROLL_DISTANCE_PX)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SCROLL_STROKE_DURATION_MS))
            .setDisplayId(DISPLAY_ID)
            .build()
    }

    /**
     * Maps three Android key codes onto global actions. Everything else returns false.
     *
     * [keyCode] is compared to [android.view.KeyEvent.KEYCODE_BACK],
     * [android.view.KeyEvent.KEYCODE_HOME], and
     * [android.view.KeyEvent.KEYCODE_APP_SWITCH]. [BarrierInputEvent.KeyDown.keyId]
     * is a Barrier key id, not one of those constants, so server key traffic
     * takes the `else` branch. [action] is ignored: down, repeat, and up would
     * fire the same global action if a code ever matched. Global actions are
     * not key-down/key-up pairs.
     *
     * There is no public discrete-key injection API on this compileSdk. Do not
     * add a call to `dispatchKeyEvent`; it is not on [AccessibilityService].
     * Arbitrary key injection would require reflection on the hidden
     * `android.hardware.input.InputManager` / `IInputManager` binder, which
     * is version-dependent and may require system signature. This is not
     * implemented.
     *
     * @return true only when [performGlobalAction] accepts one of the three
     * mapped codes.
     */
    fun injectKey(keyCode: Int, action: Int): Boolean {
        val globalAction = when (keyCode) {
            KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return false
        }
        return performGlobalAction(globalAction)
    }

    /**
     * Injects a framework global action (back / home / recents / quick settings /
     * notification shade / dpad / screenshot) via the accessibility service.
     *
     * @return true when the framework accepted the action.
     */
    fun injectGlobalAction(globalAction: Int): Boolean = performGlobalAction(globalAction)

    /**
     * Shows the on-screen pointer cursor at (x, y) by adding a translucent
     * overlay window to the accessibility window manager. The window is hosted
     * here (the Red Moon `AccessibilityFilterService` pattern) so the cursor
     * outlives the activity and the foreground service. The overlay draws a
     * standard arrow; it mirrors position but is not a source of pointer events.
     *
     * The window params mirror Red Moon's shipped overlay: a translucent window
     * sized to the display plus margin, offset by [CURSOR_WINDOW_X] / [CURSOR_WINDOW_Y]
     * so it covers the full display, typed `TYPE_ACCESSIBILITY_OVERLAY` while the
     * service is enabled (application overlay on newer APIs, system overlay
     * below), flagged `NOT_TOUCHABLE | NOT_FOCUSABLE | LAYOUT_NO_LIMITS |
     * LAYOUT_IN_SCREEN`, translucent format, and cutout mode. The arrow tip is
     * placed at the injected position.
     *
     * Runs on the main thread via the service handler, because
     * [android.view.WindowManager.addView] must be called on the main thread.
     * If an overlay is already attached it is repositioned; otherwise one is
     * created. Safe to call when no overlay exists or the service is not bound.
     */
    fun showCursor(x: Float, y: Float) {
        handler.post {
            BarrierAccessibilityService.log(this, "cursor overlay show x=$x y=$y")
            val wm = resolveWindowManager()
            val view = cursorView
            if (view == null) {
                val params = createCursorLayoutParams()
                val overlay = CursorOverlayView(this)
                try {
                    wm.addView(overlay, params)
                    cursorView = overlay
                } catch (t: Throwable) {
                    // Capture window-add failures in the debug sink before
                    // rethrowing, so a failing overlay add is traceable.
                    BarrierAccessibilityService.log(
                        this,
                        "cursor overlay addView failed ${t.javaClass.simpleName}: ${t.message}",
                    )
                    throw t
                }
                BarrierAccessibilityService.log(
                    this,
                    "cursor overlay addView ok attached=${overlay.isAttachedToWindow} type=${params.type} flags=${params.flags}",
                )
            }
            cursorView?.updatePosition(x, y)
            BarrierAccessibilityService.log(this, "cursor overlay show x=$x y=$y")
        }
    }

    /**
     * Hides the on-screen pointer cursor by removing its overlay window.
     * Runs on the main thread via the service handler. Called from
     * [injectPointer] when a button is released, from the client state listener
     * on disconnect, and from the screensaver callback.
     */
    fun hideCursor() {
        handler.post {
            val view = cursorView
            if (view != null) {
                val wm = resolveWindowManager()
                runCatching { wm.removeView(view) }
            }
            cursorView = null
            BarrierAccessibilityService.log(this, "cursor overlay hide")
        }
    }

    private fun resolveWindowManager(): WindowManager {
        val existing = windowManager
        if (existing != null) {
            return existing
        }
        val created = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = created
        return created
    }

    /**
     * Builds the cursor overlay window params. Mirrors Red Moon's shipped
     * overlay window (`Overlay.mLayoutParams`): a translucent window sized to
     * the display plus margin, offset by [CURSOR_WINDOW_X] / [CURSOR_WINDOW_Y]
     * so it covers the full display, typed `TYPE_ACCESSIBILITY_OVERLAY` while
     * the service is enabled (application overlay on newer APIs, system overlay
     * below), flagged `NOT_TOUCHABLE | NOT_FOCUSABLE | LAYOUT_NO_LIMITS |
     * LAYOUT_IN_SCREEN`, translucent format, and cutout mode. Uses the
     * no-argument constructor plus field assignment, as Red Moon does, because
     * the convenience constructors shift their arguments.
     */
    private fun createCursorLayoutParams(): WindowManager.LayoutParams {
        val metrics = getResources().displayMetrics
        return WindowManager.LayoutParams().apply {
            width = (metrics.widthPixels + CURSOR_WINDOW_MARGIN_PX).toInt()
            height = (metrics.heightPixels + CURSOR_WINDOW_MARGIN_PX).toInt()
            x = CURSOR_WINDOW_X
            y = CURSOR_WINDOW_Y
            type = cursorWindowType()
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                .or(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                .or(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                .or(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            format = PixelFormat.TRANSLUCENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Red Moon's overlay window type ladder: accessibility overlay when the
     * service is enabled (API >= 23), application overlay on newer APIs, system
     * overlay below that. While this service is bound and enabled, the
     * accessibility branch is the live path.
     */
    private fun cursorWindowType(): Int {
        val accessibilityOverlayAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        val applicationOverlayAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        return when {
            accessibilityOverlayAvailable && BarrierAccessibilityService.isEnabled(this) ->
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            applicationOverlayAvailable ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else ->
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
    }

    /**
     * Replaces the text of the focused editable node with [text].
     *
     * This is not keystroke injection. `ACTION_SET_TEXT` sets the field
     * contents. There is no public "insert text at cursor" action, so
     * appending requires reading the current text, concatenating, and
     * setting the whole string. This method does not append; it replaces.
     * It is only useful after a clipboard payload, which is the only caller.
     * It requires `getRootInActiveWindow()` to return a tree, which requires
     * `canRetrieveWindowContent` in the service config (now declared).
     *
     * The walk stops at depth [MAX_EDITABLE_NODE_SEARCH_DEPTH] and does not
     * recycle [AccessibilityNodeInfo] instances. A match must be both
     * [AccessibilityNodeInfo.isEditable] and [AccessibilityNodeInfo.isFocused].
     * A focused field deeper than that, or an editable field that is not
     * focused, is a miss and returns false.
     *
     * @return true when [AccessibilityNodeInfo.performAction] reports that the
     * node accepted `ACTION_SET_TEXT`.
     */
    fun injectText(text: CharSequence): Boolean {
        val root = getRootInActiveWindow() ?: return false
        val node = findEditableFocusedNode(root) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findEditableFocusedNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_EDITABLE_NODE_SEARCH_DEPTH) {
            return null
        }
        if (node.isEditable && node.isFocused) {
            return node
        }
        val childCount = node.childCount
        for (index in 0 until childCount) {
            val child = node.getChild(index) ?: continue
            findEditableFocusedNode(child, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Translucent overlay window that draws the visible pointer cursor. Added
     * via [WindowManager.addView] from the service and removed via
     * [WindowManager.removeView]; added/removed on the main thread via the
     * service handler. [FLAG_NOT_TOUCHABLE] lets touches fall through to the
     * app beneath, and [FLAG_NOT_FOCUSABLE] keeps the window out of the focus
     * path.
     */
    private inner class CursorOverlayView(context: Context) : View(context) {
        private var positionX = 0f
        private var positionY = 0f

        fun updatePosition(x: Float, y: Float) {
            positionX = x
            positionY = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            BarrierAccessibilityService.log(
                this@BarrierAccessibilityService,
                "cursor overlay draw x=${positionX} y=${positionY}",
            )
            val width = CURSOR_WIDTH_PX
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(width - ARROW_SIDE_INSET_PX, ARROW_BASE_HEIGHT_PX)
                lineTo(ARROW_SIDE_INSET_PX, ARROW_BASE_HEIGHT_PX)
                close()
            }
            canvas.save()
            canvas.translate(positionX - CURSOR_WINDOW_X, positionY - CURSOR_WINDOW_Y)
            canvas.drawPath(path, cursorPaint)
            canvas.restore()
        }
    }

    companion object {
        private const val DISPLAY_ID = 0
        private const val MAX_EDITABLE_NODE_SEARCH_DEPTH = 8
        private const val POINTER_STROKE_DURATION_MS = 20L
        private const val SCROLL_STROKE_DURATION_MS = 100L
        private const val SCROLL_DISTANCE_PX = 200f
        private const val MOVE_THROTTLE_PX = 8f
        private val CURSOR_WINDOW_MARGIN_PX = 4000f
        private const val CURSOR_WINDOW_X = -1000
        private const val CURSOR_WINDOW_Y = -1000
        private val CURSOR_WIDTH_PX = 48f
        private val ARROW_SIDE_INSET_PX = 6f
        private val ARROW_BASE_HEIGHT_PX = 40f

        private val cursorPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
        }

        private const val LOG_FILE_NAME = "barrier_debug.log"

        /**
         * Appends one timestamped line to the in-app debug log file. The file
         * lives in the app's external files directory so it can be read without
         * adb. A write failure is swallowed because the debug sink must never
         * take down the pointer injection path.
         */
        fun log(context: Context, message: String) {
            try {
                val dir = context.getExternalFilesDir(null) ?: return
                val file = File(dir, LOG_FILE_NAME)
                FileOutputStream(file, true).use { stream ->
                    stream.write(("$message\n").toByteArray(Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                // Debug sink only: a log write failure must not interrupt input.
            }
        }

        var instance: BarrierAccessibilityService? = null
            private set

        /**
         * True when secure settings list this component as an enabled
         * accessibility service.
         *
         * The manifest registers this class with the `android.accessibilityservice`
         * meta-data resource pointing at `res/xml/accessibility_service_config.xml`,
         * so Android can list it in Settings → Accessibility. [isEnabled] only
         * reads `ENABLED_ACCESSIBILITY_SERVICES`; it cannot enable the service.
         * A true result does not mean [instance] is non-null: the setting can be
         * stale relative to process death, and this method does not bind
         * the service.
         */
        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = ComponentName(context, BarrierAccessibilityService::class.java).flattenToString()
            return enabledServices.split(":").any { it.equals(expected, ignoreCase = true) }
        }

        /** Launches the system accessibility settings so the user can enable this service. */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
