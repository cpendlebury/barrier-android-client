package com.crrpendlebury.barrierandroid.protocol

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** In-memory [BarrierTransport] fake for unit testing. */
class FakeBarrierTransport : BarrierTransport {

    override var state: TransportState = TransportState.DISCONNECTED
        private set

    override var onPacket: ((BarrierPacket) -> Unit)? = null

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
