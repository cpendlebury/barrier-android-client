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
import android.view.Gravity
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
        override fun onCompleted(gestureDescription: GestureDescription) {
            gestureInFlight = false
            // Chain the next segment if the drag is still active.
            drag.onSegmentDone()?.let { segment -> dispatchSegment(segment) }
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
            gestureInFlight = false
            drag.onCancelled()
            synchronized(drag) { dragStroke = null }
        }
    }

    /** Dispatches one segment of an in-flight drag, chaining via continueStroke. */
    private fun dispatchSegment(segment: DragCoalescer.Segment) {
        val stroke = dragStroke ?: return
        val path = Path().apply {
            moveTo(segment.fromX, segment.fromY)
            lineTo(segment.toX, segment.toY)
        }
        val newStroke = try {
            stroke.continueStroke(path, 0L, SEGMENT_DURATION_MS, !segment.last)
        } catch (t: Throwable) {
            BarrierAccessibilityService.log(this, "drag continueStroke failed ${t.javaClass.simpleName}: ${t.message}")
            drag.onCancelled()
            dragStroke = null
            return
        }
        dragStroke = if (segment.last) null else newStroke
        gestureInFlight = !segment.last
        val gesture = GestureDescription.Builder()
            .addStroke(newStroke)
            .setDisplayId(DISPLAY_ID)
            .build()
        dispatchGesture(gesture, gestureResultCallback, handler)
    }

    private var windowManager: WindowManager? = null

    private var cursorView: CursorOverlayView? = null

    /**
     * Tracks the last injected pointer position so relative moves and tap
     * gestures have a sensible origin. Updated on every call.
     */
    private var lastX = 0f
    private var lastY = 0f

    /** Tracks the press position and whether the pointer moved enough to be a drag. */
    private var downX = 0f
    private var downY = 0f
    private var dragMoved = false
    /** True while a mouse button is held (between ACTION_DOWN and ACTION_UP). */
    private var buttonDown = false
    private val TAP_MOVE_THRESHOLD_PX = 8f

    /**
     * Drag coalescer (Input Leaf pattern) plus the in-flight stroke. A button
     * press starts a continuing stroke; moves append segments via
     * `continueStroke`; release ends it. Only one segment is in flight at a
     * time; moves that arrive meanwhile update the target and are dispatched
     * when the current segment completes (see [DragCoalescer.onSegmentDone]).
     */
    private val drag = DragCoalescer()
    private var dragStroke: GestureDescription.StrokeDescription? = null
    @Volatile
    private var gestureInFlight = false

    /**
     * Dispatches a gesture for the requested pointer action.
     *
     * `dispatchGesture` creates discrete, fire-and-forget gestures. It cannot
     * hold a button down across calls or track a continuous drag the way a
     * real mouse does. What this does:
     *
     * - [android.view.MotionEvent.ACTION_DOWN]: a tap ([TAP_DURATION_MS] stroke) at (x, y).
     * - [android.view.MotionEvent.ACTION_UP]: no gesture. The tap was already
     *   dispatched on press; a second tap here made every click a double press
     *   (9 became 99, a checkbox toggled twice). The framework has no "release
     *   held button" gesture, so a real hold needs [android.view.InputManager]
     *   injection, which is not public. This only hides the cursor.
     * - [android.view.MotionEvent.ACTION_MOVE]: no gesture. It only updates the
     *   tracked position and moves the cursor. Movement strokes were removed:
     *   they caused side effects (phantom keystrokes, accidental scrolls), and
     *   clicks already land on the tracked position.
     * - [android.view.MotionEvent.ACTION_SCROLL]: a vertical swipe. x/y deltas
     *   are not passed in (the caller only gives a point), so this swipes
     *   down a fixed amount. A real scroll needs axis data, which
     *   [GestureDescription] does not carry.
     * - anything else: treated as a tap at (x, y).
     *
     * [action] is now consulted. Earlier versions ignored it.
     *
     * @return whatever [dispatchGesture] returns, or false when no gesture was
     * dispatched (movement and release).
     * True means the framework accepted the gesture object, not that a
     * pointer event was delivered to an app.
     *
     * The injected motion is also mirrored onto the visible cursor overlay via
     * [showCursor] / [hideCursor]. The overlay is a render surface for position,
     * not a gesture source. Button-down, movement, and scroll show the cursor;
     * button-up hides it.
     */
    fun injectPointer(x: Float, y: Float, action: Int): Boolean {
        // Mirror injected pointer motion onto the visible cursor overlay. The
        // overlay is a render surface only; dispatchGesture below remains the
        // sole gesture-producing path. Movement and button-up hide/show here.
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                BarrierAccessibilityService.log(this, "injectPointer ACTION_DOWN x=$x y=$y")
                showCursor(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                logVerbose(this, "injectPointer ACTION_MOVE x=$x y=$y")
                showCursor(x, y)
            }
            MotionEvent.ACTION_SCROLL -> {
                logVerbose(this, "injectPointer ACTION_SCROLL x=$x y=$y")
                showCursor(x, y)
            }
            MotionEvent.ACTION_UP -> {
                BarrierAccessibilityService.log(this, "injectPointer ACTION_UP lastX=$lastX lastY=$lastY")
                hideCursor()
            }
            else -> Unit
        }

        lastX = x
        lastY = y

        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                dragMoved = false
                buttonDown = true
                // Tap on press (original, proven behaviour).
                val gesture = tapGesture(x, y)
                dispatchGesture(gesture, gestureResultCallback, handler)
            }
            MotionEvent.ACTION_MOVE -> {
                // Only start/continue a drag if a button is actually held.
                // Without this guard, mouse movement alone (no button) triggered
                // a drag stroke starting at (0,0), producing phantom taps.
                if (!buttonDown) {
                    false
                } else if (!dragMoved && distance(downX, downY, x, y) > TAP_MOVE_THRESHOLD_PX) {
                    dragMoved = true
                    drag.onDown(downX, downY)
                    // Opening segment is a line from down to first move, NOT a
                    // stationary tap, so it does not register as an extra click.
                    val opening = Path().apply { moveTo(downX, downY); lineTo(x, y) }
                    val stroke = GestureDescription.StrokeDescription(opening, 0L, SEGMENT_DURATION_MS, true)
                    dragStroke = stroke
                    gestureInFlight = true
                    dispatchGesture(
                        GestureDescription.Builder().addStroke(stroke).setDisplayId(DISPLAY_ID).build(),
                        gestureResultCallback,
                        handler,
                    )
                    true
                } else if (dragMoved) {
                    drag.onMove(x, y)?.let { segment -> dispatchSegment(segment) }
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_UP -> {
                buttonDown = false
                if (dragMoved) {
                    // End the drag stroke.
                    drag.onUp(x, y)?.let { segment -> dispatchSegment(segment) }
                    dragStroke = null
                    gestureInFlight = false
                }
                downX = 0f
                downY = 0f
                dragMoved = false
                hideCursor()
                false
            }
            MotionEvent.ACTION_SCROLL -> {
                val gesture = scrollGesture(x, y)
                dispatchGesture(gesture, gestureResultCallback, handler)
            }
            else -> {
                val gesture = tapGesture(x, y)
                dispatchGesture(gesture, gestureResultCallback, handler)
            }
        }
    }

    /**
     * Performs ACTION_CLICK on the deepest clickable accessibility node under
     * (x, y). Returns true when a node was found and clicked. Used as the
     * primary tap mechanism for standard apps (Files, settings, etc.) where a
     * gesture tap may not register; gesture tap is the fallback when no
     * clickable node is found (games, custom views).
     */
    fun clickNodeAt(x: Float, y: Float): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        for (window in windows) {
            val root = window.root ?: continue
            if (roots.none { it == root }) roots.add(root)
        }
        var best: AccessibilityNodeInfo? = null
        var bestDepth = -1
        var bestArea = Long.MAX_VALUE
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (!rect.contains(x.toInt(), y.toInt())) return
            if (node.isClickable) {
                val area = rect.width().toLong() * rect.height().toLong()
                // Prefer the deepest node; at the same depth prefer the smallest
                // (most specific) clickable element. Large clickable containers
                // (list rows, toolbars, cards) are skipped in favour of a gesture
                // tap, because ACTION_CLICK on them tends to trigger heavier
                // navigation than a normal tap.
                if (depth > bestDepth || (depth == bestDepth && area < bestArea)) {
                    best = node
                    bestDepth = depth
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        for (root in roots) walk(root, 0)
        val target = best
        if (target == null) {
            BarrierAccessibilityService.log(this, "clickNodeAt x=$x y=$y no clickable node")
            return false
        }
        val rect = android.graphics.Rect()
        target.getBoundsInScreen(rect)
        val screenArea = resources.displayMetrics.widthPixels.toLong() * resources.displayMetrics.heightPixels.toLong()
        val nodeArea = rect.width().toLong() * rect.height().toLong()
        // Skip large clickable containers: ACTION_CLICK on them tends to trigger
        // heavier navigation than a normal tap. Fall back to a gesture tap.
        if (nodeArea > screenArea / 5) {
            BarrierAccessibilityService.log(this, "clickNodeAt x=$x y=$y node too large (${nodeArea}px, skipping)")
            return false
        }
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        BarrierAccessibilityService.log(this, "clickNodeAt x=$x y=$y clicked=$clicked")
        return clicked
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Begins a held-button drag: a continuing stroke starting at (x, y) with
     * `willContinue = true`. Subsequent moves append segments via
     * [continueDrag]; release ends it via [endDrag]. A tap with no movement
     * becomes a short stroke from down to up at the same point.
     */
    private fun startDrag(x: Float, y: Float): Boolean {
        if (gestureInFlight) {
            // A previous gesture is still running; skip. Position is tracked
            // by the caller; the next event retries.
            return false
        }
        // Opening stroke: a zero-duration-ish tap at the press point, marked
        // willContinue so moves can extend it.
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, SEGMENT_DURATION_MS, true)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .setDisplayId(DISPLAY_ID)
            .build()
        gestureInFlight = true
        dragStroke = stroke
        drag.onDown(x, y)
        BarrierAccessibilityService.log(this, "drag start x=$x y=$y")
        return dispatchGesture(gesture, gestureResultCallback, handler)
    }

    private fun continueDrag(x: Float, y: Float): Boolean {
        if (!drag.isActive()) {
            // No active drag: nothing to do.
            return false
        }
        val segment = drag.onMove(x, y) ?: return false
        if (gestureInFlight) {
            // Coalesced: segment queued; position tracked by DragCoalescer.
            return false
        }
        dispatchSegment(segment)
        return true
    }

    private fun endDrag(x: Float, y: Float): Boolean {
        if (!drag.isActive()) {
            hideCursor()
            return false
        }
        val segment = drag.onUp(x, y)
        if (segment != null) {
            dispatchSegment(segment)
        } else {
            // Release is pending on the in-flight segment; it will be dispatched
            // from onSegmentDone when that segment completes.
            gestureInFlight = gestureInFlight // unchanged
        }
        BarrierAccessibilityService.log(this, "drag end x=$x y=$y")
        hideCursor()
        return true
    }

    private fun tapGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
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
     * A failed add is logged as `cursor overlay addView failed` and skipped. It
     * never throws, because an exception here would crash the app on the main
     * looper.
     *
     * The overlay view and its window manager are hosted on the service
     * context; the application context does not carry the window token the
     * accessibility overlay requires (the add then fails with
     * BadTokenException: token null is not valid).
     */
    fun showCursor(x: Float, y: Float) {
        handler.post {
            val wm = resolveWindowManager()
            val view = cursorView
            if (view == null) {
                val params = createCursorLayoutParams()
                val overlay = CursorOverlayView(this)
                try {
                    wm.addView(overlay, params)
                } catch (t: Throwable) {
                    // Logged and skipped, not rethrown: this runs on the main
                    // looper, so a rethrow would crash the whole app and drop the
                    // Barrier connection. cursorView stays null, so the next
                    // movement or click retries the add.
                    BarrierAccessibilityService.log(
                        this,
                        "cursor overlay addView failed ${t.javaClass.simpleName}: ${t.message}",
                    )
                    return@post
                }
                cursorView = overlay
                overlay.setVisibility(View.VISIBLE)
                BarrierAccessibilityService.log(
                    this,
                    "cursor overlay addView ok attached=${overlay.isAttachedToWindow} type=${params.type} flags=${params.flags} visibility=${overlay.visibility}",
                )
            }
            cursorView?.updatePosition(x, y)
            logVerbose(this, "cursor overlay show x=$x y=$y")
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
            // Anchor to the top-left so x and y are absolute window offsets.
            // With the default centre gravity the system shifts the window by
            // (display - window) / 2, which put it at -3000 instead of -1000 on
            // device and pushed the arrow off the left and top edges.
            gravity = Gravity.TOP or Gravity.START
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

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            BarrierAccessibilityService.log(this@BarrierAccessibilityService, "cursor overlay attached to window")
        }

        override fun onDetachedFromWindow() {
            BarrierAccessibilityService.log(this@BarrierAccessibilityService, "cursor overlay detached from window")
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            BarrierAccessibilityService.log(
                this@BarrierAccessibilityService,
                "cursor overlay size w=$w h=$h",
            )
        }

        override fun onDraw(canvas: Canvas) {
            val location = IntArray(2)
            getLocationOnScreen(location)
            logVerbose(
                this@BarrierAccessibilityService,
                "cursor overlay draw x=$positionX y=$positionY w=$width h=$height " +
                    "screenX=${location[0]} screenY=${location[1]} " +
                    "windowVisibility=$windowVisibility shown=$isShown",
            )
            if (DEBUG_OVERLAY_FILL) {
                canvas.drawColor(DEBUG_OVERLAY_FILL_COLOR)
            }
            val density = resources.displayMetrics.density
            val scale = CURSOR_SCALE * density
            val path = Path().apply {
                val points = CURSOR_OUTLINE_POINTS
                moveTo(points[0] * scale, points[1] * scale)
                var index = 2
                while (index < points.size) {
                    lineTo(points[index] * scale, points[index + 1] * scale)
                    index += 2
                }
                close()
            }
            cursorOutlinePaint.strokeWidth = CURSOR_OUTLINE_DP * density
            canvas.save()
            canvas.translate(positionX - CURSOR_WINDOW_X, positionY - CURSOR_WINDOW_Y)
            canvas.drawPath(path, cursorFillPaint)
            canvas.drawPath(path, cursorOutlinePaint)
            canvas.restore()
        }
    }

    companion object {
        private const val DISPLAY_ID = 0
        private const val MAX_EDITABLE_NODE_SEARCH_DEPTH = 8
        /**
         * Duration of a tap gesture. `StrokeDescription` rejects a non-positive
         * duration with `IllegalArgumentException: Duration must be positive`, so
         * a zero-length tap crashed the app on the first click. Keep this above 0.
         */
        private const val TAP_DURATION_MS = 10L
        private const val SEGMENT_DURATION_MS = 10L
        private const val SCROLL_STROKE_DURATION_MS = 100L
        private const val SCROLL_DISTANCE_PX = 200f
        private val CURSOR_WINDOW_MARGIN_PX = 4000f
        private const val CURSOR_WINDOW_X = -1000
        private const val CURSOR_WINDOW_Y = -1000
        /**
         * Classic pointer outline in design units (tip at the origin, about 12 by
         * 19 units), as x, y pairs. Scaled by [CURSOR_SCALE] and display density.
         */
        private val CURSOR_OUTLINE_POINTS = floatArrayOf(
            0f, 0f,
            0f, 16f,
            4.2f, 12.4f,
            7f, 18.8f,
            9.4f, 17.8f,
            6.6f, 11.6f,
            11.6f, 11.6f,
        )
        private const val CURSOR_SCALE = 1.3f
        private const val CURSOR_OUTLINE_DP = 1.5f

        /**
         * Diagnostic only. When true, the overlay fills its whole window with a
         * translucent red colour on every draw, so a visible result proves the
         * window reaches the screen and the arrow's position is the remaining
         * question. Must be set to false once the cursor is confirmed visible.
         */
        /**
         * Diagnostic only. When true, per-event pointer and overlay lines (every
         * injected motion, every overlay show, every draw) go to the debug log.
         * Each call opens and appends to the file on the calling thread, so this
         * is too costly for normal use and is off by default. Lifecycle lines
         * (addView, attach, detach, size, hide) are always logged.
         */
        private const val VERBOSE_CURSOR_LOG = false

        private fun logVerbose(context: Context, message: String) {
            if (VERBOSE_CURSOR_LOG) {
                log(context, message)
            }
        }

        private const val DEBUG_OVERLAY_FILL = false
        private const val DEBUG_OVERLAY_FILL_COLOR = 0x80FF0000.toInt()

        /**
         * Cursor fill is white and the outline is black, as on a desktop pointer,
         * so the arrow stays visible on both light and dark content. A white-only
         * arrow disappeared on white backgrounds.
         */
        private val cursorFillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        private val cursorOutlinePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
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
