package com.crrpendlebury.barrierandroid.protocol

/**
 * Barrier protocol packet types.
 *
 * A packet payload is the command string followed by command specific
 * parameters. Integers are encoded big endian and strings are encoded as an
 * unsigned 32-bit big endian byte length followed by the UTF-8 bytes.
 */
sealed class BarrierPacket {
    abstract val command: BarrierCommand
    abstract fun encode(): ByteArray

    // ------------------------------------------------------------------
    // Server -> client
    // ------------------------------------------------------------------

    /** Server -> client hello, carries the protocol versions in use. */
    data class Hello(val versionMajor: Int, val versionMinor: Int) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.HELLO

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.HELLO.wireLength + 4)
            writeAscii(out, 0, BarrierCommand.HELLO.value, BarrierCommand.HELLO.wireLength)
            var offset = BarrierCommand.HELLO.wireLength
            writeInt16(out, offset, versionMajor)
            offset += 2
            writeInt16(out, offset, versionMinor)
            return out
        }
    }

    /** Server -> client query screen info (QINF). No parameters. */
    class QueryInfoPacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.QINF

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.QINF)
    }

    /** Server -> client clipboard grab (CCLP). */
    data class ClipboardGrabPacket(
        val clipboardId: Int,
        val seq: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.CCLP

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.CCLP.wireLength + 5)
            writeAscii(out, 0, BarrierCommand.CCLP.value, BarrierCommand.CCLP.wireLength)
            writeInt8(out, BarrierCommand.CCLP.wireLength, clipboardId)
            writeInt32(out, BarrierCommand.CCLP.wireLength + 1, seq)
            return out
        }
    }

    /** Server -> client screen enter (CINN). */
    data class ScreenEnterPacket(
        val x: Int,
        val y: Int,
        val seq: Int,
        val modifierMask: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.CINN

            override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.CINN.wireLength + 2 + 2 + 4 + 2)
            writeAscii(out, 0, BarrierCommand.CINN.value, BarrierCommand.CINN.wireLength)
            var offset = BarrierCommand.CINN.wireLength
            writeInt16(out, offset, x)
            offset += 2
            writeInt16(out, offset, y)
            offset += 2
            writeInt32(out, offset, seq)
            offset += 4
            writeInt16(out, offset, modifierMask)
            return out
        }
    }

    /** Server -> client screen leave (COUT). No parameters. */
    class ScreenLeftPacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.COUT

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.COUT)
    }

    /** Server -> client keepalive (CALV). No parameters; client replies in kind. */
    class KeepAlivePacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.CALV

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.CALV)
    }

    /** Server -> client screensaver state change (CSEC). */
    data class ScreenSaverPacket(val started: Boolean) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.CSEC

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.CSEC.wireLength + 1)
            writeAscii(out, 0, BarrierCommand.CSEC.value, BarrierCommand.CSEC.wireLength)
            writeInt8(out, BarrierCommand.CSEC.wireLength, if (started) 1 else 0)
            return out
        }
    }

    /** Server -> client reset options (CROP). No parameters. */
    class ResetOptionsPacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.CROP

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.CROP)
    }

    /** Server -> client resolution change acknowledgment (CIAK). No parameters. */
    class InfoAcknowledgedPacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.CIAK

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.CIAK)
    }

    /** Server -> client clipboard data update (DCLP). */
    data class ClipboardDataPacket(
        val clipboardId: Int,
        val seq: Int,
        val mark: Int,
        val data: ByteArray,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DCLP

        override fun encode(): ByteArray {
            val out = ByteArray(
                BarrierCommand.DCLP.wireLength + 1 + 4 + 1 + 4 + data.size,
            )
            writeAscii(out, 0, BarrierCommand.DCLP.value, BarrierCommand.DCLP.wireLength)
            var offset = BarrierCommand.DCLP.wireLength
            writeInt8(out, offset, clipboardId)
            offset += 1
            writeInt32(out, offset, seq)
            offset += 4
            writeInt8(out, offset, mark)
            offset += 1
            writeInt32(out, offset, data.size)
            offset += 4
            System.arraycopy(data, 0, out, offset, data.size)
            return out
        }
    }

    /** Server -> client option update (DSOP). */
    data class SetOptionsPacket(val options: List<Pair<Int, Int>>) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DSOP

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DSOP.wireLength + 8 * options.size)
            writeAscii(out, 0, BarrierCommand.DSOP.value, BarrierCommand.DSOP.wireLength)
            var offset = BarrierCommand.DSOP.wireLength
            for ((id, value) in options) {
                writeInt32(out, offset, id)
                offset += 4
                writeInt32(out, offset, value)
                offset += 4
            }
            return out
        }
    }

    /** Server -> client file transfer chunk (DFTR). */
    data class FileTransferPacket(val mark: Int, val data: ByteArray) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DFTR

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DFTR.wireLength + 1 + 4 + data.size)
            writeAscii(out, 0, BarrierCommand.DFTR.value, BarrierCommand.DFTR.wireLength)
            var offset = BarrierCommand.DFTR.wireLength
            writeInt8(out, offset, mark)
            offset += 1
            writeInt32(out, offset, data.size)
            offset += 4
            System.arraycopy(data, 0, out, offset, data.size)
            return out
        }
    }

    /** Server -> client drag information (DDRG). */
    data class DragInfoPacket(val objectCount: Int, val objects: ByteArray) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DDRG

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DDRG.wireLength + 2 + 4 + objects.size)
            writeAscii(out, 0, BarrierCommand.DDRG.value, BarrierCommand.DDRG.wireLength)
            var offset = BarrierCommand.DDRG.wireLength
            writeInt16(out, offset, objectCount)
            offset += 2
            writeInt32(out, offset, objects.size)
            offset += 4
            System.arraycopy(objects, 0, out, offset, objects.size)
            return out
        }
    }

    // ------------------------------------------------------------------
    // Server -> client errors
    // ------------------------------------------------------------------

    /** Server -> client incompatible version notification (EICV). */
    data class IncompatibleVersionPacket(
        val majorVersion: Int,
        val minorVersion: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.EICV

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.EICV.wireLength + 4)
            writeAscii(out, 0, BarrierCommand.EICV.value, BarrierCommand.EICV.wireLength)
            var offset = BarrierCommand.EICV.wireLength
            writeInt16(out, offset, majorVersion)
            offset += 2
            writeInt16(out, offset, minorVersion)
            return out
        }
    }

    /** Server -> client busy notification (EBSY). */
    class BusyPacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.EBSY

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.EBSY)
    }

    /** Server -> client unknown client notification (EUNK). */
    class UnknownPacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.EUNK

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.EUNK)
    }

    /** Server -> client protocol violation notification (EBAD). */
    class BadPacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.EBAD

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.EBAD)
    }

    /** Server -> client close connection (CBYE). */
    class ClosePacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.CBYE

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.CBYE)
    }

    // ------------------------------------------------------------------
    // Server -> client: input events
    // ------------------------------------------------------------------

    /** Server -> client key pressed (DKDN). */
    data class KeyDownPacket(
        val keyId: Int,
        val modifierMask: Int,
        val button: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DKDN

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DKDN.wireLength + 6)
            writeAscii(out, 0, BarrierCommand.DKDN.value, BarrierCommand.DKDN.wireLength)
            var offset = BarrierCommand.DKDN.wireLength
            writeInt16(out, offset, keyId)
            offset += 2
            writeInt16(out, offset, modifierMask)
            offset += 2
            writeInt16(out, offset, button)
            return out
        }
    }

    /** Server -> client key auto-repeat (DKRP). */
    data class KeyRepeatPacket(
        val keyId: Int,
        val modifierMask: Int,
        val repeat: Int,
        val button: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DKRP

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DKRP.wireLength + 8)
            writeAscii(out, 0, BarrierCommand.DKRP.value, BarrierCommand.DKRP.wireLength)
            var offset = BarrierCommand.DKRP.wireLength
            writeInt16(out, offset, keyId)
            offset += 2
            writeInt16(out, offset, modifierMask)
            offset += 2
            writeInt16(out, offset, repeat)
            offset += 2
            writeInt16(out, offset, button)
            return out
        }
    }

    /** Server -> client key released (DKUP). */
    data class KeyUpPacket(
        val keyId: Int,
        val modifierMask: Int,
        val button: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DKUP

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DKUP.wireLength + 6)
            writeAscii(out, 0, BarrierCommand.DKUP.value, BarrierCommand.DKUP.wireLength)
            var offset = BarrierCommand.DKUP.wireLength
            writeInt16(out, offset, keyId)
            offset += 2
            writeInt16(out, offset, modifierMask)
            offset += 2
            writeInt16(out, offset, button)
            return out
        }
    }

    /** Server -> client absolute mouse move (DMMV). */
    data class MouseMovePacket(
        val x: Int,
        val y: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DMMV

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DMMV.wireLength + 4)
            writeAscii(out, 0, BarrierCommand.DMMV.value, BarrierCommand.DMMV.wireLength)
            var offset = BarrierCommand.DMMV.wireLength
            writeInt16(out, offset, x)
            offset += 2
            writeInt16(out, offset, y)
            return out
        }
    }

    /** Server -> client relative mouse move (DMRM). */
    data class MouseRelativeMovePacket(
        val dx: Int,
        val dy: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DMRM

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DMRM.wireLength + 4)
            writeAscii(out, 0, BarrierCommand.DMRM.value, BarrierCommand.DMRM.wireLength)
            var offset = BarrierCommand.DMRM.wireLength
            writeInt16(out, offset, dx)
            offset += 2
            writeInt16(out, offset, dy)
            return out
        }
    }

    /** Server -> client mouse scroll (DMWM). */
    data class MouseWheelPacket(
        val xDelta: Int,
        val yDelta: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DMWM

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DMWM.wireLength + 4)
            writeAscii(out, 0, BarrierCommand.DMWM.value, BarrierCommand.DMWM.wireLength)
            var offset = BarrierCommand.DMWM.wireLength
            writeInt16(out, offset, xDelta)
            offset += 2
            writeInt16(out, offset, yDelta)
            return out
        }
    }

    /** Server -> client mouse button pressed (DMDN). */
    data class MouseButtonDownPacket(
        val button: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DMDN

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DMDN.wireLength + 1)
            writeAscii(out, 0, BarrierCommand.DMDN.value, BarrierCommand.DMDN.wireLength)
            writeInt8(out, BarrierCommand.DMDN.wireLength, button)
            return out
        }
    }

    /** Server -> client mouse button released (DMUP). */
    data class MouseButtonUpPacket(
        val button: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DMUP

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DMUP.wireLength + 1)
            writeAscii(out, 0, BarrierCommand.DMUP.value, BarrierCommand.DMUP.wireLength)
            writeInt8(out, BarrierCommand.DMUP.wireLength, button)
            return out
        }
    }

    // ------------------------------------------------------------------
    // Client -> server
    // ------------------------------------------------------------------

    /** Client -> server screen geometry reply to server query (DINF). */
    data class ScreenInfoPacket(
        val xOrigin: Int,
        val yOrigin: Int,
        val width: Int,
        val height: Int,
        val obsoleteWarpZone: Int,
        val mouseX: Int,
        val mouseY: Int,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.DINF

        override fun encode(): ByteArray {
            val out = ByteArray(BarrierCommand.DINF.wireLength + 14)
            writeAscii(out, 0, BarrierCommand.DINF.value, BarrierCommand.DINF.wireLength)
            var offset = BarrierCommand.DINF.wireLength
            writeInt16(out, offset, xOrigin)
            offset += 2
            writeInt16(out, offset, yOrigin)
            offset += 2
            writeInt16(out, offset, width)
            offset += 2
            writeInt16(out, offset, height)
            offset += 2
            writeInt16(out, offset, obsoleteWarpZone)
            offset += 2
            writeInt16(out, offset, mouseX)
            offset += 2
            writeInt16(out, offset, mouseY)
            return out
        }
    }

    /** Client -> server hello response (HELLO). */
    data class HelloBack(
        val versionMajor: Int,
        val versionMinor: Int,
        val name: String,
    ) : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.HELLO

        override fun encode(): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val out = ByteArray(BarrierCommand.HELLO.wireLength + 2 + 2 + 4 + nameBytes.size)
            writeAscii(out, 0, BarrierCommand.HELLO.value, BarrierCommand.HELLO.wireLength)
            var offset = BarrierCommand.HELLO.wireLength
            writeInt16(out, offset, versionMajor)
            offset += 2
            writeInt16(out, offset, versionMinor)
            offset += 2
            writeInt32(out, offset, nameBytes.size)
            offset += 4
            System.arraycopy(nameBytes, 0, out, offset, nameBytes.size)
            return out
        }
    }

    /** Client -> server no operation (CNOP). */
    class NoOpPacket : BarrierPacket() {
        override val command: BarrierCommand
            get() = BarrierCommand.CNOP

        override fun encode(): ByteArray = encodeCommandOnly(BarrierCommand.CNOP)
    }

    companion object {

        private fun encodeCommandOnly(command: BarrierCommand): ByteArray {
            val out = ByteArray(command.wireLength)
            writeAscii(out, 0, command.value, command.wireLength)
            return out
        }

        fun decode(payload: ByteArray): BarrierPacket? {
            if (payload.size < 4) {
                return null
            }
            // The greeting handshake command is "Barrier" and is not encoded on
            // four bytes, everything else starts with a four character command.
            if (payload.size >= 7) {
                val greeting = String(payload, 0, 7, Charsets.US_ASCII)
                if (greeting == BarrierCommand.HELLO.value) {
                    val versionMajor = readInt16(payload, 7)
                    val versionMinor = readInt16(payload, 9)
                    // A bare hello is "Barrier" + versions; a hello-back additionally
                    // carries the client name as a BE32 length prefixed UTF-8 string.
                    return if (payload.size == 11) {
                        Hello(versionMajor, versionMinor)
                    } else {
                        val nameLength = readInt32(payload, 11)
                        val name = String(payload, 15, nameLength, Charsets.US_ASCII)
                        HelloBack(versionMajor, versionMinor, name)
                    }
                }
            }
            val command = String(payload, 0, 4, Charsets.US_ASCII)
            val body = payload.copyOfRange(4, payload.size)
            return when (command) {
                BarrierCommand.QINF.value -> QueryInfoPacket()
                BarrierCommand.CIAK.value -> InfoAcknowledgedPacket()
                BarrierCommand.CNOP.value -> NoOpPacket()
                BarrierCommand.CBYE.value -> ClosePacket()
                BarrierCommand.CSEC.value -> ScreenSaverPacket(readInt8(body, 0) != 0)
                BarrierCommand.CROP.value -> ResetOptionsPacket()
                BarrierCommand.CALV.value -> KeepAlivePacket()
                BarrierCommand.CCLP.value -> ClipboardGrabPacket(readInt8(body, 0), readInt32(body, 1))
                BarrierCommand.EICV.value -> IncompatibleVersionPacket(readInt16(body, 0), readInt16(body, 2))
                BarrierCommand.EBSY.value -> BusyPacket()
                BarrierCommand.EUNK.value -> UnknownPacket()
                BarrierCommand.EBAD.value -> BadPacket()
                BarrierCommand.CINN.value ->
                    ScreenEnterPacket(readInt16(body, 0), readInt16(body, 2), readInt32(body, 4), readInt16(body, 8))
                BarrierCommand.COUT.value -> ScreenLeftPacket()
                BarrierCommand.DKDN.value -> KeyDownPacket(readInt16(body, 0), readInt16(body, 2), readInt16(body, 4))
                BarrierCommand.DKRP.value -> KeyRepeatPacket(readInt16(body, 0), readInt16(body, 2), readInt16(body, 4), readInt16(body, 6))
                BarrierCommand.DKUP.value -> KeyUpPacket(readInt16(body, 0), readInt16(body, 2), readInt16(body, 4))
                BarrierCommand.DMMV.value -> MouseMovePacket(readInt16(body, 0), readInt16(body, 2))
                BarrierCommand.DMRM.value -> MouseRelativeMovePacket(readInt16(body, 0), readInt16(body, 2))
                BarrierCommand.DMWM.value -> MouseWheelPacket(readInt16(body, 0), readInt16(body, 2))
                BarrierCommand.DMDN.value -> MouseButtonDownPacket(readInt8(body, 0))
                BarrierCommand.DMUP.value -> MouseButtonUpPacket(readInt8(body, 0))
                BarrierCommand.DINF.value -> ScreenInfoPacket(
                    readInt16(body, 0),
                    readInt16(body, 2),
                    readInt16(body, 4),
                    readInt16(body, 6),
                    readInt16(body, 8),
                    readInt16(body, 10),
                    readInt16(body, 12),
                )
                BarrierCommand.DCLP.value -> {
                    val clipboardId = readInt8(body, 0)
                    val seq = readInt32(body, 1)
                    val mark = readInt8(body, 5)
                    val dataSize = readInt32(body, 6)
                    ClipboardDataPacket(clipboardId, seq, mark, payload.copyOfRange(14, 14 + dataSize))
                }
                BarrierCommand.DSOP.value -> SetOptionsPacket(parseOptions(body))
                BarrierCommand.DFTR.value -> {
                    val mark = readInt8(body, 0)
                    val dataSize = readInt32(body, 1)
                    FileTransferPacket(mark, payload.copyOfRange(9, 9 + dataSize))
                }
                BarrierCommand.DDRG.value -> {
                    val objectCount = readInt16(body, 0)
                    val objectDataSize = readInt32(body, 2)
                    DragInfoPacket(objectCount, payload.copyOfRange(10, 10 + objectDataSize))
                }
                else -> null
            }
        }

        private fun parseOptions(body: ByteArray): List<Pair<Int, Int>> {
            val options = mutableListOf<Pair<Int, Int>>()
            var offset = 0
            while (offset + 8 <= body.size) {
                val id = readInt32(body, offset)
                val value = readInt32(body, offset + 4)
                options.add(id to value)
                offset += 8
            }
            return options
        }
    }
}

