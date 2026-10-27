package com.mcpanel.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogCollector {

    /** Collects server logs / crash reports / panel console into a single zip for the user. */
    fun export(profile: ServerProfile, destDir: File): Result<File> {
        val dir = profile.dirFile
        if (!dir.exists()) return Result.failure(IllegalStateException("服务器目录不存在"))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "错误日志-" + FileUtils.sanitizeName(profile.displayTitle) + "-" + stamp + ".zip"
        val zip = File(destDir, name)
        val files = mutableListOf<Pair<File, String>>()

        fun add(file: File, entry: String) {
            if (file.exists() && file.isFile) files.add(file to entry)
        }

        add(File(dir, "logs/latest.log"), "logs/latest.log")
        val logs = File(dir, "logs").listFiles()?.filter { it.isFile && it.name.endsWith(".gz") }
            ?.sortedByDescending { it.lastModified() }?.take(3) ?: emptyList()
        logs.forEach { add(it, "logs/" + it.name) }
        File(dir, "crash-reports").listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }?.take(5)
            ?.forEach { add(it, "crash-reports/" + it.name) }
        dir.listFiles()?.filter {
            it.isFile && (it.name.startsWith("hs_err_pid") || it.name.startsWith("replay_pid")) &&
                it.name.endsWith(".log")
        }?.take(5)?.forEach { add(it, it.name) }
        add(File(dir, "logs/panel-console.log"), "logs/panel-console.log")
        add(File(dir, "server.properties"), "server.properties")
        add(File(dir, "eula.txt"), "eula.txt")
        add(File(Paths.logs, "${profile.id}-last.log"), "panel/last-session.log")

        // panel info
        try {
            val info = buildString {
                appendLine("服务器: ${profile.displayTitle}")
                appendLine("目录: ${profile.dir}")
                appendLine("核心: ${profile.jar}  (${profile.coreType})")
                appendLine("版本: ${profile.mcVersion}")
                appendLine("Java: ${profile.javaId}")
                appendLine("内存: ${profile.ramMb}MB")
                appendLine("端口: ${profile.port}")
                appendLine("在线模式: ${profile.onlineMode}")
                appendLine("时间: ${Date()}")
                appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            }
            val infoFile = File(Paths.logs, "panel-info.tmp")
            FileUtils.writeText(infoFile, info)
            files.add(infoFile to "panel/info.txt")
        } catch (_: Throwable) {
        }

        if (files.isEmpty()) return Result.failure(IllegalStateException("没有找到日志文件"))
        FileUtils.ensureDir(destDir)
        val ok = FileUtils.zipFiles(files, zip)
        return if (ok) Result.success(zip) else Result.failure(IllegalStateException("打包日志失败"))
    }
}
