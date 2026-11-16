package com.crrpendlebury.barrierandroid.protocol

/**
 * Framing and payload codec for the Barrier protocol.
 *
 * Every message is sent as a 32-bit big endian payload length followed by the
 * payload itself. The payload starts with the command string followed by
 * command specific parameters.
 */
object PacketCodec {

    /** Encodes a framed packet payload into its wire representation. */
    fun encodeFrame(payload: ByteArray): ByteArray {
        val frame = ByteArray(4 + payload.size)
        frame[0] = ((payload.size ushr 24) and 0xff).toByte()
        frame[1] = ((payload.size ushr 16) and 0xff).toByte()
        frame[2] = ((payload.size ushr 8) and 0xff).toByte()
        frame[3] = (payload.size and 0xff).toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        return frame
    }

    /**
     * Decodes a framed packet. Returns the payload bytes, or null when the
     * frame is malformed or exceeds the protocol maximum message length.
     */
    fun decodeFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 4) {
            return null
        }
        val length = (
            ((frame[0].toInt() and 0xff) shl 24) or
                ((frame[1].toInt() and 0xff) shl 16) or
                ((frame[2].toInt() and 0xff) shl 8) or
                (frame[3].toInt() and 0xff)
            )
        if (frame.size < 4 + length) {
            return null
        }
        if (length > PROTOCOL_MAX_MESSAGE_LENGTH) {
            return null
        }
        return frame.copyOfRange(4, 4 + length)
    }

    /** Encodes a complete packet (frame + payload) into its wire representation. */
    fun encodePacket(packet: BarrierPacket): ByteArray = encodeFrame(packet.encode())

    /** Decodes a packet payload into its typed representation, or null if unknown. */
    fun decodePayload(payload: ByteArray): BarrierPacket? = BarrierPacket.decode(payload)
}
