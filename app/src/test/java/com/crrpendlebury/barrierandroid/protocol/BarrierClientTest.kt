package com.crrpendlebury.barrierandroid.protocol

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BarrierClient] against [FakeBarrierTransport]. No sockets, no Android
 * service, no accessibility service.
 *
 * The client is constructed with `backgroundScope` so keepalive and reconnect
 * jobs are not children of the `runTest` body. After `launch { connect }` or
 * [FakeBarrierTransport.emit], call `runCurrent()` before asserting. Plain
 * `advanceUntilIdle()` does not dispatch those jobs on coroutines-test 1.7.3
 * by itself.
 *
 * The reconnect test must also `advanceTimeBy(INITIAL_RECONNECT_BACKOFF_MILLIS)`.
 * `advanceUntilIdle` does not fire that delay. The test does not install the
 * foreground-service listener, so it does not cover `stopSelf()` cancelling
 * the reconnect scope.
 */
class BarrierClientTest {

    private val testScope = TestScope()

    @Test
    fun `client completes handshake with server`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope)

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()

        // Connected to transport, waiting for the server's hello. The client
        // does not send Hello first; Barrier's protocol has the server send it.
        assertEquals(BarrierClientState.HANDSHAKE, client.currentState)
        assertTrue(transport.sentPackets.none { it is BarrierPacket.Hello })

        // Server hello -> client hello back.
        transport.emit(BarrierPacket.Hello(versionMajor = 1, versionMinor = 6))
        runCurrent()
        advanceUntilIdle()
        assertTrue(transport.sentPackets.any { it is BarrierPacket.HelloBack })

        // Server query info -> client screen info -> server info ack -> server options.
        transport.emit(BarrierPacket.QueryInfoPacket())
        runCurrent()
        advanceUntilIdle()
        val screenInfo = transport.sentPackets.filterIsInstance<BarrierPacket.ScreenInfoPacket>().single()
        assertEquals(0, screenInfo.xOrigin)
        assertEquals(0, screenInfo.yOrigin)
        assertEquals(0, screenInfo.width)
        assertEquals(0, screenInfo.height)

        transport.emit(BarrierPacket.InfoAcknowledgedPacket())
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()

        assertEquals(BarrierClientState.CONNECTED, client.currentState)
    }

    @Test
    fun `client delivers key events to listener`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val inputEvents = mutableListOf<BarrierInputEvent>()
        val listener = object : BarrierClientListener {
            override fun onStateChanged(state: BarrierClientState) = Unit
            override fun onInputEvent(event: BarrierInputEvent) {
                inputEvents.add(event)
            }
            override fun onError(error: BarrierError) = Unit
            override fun onServerFingerprintObserved(fingerprint: String?) = Unit
        }
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope, listener = listener)

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()

        transport.emit(BarrierPacket.KeyDownPacket(keyId = 38, modifierMask = 1, button = 66))
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf<BarrierInputEvent>(BarrierInputEvent.KeyDown(keyId = 38, modifierMask = 1, button = 66)), inputEvents)
        // Client acks server input with CNOP.
        assertTrue(transport.sentPackets.any { it is BarrierPacket.NoOpPacket })
    }

    @Test
    fun `client replies to keepalive`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope)

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()
        assertEquals(BarrierClientState.CONNECTED, client.currentState)

        transport.emit(BarrierPacket.KeepAlivePacket())
        runCurrent()
        advanceUntilIdle()

        assertTrue(transport.sentPackets.any { it is BarrierPacket.KeepAlivePacket })
    }

    @Test
    fun `client disconnects on server close`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val states = mutableListOf<BarrierClientState>()
        val listener = object : BarrierClientListener {
            override fun onStateChanged(state: BarrierClientState) { states.add(state) }
            override fun onInputEvent(event: BarrierInputEvent) = Unit
            override fun onError(error: BarrierError) = Unit
            override fun onServerFingerprintObserved(fingerprint: String?) = Unit
        }
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope, listener = listener)

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()

        transport.emit(BarrierPacket.ClosePacket())
        runCurrent()
        advanceTimeBy(INITIAL_RECONNECT_BACKOFF_MILLIS)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(BarrierClientState.CONNECTED, BarrierClientState.DISCONNECTED), states)

        // Server closed the connection; the client schedules a reconnect with
        // backoff and re-runs the handshake. The client does not send Hello;
        // it waits for the server's. State HANDSHAKE is the evidence that
        // performConnect ran again.
        assertEquals(BarrierClientState.HANDSHAKE, client.currentState)
    }

    @Test
    fun `client does not reconnect after user disconnect`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope)

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()

        client.disconnect()
        runCurrent()
        advanceUntilIdle()

        assertEquals(BarrierClientState.DISCONNECTED, client.currentState)
    }

    @Test
    fun `client reports incompatible server version`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val errors = mutableListOf<BarrierError>()
        val listener = object : BarrierClientListener {
            override fun onStateChanged(state: BarrierClientState) = Unit
            override fun onInputEvent(event: BarrierInputEvent) = Unit
            override fun onError(error: BarrierError) { errors.add(error) }
            override fun onServerFingerprintObserved(fingerprint: String?) = Unit
        }
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope, listener = listener)

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.Hello(versionMajor = 2, versionMinor = 0))
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf<BarrierError>(BarrierError.IncompatibleVersion(serverMajorVersion = 2, serverMinorVersion = 0)), errors)
        assertEquals(BarrierClientState.DISCONNECTED, client.currentState)
    }

    @Test
    fun `client replies to clipboard grab with data`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope)

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()

        transport.emit(BarrierPacket.ClipboardGrabPacket(clipboardId = 0, seq = 5))
        runCurrent()
        advanceUntilIdle()

        val dclp = transport.sentPackets.filterIsInstance<BarrierPacket.ClipboardDataPacket>().single()
        assertEquals(0, dclp.clipboardId)
        assertEquals(5, dclp.seq)
    }

    @Test
    fun `client notifies clipboard data received from server`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val clipboardPackets = mutableListOf<BarrierPacket.ClipboardDataPacket>()
        val client = BarrierClient(
            clientName = "AndroidBarrierClient",
            transport = transport,
            scope = backgroundScope,
        )
        client.onClipboardData = { clipboardPackets.add(it) }

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()

        transport.emit(
            BarrierPacket.ClipboardDataPacket(
                clipboardId = 0,
                seq = 7,
                mark = 7,
                data = byteArrayOf(1, 2, 3),
            ),
        )
        runCurrent()
        advanceUntilIdle()

        val dclp = client.receivedClipboardData
        assertEquals(0, dclp?.clipboardId)
        assertEquals(7, dclp?.seq)
        assertEquals(1, clipboardPackets.size)
        assertEquals(dclp, clipboardPackets.single())
    }

    @Test
    fun `client notifies screensaver state change`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val screensaverStates = mutableListOf<Boolean>()
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope)
        client.onScreenSaverChanged = { screensaverStates.add(it) }

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()

        transport.emit(BarrierPacket.ScreenSaverPacket(started = true))
        runCurrent()
        advanceUntilIdle()

        assertTrue(client.screenSaverActive)
        assertEquals(listOf(true), screensaverStates)

        transport.emit(BarrierPacket.ScreenSaverPacket(started = false))
        runCurrent()
        advanceUntilIdle()

        assertTrue(!client.screenSaverActive)
        assertEquals(listOf(true, false), screensaverStates)
    }

    @Test
    fun `client applies server options and notifies`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val optionUpdates = mutableListOf<List<Pair<Int, Int>>>()
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope)
        client.onOptionsChanged = { optionUpdates.add(it) }

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()

        val options = listOf(OPTION_CLIPBOARD_SHARING to 0, OPTION_HEARTBEAT to 30)
        optionUpdates.clear()
        transport.emit(BarrierPacket.SetOptionsPacket(options = options))
        runCurrent()
        advanceUntilIdle()

        assertEquals(options, client.appliedOptions)
        assertTrue(!client.clipboardSharingEnabled)
        assertEquals(30, client.heartbeatSeconds)
        assertEquals(listOf(options), optionUpdates)
    }

    @Test
    fun `client resets options on reset-options packet`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val optionUpdates = mutableListOf<List<Pair<Int, Int>>>()
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope)
        client.onOptionsChanged = { optionUpdates.add(it) }

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = listOf(OPTION_CLIPBOARD_SHARING to 0)))
        runCurrent()
        advanceUntilIdle()

        transport.emit(BarrierPacket.ResetOptionsPacket())
        runCurrent()
        advanceUntilIdle()

        assertEquals(emptyList<Pair<Int, Int>>(), client.appliedOptions)
        assertTrue(client.clipboardSharingEnabled)
        assertEquals(0, client.heartbeatSeconds)
        assertEquals(emptyList<Pair<Int, Int>>(), optionUpdates.last())
    }

    @Test
    fun `client notifies server fingerprint observed after handshake`() = testScope.runTest {
        val transport = FakeBarrierTransport()
        val fingerprints = mutableListOf<String?>()
        val listener = object : BarrierClientListener {
            override fun onStateChanged(state: BarrierClientState) = Unit
            override fun onInputEvent(event: BarrierInputEvent) = Unit
            override fun onError(error: BarrierError) = Unit
            override fun onServerFingerprintObserved(fingerprint: String?) { fingerprints.add(fingerprint) }
        }
        val client = BarrierClient(clientName = "AndroidBarrierClient", transport = transport, scope = backgroundScope, listener = listener)

        backgroundScope.launch { client.connect(serverHost = "192.168.1.50", serverPort = 24800, useTls = false) }
        runCurrent()
        advanceUntilIdle()
        transport.emit(BarrierPacket.SetOptionsPacket(options = emptyList()))
        runCurrent()
        advanceUntilIdle()

        assertEquals(BarrierClientState.CONNECTED, client.currentState)
        // The fake transport does not run TLS, so the observed fingerprint is null.
        assertEquals(listOf<String?>(null), fingerprints)
    }
}
