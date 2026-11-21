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
 * calls [startService]. The service reads that companion property and builds
 * the [com.crrpendlebury.barrierandroid.protocol.BarrierClient]; the client's
 * handshake runs on the foreground service scope, and the client reaches
 * [BarrierClientState.CONNECTED] from a server speaking protocol 1.6.
 *
 * Disconnect stops the service. Leaving the activity does not stop the
 * service; the foreground service survives backgrounding. There is no separate
 * "keep alive in the background" control — the foreground service is always
 * active while connected.
 *
 * [handleClipboardData] and [readClipboardBytes] run only when the client
 * invokes them. The clipboard lambdas from the pending connection are copied
 * onto the client, so a clipboard grab from the server reaches them. When the
 * server pushes clipboard data (DCLP), [handleClipboardData] stores it into the
 * device clipboard via ClipboardManager rather than injecting it into whatever
 * field is focused, so the content is available for an explicit user-initiated
 * paste instead of landing in the focused field on arrival.
 *
 * Views are the XML layout `activity_main`. Compose is on the classpath and
 * unused here.
 */

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
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
import com.crrpendlebury.barrierandroid.ime.BarrierIME
import com.crrpendlebury.barrierandroid.ime.BarrierKeyMap
import com.crrpendlebury.barrierandroid.ime.ImeSwitcher

class MainActivity : ComponentActivity() {

    private lateinit var serverHostEditText: EditText
    private lateinit var serverPortEditText: EditText
    private lateinit var clientNameEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var accessibilityStatusTextView: TextView
    private lateinit var openAccessibilitySettingsButton: Button
    private lateinit var imeStatusTextView: TextView
    private lateinit var openKeyboardSettingsButton: Button
    private lateinit var errorTextView: TextView

    private var lastPointerX = 0f
    private var lastPointerY = 0f

