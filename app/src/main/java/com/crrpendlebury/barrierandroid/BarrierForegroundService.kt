package com.crrpendlebury.barrierandroid

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that is supposed to own the [BarrierClient].
 *
 * Direction of control is unchanged: this process is the Barrier client, not
 * a keyboard for the computer. The service exists so a future working session
 * can outlive the activity. It does not do that today.
 *
 * Wiring: [MainActivity] assigns [Companion.pendingConnection]. [onStartCommand]
 * reads that companion property and copies [PendingConnection.listener] onto
 * [connectionListener]. [startConnection] builds the [BarrierClient] with the
 * internal [clientListener] wrapper (which forwards to [connectionListener]
 * and calls `stopSelf()` on [BarrierClientState.DISCONNECTED]) and copies the
 * clipboard lambdas from the pending connection. The client now actually starts.
 *
 * Remaining caveat: [clientListener] calls `stopSelf()` on every
 * [BarrierClientState.DISCONNECTED], including the transient state published
 * by [BarrierClient.closeTransport] before it schedules a reconnect. With the
 * service alive, that cancels [serviceScope] and the reconnect job. Reconnect
 * only survives if the listener does not stop the service, or if the service
 * is not the reconnect owner. This is tracked as a known limitation, not
 * fixed here.
 *
 * [clientListener] calls `stopSelf()` on every [BarrierClientState.DISCONNECTED].
 * [BarrierClient.closeTransport] notifies that state before it schedules
 * reconnect. Stopping the service cancels [serviceScope] and cancels the
 * reconnect job. Do not "fix" reconnect inside the client without changing
 * this listener, and do not treat disconnect as always fatal if reconnect is
 * meant to run.
 *
 * [onStartCommand] always calls [startForeground] with a type argument and
 * always creates a [android.app.NotificationChannel]. Those require API 29
 * and API 26. minSdk is 22. There is no `SDK_INT` guard. The manifest type
 * is `specialUse` without `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, which Android 14
 * can reject.
 *
 * The notification text is fixed. It does not reflect connection state.
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
            }
            connectionListener?.onStateChanged(state)
        }

        override fun onInputEvent(event: BarrierInputEvent) {
            connectionListener?.onInputEvent(event)
        }

        override fun onError(error: BarrierError) {
            connectionListener?.onError(error)
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
        // Acquire a partial wake lock so the CPU stays awake for keepalive
        // and socket reads while the screen is off. Released in onDestroy.
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BarrierAndroid::Connection").apply {
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
                override fun onLost(network: Network) {
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
        val notification = buildForegroundNotification()
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopClient() {
        client?.disconnect()
        client = null
    }

    private fun startConnection(pending: PendingConnection) {
        stopClient()
        val newClient = BarrierClient(
            clientName = pending.clientName,
            transport = SocketBarrierTransport(
                host = pending.host,
                port = pending.port,
                useTls = true,
                scope = serviceScope,
                onPacket = null,
            ),
            scope = serviceScope,
            listener = clientListener,
        ).also { newClient ->
            newClient.onClipboardGrab = pending.onClipboardGrab
            newClient.onClipboardData = pending.onClipboardData
            newClient.onScreenSaverChanged = { started ->
                pending.onScreenSaverChanged?.invoke(started)
                if (started) {
                    BarrierAccessibilityService.instance?.hideCursor()
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
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Barrier")
            .setContentText("Waiting for Barrier server")
            .setSmallIcon(R.drawable.ic_barrier_notification)
            .build()
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