// ---------------------------------------------------------------------------
// Packet encoding helpers
// ---------------------------------------------------------------------------

private fun encodeCommandOnly(command: BarrierCommand): ByteArray {
    val out = ByteArray(command.wireLength)
    writeAscii(out, 0, command.value, command.wireLength)
    return out
}

private fun writeAscii(out: ByteArray, offset: Int, value: String, count: Int) {
    val bytes = value.toByteArray(Charsets.US_ASCII)
    System.arraycopy(bytes, 0, out, offset, count)
}

/** Writes a big endian signed 8 bit integer. */
internal fun writeInt8(out: ByteArray, offset: Int, value: Int) {
    out[offset] = value.toByte()
}

/** Writes a big endian unsigned 16 bit integer. */
internal fun writeInt16(out: ByteArray, offset: Int, value: Int) {
    out[offset] = ((value shr 8) and 0xff).toByte()
    out[offset + 1] = (value and 0xff).toByte()
}

/** Writes a big endian unsigned 32 bit integer. */
internal fun writeInt32(out: ByteArray, offset: Int, value: Int) {
    out[offset] = ((value ushr 24) and 0xff).toByte()
    out[offset + 1] = ((value ushr 16) and 0xff).toByte()
    out[offset + 2] = ((value ushr 8) and 0xff).toByte()
    out[offset + 3] = (value and 0xff).toByte()
}

/** Reads a big endian unsigned 8 bit integer. */
internal fun readInt8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

/** Reads a big endian unsigned 16 bit integer. */
internal fun readInt16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

/** Reads a big endian unsigned 32 bit integer. */
internal fun readInt32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
