package com.crrpendlebury.barrierandroid.protocol

/**
 * Byte pipe used by [BarrierClient]. The client does not know whether the
 * implementation is a socket or the in-memory fake used by unit tests.
 *
 * Contract:
 * - [connect] moves [state] to [TransportState.CONNECTED] or throws. The
 *   `useTls` argument is part of the contract; [SocketBarrierTransport]
 *   currently ignores it and uses the flag from its constructor instead.
 * - [send] may be called only after a successful connect. It writes one
 *   length-prefixed frame. It is suspending but the socket implementation
 *   blocks the caller on the write.
 * - [close] is idempotent and must not throw. After close, [state] is
 *   [TransportState.DISCONNECTED].
 * - [onPacket] is invoked on whatever thread the implementation reads on.
 *   [BarrierClient] immediately re-dispatches onto its own scope, so listeners
 *   must not assume the main thread.
 *
 * [TransportState.CONNECTING] is defined and unused. Do not treat it as
 * observed behaviour.
 */
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

    /**
     * Invoked when the read loop exits without an explicit [close]. The
     * implementation must not call this after [close] has been called. The
     * client uses it to run [BarrierClient.closeTransport] without waiting
     * for the keepalive watchdog.
     */
    var onConnectionLost: (() -> Unit)?
}

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}
