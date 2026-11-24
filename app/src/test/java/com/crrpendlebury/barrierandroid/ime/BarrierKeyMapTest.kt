package com.crrpendlebury.barrierandroid.ime

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Printable key ids to Android key codes, used to send Ctrl/Alt/Meta chords as
 * key events. The KeyEvent constants are compile-time values, so this runs on
 * the JVM without an Android runtime.
 */
class BarrierKeyMapTest {

    @Test
    fun lettersMapToTheirKeyCodeCaseInsensitively() {
        assertEquals(KeyEvent.KEYCODE_L, BarrierKeyMap.keyCodeForPrintable('l'.code))
        assertEquals(KeyEvent.KEYCODE_L, BarrierKeyMap.keyCodeForPrintable('L'.code))
        assertEquals(KeyEvent.KEYCODE_A, BarrierKeyMap.keyCodeForPrintable('a'.code))
        assertEquals(KeyEvent.KEYCODE_Z, BarrierKeyMap.keyCodeForPrintable('z'.code))
    }

    @Test
    fun digitsAndPunctuationMapToKeyCodes() {
        assertEquals(KeyEvent.KEYCODE_0, BarrierKeyMap.keyCodeForPrintable('0'.code))
        assertEquals(KeyEvent.KEYCODE_9, BarrierKeyMap.keyCodeForPrintable('9'.code))
        assertEquals(KeyEvent.KEYCODE_SLASH, BarrierKeyMap.keyCodeForPrintable('/'.code))
        assertEquals(KeyEvent.KEYCODE_SPACE, BarrierKeyMap.keyCodeForPrintable(' '.code))
    }

    @Test
    fun unknownCharactersHaveNoKeyCode() {
        assertEquals(0, BarrierKeyMap.keyCodeForPrintable(0xE9))
    }
}