    private val barrierClientListener = object : BarrierClientListener {
        override fun onStateChanged(state: BarrierClientState) {
            runOnUiThread {
                updateStatus(state)
                if (state == BarrierClientState.CONNECTED) {
                    errorTextView.text = ""
                    if (!isBarrierImeEnabled()) {
                        errorTextView.text = "Barrier keyboard not enabled; keystrokes will not be delivered. Enable it in keyboard settings."
                        Toast.makeText(this@MainActivity, "Enable the Barrier keyboard in keyboard settings.", Toast.LENGTH_LONG).show()
                    } else {
                        // Switch to the Barrier IME so server keystrokes can be
                        // delivered into the focused field.
                        ImeSwitcher.useOurs(this@MainActivity)
                        // If the IME still isn't bound, open the picker.
                        if (BarrierIME.instance == null) {
                            ImeSwitcher.showPicker(this@MainActivity)
                        }
                    }
                } else if (state == BarrierClientState.DISCONNECTED) {
                    ImeSwitcher.restore(this@MainActivity, leaving = true)
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

                is BarrierInputEvent.KeyDown -> handleBarrierKey(event.keyId, event.modifierMask, KeyEvent.ACTION_DOWN)

                is BarrierInputEvent.KeyRepeat -> handleBarrierKey(event.keyId, event.modifierMask, KeyEvent.ACTION_DOWN)

                is BarrierInputEvent.KeyUp -> handleBarrierKey(event.keyId, event.modifierMask, KeyEvent.ACTION_UP)
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

    private val prefs by lazy { getSharedPreferences("barrier_settings", Context.MODE_PRIVATE) }

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
        imeStatusTextView = findViewById(R.id.imeStatusTextView)
        openKeyboardSettingsButton = findViewById(R.id.openKeyboardSettingsButton)
        errorTextView = findViewById(R.id.errorTextView)

        connectButton.setOnClickListener { connectToBarrierServer() }
        disconnectButton.setOnClickListener {
            BarrierAccessibilityService.log(this, "disconnect button pressed")
            stopService(Intent(this, BarrierForegroundService::class.java))
        }
        openAccessibilitySettingsButton.setOnClickListener { BarrierAccessibilityService.openSettings(this) }
        openKeyboardSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        // Restore from SharedPreferences (survives process death).
        serverHostEditText.setText(prefs.getString("host", ""))
        serverPortEditText.setText(prefs.getString("port", ""))
        val savedClientName = prefs.getString("clientName", "")
        // Show the default if the saved value is blank, so the field is never empty.
        clientNameEditText.setText(if (savedClientName.isNullOrBlank()) DEFAULT_CLIENT_NAME else savedClientName)

        // Save on every text change so settings survive even abrupt process death.
        val saveSettings = {
            prefs.edit()
                .putString("host", serverHostEditText.text.toString())
                .putString("port", serverPortEditText.text.toString())
                .putString("clientName", clientNameEditText.text.toString())
                .commit()
        }
        serverHostEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { saveSettings() }
        })
        serverPortEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { saveSettings() }
        })
        clientNameEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { saveSettings() }
        })

        // Also restore from savedInstanceState if available (overrides prefs).
        savedInstanceState?.let { bundle ->
            serverHostEditText.setText(bundle.getString("host", serverHostEditText.text.toString()))
            serverPortEditText.setText(bundle.getString("port", serverPortEditText.text.toString()))
            clientNameEditText.setText(bundle.getString("clientName", clientNameEditText.text.toString()))
        }

        updateStatus(BarrierClientState.DISCONNECTED)
        updateAccessibilityStatus()
        updateImeStatus()

        // Re-attach to a running service if the activity was recreated.
        BarrierForegroundService.instance?.let { service ->
            service.attachListener(barrierClientListener)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("host", serverHostEditText.text.toString())
        outState.putString("port", serverPortEditText.text.toString())
        outState.putString("clientName", clientNameEditText.text.toString())
    }

    override fun onPause() {
        super.onPause()
        BarrierAccessibilityService.log(this, "activity paused")
        // Persist settings on every pause so they survive process death.
        // Use commit() for synchronous disk write.
        prefs.edit()
            .putString("host", serverHostEditText.text.toString())
            .putString("port", serverPortEditText.text.toString())
            .putString("clientName", clientNameEditText.text.toString())
            .commit()
    }

    override fun onResume() {
        super.onResume()
        BarrierAccessibilityService.log(this, "activity resumed")
        updateAccessibilityStatus()
        updateImeStatus()
        requestBatteryOptimizationExemption()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Retry switching now that the window token is available.
            if (isBarrierImeEnabled()) {
                ImeSwitcher.useOurs(this)
            }
        }
    }

    override fun onStop() {
        BarrierAccessibilityService.log(this, "activity stopped")
        super.onStop()
    }

    override fun onDestroy() {
        BarrierAccessibilityService.log(this, "activity destroyed")
        // Do not stop the service here. The service is a foreground service
        // that should survive activity destruction (backgrounding). The user
        // disconnects explicitly via the Disconnect button.
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

        // Persist settings so they survive process death. Use commit() for
        // synchronous disk write — apply() is async and may not flush before
        // the process is killed.
        prefs.edit()
            .putString("host", host)
            .putString("port", port.toString())
            .putString("clientName", clientName)
            .commit()

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
        // content is available for an explicit user-initiated paste. The payload
        // is deliberately NOT injected into whatever field happens to be
        // focused; delivery into a field is an explicit paste action, not an
        // arrival side effect.
        val text = String(packet.data, 0, packet.data.size, Charsets.UTF_8)
        runOnUiThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("barrier", text))
            errorTextView.text = ""
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

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            runCatching { startActivity(intent) }
        }
    }

    private fun updateAccessibilityStatus() {
        val enabled = BarrierAccessibilityService.isEnabled(this)
        accessibilityStatusTextView.text = "Accessibility service: " + if (enabled) "enabled" else "disabled"
        openAccessibilitySettingsButton.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun isBarrierImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            ?: return false
        return imm.enabledInputMethodList.any {
            it.id.contains("BarrierIME") || it.id.contains("barrierandroid.ime")
        }
    }

    private fun updateImeStatus() {
        val enabled = isBarrierImeEnabled()
        imeStatusTextView.text = "Keyboard (IME): " + if (enabled) "enabled" else "disabled (enable in keyboard settings)"
        openKeyboardSettingsButton.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    /**
     * Routes a Barrier key event to the Barrier IME when it is the active input
     * method, falling back to the accessibility service's global-action path
     * (Back/Home/Recents) otherwise. Modifier state is tracked from the Barrier
     * modifier mask and applied to the committed/sent key.
     */
    private fun handleBarrierKey(keyId: Int, modifierMask: Int, action: Int) {
        val ime = BarrierIME.instance
        val conn = ime?.connection
        val isDown = action == KeyEvent.ACTION_DOWN
        BarrierAccessibilityService.log(
            this,
            "handleBarrierKey keyId=0x${keyId.toString(16)} mask=$modifierMask action=$action ime=${ime != null} conn=${conn != null}",
        )
        // If the IME is enabled but not bound, try to switch again.
        if (ime == null && isBarrierImeEnabled()) {
            ImeSwitcher.useOurs(this)
        }

        // Back key (Escape / Alt+Left): global Back action, regardless of IME.
        if (BarrierKeyMap.isBackKey(keyId, modifierMask)) {
            if (isDown) {
                BarrierAccessibilityService.instance?.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                )
            }
            return
        }

        // Modifier keys: update tracked state, also forward as a key event.
        if (BarrierKeyMap.isModifier(keyId)) {
            ime?.updateModifiers(modifierMask)
            val code = BarrierKeyMap.keyCodeFor(keyId)
            if (code != 0 && conn != null) {
                ime?.sendKey(code, ime.metaState, isDown)
            }
            if (!isDown) ime?.clearModifiers()
            return
        }

        // Host Ctrl+V (paste): deliver the stored clipboard content into the
        // focused field instead of committing the literal character. The Barrier
        // server reports Ctrl-modified keys with the CONTROL bit (0x0002) set in
        // the modifier mask and the letter's keysym as the key id, so Ctrl+V
        // arrives here as keyId 0x76 with mask 0x0002.
        if ((modifierMask and 0x0002) != 0 && keyId == 0x76) {
            if (isDown) {
                BarrierIME.instance?.paste()
            }
            return
        }

        // Host Ctrl+C (copy) / Ctrl+X (cut): source from the focused editable
        // field via the accessibility service and land in the Android clipboard,
        // so a later host Ctrl+V (paste) delivers it. Copy keeps the field;
        // cut clears it (no selection-capable action exists).
        if ((modifierMask and 0x0002) != 0 && keyId == 0x43) {
            if (isDown) {
                BarrierAccessibilityService.instance?.copyFocusedEditableText()
            }
            return
        }
        if ((modifierMask and 0x0002) != 0 && keyId == 0x58) {
            if (isDown) {
                BarrierAccessibilityService.instance?.cutFocusedEditableText()
            }
            return
        }

        if (conn == null) {
            // No focused editable field / IME not active: fall back to the
            // accessibility service's (limited) key path.
            BarrierAccessibilityService.instance?.injectKey(keyId, action)
            return
        }

        ime?.updateModifiers(modifierMask)
        val shift = (modifierMask and 0x0001) != 0

        if (BarrierKeyMap.isPrintable(keyId)) {
            if (isDown) {
                BarrierKeyMap.charFor(keyId, shift)?.let { ime?.commitText(it.toString()) }
            }
        } else {
            val code = BarrierKeyMap.keyCodeFor(keyId)
            if (code != 0) {
                ime?.sendKey(code, ime?.metaState ?: 0, isDown)
            }
        }
        if (!isDown) ime?.clearModifiers()
    }
}
