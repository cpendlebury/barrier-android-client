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

/**
 * Socket transport. Not a working Barrier connection.
 *
 * Known defects, left in place and commented so they are not "cleaned up"
 * into a different bug:
 *
 * - [createSocket] never constructs a plain [java.net.Socket]. Both branches
 *   use [javax.net.ssl.SSLSocketFactory]. The comment inside the catch block
 *   that says "fall back to plaintext" is wrong; the fallback is still an
 *   SSL socket from the default [javax.net.ssl.SSLContext].
 * - The reflected method `SSLSocketFactory.createSSLSocket(String, int)` is
 *   not a platform API. The reflection fails, so the try branch never
 *   succeeds on a normal Android runtime. Barrier's own TLS (fingerprint
 *   pinning of a self-signed certificate) is not implemented either.
 * - [javax.net.ssl.SSLSocketFactory.createSocket] with a host and port already
 *   connects. [connect] then calls [java.net.Socket.connect] again on that
 *   socket.
 * - [connect]'s `useTls` parameter is ignored. [createSocket] reads the
 *   constructor property of the same name. Callers that pass a different
 *   value, including [BarrierClient.performConnect], do not change the socket.
 * - The read loop returns on unknown packets and on I/O errors without
 *   closing the socket and without notifying [BarrierClient]. The client
 *   watchdog may eventually drop the session; it is not told immediately.
 * - [connect] returns immediately if [state] is not [TransportState.DISCONNECTED].
 *   A second connect on a live transport is a silent no-op, which will stall
 *   a reconnect that forgot to [close] first. [close] does reset the state.
 */
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
        // `useTls` is intentionally unread. createSocket() uses the constructor
        // flag. See the class KDoc before "fixing" this by threading the
        // parameter through without also replacing the SSL factory hack.
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
     * Builds the socket [connect] will use.
     *
     * Does not honour a plaintext request. See the class KDoc. The catch block
     * is the path that actually runs, because `createSSLSocket` is not on
     * [SSLSocketFactory].
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
