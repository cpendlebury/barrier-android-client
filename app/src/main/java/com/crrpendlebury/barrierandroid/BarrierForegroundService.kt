package com.crrpendlebury.barrierandroid

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.crrpendlebury.barrierandroid.accessibility.BarrierAccessibilityService
import com.crrpendlebury.barrierandroid.protocol.BarrierClient
import com.crrpendlebury.barrierandroid.protocol.BarrierError
import com.crrpendlebury.barrierandroid.protocol.BarrierInputEvent
import com.crrpendlebury.barrierandroid.protocol.BarrierClientListener
import com.crrpendlebury.barrierandroid.protocol.BarrierPacket
import com.crrpendlebury.barrierandroid.protocol.BarrierClientState
import com.crrpendlebury.barrierandroid.protocol.DEFAULT_BARRIER_PORT
import com.crrpendlebury.barrierandroid.protocol.DEFAULT_CLIENT_NAME
import com.crrpendlebury.barrierandroid.protocol.SocketBarrierTransport
import com.crrpendlebury.barrierandroid.protocol.BarrierServerTrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [BarrierClient].
 *
 * Direction of control is unchanged: this process is the Barrier client, not
 * a keyboard for the computer. The service exists so a working session can
 * outlive the activity; it holds the client, the foreground notification, the
 * wake lock, the WiFi lock, and the network callback that drives reconnect.
 *
 * Wiring: [MainActivity] assigns [Companion.pendingConnection]. [onStartCommand]
 * reads that companion property and copies [PendingConnection.listener] onto
 * [connectionListener]. [startConnection] builds the [BarrierClient] with the
 * internal [clientListener] wrapper (which forwards to [connectionListener] and
 * hides the cursor on [BarrierClientState.DISCONNECTED]) and copies the
 * clipboard lambdas from the pending connection. The client now actually starts,
 * and its handshake runs to [BarrierClientState.CONNECTED] against a server
 * speaking protocol 1.6.
 *
 * The client listener forwards [BarrierClientState.DISCONNECTED] to the
 * activity but does not stop the service: [BarrierClient.closeTransport]
 * publishes that state before scheduling the reconnect, so stopping here would
 * cancel [serviceScope] and the reconnect job. The service is stopped by the
 * activity's Disconnect button.
 *
 * [onStartCommand] guards [android.app.NotificationChannel] creation
 * (API 26) and the three-argument [startForeground] (API 29) with `SDK_INT`
 * checks, and the manifest declares `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` for
 * Android 14+, so the service can start on older and newer devices.
 *
 * The foreground notification text reflects connection state: it reads
 * "Waiting for Barrier server" before a client connects, is updated to
 * "Barrier: connected" when the client reaches [BarrierClientState.CONNECTED],
 * and moves to reconnecting text after [BarrierClientState.DISCONNECTED].
 */
class BarrierForegroundService : Service() {

    private var client: BarrierClient? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Forwards client lifecycle to the hosting activity. Does not stop the
     * service on [BarrierClientState.DISCONNECTED]: that state is published by
     * [BarrierClient.closeTransport] before it schedules a reconnect, so
     * stopping here cancels the service scope and the reconnect job. The
     * service is stopped by the activity's Disconnect button or onDestroy.
     */
    private val clientListener = object : BarrierClientListener {
        override fun onStateChanged(state: BarrierClientState) {
            // The cursor is a render surface for injected input; once the client
            // is gone there is nothing to mirror. DISCONNECTED is transient here
            // (the client schedules a reconnect after notifying), and any new
            // input re-shows the cursor.
            if (state == BarrierClientState.DISCONNECTED) {
                BarrierAccessibilityService.instance?.hideCursor()
                BarrierAccessibilityService.log(
                    this@BarrierForegroundService,
                    "client state disconnected; hiding cursor",
                )
            }
            updateForegroundNotification(state)
            connectionListener?.onStateChanged(state)
        }

        override fun onInputEvent(event: BarrierInputEvent) {
            // Host input can arrive while the screen is off or the device is
            // locked. The wake lock was acquired at service start, which may
            // have been while the screen was awake, so its ACQUIRE_CAUSES_WAKEUP
            // effect was consumed then; re-arm it (release + acquire re-applies
            // the flag) on input so the display can be woken by host input. The
            // re-arm outcome, the keyguard state, and the device-interactive
            // state are logged per event so a retest can tell an unwoken display
            // apart from a failed re-arm or from a keyguard that swallows the wake.
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val interactive = powerManager.isInteractive()
            val keyguardLocked = keyguardManager.isKeyguardLocked()
            val keyguardSecure = keyguardManager.isKeyguardSecure()
            wakeLock?.let { lock ->
                val wasHeld = lock.isHeld
                if (wasHeld) {
                    lock.release()
                }
                val acquired = lock.acquire()
                BarrierAccessibilityService.log(
                    this@BarrierForegroundService,
                    "input event: ${event.javaClass.simpleName}; wakeLock wasHeld=$wasHeld; acquire=$acquired; held=${lock.isHeld}; interactive=$interactive; keyguardLocked=$keyguardLocked; keyguardSecure=$keyguardSecure",
                )
            } ?: BarrierAccessibilityService.log(
                this@BarrierForegroundService,
                "input event: ${event.javaClass.simpleName}; wakeLock absent; interactive=$interactive; keyguardLocked=$keyguardLocked; keyguardSecure=$keyguardSecure",
            )
            connectionListener?.onInputEvent(event)
        }

        override fun onError(error: BarrierError) {
            BarrierAccessibilityService.log(this@BarrierForegroundService, "client error: $error")
            connectionListener?.onError(error)
        }

        override fun onServerFingerprintObserved(fingerprint: String?) {
            connectionListener?.onServerFingerprintObserved(fingerprint)
        }
    }

