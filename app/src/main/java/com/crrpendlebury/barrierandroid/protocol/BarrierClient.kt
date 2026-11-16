package com.crrpendlebury.barrierandroid.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Client-side Barrier session state machine.
 *
 * The computer runs the server and owns the keyboard and mouse. This object
 * receives that input and tells [listener]. It does not inject Android events
 * itself. Injection lives in the activity and the accessibility service, and
 * those are not wired to a live client today (see [com.crrpendlebury.barrierandroid.BarrierForegroundService]).
 *
 * Handshake this object actually performs:
 * 1. Send an 11-byte [BarrierPacket.Hello].
 * 2. On a compatible server hello, send [BarrierPacket.HelloBack] carrying [clientName].
 * 3. On `QINF`, send `DINF` using [screenWidth] and [screenHeight]. Both default
 *    to 0. Nothing in the app sets them, so a real server would be told the
 *    screen is 0 by 0.
 * 4. Ignore `CIAK`. Enter [BarrierClientState.CONNECTED] only when `DSOP` arrives.
 *
 * [scope] owns keepalive, the watchdog, and reconnect. Tests pass
 * `backgroundScope` so `runTest` can finish. The app passes the foreground
 * service scope, which is cancelled in `onDestroy`. [listener] is invoked on
 * [scope], not the main thread.
 *
 * [CONNECTING] and [HANDSHAKE] are stored in [currentState] and are not
 * delivered to [BarrierClientListener.onStateChanged].
 *
 * Reconnect runs only after [closeTransport] observed [BarrierClientState.CONNECTED].
 * [disconnect] sets [userInitiatedClose] and must not schedule another attempt.
 * The service's listener calls `stopSelf()` on every disconnect, which cancels
 * [scope] and therefore cancels the reconnect job. Reconnect is unit-tested
 * and is not a feature of the running app until that listener changes.
 */
