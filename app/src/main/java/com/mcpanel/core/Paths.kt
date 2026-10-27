package com.mcpanel.core

import android.content.Context
import android.os.Environment
import java.io.File

object Paths {
    private val ctx: Context get() = Prefs.ctx

    val files: File get() = ctx.filesDir
    val javaDir: File get() = File(files, "java")
    val prootEnvDir: File get() = File(javaDir, "proot")
    val rootfs: File get() = File(prootEnvDir, "rootfs")
    val nativeDir: File get() = File(javaDir, "native")
    val tmp: File get() = File(files, "tmp")
    val logs: File get() = File(files, "logs")
    val downloads: File get() = File(files, "downloads")
    val prootTmp: File get() = File(files, "proot-tmp")

    val externalRoot: File
        get() = Environment.getExternalStorageDirectory() ?: File("/sdcard")

    fun defaultServerRoot(): File = File(externalRoot, "MC服务器")

    fun ensure() {
        listOf(javaDir, prootEnvDir, nativeDir, tmp, logs, downloads, prootTmp).forEach {
            if (!it.exists()) it.mkdirs()
        }
    }

    fun rootfsFile(vararg parts: String): File {
        var f = rootfs
        parts.forEach { f = File(f, it) }
        return f
    }
}
