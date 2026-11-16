package com.crrpendlebury.barrierandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.crrpendlebury.barrierandroid.protocol.BarrierClient
import com.crrpendlebury.barrierandroid.protocol.BarrierError
import com.crrpendlebury.barrierandroid.protocol.BarrierInputEvent
import com.crrpendlebury.barrierandroid.protocol.BarrierClientListener
import com.crrpendlebury.barrierandroid.protocol.BarrierPacket
import com.crrpendlebury.barrierandroid.protocol.BarrierClientState
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

    /** Forwards client lifecycle to the hosting activity and stops the service on unexpected disconnect. */
    private val clientListener = object : BarrierClientListener {
        override fun onStateChanged(state: BarrierClientState) {
            connectionListener?.onStateChanged(state)
            if (state == BarrierClientState.DISCONNECTED) {
                // Unexpected disconnect: release the foreground service.
                mainHandler.post { stopSelf() }
            }
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

    var onClipboardGrab: ((clipboardId: Int, seq: Int) -> ByteArray?)? = null
        private set

    override fun onCreate() = super.onCreate()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read the companion property the activity actually writes. The
        // instance field of the same name was never assigned and is removed.
        val pending = Companion.pendingConnection
        Companion.pendingConnection = null
        if (pending != null) {
            connectionListener = pending.listener
            startConnection(pending)
        }
        startForeground(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopClient()
        serviceScope.cancel()
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
            newClient.onScreenSaverChanged = pending.onScreenSaverChanged
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
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Barrier Input",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
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
    }
}
