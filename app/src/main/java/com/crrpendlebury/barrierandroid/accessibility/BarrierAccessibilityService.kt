package com.crrpendlebury.barrierandroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that is supposed to apply Barrier input on the phone.
 *
 * Direction of control: the computer's keyboard and mouse are the source.
 * This service is the sink. It does not capture the phone's own input to send
 * upstream. [onKeyEvent] and [onMotionEvent] return without consuming events.
 *
 * The system starts an accessibility service only after the user enables it.
 * This app's manifest does not declare the required
 * `android.accessibilityservice` meta-data resource, and there is no
 * config XML under `res/xml/`. Android will not list this class in
 * Settings → Accessibility, so [instance] stays null in a normal install.
 * [isEnabled] only reads `ENABLED_ACCESSIBILITY_SERVICES`; it cannot enable
 * the service.
 *
 * Even if the system did bind it, the inject methods are not a working input
 * path. Read each method's KDoc before calling it from new code. The public
 * compileSdk 35 surface has `dispatchGesture` and `performGlobalAction`, and
 * no `dispatchKeyEvent`.
 */
class BarrierAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?): Unit = Unit

    override fun onInterrupt() = Unit

    override fun onGesture(gestureId: Int): Boolean = false

    override fun onKeyEvent(event: KeyEvent?): Boolean = false

    override fun onMotionEvent(event: MotionEvent): Unit { }

    override fun onServiceConnected() {
        instance = this
    }

    private val handler = Handler(Looper.getMainLooper())

    private val gestureResultCallback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) = Unit

        override fun onCancelled(gestureDescription: GestureDescription) = Unit
    }

    /**
     * Tracks the last injected pointer position so relative moves and tap
     * gestures have a sensible origin. Updated on every call.
     */
    private var lastX = 0f
    private var lastY = 0f

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
     * - [android.view.MotionEvent.ACTION_MOVE]: a short stroke from the last
     *   position to (x, y). Duration is [POINTER_STROKE_DURATION_MS].
     * - [android.view.MotionEvent.ACTION_SCROLL]: a vertical swipe. x/y deltas
     *   are not passed in (the caller only gives a point), so this swipes
     *   down a fixed amount. A real scroll needs axis data, which
     *   [GestureDescription] does not carry.
     * - anything else: treated as a tap at (x, y).
     *
     * [action] is now consulted. Earlier versions ignored it.
     *
     * @return whatever [dispatchGesture] returns. True means the framework
     * accepted the gesture object, not that a pointer event was delivered to
     * an app.
     */
    fun injectPointer(x: Float, y: Float, action: Int): Boolean {
        val gesture = when (action) {
            MotionEvent.ACTION_DOWN -> tapGesture(x, y)
            MotionEvent.ACTION_UP -> tapGesture(lastX, lastY)
            MotionEvent.ACTION_MOVE -> strokeGesture(lastX, lastY, x, y)
            MotionEvent.ACTION_SCROLL -> scrollGesture(x, y)
            else -> tapGesture(x, y)
        }
        lastX = x
        lastY = y
        return dispatchGesture(gesture, gestureResultCallback, handler)
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

    companion object {
        private const val DISPLAY_ID = 0
        private const val MAX_EDITABLE_NODE_SEARCH_DEPTH = 8
        private const val POINTER_STROKE_DURATION_MS = 50L
        private const val SCROLL_STROKE_DURATION_MS = 100L
        private const val SCROLL_DISTANCE_PX = 200f

        var instance: BarrierAccessibilityService? = null
            private set

        /**
         * True when secure settings list this component as an enabled
         * accessibility service.
         *
         * False on a normal install, because the manifest does not register
         * the service config Android needs to offer it to the user. A true
         * result does not mean [instance] is non-null: the setting can be
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