    var connectionListener: BarrierClientListener? = null
        private set

    /** Allows a (re)created activity to re-attach its listener to a running client. */
    fun attachListener(listener: BarrierClientListener) {
        connectionListener = listener
    }

    var onClipboardGrab: ((clipboardId: Int, seq: Int) -> ByteArray?)? = null
        private set

    override fun onCreate() {
        super.onCreate()
        Companion.instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BarrierAccessibilityService.log(this, "foreground service onStartCommand")

        // Acquire a partial wake lock so the CPU stays awake for keepalive
        // and socket reads while the screen is off, and so the display wakes
        // while the session runs (ACQUIRE_CAUSES_WAKEUP). Released in onDestroy.
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "BarrierAndroid::Connection",
            ).apply {
                setReferenceCounted(false)
                acquire() // Hold indefinitely while service runs
            }
        }

        // Acquire a wifi lock to keep WiFi active while backgrounded.
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BarrierAndroid::Connection").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // Register a network callback to detect network changes and trigger
        // a reconnect. When the network drops, close the transport so the
        // client's reconnect loop can retry on the new network.
        if (networkCallback == null) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    BarrierAccessibilityService.log(this@BarrierForegroundService, "network available: $network")
                }

                override fun onLost(network: Network) {
                    BarrierAccessibilityService.log(
                        this@BarrierForegroundService,
                        "network lost: $network client=${client?.currentState}",
                    )
                    client?.let { c ->
                        if (c.currentState == BarrierClientState.CONNECTED) {
                            c.disconnect()
                        }
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }

        // Read the companion property the activity actually writes.
        val pending = Companion.pendingConnection
        Companion.pendingConnection = null
        if (pending != null) {
            connectionListener = pending.listener
            startConnection(pending)
        } else {
            // Service restarted by START_STICKY without a pending connection.
            // Try to reconnect from saved settings.
            val prefs = getSharedPreferences("barrier_settings", Context.MODE_PRIVATE)
            val host = prefs.getString("host", null)
            val port = prefs.getString("port", null)?.toIntOrNull() ?: DEFAULT_BARRIER_PORT
            val clientName = prefs.getString("clientName", null) ?: DEFAULT_CLIENT_NAME
            if (host != null && host.isNotBlank()) {
                val listener = connectionListener ?: object : BarrierClientListener {
                    override fun onStateChanged(state: BarrierClientState) = Unit
                    override fun onInputEvent(event: BarrierInputEvent) = Unit
                    override fun onError(error: BarrierError) = Unit
                    override fun onServerFingerprintObserved(fingerprint: String?) = Unit
                }
                val pendingConn = PendingConnection(
                    host = host,
                    port = port,
                    clientName = clientName,
                    listener = listener,
                    onClipboardGrab = null,
                    onClipboardData = null,
                    onScreenSaverChanged = null,
                )
                startConnection(pendingConn)
            }
        }
        val notification = buildForegroundNotification(client?.currentState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopClient()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        networkCallback = null
        Companion.instance = null
        BarrierAccessibilityService.log(this, "foreground service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopClient() {
        client?.disconnect()
        client = null
    }

    private fun startConnection(pending: PendingConnection) {
        stopClient()
        // Barrier TLS: load any previously accepted server fingerprint so the
        // transport can pin the server by SHA-256 (Barrier:
        // ENCRYPTED_AUTHENTICATED). A null pin means trust on first use.
        val prefs = getSharedPreferences("barrier_settings", Context.MODE_PRIVATE)
        val savedFingerprint = prefs.getString("server_fingerprint", null)
        val transport = SocketBarrierTransport(
            host = pending.host,
            port = pending.port,
            useTls = true,
            scope = serviceScope,
            onPacket = null,
            expectedServerFingerprint = savedFingerprint,
        )
        val newClient = BarrierClient(
            clientName = pending.clientName,
            transport = transport,
            scope = serviceScope,
            listener = clientListener,
        ).also { newClient ->
            newClient.onClipboardGrab = pending.onClipboardGrab
            newClient.onClipboardData = pending.onClipboardData
            newClient.onScreenSaverChanged = { started ->
                pending.onScreenSaverChanged?.invoke(started)
                if (started) {
                    BarrierAccessibilityService.instance?.hideCursor()
                    BarrierAccessibilityService.log(
                        this@BarrierForegroundService,
                        "screensaver started; hiding cursor",
                    )
                }
            }
            // Advertise the device screen size so the server knows the
            // geometry. Previously both stayed 0 and DINF reported 0x0.
            val metrics = resources.displayMetrics
            newClient.screenWidth = metrics.widthPixels
            newClient.screenHeight = metrics.heightPixels
        }
        client = newClient
        serviceScope.launch {
            runCatching {
                newClient.connect(
                    serverHost = pending.host,
                    serverPort = pending.port,
                    useTls = true,
                )
            }.onSuccess {
                // Trust on first use: if no pin was stored yet, accept and
                // persist the fingerprint presented by the server so subsequent
                // connects pin against it (Barrier's accept-and-store flow).
                if (savedFingerprint == null) {
                    val observed = newClient.observedServerFingerprint
                    if (observed != null) {
                        prefs.edit()
                            .putString("server_fingerprint", observed)
                            .commit()
                        BarrierAccessibilityService.log(
                            this@BarrierForegroundService,
                            "server fingerprint accepted and stored: ${BarrierServerTrustManager.format(observed)}",
                        )
                    }
                }
            }
        }
    }

    private fun buildForegroundNotification(state: BarrierClientState?): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Barrier Input",
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(channel)
        }
        val contentText = when {
            state == BarrierClientState.CONNECTED -> "Barrier: connected"
            state == BarrierClientState.DISCONNECTED -> "Barrier: reconnecting"
            else -> "Waiting for Barrier server"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Barrier")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_barrier_notification)
            .build()
    }

    private fun updateForegroundNotification(state: BarrierClientState?) {
        // The client listener fires from the service coroutine scope (background);
        // notifications are posted on the main thread.
        mainHandler.post {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(
                FOREGROUND_NOTIFICATION_ID,
                buildForegroundNotification(state),
            )
        }
    }

    /**
     * Connection request the activity builds.
     *
     * [listener] is the activity's [BarrierClientListener]. It is assigned to
     * [connectionListener] in [onStartCommand] so the internal [clientListener]
     * wrapper can forward state, input, and errors back to the activity.
     */
    data class PendingConnection(
        val host: String,
        val port: Int,
        val clientName: String,
        val listener: BarrierClientListener,
        val onClipboardGrab: ((clipboardId: Int, seq: Int) -> ByteArray?)?,
        val onClipboardData: ((clipboardData: BarrierPacket.ClipboardDataPacket) -> Unit)?,
        val onScreenSaverChanged: ((started: Boolean) -> Unit)? = null,
    )

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val CHANNEL_ID = "barrier_foreground_service"

        /**
         * Set by [MainActivity] immediately before [startService].
         *
         * Read by [onStartCommand]. `@Volatile` matters because the activity
         * writes from the UI thread and the service reads on the main thread
         * of the service process (same process here, but still cross-thread).
         */
        @Volatile
        var pendingConnection: PendingConnection? = null

        @Volatile
        var instance: BarrierForegroundService? = null
            private set
    }
}
