package com.crrpendlebury.barrierandroid

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.crrpendlebury.barrierandroid.accessibility.BarrierAccessibilityService
import com.crrpendlebury.barrierandroid.protocol.BarrierClient
import com.crrpendlebury.barrierandroid.protocol.BarrierClientListener
import com.crrpendlebury.barrierandroid.protocol.BarrierClientState
import com.crrpendlebury.barrierandroid.protocol.BarrierError
import com.crrpendlebury.barrierandroid.protocol.BarrierInputEvent
import com.crrpendlebury.barrierandroid.protocol.DEFAULT_BARRIER_PORT
import com.crrpendlebury.barrierandroid.protocol.DEFAULT_CLIENT_NAME
import com.crrpendlebury.barrierandroid.protocol.SocketBarrierTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var serverHostEditText: EditText
    private lateinit var serverPortEditText: EditText
    private lateinit var clientNameEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusTextView: TextView

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var barrierClient: BarrierClient? = null
    private var lastPointerX = 0f
    private var lastPointerY = 0f

    private val barrierClientListener = object : BarrierClientListener {
        override fun onStateChanged(state: BarrierClientState) {
            runOnUiThread { updateStatus(state) }
        }

        override fun onInputEvent(event: BarrierInputEvent) {
            // Phase 2: inject input via the accessibility service.
            val service = BarrierAccessibilityService.instance ?: return
            when (event) {
                is BarrierInputEvent.MouseMove -> {
                    service.injectPointer(event.x.toFloat(), event.y.toFloat(), MotionEvent.ACTION_MOVE)
                    lastPointerX = event.x.toFloat()
                    lastPointerY = event.y.toFloat()
                }

                is BarrierInputEvent.MouseRelativeMove -> {
                    val x = lastPointerX + event.dx.toFloat()
                    val y = lastPointerY + event.dy.toFloat()
                    service.injectPointer(x, y, MotionEvent.ACTION_MOVE)
                    lastPointerX = x
                    lastPointerY = y
                }

                is BarrierInputEvent.MouseWheel -> {
                    service.injectPointer(0f, 0f, MotionEvent.ACTION_SCROLL)
                }

                is BarrierInputEvent.MouseButtonDown -> {
                    service.injectPointer(lastPointerX, lastPointerY, MotionEvent.ACTION_DOWN)
                }

                is BarrierInputEvent.MouseButtonUp -> {
                    service.injectPointer(lastPointerX, lastPointerY, MotionEvent.ACTION_UP)
                }

                is BarrierInputEvent.ScreenEntered, is BarrierInputEvent.ScreenLeft -> {
                    // Screen tracking is handled separately (Phase 3 hardening).
                }

                is BarrierInputEvent.KeyDown, is BarrierInputEvent.KeyRepeat, is BarrierInputEvent.KeyUp -> {
                    // Discrete key injection via the framework key dispatch is not exposed
                    // through the accessibility service API surface in compileSdk 35; key
                    // events delivered through the service's onKeyEvent are handled there.
                }
            }
        }

        override fun onError(error: BarrierError) {
            runOnUiThread { updateStatus(error) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serverHostEditText = findViewById(R.id.serverHostEditText)
        serverPortEditText = findViewById(R.id.serverPortEditText)
        clientNameEditText = findViewById(R.id.clientNameEditText)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        statusTextView = findViewById(R.id.statusTextView)

        connectButton.setOnClickListener { connectToBarrierServer() }
        disconnectButton.setOnClickListener { barrierClient?.disconnect() }

        updateStatus(BarrierClientState.DISCONNECTED)
    }

    override fun onDestroy() {
        barrierClient?.disconnect()
        stopService(Intent(this, BarrierAccessibilityService::class.java))
        clientScope.cancel()
        super.onDestroy()
    }

    private fun connectToBarrierServer() {
        val host = serverHostEditText.text.toString().trim()
        val port = serverPortEditText.text.toString().toIntOrNull() ?: DEFAULT_BARRIER_PORT
        val clientName = clientNameEditText.text.toString().trim().ifEmpty { DEFAULT_CLIENT_NAME }

        barrierClient?.disconnect()
        val client = BarrierClient(
            clientName = clientName,
            transport = SocketBarrierTransport(
                host = host,
                port = port,
                useTls = true,
                scope = clientScope,
                onPacket = null,
            ),
            scope = clientScope,
            listener = barrierClientListener,
        )
        client.onClipboardGrab = { clipboardId, seq -> readClipboardBytes() }
        barrierClient = client
        runCatching { startService(Intent(this, BarrierAccessibilityService::class.java)) }
        clientScope.launch { client.connect(serverHost = host, serverPort = port, useTls = true) }
    }

    private fun readClipboardBytes(): ByteArray? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        val item = clip.getItemAt(0) ?: return null
        val text = item.text?.toString() ?: return ByteArray(0)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun updateStatus(state: BarrierClientState) {
        statusTextView.text = "Barrier state: $state"
    }

    private fun updateStatus(error: BarrierError) {
        val description = when (error) {
            is BarrierError.IncompatibleVersion ->
                "incompatible server version ${error.serverMajorVersion}.${error.serverMinorVersion}"
            is BarrierError.ConnectionFailed -> error.message
        }
        statusTextView.text = "Barrier error: $description"
    }
}
