package com.crrpendlebury.barrierandroid.protocol

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * In-memory [BarrierTransport] for unit tests.
 *
 * [connect] does not look at host, port, or TLS. [send] appends to
 * [sentPackets]. [emit] invokes [onPacket] on the calling thread, which is
 * the test thread; [BarrierClient] then hops onto its scope. Tests must
 * `runCurrent` after [emit] or the handler has not run.
 *
 * [sentPackets] is not a concurrent queue. [send] synchronizes on [mutex],
 * which is a coroutines [kotlinx.coroutines.sync.Mutex] used only as a
 * monitor object. Do not `mutex.withLock` and `synchronized(mutex)` on the
 * same instance from new code; pick one. The unused imports above this class
 * are leftover and not part of the fake's behaviour.
 */
class FakeBarrierTransport : BarrierTransport {

    override var state: TransportState = TransportState.DISCONNECTED
        private set

    override var onPacket: ((BarrierPacket) -> Unit)? = null

    override var onConnectionLost: (() -> Unit)? = null

    private val mutex = kotlinx.coroutines.sync.Mutex()

    val sentPackets = mutableListOf<BarrierPacket>()

    override suspend fun connect(host: String, port: Int, useTls: Boolean) {
        state = TransportState.CONNECTED
    }

    override suspend fun send(packet: BarrierPacket) {
        synchronized(mutex) {
            sentPackets.add(packet)
        }
    }

    override fun close() {
        state = TransportState.DISCONNECTED
    }

    fun emit(packet: BarrierPacket) {
        onPacket?.invoke(packet)
    }
}
