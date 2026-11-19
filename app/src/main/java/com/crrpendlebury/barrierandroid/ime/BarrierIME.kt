package com.crrpendlebury.barrierandroid.ime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.crrpendlebury.barrierandroid.accessibility.BarrierAccessibilityService

/**
 * Barrier input method. The computer runs the Barrier server and owns the
 * keyboard and mouse; this IME receives Barrier key events and injects them
 * into the focused Android text field via [InputConnection].
 *
 * Direction of control is unchanged: this app is the phone-side client. The IME
 * is only the delivery mechanism for server keystrokes; it does not turn the
 * phone into a keyboard for the computer.
 *
 * Public methods are called from [com.crrpendlebury.barrierandroid.MainActivity]
 * when Barrier key packets arrive. If no input connection is available (no
 * focused editable field), the call is a no-op and is logged.
 */
class BarrierIME : InputMethodService() {

    companion object {
        private const val TAG = "BarrierIME"

        @Volatile
        var instance: BarrierIME? = null
            private set

        fun log(context: android.content.Context, message: String) {
            BarrierAccessibilityService.log(context, message)
        }
    }

    /** Accumulated modifier state (shift/ctrl/alt/meta) for the current key. */
    @Volatile
    var metaState: Int = 0
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        log(this, "BarrierIME onCreate")
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        log(this, "BarrierIME onStartInput restarting=$restarting hasConnection=${currentInputConnection != null}")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        log(this, "BarrierIME onDestroy")
        super.onDestroy()
    }

    /** The active input connection, or null if no editable field is focused. */
    val connection: InputConnection?
        get() = currentInputConnection

    /** Commits [text] into the focused field. */
    fun commitText(text: CharSequence) {
        val ic = currentInputConnection
        if (ic == null) {
            log(this, "commitText: no InputConnection available")
            return
        }
        ic.commitText(text, 1)
        log(this, "commitText: '$text'")
    }

    /** Sends a key event (down or up) into the focused field. */
    fun sendKey(keyCode: Int, meta: Int = 0, isDown: Boolean) {
        val ic = currentInputConnection
        if (ic == null) {
            log(this, "sendKey: no InputConnection available keyCode=$keyCode")
            return
        }
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val eventTime = SystemClock.uptimeMillis()
        val event = KeyEvent(
            eventTime, eventTime,
            action, keyCode, 0, meta,
            -1, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        )
        ic.sendKeyEvent(event)
        log(this, "sendKey: action=$action keyCode=$keyCode meta=$meta")
    }

    /** Updates the accumulated modifier state from a Barrier modifier mask. */
    fun updateModifiers(mask: Int) {
        var m = 0
        if (mask and 0x0001 != 0) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (mask and 0x0002 != 0) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (mask and 0x0004 != 0) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (mask and 0x0008 != 0) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        metaState = m
        log(this, "updateModifiers mask=$mask metaState=$m")
    }

    /** Clears modifier state (e.g. on key up of a modifier). */
    fun clearModifiers() {
        metaState = 0
    }
}