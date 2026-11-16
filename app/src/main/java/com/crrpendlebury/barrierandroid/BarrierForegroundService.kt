package com.crrpendlebury.barrierandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.crrpendlebury.barrierandroid.protocol.BarrierClient
import com.crrpendlebury.barrierandroid.protocol.BarrierPacket
import com.crrpendlebury.barrierandroid.protocol.BarrierClientListener
import com.crrpendlebury.barrierandroid.protocol.SocketBarrierTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android 8+ foreground service hosting the [BarrierClient] connection.
 *
 * The client (and its keepalive loop) lives in the service scope so the
 * connection survives while the app is not in the foreground, and the service
 * holds the foreground start notification required on Android 8+.
 */
class BarrierForegroundService : Service() {

    private var client: BarrierClient? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var connectionListener: BarrierClientListener? = null
        private set

    var onClipboardGrab: ((clipboardId: Int, seq: Int) -> ByteArray?)? = null
        private set

    private var pendingConnection: PendingConnection? = null

    override fun onCreate() = super.onCreate()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pending = pendingConnection
        pendingConnection = null
        if (pending != null) {
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
            listener = pending.listener,
        ).also { newClient ->
            newClient.onClipboardGrab = pending.onClipboardGrab
            newClient.onClipboardData = pending.onClipboardData
        }
        client = newClient
        serviceScope.launch {
            newClient.connect(
                serverHost = pending.host,
                serverPort = pending.port,
                useTls = true,
            )
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
     * Connection request handed from the hosting activity to the foreground
     * service; consumed by the next [onStartCommand].
     */
    data class PendingConnection(
        val host: String,
        val port: Int,
        val clientName: String,
        val listener: BarrierClientListener,
        val onClipboardGrab: ((clipboardId: Int, seq: Int) -> ByteArray?)?,
        val onClipboardData: ((clipboardData: BarrierPacket.ClipboardDataPacket) -> Unit)?,
    )

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val CHANNEL_ID = "barrier_foreground_service"

        @Volatile
        var pendingConnection: PendingConnection? = null
    }
}
