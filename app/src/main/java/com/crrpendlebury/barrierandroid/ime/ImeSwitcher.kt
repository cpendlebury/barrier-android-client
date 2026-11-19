package com.crrpendlebury.barrierandroid.ime

import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * Best-effort switching between the system keyboard and the Barrier IME.
 *
 * Android only allows programmatic IME switching when the target IME is
 * already enabled by the user. If BarrierIME is not enabled in settings, these
 * calls are no-ops and the user must select it manually (see the in-app
 * "Open Keyboard Settings" button).
 */
object ImeSwitcher {

    private const val IME_ID = "com.crrpendlebury.barrierandroid/.ime.BarrierIME"

    fun isOursEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        return imm.enabledInputMethodList.any { it.id == IME_ID }
    }

    /** Switch to the Barrier IME if it is enabled. Tries multiple token sources. */
    fun useOurs(context: Context) {
        if (!isOursEnabled(context)) {
            BarrierIME.log(context, "ImeSwitcher.useOurs: BarrierIME not enabled")
            return
        }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = windowToken(context)
        if (token == null) {
            BarrierIME.log(context, "ImeSwitcher.useOurs: no window token, showing picker")
            showPicker(context)
            return
        }
        val switched = runCatching {
            imm.setInputMethod(token, IME_ID)
            true
        }.getOrDefault(false)
        if (switched) {
            BarrierIME.log(context, "ImeSwitcher.useOurs: switched to BarrierIME via setInputMethod")
        } else {
            BarrierIME.log(context, "ImeSwitcher.useOurs: setInputMethod failed, showing picker")
            showPicker(context)
        }
    }

    /** Shows the system IME picker so the user can select BarrierIME manually. */
    fun showPicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = windowToken(context)
        if (token != null) {
            runCatching { imm.showInputMethodPicker() }
                .onFailure { BarrierIME.log(context, "ImeSwitcher.showPicker: failed ${it.javaClass.simpleName}: ${it.message}") }
            BarrierIME.log(context, "ImeSwitcher.showPicker: shown")
        } else {
            BarrierIME.log(context, "ImeSwitcher.showPicker: no token, cannot show picker")
        }
    }

    /** Restore the previous (system) keyboard. */
    fun restore(context: Context, leaving: Boolean = false) {
        if (!leaving) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = windowToken(context) ?: return
        runCatching {
            @Suppress("DEPRECATION")
            imm.switchToLastInputMethod(token)
            BarrierIME.log(context, "ImeSwitcher.restore: switched back")
        }.onFailure {
            BarrierIME.log(context, "ImeSwitcher.restore: failed ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun windowToken(context: Context): android.os.IBinder? {
        val activity = context as? android.app.Activity
        // Try multiple sources; the decor view token is most reliable.
        return activity?.window?.decorView?.windowToken
            ?: activity?.currentFocus?.windowToken
            ?: activity?.window?.attributes?.token
    }
}