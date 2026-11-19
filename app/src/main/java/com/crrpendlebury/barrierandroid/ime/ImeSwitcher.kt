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

    /** Finds the BarrierIME in the enabled list, or null if not enabled. */
    private fun findOurs(context: Context): android.view.inputmethod.InputMethodInfo? {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return null
        return imm.enabledInputMethodList.firstOrNull {
            it.id.contains("BarrierIME") || it.id.contains("barrierandroid.ime")
        }
    }

    fun isOursEnabled(context: Context): Boolean = findOurs(context) != null

    /** The full component ID for setInputMethod, or null if not enabled. */
    private fun oursId(context: Context): String? = findOurs(context)?.id

    /** Switch to the Barrier IME if it is enabled. Tries multiple token sources. */
    fun useOurs(context: Context) {
        val id = oursId(context)
        if (id == null) {
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
            imm.setInputMethod(token, id)
            true
        }.getOrDefault(false)
        if (switched) {
            BarrierIME.log(context, "ImeSwitcher.useOurs: setInputMethod($id) succeeded")
        } else {
            BarrierIME.log(context, "ImeSwitcher.useOurs: setInputMethod($id) failed, showing picker")
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