package com.crrpendlebury.barrierandroid.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PacketCodecTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.toIntArray(): IntArray = IntArray(size) { (this[it].toInt() and 0xff) }

    private fun encodePacket(packet: BarrierPacket): ByteArray = PacketCodec.encodeFrame(packet.encode())

    private fun decodePacket(packet: BarrierPacket): BarrierPacket =
        PacketCodec.decodePayload(PacketCodec.decodeFrame(encodePacket(packet))!!)!!

    @Test
    fun frameEncodeAndDecodeRoundTrip() {
        val payload = bytes('C'.code, 'N'.code, 'O'.code, 'P'.code)
        val frame = PacketCodec.encodeFrame(payload)
        assertEquals(8, frame.size)
        assertArrayEquals(
            intArrayOf(0, 0, 0, 4, 67, 78, 79, 80),
            frame.toIntArray(),
        )

        val decoded = PacketCodec.decodeFrame(frame)
        assertNotNull(decoded)
        assertArrayEquals(
            intArrayOf(67, 78, 79, 80),
            decoded!!.toIntArray(),
        )
    }

    @Test
    fun frameDecodeRejectsTruncatedFrame() {
        val frame = PacketCodec.encodeFrame(bytes('C'.code, 'N'.code, 'O'.code, 'P'.code))
        assertNull(PacketCodec.decodeFrame(frame.copyOf(5)))
    }

    @Test
    fun frameDecodeRejectsOversizedMessage() {
        val length = PROTOCOL_MAX_MESSAGE_LENGTH + 1
        val frame = ByteArray(8)
        frame[0] = ((length ushr 24) and 0xff).toByte()
        frame[1] = ((length ushr 16) and 0xff).toByte()
        frame[2] = ((length ushr 8) and 0xff).toByte()
        frame[3] = (length and 0xff).toByte()
        assertNull(PacketCodec.decodeFrame(frame))
    }

    @Test
    fun helloPacketEncodesServerVersions() {
        val packet = BarrierPacket.Hello(versionMajor = 1, versionMinor = 6)
        assertEquals(BarrierCommand.HELLO, packet.command)
        assertArrayEquals(
            intArrayOf(
                66, 97, 114, 114, 105, 101, 114, // "Barrier" (ASCII)
                0, 1, // major version 1
                0, 6, // minor version 6
            ),
            packet.encode().toIntArray(),
        )
        assertEquals(BarrierPacket.Hello(1, 6), decodePacket(packet))
    }

    @Test
    fun helloBackPacketEncodesVersionAndName() {
        val packet = BarrierPacket.HelloBack(versionMajor = 1, versionMinor = 6, name = "phone")
        assertEquals(20, packet.encode().size) // 7 + 2 + 2 + 4 + len("phone")=5
        assertArrayEquals(
            intArrayOf(
                66, 97, 114, 114, 105, 101, 114, // "Barrier" (ASCII)
                0, 1,
                0, 6,
                0, 0, 0, 5,
                112, 104, 111, 110, 101, // "phone" (ASCII)
            ),
            packet.encode().toIntArray(),
        )
        assertEquals(BarrierPacket.HelloBack(versionMajor = 1, versionMinor = 6, name = "phone"), decodePacket(packet))
    }

    @Test
    fun keyDownPacketEncodesKeyAndModifier() {
        val packet = BarrierPacket.KeyDownPacket(keyId = 38, modifierMask = 1, button = 66)
        assertEquals(BarrierCommand.DKDN, packet.command)
        assertArrayEquals(
            intArrayOf(68, 75, 68, 78, 0, 38, 0, 1, 0, 66), // "DKDN" + BE16(38) + BE16(1) + BE16(66)
            packet.encode().toIntArray(),
        )
        assertEquals(BarrierPacket.KeyDownPacket(keyId = 38, modifierMask = 1, button = 66), decodePacket(packet))
    }

    @Test
    fun keyRepeatPacketEncodesRepeatCount() {
        val packet = BarrierPacket.KeyRepeatPacket(keyId = 38, modifierMask = 1, repeat = 3, button = 66)
        assertEquals(BarrierCommand.DKRP, packet.command)
        assertArrayEquals(
            intArrayOf(68, 75, 82, 80, 0, 38, 0, 1, 0, 3, 0, 66), // "DKRP" + 4 x BE16
            packet.encode().toIntArray(),
        )
        assertEquals(BarrierPacket.KeyRepeatPacket(keyId = 38, modifierMask = 1, repeat = 3, button = 66), decodePacket(packet))
    }

    @Test
    fun keyUpPacketEncodesKeyAndModifier() {
        val packet = BarrierPacket.KeyUpPacket(keyId = 38, modifierMask = 0, button = 66)
        assertEquals(BarrierCommand.DKUP, packet.command)
        assertEquals(BarrierPacket.KeyUpPacket(keyId = 38, modifierMask = 0, button = 66), decodePacket(packet))
    }

    @Test
    fun mouseMovePacketEncodesAbsoluteCoordinates() {
        val packet = BarrierPacket.MouseMovePacket(x = 100, y = 200)
        assertEquals(BarrierCommand.DMMV, packet.command)
        assertArrayEquals(
            intArrayOf(68, 77, 77, 86, 0, 100, 0, 200), // "DMMV" + BE16(100) + BE16(200)
            packet.encode().toIntArray(),
        )
        assertEquals(BarrierPacket.MouseMovePacket(x = 100, y = 200), decodePacket(packet))
    }

    @Test
    fun screenEnterPacketEncodesSequenceAndModifiers() {
        val packet = BarrierPacket.ScreenEnterPacket(x = 0, y = 0, seq = 7, modifierMask = 31)
        assertEquals(BarrierCommand.CINN, packet.command)
        assertArrayEquals(
            intArrayOf(67, 73, 78, 78, 0, 0, 0, 0, 0, 0, 0, 7, 0, 31), // "CINN" + BE16(0) + BE16(0) + BE32(7) + BE16(31)
            packet.encode().toIntArray(),
        )
        assertEquals(BarrierPacket.ScreenEnterPacket(x = 0, y = 0, seq = 7, modifierMask = 31), decodePacket(packet))
    }

    @Test
    fun clipboardGrabPacketEncodesIdAndSequence() {
        val packet = BarrierPacket.ClipboardGrabPacket(clipboardId = 0, seq = 5)
        assertEquals(BarrierCommand.CCLP, packet.command)
        assertArrayEquals(
            intArrayOf(67, 67, 76, 80, 0, 0, 0, 0, 5), // "CCLP" + BE8(0) + BE32(5)
            packet.encode().toIntArray(),
        )
    }

    @Test
    fun mouseWheelPacketEncodesScrollDelta() {
        val packet = BarrierPacket.MouseWheelPacket(xDelta = 0, yDelta = 120)
        assertEquals(BarrierCommand.DMWM, packet.command)
        assertArrayEquals(
            intArrayOf(68, 77, 87, 77, 0, 0, 0, 120), // "DMWM" + BE16(0) + BE16(120)
            packet.encode().toIntArray(),
        )
    }

    @Test
    fun incompatibleVersionPacketEncodesVersions() {
        val packet = BarrierPacket.IncompatibleVersionPacket(majorVersion = 2, minorVersion = 0)
        assertEquals(BarrierCommand.EICV, packet.command)
        assertArrayEquals(
            intArrayOf(69, 73, 67, 86, 0, 2, 0, 0), // "EICV" + BE16(2) + BE16(0)
            packet.encode().toIntArray(),
        )
    }

    @Test
    fun noOpPacketEncodesBareCommand() {
        val packet = BarrierPacket.NoOpPacket()
        assertEquals(BarrierCommand.CNOP, packet.command)
        assertArrayEquals(
            intArrayOf('C'.code.toInt(), 'N'.code.toInt(), 'O'.code.toInt(), 'P'.code.toInt()),
            packet.encode().toIntArray(),
        )
    }

    @Test
    fun screenInfoPacketEncodesSevenInt16Fields() {
        val packet = BarrierPacket.ScreenInfoPacket(xOrigin = 0, yOrigin = 0, width = 1080, height = 1920, obsoleteWarpZone = 0, mouseX = 100, mouseY = 200)
        assertEquals(BarrierCommand.DINF, packet.command)
        assertArrayEquals(
            intArrayOf(
                68, 73, 78, 70, // "DINF"
                0, 0, // xOrigin
                0, 0, // yOrigin
                4, 56, // width 1080
                7, 128, // height 1920
                0, 0, // obsoleteWarpZone
                0, 100, // mouseX
                0, 200, // mouseY
            ),
            packet.encode().toIntArray(),
        )
    }

    @Test
    fun unknownCommandDecodesToNull() {
        val payload = bytes('X'.code, 'Y'.code, 'Z'.code, 'W'.code)
        assertNull(PacketCodec.decodePayload(payload))
    }
}
