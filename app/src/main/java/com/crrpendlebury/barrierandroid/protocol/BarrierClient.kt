package com.crrpendlebury.barrierandroid.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Barrier client protocol state machine.
 *
 * Implements the client side of the Barrier control protocol (v1.6):
 * hello / hello-back handshake, screen info exchange, keepalives, input event
 * reception and error handling.
 */
class BarrierClient(
    private val clientName: String,
    private val transport: BarrierTransport,
    private val scope: CoroutineScope,
    private val listener: BarrierClientListener? = null,
) {

    /** Screen size used when replying to server screen info queries. */
    var screenWidth: Int = 0
    var screenHeight: Int = 0

    @Volatile
    private var state: BarrierClientState = BarrierClientState.DISCONNECTED

    @Volatile
    private var currentScreenSeq: Int = 0

    private var keepAliveJob: Job? = null
    private var keepAliveWatchdogJob: Job? = null
    private var lastServerActivityNanos: Long = 0L

    private var reconnectJob: Job? = null
    private var backoffMillis: Long = INITIAL_RECONNECT_BACKOFF_MILLIS
    @Volatile
    private var userInitiatedClose = false

    private var currentServerHost: String? = null
    private var currentServerPort: Int? = null
    private var currentServerUseTls: Boolean? = null

    /** Clipboard data most recently received from the server (DCLP). */
    var receivedClipboardData: BarrierPacket.ClipboardDataPacket? = null

    /** Invoked when clipboard data is received from the server (DCLP). */
    var onClipboardData: ((clipboardData: BarrierPacket.ClipboardDataPacket) -> Unit)? = null

    /** Supplies clipboard bytes when the server requests a clipboard grab (CCLP -> DCLP). */
    var onClipboardGrab: ((clipboardId: Int, seq: Int) -> ByteArray?)? = null

    val currentState: BarrierClientState
        get() = state

    /** Opens a connection to the Barrier server and runs the handshake. */
    suspend fun connect(serverHost: String, serverPort: Int, useTls: Boolean) {
        currentServerHost = serverHost
        currentServerPort = serverPort
        currentServerUseTls = useTls
        performConnect(serverHost, serverPort, useTls)
    }

    /** Closes the connection and stops keepalives. Does not schedule a reconnect. */
    fun disconnect() {
        userInitiatedClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopKeepAlive()
        transport.close()
        state = BarrierClientState.DISCONNECTED
        notifyStateChanged(BarrierClientState.DISCONNECTED)
    }

    // ------------------------------------------------------------------
    // Packet handling
    // ------------------------------------------------------------------

    private suspend fun handlePacket(packet: BarrierPacket) {
        lastServerActivityNanos = System.nanoTime()
        when (packet) {
            is BarrierPacket.Hello -> handleHello(packet)
            is BarrierPacket.HelloBack -> handleHelloBack()
            is BarrierPacket.QueryInfoPacket -> handleQueryInfo()
            is BarrierPacket.ScreenInfoPacket -> Unit // client -> server; not expected from server
            is BarrierPacket.InfoAcknowledgedPacket -> handleInfoAcknowledged()
            is BarrierPacket.SetOptionsPacket -> handleSetOptions(packet)
            is BarrierPacket.KeepAlivePacket -> handleKeepAlive()
            is BarrierPacket.ScreenEnterPacket -> handleScreenEnter(packet)
            is BarrierPacket.ScreenLeftPacket -> handleScreenLeft()
            is BarrierPacket.ClipboardGrabPacket -> handleClipboardGrab(packet)
            is BarrierPacket.ClipboardDataPacket -> handleClipboardData(packet)
            is BarrierPacket.ScreenSaverPacket -> handleScreenSaver(packet)
            is BarrierPacket.ResetOptionsPacket -> handleResetOptions()
            is BarrierPacket.KeyDownPacket -> {
                handleKeyEvent(packet)
                sendNoOp()
            }
            is BarrierPacket.KeyRepeatPacket -> {
                handleKeyEvent(packet)
                sendNoOp()
            }
            is BarrierPacket.KeyUpPacket -> {
                handleKeyEvent(packet)
                sendNoOp()
            }
            is BarrierPacket.MouseMovePacket -> {
                handlePointerEvent(packet)
                sendNoOp()
            }
            is BarrierPacket.MouseRelativeMovePacket -> {
                handlePointerEvent(packet)
                sendNoOp()
            }
            is BarrierPacket.MouseWheelPacket -> {
                handlePointerEvent(packet)
                sendNoOp()
            }
            is BarrierPacket.MouseButtonDownPacket -> {
                handlePointerEvent(packet)
                sendNoOp()
            }
            is BarrierPacket.MouseButtonUpPacket -> {
                handlePointerEvent(packet)
                sendNoOp()
            }
            is BarrierPacket.ClosePacket -> handleClose()
            is BarrierPacket.IncompatibleVersionPacket -> handleIncompatibleVersion(packet)
            is BarrierPacket.BusyPacket -> handleServerRejected("server is busy")
            is BarrierPacket.UnknownPacket -> handleServerRejected("server does not know this client")
            is BarrierPacket.BadPacket -> handleServerRejected("protocol error reported by server")
            is BarrierPacket.FileTransferPacket -> handleFileTransfer(packet)
            is BarrierPacket.DragInfoPacket -> handleDragInfo(packet)
            is BarrierPacket.NoOpPacket -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    private suspend fun handleHello(packet: BarrierPacket.Hello) {
        // The client supports protocol major version PROTOCOL_MAJOR_VERSION with
        // at least PROTOCOL_MINOR_VERSION; anything older is incompatible.
        val compatible = packet.versionMajor == PROTOCOL_MAJOR_VERSION &&
            packet.versionMinor >= PROTOCOL_MINOR_VERSION
        if (!compatible) {
            notifyError(BarrierError.IncompatibleVersion(packet.versionMajor, packet.versionMinor))
            closeTransport()
            return
        }
        transport.send(
            BarrierPacket.HelloBack(
                versionMajor = PROTOCOL_MAJOR_VERSION,
                versionMinor = PROTOCOL_MINOR_VERSION,
                name = clientName,
            ),
        )
    }

    private suspend fun handleHelloBack() {
        // Server -> client hello-back should not occur; nothing to do.
    }

    private suspend fun handleQueryInfo() {
        // Reply with our screen geometry, then await the server's CIAK.
        transport.send(
            BarrierPacket.ScreenInfoPacket(
                xOrigin = 0,
                yOrigin = 0,
                width = screenWidth,
                height = screenHeight,
                obsoleteWarpZone = 0,
                mouseX = 0,
                mouseY = 0,
            ),
        )
    }

    private suspend fun handleInfoAcknowledged() {
        // Server acknowledged our screen info; await options before connecting.
    }

    private suspend fun handleSetOptions(packet: BarrierPacket.SetOptionsPacket) {
        // Apply server options (clipboard sharing, keepalive rate, modifiers).
        applyOptions(packet.options)
        // Handshake complete.
        state = BarrierClientState.CONNECTED
        notifyStateChanged(BarrierClientState.CONNECTED)
        startKeepAlive()
    }

    private fun applyOptions(options: List<Pair<Int, Int>>) {
        for ((id, value) in options) {
            when (id) {
                // Option ids are defined by the protocol; clipboard sharing and
                // keepalive rate are consumed as they become available.
                // kOptionClipboardSharing -> value false disables clipboard sharing.
                // kOptionHeartbeat -> keepalive interval in seconds.
            }
        }
    }

    // ------------------------------------------------------------------
    // Keepalive
    // ------------------------------------------------------------------

    private fun startKeepAlive() {
        stopKeepAlive()
        lastServerActivityNanos = System.nanoTime()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MILLIS)
                if (state != BarrierClientState.CONNECTED) {
                    break
                }
                lastServerActivityNanos = System.nanoTime()
                transport.send(BarrierPacket.KeepAlivePacket())
            }
        }
        keepAliveWatchdogJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MILLIS)
                if (state != BarrierClientState.CONNECTED) {
                    break
                }
                val missedThresholdNanos = TimeUnit.MILLISECONDS.toNanos(
                    (KEEP_ALIVE_INTERVAL_MILLIS * KEEP_ALIVES_UNTIL_DEATH).toLong(),
                )
                if (System.nanoTime() - lastServerActivityNanos >= missedThresholdNanos) {
                    // Three missed keepalives: drop the connection.
                    closeTransport()
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        keepAliveWatchdogJob?.cancel()
        keepAliveWatchdogJob = null
    }

    // ------------------------------------------------------------------
    // Screen events
    // ------------------------------------------------------------------

    private suspend fun handleScreenEnter(packet: BarrierPacket.ScreenEnterPacket) {
        currentScreenSeq = packet.seq
        notifyInput(
            BarrierInputEvent.ScreenEntered(
                x = packet.x,
                y = packet.y,
                seq = packet.seq,
                modifierMask = packet.modifierMask,
            ),
        )
        sendNoOp()
    }

    private suspend fun handleScreenLeft() {
        notifyInput(BarrierInputEvent.ScreenLeft(currentScreenSeq))
        sendNoOp()
    }

    // ------------------------------------------------------------------
    // Input events
    // ------------------------------------------------------------------

    private suspend fun handleKeyEvent(packet: BarrierPacket.KeyDownPacket) {
        notifyInput(
            BarrierInputEvent.KeyDown(
                keyId = packet.keyId,
                modifierMask = packet.modifierMask,
                button = packet.button,
            ),
        )
    }

    private suspend fun handleKeyEvent(packet: BarrierPacket.KeyRepeatPacket) {
        notifyInput(
            BarrierInputEvent.KeyRepeat(
                keyId = packet.keyId,
                modifierMask = packet.modifierMask,
                repeat = packet.repeat,
                button = packet.button,
            ),
        )
    }

    private suspend fun handleKeyEvent(packet: BarrierPacket.KeyUpPacket) {
        notifyInput(
            BarrierInputEvent.KeyUp(
                keyId = packet.keyId,
                modifierMask = packet.modifierMask,
                button = packet.button,
            ),
        )
    }

    private suspend fun handlePointerEvent(packet: BarrierPacket.MouseMovePacket) {
        notifyInput(BarrierInputEvent.MouseMove(x = packet.x, y = packet.y))
    }

    private suspend fun handlePointerEvent(packet: BarrierPacket.MouseRelativeMovePacket) {
        notifyInput(BarrierInputEvent.MouseRelativeMove(dx = packet.dx, dy = packet.dy))
    }

    private suspend fun handlePointerEvent(packet: BarrierPacket.MouseWheelPacket) {
        notifyInput(
            BarrierInputEvent.MouseWheel(
                xDelta = packet.xDelta,
                yDelta = packet.yDelta,
            ),
        )
    }

    private suspend fun handlePointerEvent(packet: BarrierPacket.MouseButtonDownPacket) {
        notifyInput(BarrierInputEvent.MouseButtonDown(button = packet.button))
    }

    private suspend fun handlePointerEvent(packet: BarrierPacket.MouseButtonUpPacket) {
        notifyInput(BarrierInputEvent.MouseButtonUp(button = packet.button))
    }

    // ------------------------------------------------------------------
    // Clipboard / screensaver / options / transfer handling
    // ------------------------------------------------------------------

    private suspend fun handleKeepAlive() {
        // Reply in kind and reset the watchdog activity clock.
        lastServerActivityNanos = System.nanoTime()
        transport.send(BarrierPacket.KeepAlivePacket())
    }

    private suspend fun handleClipboardGrab(packet: BarrierPacket.ClipboardGrabPacket) {
        // The server asked for the clipboard we hold; read it via the registered
        // provider and reply with the clipboard data (DCLP).
        val data = onClipboardGrab?.invoke(packet.clipboardId, packet.seq) ?: ByteArray(0)
        transport.send(
            BarrierPacket.ClipboardDataPacket(
                clipboardId = packet.clipboardId,
                seq = packet.seq,
                mark = packet.seq,
                data = data,
            ),
        )
    }

    private suspend fun handleClipboardData(packet: BarrierPacket.ClipboardDataPacket) {
        // Clipboard data received from the server; retained and surfaced to the host.
        receivedClipboardData = packet
        onClipboardData?.invoke(packet)
    }

    private suspend fun handleScreenSaver(packet: BarrierPacket.ScreenSaverPacket) {
        // Screensaver state changes are handled in Phase 3 hardening.
    }

    private suspend fun handleResetOptions() {
        stopKeepAlive()
        startKeepAlive()
    }

    private suspend fun handleClose() {
        closeTransport()
    }

    private suspend fun handleIncompatibleVersion(packet: BarrierPacket.IncompatibleVersionPacket) {
        notifyError(BarrierError.IncompatibleVersion(packet.majorVersion, packet.minorVersion))
        closeTransport()
    }

    private suspend fun handleServerRejected(reason: String) {
        notifyError(BarrierError.ConnectionFailed(reason))
        closeTransport()
    }

    private suspend fun handleFileTransfer(packet: BarrierPacket.FileTransferPacket) {
        // File transfer handling is deferred to Phase 3 hardening.
    }

    private suspend fun handleDragInfo(packet: BarrierPacket.DragInfoPacket) {
        // Drag information handling is deferred to Phase 3 hardening.
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun send(packet: BarrierPacket) {
        transport.send(packet)
    }

    private suspend fun sendNoOp() {
        transport.send(BarrierPacket.NoOpPacket())
    }

    private fun notifyInput(event: BarrierInputEvent) {
        listener?.onInputEvent(event)
    }

    private fun notifyError(error: BarrierError) {
        listener?.onError(error)
    }

    private fun notifyStateChanged(state: BarrierClientState) {
        listener?.onStateChanged(state)
    }

    private fun closeTransport() {
        stopKeepAlive()
        val wasConnected = state == BarrierClientState.CONNECTED
        transport.close()
        state = BarrierClientState.DISCONNECTED
        notifyStateChanged(BarrierClientState.DISCONNECTED)
        // Server-initiated close while connected: schedule a reconnect with
        // exponential backoff. User-initiated disconnects bypass this path.
        if (wasConnected) {
            scheduleReconnect()
        }
    }

    /** Schedules a reconnect attempt with exponential backoff after a drop. */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) {
            return
        }
        backoffMillis = INITIAL_RECONNECT_BACKOFF_MILLIS
        reconnectJob = scope.launch {
            while (isActive) {
                delay(backoffMillis)
                if (userInitiatedClose) {
                    return@launch
                }
                val host = currentServerHost ?: return@launch
                val port = currentServerPort ?: return@launch
                val useTls = currentServerUseTls ?: false
                backoffMillis = minOf(backoffMillis * 2, MAX_RECONNECT_BACKOFF_MILLIS)
                try {
                    performConnect(host, port, useTls)
                    // Connected; wait for the next failure to reschedule.
                    break
                } catch (e: Exception) {
                    // Attempt failed; loop applies backoff and retries.
                }
            }
        }
    }

    private suspend fun performConnect(serverHost: String, serverPort: Int, useTls: Boolean) {
        transport.onPacket = { packet -> scope.launch { handlePacket(packet) } }
        state = BarrierClientState.CONNECTING
        transport.connect(serverHost, serverPort, useTls)
        send(BarrierPacket.Hello(PROTOCOL_MAJOR_VERSION, PROTOCOL_MINOR_VERSION))
        state = BarrierClientState.HANDSHAKE
    }
}
