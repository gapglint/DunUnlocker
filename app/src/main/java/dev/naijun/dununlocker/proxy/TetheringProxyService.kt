package dev.naijun.dununlocker.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.naijun.dununlocker.MainActivity
import dev.naijun.dununlocker.R

class TetheringProxyService : Service() {
    companion object {
        private const val TAG = "TetheringProxyService"
        private const val CHANNEL_ID = "tethering_proxy"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "dev.naijun.dununlocker.proxy.ACTION_START"
        private const val ACTION_STOP = "dev.naijun.dununlocker.proxy.ACTION_STOP"
        private const val EXTRA_PORT = "port"

        fun start(context: Context, port: Int) {
            TetheringProxyState.savePort(context, port)
            val intent = Intent(context, TetheringProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TetheringProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var proxyServer: LocalProxyServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, TetheringProxyState.loadSavedPort(this))
                    ?: TetheringProxyState.loadSavedPort(this)
                startProxy(port)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun startProxy(port: Int) {
        if (proxyServer != null && TetheringProxyState.state.value.isRunning &&
            TetheringProxyState.state.value.port == port
        ) {
            TetheringProxyState.refreshEndpoints()
            updateNotification(port)
            return
        }

        stopProxy()
        ensureNotificationChannel()

        val server = LocalProxyServer(
            port = port,
            onActiveConnectionsChanged = { count ->
                TetheringProxyState.setActiveConnections(count)
            }
        )

        try {
            server.start()
            proxyServer = server
            TetheringProxyState.setRunning(port)
            val notification = buildNotification(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local proxy on port $port", e)
            runCatching { server.stop() }
            proxyServer = null
            TetheringProxyState.setStopped(
                getString(R.string.proxy_start_failed, e.message ?: getString(R.string.unknown_error))
            )
            stopSelf()
        }
    }

    private fun stopProxy() {
        proxyServer?.stop()
        proxyServer = null
        if (TetheringProxyState.state.value.isRunning) {
            TetheringProxyState.setStopped()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.proxy_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(port: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(port))
    }

    private fun buildNotification(port: Int): Notification {
        val endpoints = TetheringProxyState.state.value.endpoints
        val primaryIp = endpoints.firstOrNull()?.ip
        val contentText = if (primaryIp != null) {
            getString(R.string.proxy_notification_address, "http://$primaryIp:$port")
        } else {
            getString(R.string.proxy_notification_waiting, port)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TetheringProxyService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.proxy_stop_action), stopIntent)
            .build()
    }
}
