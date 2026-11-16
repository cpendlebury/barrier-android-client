package com.crrpendlebury.barrierandroid

/**
 * Connection form for the Barrier Android client.
 *
 * The computer runs the Barrier server and owns the keyboard and mouse. This
 * activity is the phone-side client UI. It does not turn the phone into an
 * input device for the computer, and it does not show the computer's screen.
 *
 * What Connect does today: if the accessibility service is not listed as
 * enabled, it shows a toast and an error line, then continues. It writes a
 * [BarrierForegroundService.PendingConnection] onto the service companion and
 * calls [startService]. The service does not read that companion property, so
 * no socket is opened and [barrierClientListener] is never attached to a
 * [com.crrpendlebury.barrierandroid.protocol.BarrierClient]. Status stays
 * `DISCONNECTED` because connecting and handshake are not listener events
 * anyway.
 *
 * Disconnect, and [onDestroy], stop the service. There is no "keep alive in
 * the background" switch distinct from the activity lifetime.
 *
 * [handleClipboardData] and [readClipboardBytes] run only if a client invokes
 * them. Connect does not create that client. If they are called,
 * [handleClipboardData] replaces the device clipboard and then tries
 * [com.crrpendlebury.barrierandroid.accessibility.BarrierAccessibilityService.injectText],
 * which replaces a focused field rather than typing. Injection failure text
 * distinguishes "service not enabled" from "no focused editable field". The
 * second message is also what you get when the service object exists but the
 * node walk misses.
 *
 * Views are the XML layout `activity_main`. Compose is on the classpath and
 * unused here.
 */

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.crrpendlebury.barrierandroid.accessibility.BarrierAccessibilityService
import com.crrpendlebury.barrierandroid.protocol.BarrierClientListener
import com.crrpendlebury.barrierandroid.protocol.BarrierClientState
import com.crrpendlebury.barrierandroid.protocol.BarrierError
import com.crrpendlebury.barrierandroid.protocol.BarrierInputEvent
import com.crrpendlebury.barrierandroid.protocol.BarrierPacket
import com.crrpendlebury.barrierandroid.protocol.DEFAULT_BARRIER_PORT
import com.crrpendlebury.barrierandroid.protocol.DEFAULT_CLIENT_NAME

class MainActivity : ComponentActivity() {

    private lateinit var serverHostEditText: EditText
    private lateinit var serverPortEditText: EditText
    private lateinit var clientNameEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var accessibilityStatusTextView: TextView
    private lateinit var openAccessibilitySettingsButton: Button
    private lateinit var mouseMovementCheckBox: CheckBox
    private lateinit var errorTextView: TextView

    private var lastPointerX = 0f
    private var lastPointerY = 0f

    private val barrierClientListener = object : BarrierClientListener {
        override fun onStateChanged(state: BarrierClientState) {
            runOnUiThread {
                updateStatus(state)
                if (state == BarrierClientState.CONNECTED) {
                    errorTextView.text = ""
                    BarrierAccessibilityService.instance?.mouseMovementEnabled = mouseMovementCheckBox.isChecked
                }
            }
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

                is BarrierInputEvent.KeyDown -> service.injectKey(event.keyId, KeyEvent.ACTION_DOWN)

                is BarrierInputEvent.KeyRepeat -> service.injectKey(event.keyId, KeyEvent.ACTION_DOWN)

                is BarrierInputEvent.KeyUp -> service.injectKey(event.keyId, KeyEvent.ACTION_UP)
            }
        }

