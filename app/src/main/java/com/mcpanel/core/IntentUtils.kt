package com.mcpanel.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object IntentUtils {

    private const val MT_PACKAGE = "bin.mt.plus"

    fun isMtInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(MT_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Opens the given directory inside MT Manager (falls back to the system file manager). */
    fun openInMt(ctx: Context, dir: File): Boolean {
        if (!dir.exists()) {
            toast(ctx, "目录不存在")
            return false
        }
        if (isMtInstalled(ctx)) {
            val attempts = listOf("resource/folder", "resource/dir")
            attempts.forEach { type ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setPackage(MT_PACKAGE)
                        setDataAndType(Uri.fromFile(dir), type)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra("path", dir.absolutePath)
                    }
                    ctx.startActivity(intent)
                    return true
                } catch (_: Throwable) {
                }
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setClassName(MT_PACKAGE, "bin.mt.plus.Main")
                    setDataAndType(Uri.fromFile(dir), "resource/folder")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return true
            } catch (_: Throwable) {
            }
        }
        return openFolder(ctx, dir)
    }

    fun openFolder(ctx: Context, dir: File): Boolean {
        val candidates = listOf("resource/folder", "inode/directory", "*/*")
        candidates.forEach { type ->
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(dir), type)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(intent)
                return true
            } catch (_: Throwable) {
            }
        }
        toast(ctx, "没有找到可用的文件管理器")
        return false
    }

    fun openFile(ctx: Context, file: File, mime: String = "*/*"): Boolean {
        if (!file.exists()) {
            toast(ctx, "文件不存在")
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("path", file.absolutePath)
            }
            ctx.startActivity(intent)
            true
        } catch (_: Throwable) {
            toast(ctx, "没有可打开该文件的应用")
            false
        }
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
        }
    }

    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    toast(ctx, "已忽略电池优化，后台运行不受限制")
                    return
                }
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:" + ctx.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }
        } catch (_: Throwable) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + ctx.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Throwable) {
            }
        }
    }

    fun wifiIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (nif in interfaces.asSequence()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("ap") && !name.startsWith("eth")) continue
                for (addr in nif.inetAddresses.asSequence()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun shareText(ctx: Context, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "分享").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
        }
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
