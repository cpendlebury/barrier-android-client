package com.crrpendlebury.barrierandroid.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host chord matching. Masks are Barrier's `KeyModifierMask` bits: Shift 0x1,
 * Control 0x2, Alt 0x4, Meta 0x8, Super 0x10. Key ids are Barrier ids, so
 * letters arrive as their ASCII code and Print is `kKeyPrint` (0xEF61).
 */
class HostShortcutsTest {

    private val meta = 0x0008
    private val alt = 0x0004
    private val control = 0x0002
    private val printScreen = 0xEF61
    private val tab = 0xEF09
    private val enter = 0xEF0D

    @Test
    fun metaHMapsToHome() {
        assertEquals(HostShortcut.HOME, HostShortcuts.match('h'.code, meta))
    }

    @Test
    fun metaEnterMapsToHome() {
        assertEquals(HostShortcut.HOME, HostShortcuts.match(enter, meta))
    }

    @Test
    fun altTabAndMetaTabMapToRecents() {
        assertEquals(HostShortcut.RECENTS, HostShortcuts.match(tab, alt))
        assertEquals(HostShortcut.RECENTS, HostShortcuts.match(tab, meta))
    }

    @Test
    fun metaChordsMapToSystemActions() {
        assertEquals(HostShortcut.NOTIFICATIONS, HostShortcuts.match('n'.code, meta))
        assertEquals(HostShortcut.QUICK_SETTINGS, HostShortcuts.match('s'.code, meta))
        assertEquals(HostShortcut.LOCK_SCREEN, HostShortcuts.match('l'.code, meta))
    }

    @Test
    fun metaCtrlSAndPrintScreenMapToScreenshot() {
        assertEquals(HostShortcut.SCREENSHOT, HostShortcuts.match('s'.code, meta or control))
        assertEquals(HostShortcut.SCREENSHOT, HostShortcuts.match(printScreen, 0))
    }

    @Test
    fun uppercaseLetterMatchesLikeLowercase() {
        assertEquals(HostShortcut.HOME, HostShortcuts.match('H'.code, meta))
    }

    @Test
    fun plainLettersAndCtrlChordsAreNotShortcuts() {
        assertNull(HostShortcuts.match('h'.code, 0))
        assertNull(HostShortcuts.match('l'.code, control))
        assertNull(HostShortcuts.match(tab, 0))
    }

    @Test
    fun metaSWithCtrlIsScreenshotNotQuickSettings() {
        assertEquals(HostShortcut.SCREENSHOT, HostShortcuts.match('s'.code, meta or control))
    }
}
