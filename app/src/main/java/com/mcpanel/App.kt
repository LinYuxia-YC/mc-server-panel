package com.mcpanel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import com.mcpanel.core.Paths
import com.mcpanel.core.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        // Allow file:// Uris to be handed to file managers such as MT Manager.
        try {
            StrictMode::class.java
                .getDeclaredMethod("disableDeathOnFileUriExposure")
                .invoke(null)
        } catch (_: Throwable) {
        }
        createChannels()
        try {
            Paths.ensure()
        } catch (_: Throwable) {
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CH_SERVER,
            "服务器运行状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "在后台保持 Minecraft 服务器运行"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        lateinit var instance: App
            private set
        const val CH_SERVER = "server_run"
    }
}
