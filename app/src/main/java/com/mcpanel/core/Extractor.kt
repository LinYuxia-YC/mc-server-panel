package com.mcpanel.core

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extractors for the archives used by the runtime installer:
 * .tar.xz / .tar.gz / .zip / .deb (ar + data.tar.xz).
 */
object Extractor {

    class ExtractException(message: String) : Exception(message)

    fun extract(archive: File, destDir: File, stripSingleRoot: Boolean = false, onProgress: ((Int) -> Unit)? = null) {
        if (!archive.exists()) throw ExtractException("压缩包不存在")
        FileUtils.ensureDir(destDir)
        val staging = File(destDir.parentFile, destDir.name + ".extracting")
        FileUtils.deleteRecursive(staging)
        FileUtils.ensureDir(staging)
        try {
            val name = archive.name.lowercase()
            val header = readHeader(archive)
            when {
                header.startsWith("PK") || name.endsWith(".zip") -> extractZip(archive, staging, onProgress)
                header.startsWith("\u001F\u008B") || name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                    extractTar(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive))), archive.length(), staging, onProgress)
                header.startsWith("\u00FD7zXZ") || name.endsWith(".tar.xz") || name.endsWith(".xz") ->
                    extractTar(XZCompressorInputStream(BufferedInputStream(FileInputStream(archive))), archive.length(), staging, onProgress)
                header.startsWith("!<arch>") || name.endsWith(".deb") ->
                    extractDeb(archive, staging, onProgress)
                name.endsWith(".tar") ->
                    extractTar(BufferedInputStream(FileInputStream(archive)), archive.length(), staging, onProgress)
                else -> throw ExtractException("不支持的压缩格式")
            }
            if (stripSingleRoot) {
                val children = staging.listFiles() ?: emptyArray()
                val roots = children.filter { !it.name.startsWith(".") }
                if (roots.size == 1 && roots[0].isDirectory) {
                    moveContents(roots[0], destDir)
                    FileUtils.deleteRecursive(staging)
                } else {
                    moveContents(staging, destDir)
                }
            } else {
                moveContents(staging, destDir)
            }
            onProgress?.invoke(100)
        } catch (e: Throwable) {
            FileUtils.deleteRecursive(staging)
            if (e is ExtractException) throw e
            throw ExtractException("解压失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun readHeader(file: File): String {
        return try {
            val buf = ByteArray(8)
            FileInputStream(file).use { it.read(buf) }
            String(buf, Charsets.ISO_8859_1)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun moveContents(from: File, to: File) {
        FileUtils.ensureDir(to)
        from.listFiles()?.forEach { child ->
            val target = File(to, child.name)
            if (target.exists()) FileUtils.deleteRecursive(target)
            if (!child.renameTo(target)) {
                if (child.isDirectory) {
                    copyDirectory(child, target)
                } else {
                    FileUtils.copy(child, target)
                }
                FileUtils.deleteRecursive(child)
            }
        }
    }

    private fun copyDirectory(src: File, dst: File) {
        FileUtils.ensureDir(dst)
        src.listFiles()?.forEach { child ->
            val target = File(dst, child.name)
            if (child.isDirectory) copyDirectory(child, target) else FileUtils.copy(child, target)
        }
    }

    private fun extractDeb(archive: File, dest: File, onProgress: ((Int) -> Unit)?) {
        FileInputStream(archive).use { fis ->
            val global = ByteArray(8)
            if (fis.read(global) != 8) throw ExtractException("无效的 deb 文件")
            while (true) {
                val header = ByteArray(60)
                var read = 0
                while (read < 60) {
                    val n = fis.read(header, read, 60 - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 60) break
                val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull() ?: 0L
                if (name.startsWith("data.tar")) {
                    val dataStream: InputStream = when {
                        name.endsWith(".xz") -> XZCompressorInputStream(BufferedInputStream(readerLimited(fis, size)))
                        name.endsWith(".gz") -> GzipCompressorInputStream(BufferedInputStream(readerLimited(fis, size)))
                        else -> BufferedInputStream(readerLimited(fis, size))
                    }
                    dataStream.use { extractTar(it, size, dest, onProgress) }
                    return
                }
                // skip entry (padded to even bytes)
                val skip = size + if (size % 2L == 1L) 1 else 0
                var remaining = skip
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = fis.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    remaining -= n
                }
            }
        }
        throw ExtractException("deb 中未找到 data.tar")
    }

    /** Wraps a stream so that at most [limit] bytes can be read from the underlying stream. */
    private fun readerLimited(fis: FileInputStream, limit: Long): InputStream {
        return object : InputStream() {
            var remaining = limit
            override fun read(): Int {
                if (remaining <= 0) return -1
                val b = fis.read()
                if (b >= 0) remaining--
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = fis.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (n > 0) remaining -= n
                return n
            }
        }
    }

    private fun extractZip(archive: File, dest: File, onProgress: ((Int) -> Unit)?) {
        val total = archive.length().coerceAtLeast(1)
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var processed = 0L
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = safeFile(dest, entry.name)
                if (entry.isDirectory) {
                    FileUtils.ensureDir(outFile)
                } else {
                    FileUtils.ensureDir(outFile.parentFile ?: dest)
                    FileOutputStream(outFile).use { out -> zis.copyTo(out, 128 * 1024) }
                    processed += entry.compressedSize.coerceAtLeast(0)
                    onProgress?.invoke(((processed * 100) / total).toInt().coerceIn(0, 99))
                }
                zis.closeEntry()
            }
        }
    }

    private fun extractTar(input: InputStream, archiveSize: Long, dest: File, onProgress: ((Int) -> Unit)?) {
        TarArchiveInputStream(input).use { tis ->
            val total = archiveSize.coerceAtLeast(1)
            var last = 0
            while (true) {
                val entry = tis.nextEntry ?: break
                if (entry.name.isNullOrBlank()) continue
                val outFile = safeFile(dest, entry.name)
                when {
                    entry.isDirectory -> FileUtils.ensureDir(outFile)
                    entry.isSymbolicLink -> {
                        FileUtils.ensureDir(outFile.parentFile ?: dest)
                        try {
                            if (outFile.exists() || outFile.isDirectory) FileUtils.deleteRecursive(outFile)
                            Os.symlink(entry.linkName, outFile.absolutePath)
                        } catch (_: Throwable) {
                        }
                    }
                    entry.isLink -> {
                        val target = safeFile(dest, entry.linkName)
                        try {
                            if (target.exists()) {
                                FileUtils.ensureDir(outFile.parentFile ?: dest)
                                if (outFile.exists()) FileUtils.deleteRecursive(outFile)
                                Os.link(target.absolutePath, outFile.absolutePath)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    else -> {
                        FileUtils.ensureDir(outFile.parentFile ?: dest)
                        FileOutputStream(outFile).use { out -> tis.copyTo(out, 128 * 1024) }
                        applyMode(outFile, entry.mode)
                    }
                }
                val pos = (total - (total - tis.bytesRead.toLong()).coerceAtLeast(0)).coerceAtLeast(0)
                val pct = ((tis.bytesRead.toLong() * 100) / total).toInt().coerceIn(0, 99)
                if (pct != last) {
                    last = pct
                    onProgress?.invoke(pct)
                }
            }
        }
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode and 0b001_000_000 != 0) file.setReadable(true, false)
        if (mode and 0b000_100_000 != 0) file.setWritable(true, true)
        if (mode and 0b000_001_000 != 0) file.setExecutable(true, false)
        // Common tarballs store 0755 / 0644; make sure jars and scripts stay readable.
        if (!file.canRead()) file.setReadable(true, false)
    }

    private fun safeFile(root: File, rawName: String): File {
        val clean = rawName.replace('\\', '/').removePrefix("/")
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        var f = root
        parts.forEach { f = File(f, it) }
        val rootPath = root.absolutePath
        if (!f.absolutePath.startsWith(rootPath)) throw ExtractException("非法压缩包路径")
        return f
    }

    fun chmodExecutableRecursive(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { f ->
            if (f.isDirectory) {
                f.setExecutable(true, false)
                f.setReadable(true, false)
            }
        }
    }
}