class BarrierClient(
    private val clientName: String,
    private val transport: BarrierTransport,
    private val scope: CoroutineScope,
    private val listener: BarrierClientListener? = null,
) {

    /**
     * Width and height, in pixels, written into `DINF`.
     *
     * Default 0. The activity never assigns these. A Barrier server uses this
     * geometry to decide when the pointer has entered the screen, so leaving
     * the defaults makes the client unusable even after the socket works.
     * Set both before [connect], from the real display metrics.
     */
    var screenWidth: Int = 0
    var screenHeight: Int = 0

    @Volatile
    private var state: BarrierClientState = BarrierClientState.DISCONNECTED

    /**
     * Sequence from the latest `CINN`. Barrier uses this to ignore stale
     * clipboard traffic after a screen switch. This client stores it and
     * echoes it on [BarrierInputEvent.ScreenLeft], but clipboard handlers do
     * not compare it to `CCLP` / `DCLP` sequence numbers.
     */
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

    /** Screen saver state most recently reported by the server (CSEC). */
    var screenSaverActive: Boolean = false
        private set

    /** Invoked when the server reports a screensaver state change (CSEC). */
    var onScreenSaverChanged: ((started: Boolean) -> Unit)? = null

    /** Options most recently applied from the server (DSOP). */
    var appliedOptions: List<Pair<Int, Int>> = emptyList()
        private set

    /** Invoked when the server applies options (DSOP). */
    var onOptionsChanged: ((options: List<Pair<Int, Int>>) -> Unit)? = null

    /**
     * Last `CLPS` value, non-zero meaning enabled. Default true.
     * Not consulted by [handleClipboardGrab] or [handleClipboardData].
     */
    var clipboardSharingEnabled: Boolean = true
        private set

    /**
     * Last `HART` value, in seconds, or 0 if the server has not sent one.
     * The keepalive loop does not read this. It always waits
     * [KEEP_ALIVE_INTERVAL_MILLIS].
     */
    var heartbeatSeconds: Int = 0
        private set

    /**
     * Last `SSVR` value, non-zero meaning the server asked for screensaver sync.
     * [handleScreenSaver] does not read this. It always records the `CSEC` flag.
     */
    var screenSaverSyncEnabled: Boolean = false
        private set

    /** Supplies clipboard bytes when the server requests a clipboard grab (CCLP -> DCLP). */
    var onClipboardGrab: ((clipboardId: Int, seq: Int) -> ByteArray?)? = null

    val currentState: BarrierClientState
        get() = state

    /**
     * Records the endpoint and starts the handshake by sending hello.
     *
     * Does not notify [listener] of [BarrierClientState.CONNECTING] or
     * [BarrierClientState.HANDSHAKE]. Returns when hello has been written,
     * not when the server has answered. Further packets arrive through
     * [BarrierTransport.onPacket], which this method installs.
     *
     * [useTls] is stored and passed to [BarrierTransport.connect]. The socket
     * implementation currently ignores the argument. See [SocketBarrierTransport].
     */
    suspend fun connect(serverHost: String, serverPort: Int, useTls: Boolean) {
        currentServerHost = serverHost
        currentServerPort = serverPort
        currentServerUseTls = useTls
        performConnect(serverHost, serverPort, useTls)
    }

    /**
     * User-requested close. Sets [userInitiatedClose] before cancelling work
     * so a reconnect loop already inside [scheduleReconnect] exits instead of
     * calling [performConnect] again.
     *
     * Notifies [BarrierClientState.DISCONNECTED]. The foreground service treats
     * that callback as "stop the service", so this also ends the process's
     * copy of the client when the listener wrapper is installed.
     */
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

    /**
     * Records [options] and copies the three ids this client names into fields.
     *
     * Unknown ids stay in [appliedOptions] only. [OPTION_RELATIVE_MOUSE_MOVES]
     * is a named constant and is still unknown to this `when`. None of the
     * stored flags change later packet handling. See the property KDocs.
     */
    private fun applyOptions(options: List<Pair<Int, Int>>) {
        appliedOptions = options
        for ((id, value) in options) {
            when (id) {
                OPTION_CLIPBOARD_SHARING -> clipboardSharingEnabled = value != 0
                OPTION_HEARTBEAT -> heartbeatSeconds = value
                OPTION_SCREEN_SAVER_SYNC -> screenSaverSyncEnabled = value != 0
            }
        }
        onOptionsChanged?.invoke(options)
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
                // This timestamp is also updated by every inbound packet and by
                // handleKeepAlive. The watchdog therefore does not measure
                // "server keepalives missed"; our own sends reset it. Do not
                // describe this as a three-missed-server-keepalive detector
                // until the send path stops touching lastServerActivityNanos.
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
        // Server asked for the clipboard we hold. clipboardSharingEnabled is
        // not checked. A null provider is answered with an empty DCLP, not
        // silence, so the server does not wait forever.
        //
        // mark is encoded as one byte. Copying seq into mark truncates any
        // sequence above 255. See BarrierPacket.ClipboardDataPacket.encode.
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
        screenSaverActive = packet.started
        onScreenSaverChanged?.invoke(packet.started)
    }

    private suspend fun handleResetOptions() {
        appliedOptions = emptyList()
        clipboardSharingEnabled = true
        heartbeatSeconds = 0
        screenSaverSyncEnabled = false
        onOptionsChanged?.invoke(emptyList())
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

    /**
     * Writes `CNOP`. This client sends one after screen enter, screen leave,
     * and every key, pointer, wheel, and button packet. That is local policy,
     * not something [BarrierPacket] requires. Do not remove it without checking
     * whether a server treats a missing ack as a stall.
     */
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
        // Only a drop from CONNECTED reconnects. A failed hello, or close
        // during HANDSHAKE, does not. disconnect() never calls this method;
        // it closes the transport itself and sets userInitiatedClose.
        //
        // The listener is notified DISCONNECTED before the job is launched.
        // BarrierForegroundService stops itself on that callback and cancels
        // the scope this job needs. Unit tests do not install that listener
        // wrapper, which is why they can observe the reconnect.
        if (wasConnected) {
            scheduleReconnect()
        }
    }

    /**
     * Reconnect loop. Waits [backoffMillis], then doubles it up to
     * [MAX_RECONNECT_BACKOFF_MILLIS], then calls [performConnect].
     *
     * The delay is real time in production and virtual time under `runTest`.
     * Tests must `advanceTimeBy` at least [INITIAL_RECONNECT_BACKOFF_MILLIS];
     * `advanceUntilIdle` alone does not release this delay on the test
     * scheduler used here.
     *
     * A successful [performConnect] only means hello was written. The job then
     * exits. The next drop has to call [scheduleReconnect] again. A thrown
     * connect stays in the loop. [userInitiatedClose] is checked after the
     * delay, so a disconnect during the wait still burns one delay.
     */
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
        transport.onConnectionLost = { scope.launch { closeTransport() } }
        state = BarrierClientState.CONNECTING
        try {
            transport.connect(serverHost, serverPort, useTls)
            send(BarrierPacket.Hello(PROTOCOL_MAJOR_VERSION, PROTOCOL_MINOR_VERSION))
            state = BarrierClientState.HANDSHAKE
        } catch (e: Exception) {
            notifyError(BarrierError.ConnectionFailed(e.message ?: "connection failed"))
            closeTransport()
            throw e
        }
    }
}
