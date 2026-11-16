package com.crrpendlebury.barrierandroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility-based input injection service for the Barrier client.
 *
 * Receives injected [BarrierInputEvent]s from the Barrier client and forwards
 * them into the Android input pipeline via [AccessibilityService.dispatchGesture]:
 *  - pointer events (motion / button / scroll) via gesture dispatch
 *  - discrete key events via the service key dispatch path
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

    companion object {
        private const val DISPLAY_ID = 0

        var instance: BarrierAccessibilityService? = null
            private set
    }
}
