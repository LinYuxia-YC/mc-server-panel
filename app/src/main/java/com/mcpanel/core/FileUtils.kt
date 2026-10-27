package com.mcpanel.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FileUtils {

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    fun ensureDir(dir: File): Boolean = dir.exists() || dir.mkdirs()

    fun copy(src: File, dst: File, overwrite: Boolean = true): Boolean {
        if (!src.exists() || src.isDirectory) return false
        if (dst.exists() && !overwrite) return false
        return try {
            ensureDir(dst.parentFile ?: return false)
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun copyUri(ctx: Context, uri: Uri, dst: File): Boolean {
        return try {
            ensureDir(dst.parentFile ?: return false)
            val input: InputStream = ctx.contentResolver.openInputStream(uri) ?: return false
            input.use { i -> dst.outputStream().use { o -> i.copyTo(o, 128 * 1024) } }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun deleteRecursive(target: File): Boolean {
        if (!target.exists()) return true
        if (target.isDirectory) {
            target.listFiles()?.forEach { deleteRecursive(it) }
        }
        return target.delete()
    }

    fun sizeOf(target: File): Long {
        if (!target.exists()) return 0
        if (target.isFile) return target.length()
        var total = 0L
        target.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    fun readText(file: File, maxBytes: Long = 4L * 1024 * 1024): String {
        if (!file.exists() || !file.isFile) return ""
        if (file.length() > maxBytes) {
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(maxBytes.toInt())
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
            return decode(bytes) + "\n\n… 文件过大，仅显示前 ${humanSize(maxBytes)}"
        }
        val bytes = file.readBytes()
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): String {
        // strip UTF-8 BOM
        val data = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            val dec = Charsets.UTF_8.newDecoder()
            dec.decode(java.nio.ByteBuffer.wrap(data)).toString()
        } catch (_: Throwable) {
            try {
                String(data, Charset.forName("GBK"))
            } catch (_: Throwable) {
                String(data, Charsets.ISO_8859_1)
            }
        }
    }

    fun writeText(file: File, text: String): Boolean {
        return try {
            ensureDir(file.parentFile ?: return false)
            file.writeText(text, Charsets.UTF_8)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "server" }

    fun zipDirectory(srcDir: File, zipFile: File, onProgress: ((Int) -> Unit)? = null): Boolean {
        if (!srcDir.exists()) return false
        val files = mutableListOf<File>()
        srcDir.walkTopDown().forEach { if (it.isFile) files.add(it) }
        if (files.isEmpty()) return false
        return try {
            ensureDir(zipFile.parentFile ?: return false)
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val base = srcDir.absolutePath.length + 1
                files.forEachIndexed { index, f ->
                    val name = f.absolutePath.substring(base).replace('\\', '/')
                    zos.putNextEntry(ZipEntry(name))
                    FileInputStream(f).use { it.copyTo(zos, 128 * 1024) }
                    zos.closeEntry()
                    if (index % 32 == 0) onProgress?.invoke((index * 100) / files.size)
                }
            }
            onProgress?.invoke(100)
            true
        } catch (_: Throwable) {
            zipFile.delete()
            false
        }
    }

    fun zipFiles(files: List<Pair<File, String>>, zipFile: File, onProgress: ((Int) -> Unit)? = null): Boolean {
        if (files.isEmpty()) return false
        return try {
            ensureDir(zipFile.parentFile ?: return false)
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                files.forEachIndexed { index, (f, name) ->
                    if (f.exists() && f.isFile) {
                        zos.putNextEntry(ZipEntry(name))
                        f.inputStream().use { it.copyTo(zos, 64 * 1024) }
                        zos.closeEntry()
                    }
                    if (index % 16 == 0) onProgress?.invoke((index * 100) / files.size)
                }
            }
            onProgress?.invoke(100)
            true
        } catch (_: Throwable) {
            zipFile.delete()
            false
        }
    }

    /** Rewrites a properties-style file keeping comments and unknown keys. */
    fun updateProperties(file: File, values: Map<String, String>) {
        val props = PropertiesFile(file)
        props.load()
        values.forEach { (k, v) -> props.set(k, v) }
        props.save()
    }

    fun listFiles(dir: File, extensions: List<String>? = null): List<File> {
        val arr = dir.listFiles() ?: return emptyList()
        return arr.filter {
            it.isFile && (extensions == null || extensions.any { ext -> it.name.endsWith(ext, true) })
        }
    }
}
