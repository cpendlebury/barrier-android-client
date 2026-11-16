package com.crrpendlebury.barrierandroid.protocol

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** TCP transport implementing [BarrierTransport] over a plain or TLS socket. */
class SocketBarrierTransport(
    private val host: String,
    private val port: Int,
    private val useTls: Boolean,
    private val scope: CoroutineScope,
    override var onPacket: ((BarrierPacket) -> Unit)? = null,
) : BarrierTransport {

    override var state: TransportState = TransportState.DISCONNECTED
        private set

    private var socket: Socket? = null
    private var readJob: Job? = null
    @Volatile
    private var closed = false

    override suspend fun connect(host: String, port: Int, useTls: Boolean) {
        if (state != TransportState.DISCONNECTED) {
            return
        }
        val newSocket = createSocket()
        newSocket.connect(InetSocketAddress(host, port))
        socket = newSocket
        state = TransportState.CONNECTED
        closed = false
        readJob?.cancel()
        readJob = scope.launch { readPackets(newSocket) }
    }

    override suspend fun send(packet: BarrierPacket) {
        val activeSocket = socket ?: throw IllegalStateException("Transport is not connected")
        val frame = PacketCodec.encodeFrame(packet.encode())
        val output = activeSocket.getOutputStream()
        output.write(frame)
        output.flush()
    }

    override fun close() {
        closed = true
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        state = TransportState.DISCONNECTED
    }

    /**
     * Creates a TLS socket when requested, falling back to a plaintext socket
     * when TLS negotiation fails (plaintext fallback).
     */
    private fun createSocket(): Socket {
        val factory = SSLContext.getDefault().socketFactory
        if (useTls) {
            return try {
                val createTlsSocket = SSLSocketFactory::class.java.getMethod(
                    "createSSLSocket",
                    String::class.java,
                    Int::class.java,
                )
                createTlsSocket.invoke(factory, host, port) as SSLSocket
            } catch (e: Exception) {
                // TLS expected but unavailable: fall back to plaintext.
                factory.createSocket(host, port)
            }
        }
        return factory.createSocket(host, port)
    }

    private suspend fun readPackets(socket: Socket) {
        try {
            val input: InputStream = socket.getInputStream()
            val lengthBuffer = ByteArray(4)
            while (!closed) {
                if (!readFully(input, lengthBuffer)) {
                    return
                }
                val length = (
                    ((lengthBuffer[0].toInt() and 0xff) shl 24) or
                        ((lengthBuffer[1].toInt() and 0xff) shl 16) or
                        ((lengthBuffer[2].toInt() and 0xff) shl 8) or
                        (lengthBuffer[3].toInt() and 0xff)
                    )
                if (length > PROTOCOL_MAX_MESSAGE_LENGTH) {
                    return
                }
                val payload = ByteArray(length)
                if (!readFully(input, payload)) {
                    return
                }
                val packet = PacketCodec.decodePayload(payload)
                if (packet == null) {
                    // Unknown or malformed packet: drop the connection.
                    return
                }
                onPacket?.invoke(packet)
            }
        } catch (e: Exception) {
            // Socket read failure or cancellation: drop the connection quietly.
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                return false
            }
            offset += read
        }
        return true
    }
}
