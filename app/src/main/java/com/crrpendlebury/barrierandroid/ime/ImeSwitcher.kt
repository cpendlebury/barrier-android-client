package com.crrpendlebury.barrierandroid.ime

import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * Best-effort switching between the system keyboard and the Barrier IME.
 *
 * Android only allows programmatic IME switching when the target IME is
 * already enabled by the user. If BarrierIME is not enabled in settings, these
 * calls are no-ops and the user must select it manually.
 */
object ImeSwitcher {

    private const val IME_ID = "com.crrpendlebury.barrierandroid/.ime.BarrierIME"

    fun isOursEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        return imm.enabledInputMethodList.any { it.id == IME_ID }
    }

    /** Switch to the Barrier IME if it is enabled. */
    fun useOurs(context: Context) {
        if (!isOursEnabled(context)) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = (context as? android.app.Activity)?.window?.attributes?.token
        if (token != null) {
            imm.setInputMethod(token, IME_ID)
        }
    }

    /** Restore the previous (system) keyboard. */
    fun restore(context: Context, leaving: Boolean = false) {
        // Best effort: show the picker so the user can choose; Android does not
        // expose a reliable "previous IME" id without tracking it ourselves.
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = (context as? android.app.Activity)?.window?.attributes?.token
        if (token != null && leaving) {
            runCatching { imm.showInputMethodPicker() }
        }
    }
}