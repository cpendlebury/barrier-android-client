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

    /** Switch to the Barrier IME if it is enabled. */
    fun useOurs(context: Context) {
        if (!isOursEnabled(context)) {
            BarrierIME.log(context, "ImeSwitcher.useOurs: BarrierIME not enabled")
            return
        }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = windowToken(context)
        if (token == null) {
            BarrierIME.log(context, "ImeSwitcher.useOurs: no window token")
            return
        }
        runCatching {
            imm.setInputMethod(token, IME_ID)
            BarrierIME.log(context, "ImeSwitcher.useOurs: switched to BarrierIME")
        }.onFailure {
            BarrierIME.log(context, "ImeSwitcher.useOurs: setInputMethod failed ${it.javaClass.simpleName}: ${it.message}")
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
        return activity?.window?.decorView?.windowToken
            ?: activity?.window?.attributes?.token
    }
}