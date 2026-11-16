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
 * Accessibility-based input injection service for the Barrier client.
 *
 * Receives injected [BarrierInputEvent]s from the Barrier client and forwards
 * them into the Android input pipeline via [AccessibilityService.dispatchGesture]:
 *  - pointer events (motion / button / scroll) via gesture dispatch
 *  - discrete key events via framework global action injection (`injectKey`)
 *    (the compileSdk 35 public accessibility API has no discrete key dispatch)
 *
 * The user must enable this service (system -> accessibility -> this app) before
 * injection is accepted by the framework.
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
     * Injects a pointer event (motion / button / scroll) into the system via
     * accessibility gesture dispatch.
     *
     * @return true when the framework accepted and dispatched the gesture.
     */
    fun injectPointer(x: Float, y: Float, action: Int): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gestureDescription = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 0L))
            .setDisplayId(DISPLAY_ID)
            .build()
        return dispatchGesture(gestureDescription, gestureResultCallback, handler)
    }

    /**
     * Injects a discrete key event (down / repeat / up) into the system.
     *
     * The public `AccessibilityService` dispatch surface in compileSdk 35 does
     * not expose a discrete key injection entry point, so the supported keycodes
     * are performed through framework global actions (back / home / recents).
     * Returns true when the framework accepted the injected action.
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
     * Injects text into the currently focused editable text field via the
     * accessibility action [AccessibilityNodeInfo.ACTION_SET_TEXT].
     *
     * This is the core text-delivery path for the barrier client: the server
     * pushes text (via clipboard / DCLP data) and the phone types it into the
     * focused app.
     *
     * @return true when the framework accepted and performed the action.
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

        var instance: BarrierAccessibilityService? = null
            private set

        /** Returns true when this accessibility service is enabled in system settings. */
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