        override fun onError(error: BarrierError) {
            runOnUiThread {
                updateStatus(error)
                val message = when (error) {
                    is BarrierError.IncompatibleVersion ->
                        "incompatible server version ${error.serverMajorVersion}.${error.serverMinorVersion}"
                    is BarrierError.ConnectionFailed -> error.message
                }
                errorTextView.text = "Error: $message"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
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
        accessibilityStatusTextView = findViewById(R.id.accessibilityStatusTextView)
        openAccessibilitySettingsButton = findViewById(R.id.openAccessibilitySettingsButton)
        mouseMovementCheckBox = findViewById(R.id.mouseMovementCheckBox)
        errorTextView = findViewById(R.id.errorTextView)

        connectButton.setOnClickListener { connectToBarrierServer() }
        disconnectButton.setOnClickListener { stopService(Intent(this, BarrierForegroundService::class.java)) }
        openAccessibilitySettingsButton.setOnClickListener { BarrierAccessibilityService.openSettings(this) }
        mouseMovementCheckBox.setOnCheckedChangeListener { _, isChecked ->
            BarrierAccessibilityService.instance?.mouseMovementEnabled = isChecked
        }

        updateStatus(BarrierClientState.DISCONNECTED)
        updateAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    override fun onDestroy() {
        stopService(Intent(this, BarrierForegroundService::class.java))
        super.onDestroy()
    }

    /**
     * Stores a [BarrierForegroundService.PendingConnection] on the service
     * companion and starts the service. Does not construct a client. The
     * service reads a different property; see that class's KDoc.
     *
     * An empty host is not rejected. A non-numeric port becomes
     * [com.crrpendlebury.barrierandroid.protocol.DEFAULT_BARRIER_PORT].
     * [startService] failures are swallowed by [runCatching].
     */
    private fun connectToBarrierServer() {
        val host = serverHostEditText.text.toString().trim()
        val port = serverPortEditText.text.toString().toIntOrNull() ?: DEFAULT_BARRIER_PORT
        val clientName = clientNameEditText.text.toString().trim().ifEmpty { DEFAULT_CLIENT_NAME }

        if (!BarrierAccessibilityService.isEnabled(this)) {
            errorTextView.text = "Accessibility service not enabled; input injection will not work."
            Toast.makeText(this, "Enable the accessibility service first.", Toast.LENGTH_LONG).show()
        } else {
            errorTextView.text = ""
        }

        BarrierForegroundService.pendingConnection = BarrierForegroundService.PendingConnection(
            host = host,
            port = port,
            clientName = clientName,
            listener = barrierClientListener,
            onClipboardGrab = { clipboardId, seq -> readClipboardBytes() },
            onClipboardData = { packet -> handleClipboardData(packet) },
            onScreenSaverChanged = { started ->
                runOnUiThread {
                    statusTextView.text = "Barrier state: ${if (started) "screensaver on" else "screensaver off"}"
                }
            },
        )
        runCatching { startService(Intent(this, BarrierForegroundService::class.java)) }
    }

    private fun readClipboardBytes(): ByteArray? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        val item = clip.getItemAt(0) ?: return null
        val text = item.text?.toString() ?: return ByteArray(0)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun handleClipboardData(packet: BarrierPacket.ClipboardDataPacket) {
        // Store clipboard data received from the server (DCLP) into the device
        // clipboard so subsequent clipboard grabs (CCLP) can serve it and so the
        // content is available for paste/injection use.
        val text = String(packet.data, 0, packet.data.size, Charsets.UTF_8)
        runOnUiThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("barrier", text))
            // Deliver received text into the focused editable app via the
            // accessibility service (ACTION_SET_TEXT).
            val service = BarrierAccessibilityService.instance
            if (service == null || !service.injectText(text)) {
                if (!BarrierAccessibilityService.isEnabled(this@MainActivity)) {
                    errorTextView.text = "Accessibility service not enabled; text not injected."
                } else {
                    errorTextView.text = "Text injection failed (no focused editable field)."
                }
            } else {
                errorTextView.text = ""
            }
        }
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

    private fun updateAccessibilityStatus() {
        val enabled = BarrierAccessibilityService.isEnabled(this)
        accessibilityStatusTextView.text = "Accessibility service: " + if (enabled) "enabled" else "disabled"
        openAccessibilitySettingsButton.visibility = if (enabled) View.GONE else View.VISIBLE
    }
}
