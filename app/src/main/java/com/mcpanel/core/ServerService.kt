package com.mcpanel.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mcpanel.App
import com.mcpanel.MainActivity
import com.mcpanel.R

class ServerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var serverName: String = "Minecraft 服务器"

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                serverName = intent.getStringExtra(EXTRA_NAME) ?: serverName
                startForeground(NOTIFICATION_ID, buildNotification())
                acquireLocks()
            }
            ACTION_STOP -> {
                ServerRunner.stop()
            }
            ACTION_STOP_SERVICE -> {
                releaseLocks()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                App.CH_SERVER,
                "服务器运行状态",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, ServerService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, App.CH_SERVER)
            .setSmallIcon(R.drawable.ic_dns)
            .setContentTitle(serverName)
            .setContentText("Minecraft 服务器正在后台运行")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止服务器", pendingStop)
        return builder.build()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcpanel:server")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(12 * 60 * 60 * 1000L)
        } catch (_: Throwable) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mcpanel:wifi")
                wifiLock?.setReferenceCounted(false)
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_START = "com.mcpanel.action.START"
        const val ACTION_STOP = "com.mcpanel.action.STOP"
        const val ACTION_STOP_SERVICE = "com.mcpanel.action.STOP_SERVICE"
        const val EXTRA_NAME = "name"
        const val EXTRA_ID = "id"
        private const val NOTIFICATION_ID = 1001
    }
}
