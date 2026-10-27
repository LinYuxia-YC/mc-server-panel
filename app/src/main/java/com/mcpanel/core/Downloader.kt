package com.mcpanel.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

object Downloader {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; arm64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 McPanel/1.0"

    class DownloadException(message: String) : Exception(message)

    /**
     * Downloads the first working url to [dest]. Supports HTTP range resume and reports
     * (downloaded, total) where total may be 0 when unknown.
     */
    suspend fun download(
        urls: List<String>,
        dest: File,
        onProgress: ((downloaded: Long, total: Long, url: String) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        var lastError: String = "没有可用下载地址"
        for ((index, url) in urls.withIndex()) {
            if (!coroutineContext.isActive) throw DownloadException("已取消")
            try {
                FileUtils.ensureDir(dest.parentFile ?: throw DownloadException("目录不可用"))
                downloadOnce(url, dest, onProgress)
                if (dest.exists() && dest.length() > 0) return@withContext dest
                lastError = "下载文件为空"
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                if (index < urls.size - 1) {
                    // retry next mirror
                }
            }
        }
        throw DownloadException("下载失败：$lastError")
    }

    private suspend fun downloadOnce(
        url: String,
        dest: File,
        onProgress: ((Long, Long, String) -> Unit)?
    ) {
        var existing = if (dest.exists()) dest.length() else 0L
        var connection = openConnection(url, existing)
        var code = connection.responseCode
        if (code == 416 && existing > 0) {
            // Already complete.
            onProgress?.invoke(existing, existing, url)
            return
        }
        if (code != 200 && code != 206) {
            connection.disconnect()
            // Retry from scratch once (some mirrors reject ranges).
            dest.delete()
            existing = 0
            connection = openConnection(url, 0)
            code = connection.responseCode
            if (code != 200 && code != 206) {
                connection.disconnect()
                throw DownloadException("HTTP $code")
            }
        }

        val total = contentLength(connection, existing)
        val append = code == 206 && existing > 0
        val startAt = if (append) existing else 0L
        connection.inputStream.use { input ->
            FileOutputStream(dest, append).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = startAt
                var lastReport = 0L
                var lastFlush = System.currentTimeMillis()
                while (true) {
                    if (!coroutineContext.isActive) {
                        connection.disconnect()
                        throw DownloadException("已取消")
                    }
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 150) {
                        lastReport = now
                        onProgress?.invoke(downloaded, total, url)
                    }
                    if (now - lastFlush > 2000) {
                        output.flush()
                        lastFlush = now
                    }
                }
                output.flush()
                onProgress?.invoke(downloaded, if (total > 0) total else downloaded, url)
            }
        }
        connection.disconnect()
    }

    private fun openConnection(url: String, startAt: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (startAt > 0) conn.setRequestProperty("Range", "bytes=$startAt-")
        return conn
    }

    private fun contentLength(conn: HttpURLConnection, existing: Long): Long {
        val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
        return if (conn.responseCode == 206 && existing > 0) len + existing else len
    }

    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(url, 0)
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            text
        } catch (_: Throwable) {
            null
        }
    }
}
