package com.crrpendlebury.barrierandroid.protocol

/** Transport abstraction used by the Barrier client. */
interface BarrierTransport {

    val state: TransportState

    /** Opens a connection to the Barrier server. */
    suspend fun connect(host: String, port: Int, useTls: Boolean)

    /** Sends a packet to the Barrier server. */
    suspend fun send(packet: BarrierPacket)

    /** Closes the connection. */
    fun close()

    /** Invoked with each packet received from the server. */
    var onPacket: ((BarrierPacket) -> Unit)?
}

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}
