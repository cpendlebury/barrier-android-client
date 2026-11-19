package com.crrpendlebury.barrierandroid.ime

import android.view.KeyEvent

/**
 * Mapping between Barrier key ids (X11 keysyms, per `src/lib/barrier/key_types.h`
 * in the Barrier tree) and Android [KeyEvent] key codes / committed characters.
 *
 * Barrier key ids are UTF-32: printable characters are their own code point;
 * control/function/modifier keys live in the U+E000..U+EFFF range (X11 keysym
 * minus 0x1000). The wire field is 16-bit, which covers the BMP and all of
 * Barrier's control-key range.
 *
 * This is a focused subset. Dead keys, extended (0xE0xx) function/media keys,
 * and keypad composition are deferred; unknown ids fall through to [isPrintable]
 * handling or are ignored.
 */
object BarrierKeyMap {

    /** True if [keyId] is a printable character we can commit as text. */
    fun isPrintable(keyId: Int): Boolean =
        keyId in 0x20..0x7E || keyId in 0xA0..0xFF

    /** Returns the character to commit for a printable [keyId], considering [shift]. */
    fun charFor(keyId: Int, shift: Boolean): Char? {
        if (!isPrintable(keyId)) return null
        val c = keyId.toChar()
        return if (shift && c in 'a'..'z') c.uppercaseChar() else c
    }

    /** Returns an Android [KeyEvent] keycode for a special Barrier key id, or 0 if unknown. */
    fun keyCodeFor(keyId: Int): Int = when (keyId) {
        0xEF08 -> KeyEvent.KEYCODE_DEL          // BackSpace
        0xEF09 -> KeyEvent.KEYCODE_TAB          // Tab
        0xEF0D -> KeyEvent.KEYCODE_ENTER        // Return
        0xEF1B -> KeyEvent.KEYCODE_ESCAPE       // Escape
        0xEFFF -> KeyEvent.KEYCODE_FORWARD_DEL  // Delete
        0xEF50 -> KeyEvent.KEYCODE_MOVE_HOME    // Home
        0xEF51 -> KeyEvent.KEYCODE_DPAD_LEFT    // Left
        0xEF52 -> KeyEvent.KEYCODE_DPAD_UP      // Up
        0xEF53 -> KeyEvent.KEYCODE_DPAD_RIGHT   // Right
        0xEF54 -> KeyEvent.KEYCODE_DPAD_DOWN    // Down
        0xEF55 -> KeyEvent.KEYCODE_PAGE_UP      // PageUp
        0xEF56 -> KeyEvent.KEYCODE_PAGE_DOWN    // PageDown
        0xEF57 -> KeyEvent.KEYCODE_MOVE_END     // End
        0xEF58 -> KeyEvent.KEYCODE_MOVE_HOME    // Begin
        0xEF63 -> KeyEvent.KEYCODE_INSERT       // Insert
        0xEF7F -> KeyEvent.KEYCODE_NUM_LOCK     // NumLock
        0xEF8D -> KeyEvent.KEYCODE_NUMPAD_ENTER // KP_Enter
        0xEF95 -> KeyEvent.KEYCODE_MOVE_HOME    // KP_Home
        0xEF96 -> KeyEvent.KEYCODE_DPAD_LEFT    // KP_Left
        0xEF97 -> KeyEvent.KEYCODE_DPAD_UP      // KP_Up
        0xEF98 -> KeyEvent.KEYCODE_DPAD_RIGHT   // KP_Right
        0xEF99 -> KeyEvent.KEYCODE_DPAD_DOWN    // KP_Down
        0xEF9A -> KeyEvent.KEYCODE_PAGE_UP      // KP_PageUp
        0xEF9B -> KeyEvent.KEYCODE_PAGE_DOWN    // KP_PageDown
        0xEF9C -> KeyEvent.KEYCODE_MOVE_END     // KP_End
        0xEF9E -> KeyEvent.KEYCODE_INSERT       // KP_Insert
        0xEF9F -> KeyEvent.KEYCODE_FORWARD_DEL  // KP_Delete
        0xEFBE -> KeyEvent.KEYCODE_F1
        0xEFBF -> KeyEvent.KEYCODE_F2
        0xEFC0 -> KeyEvent.KEYCODE_F3
        0xEFC1 -> KeyEvent.KEYCODE_F4
        0xEFC2 -> KeyEvent.KEYCODE_F5
        0xEFC3 -> KeyEvent.KEYCODE_F6
        0xEFC4 -> KeyEvent.KEYCODE_F7
        0xEFC5 -> KeyEvent.KEYCODE_F8
        0xEFC6 -> KeyEvent.KEYCODE_F9
        0xEFC7 -> KeyEvent.KEYCODE_F10
        0xEFC8 -> KeyEvent.KEYCODE_F11
        0xEFC9 -> KeyEvent.KEYCODE_F12
        // Modifiers
        0xEFE1 -> KeyEvent.KEYCODE_SHIFT_LEFT
        0xEFE2 -> KeyEvent.KEYCODE_SHIFT_RIGHT
        0xEFE3 -> KeyEvent.KEYCODE_CTRL_LEFT
        0xEFE4 -> KeyEvent.KEYCODE_CTRL_RIGHT
        0xEFE5 -> KeyEvent.KEYCODE_CAPS_LOCK
        0xEFE7 -> KeyEvent.KEYCODE_META_LEFT
        0xEFE8 -> KeyEvent.KEYCODE_META_RIGHT
        0xEFE9 -> KeyEvent.KEYCODE_ALT_LEFT
        0xEFEA -> KeyEvent.KEYCODE_ALT_RIGHT
        0xEFEB -> KeyEvent.KEYCODE_META_LEFT    // Super_L
        0xEFEC -> KeyEvent.KEYCODE_META_RIGHT   // Super_R
        // Keypad digits
        0xEFB0 -> KeyEvent.KEYCODE_NUMPAD_0
        0xEFB1 -> KeyEvent.KEYCODE_NUMPAD_1
        0xEFB2 -> KeyEvent.KEYCODE_NUMPAD_2
        0xEFB3 -> KeyEvent.KEYCODE_NUMPAD_3
        0xEFB4 -> KeyEvent.KEYCODE_NUMPAD_4
        0xEFB5 -> KeyEvent.KEYCODE_NUMPAD_5
        0xEFB6 -> KeyEvent.KEYCODE_NUMPAD_6
        0xEFB7 -> KeyEvent.KEYCODE_NUMPAD_7
        0xEFB8 -> KeyEvent.KEYCODE_NUMPAD_8
        0xEFB9 -> KeyEvent.KEYCODE_NUMPAD_9
        0xEFAB -> KeyEvent.KEYCODE_NUMPAD_ADD
        0xEFAD -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
        0xEFAA -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
        0xEFAF -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
        0xEFAE -> KeyEvent.KEYCODE_NUMPAD_DOT
        0xEFBD -> KeyEvent.KEYCODE_NUMPAD_EQUALS
        else -> 0
    }

    /** True if [keyId] is a modifier key whose state should be tracked. */
    fun isModifier(keyId: Int): Boolean = keyId in 0xEFE1..0xEFEE

    /** True if [keyId] is Back (Escape or Alt+Left) — handled as Android Back. */
    fun isBackKey(keyId: Int, mask: Int): Boolean =
        keyId == 0xEF1B || (keyId == 0xEF51 && (mask and 0x0004) != 0)
}