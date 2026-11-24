/*
 * Host keyboard shortcut matching for the Barrier Android client.
 *
 * Role: maps a host key chord (Barrier key id plus Barrier modifier mask) onto
 * a phone system action, so Meta/Alt chords such as Meta+H (Home) or Alt+Tab
 * (recents) act like they do on a desktop. The computer runs the Barrier server
 * and owns the keyboard; this file only decides what a chord means on the phone.
 *
 * Known gaps: only the chords listed in [HostShortcuts.match] are recognised.
 * The Meta key alone (app drawer), Meta+A (assistant), and Meta+/ (keyboard
 * shortcuts) have no public global action on compileSdk 35 and are not mapped.
 * Shift is ignored when matching, so Meta+Shift+S is treated as Meta+S.
 */
package com.crrpendlebury.barrierandroid.ime

/** A phone system action that a host chord can trigger. */
enum class HostShortcut {
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    LOCK_SCREEN,
    SCREENSHOT,
}

object HostShortcuts {

    // Barrier modifier mask bits (`KeyModifierShift` ... `KeyModifierSuper` in
    // Barrier's key_types.h). Meta and Super are both the host's Windows or
    // Command key, depending on the host OS, so either one counts as Meta.
    private const val MASK_CONTROL = 0x0002
    private const val MASK_ALT = 0x0004
    private const val MASK_META = 0x0008
    private const val MASK_SUPER = 0x0010

    // X11 keysyms minus 0x1000 (Barrier key ids). Print is `kKeyPrint`.
    private const val KEY_PRINT = 0xEF61
    private const val KEY_TAB = 0xEF09
    private const val KEY_RETURN = 0xEF0D

    /**
     * Returns the phone action for a host chord, or null when the chord is not
     * a shortcut and should be delivered as ordinary key input.
     *
     * Callers invoke this on key-down only. The key-up of a matched chord must be
     * swallowed by the caller so the letter is not typed, and key repeats must
     * not fire the action twice.
     *
     * @param keyId Barrier key id; letters may arrive as lowercase or uppercase.
     * @param mask Barrier modifier mask as sent in the key packet.
     */
    fun match(keyId: Int, mask: Int): HostShortcut? {
        val meta = (mask and (MASK_META or MASK_SUPER)) != 0
        val control = (mask and MASK_CONTROL) != 0
        val alt = (mask and MASK_ALT) != 0
        val letter = letterOf(keyId)

        if (keyId == KEY_PRINT) return HostShortcut.SCREENSHOT
        if (meta && control && letter == 's') return HostShortcut.SCREENSHOT
        if (control) return null

        return when {
            meta && letter == 'h' -> HostShortcut.HOME
            meta && keyId == KEY_RETURN -> HostShortcut.HOME
            (meta || alt) && keyId == KEY_TAB -> HostShortcut.RECENTS
            meta && letter == 'n' -> HostShortcut.NOTIFICATIONS
            meta && letter == 's' -> HostShortcut.QUICK_SETTINGS
            meta && letter == 'l' -> HostShortcut.LOCK_SCREEN
            else -> null
        }
    }

    /** Lowercase ASCII letter for a Barrier letter key id, or null if [keyId] is not a letter. */
    private fun letterOf(keyId: Int): Char? = when (keyId) {
        in 0x61..0x7A -> keyId.toChar()
        in 0x41..0x5A -> (keyId + 0x20).toChar()
        else -> null
    }
}
