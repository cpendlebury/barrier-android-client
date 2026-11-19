package com.crrpendlebury.barrierandroid.protocol

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TCP transport implementing [BarrierTransport].
 *
 * Plain and TLS are both supported. TLS authenticates the server the way
 * Barrier's own client does: by the SHA-256 fingerprint of the server's leaf
 * certificate, compared against a previously accepted (pinned) fingerprint
 * (Barrier: `ConnectionSecurityLevel.ENCRYPTED_AUTHENTICATED`).
 * `useTls == false` is Barrier's `PLAINTEXT` level.
 *
 * Hostname verification is deliberately not performed: Barrier's server cert is
 * self-signed (CN=Barrier) and used on a LAN, and identity is established by
 * the fingerprint pin rather than a DNS name, matching Barrier's client.
 *
 * [expectedServerFingerprint] is the accepted pin (lowercase hex, no
 * separators), or `null` for trust on first use: the first observed fingerprint
 * is accepted and recorded in [observedServerFingerprint] for the caller to
 * persist. It may be updated before [connect] is called.
 *
 * The constructor's `host`, `port`, and `useTls` are retained for backward
 * compatibility. [connect] uses its own `useTls` argument and ignores the
 * constructor flag. [connect] also takes `host` and `port` arguments and
 * ignores the constructor copies. The constructor copies exist only because
 * [BarrierForegroundService] passes them; they are not read.
 */
class SocketBarrierTransport(
    private val host: String,
    private val port: Int,
    private val useTls: Boolean,
    private val scope: CoroutineScope,
    override var onPacket: ((BarrierPacket) -> Unit)? = null,
    override var onConnectionLost: (() -> Unit)? = null,
    var expectedServerFingerprint: String? = null,
) : BarrierTransport {

    /** SHA-256 fingerprint (lowercase hex, no separators) of the leaf cert
     *  actually presented by the server in the most recent handshake, or null. */
    var observedServerFingerprint: String? = null
        private set

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
        // Build the trust manager per connection so the pinned fingerprint
        // (which may have been accepted/stored since the last connect) is
        // honoured. Barrier's ENCRYPTED_AUTHENTICATED model pins the server
        // leaf certificate by SHA-256 instead of trusting a CA chain.
        val trustManager = if (useTls) {
            BarrierServerTrustManager(expectedServerFingerprint)
        } else {
            null
        }
        val newSocket = createSocket(host, useTls, trustManager)
        newSocket.connect(InetSocketAddress(host, port))
        if (useTls) {
            (newSocket as SSLSocket).startHandshake()
            observedServerFingerprint = trustManager?.observedFingerprint
        } else {
            observedServerFingerprint = null
        }
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
     * Builds an unconnected socket. Plain for `useTls == false`, an
     * unconnected [SSLSocket] with hostname verification disabled and the
     * server pinned by [trustManager] for `useTls == true`. The socket is
     * connected by [connect] afterwards.
     */
    private fun createSocket(host: String, useTls: Boolean, trustManager: X509TrustManager?): Socket {
        if (!useTls) {
            return Socket()
        }
        val tm = trustManager ?: throw IllegalStateException("TLS requires a trust manager")
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(tm), SecureRandom())
        val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
        sslSocket.sslParameters = SSLParameters().apply {
            // Barrier's server cert is self-signed and used on a LAN; identity
            // is established by the fingerprint pin, not a DNS name.
            endpointIdentificationAlgorithm = null
        }
        return sslSocket
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
            // Socket read failure or cancellation. Swallowed here; the client
            // learns about it through onConnectionLost below.
        }
        // Notify only when we did not close explicitly. close() sets
        // closed = true before cancelling this job.
        if (!closed) {
            onConnectionLost?.invoke()
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

    // No companion trust-all manager: TLS authentication is performed by
    // BarrierServerTrustManager (SHA-256 fingerprint pinning).
}
