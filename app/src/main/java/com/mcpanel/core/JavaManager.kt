package com.mcpanel.core

import android.content.Context
import java.io.File

object JavaManager {

    class InstallException(message: String) : Exception(message)

    private fun map(v: Int, inFrom: Int, inTo: Int, outFrom: Int, outTo: Int): Int {
        val clamped = v.coerceIn(inFrom, inTo)
        if (inTo == inFrom) return outFrom
        val ratio = (clamped - inFrom).toDouble() / (inTo - inFrom)
        return (outFrom + ratio * (outTo - outFrom)).toInt().coerceIn(outFrom, outTo)
    }

    fun installed(): List<JavaRuntime> = Prefs.runtimes().sortedBy { it.major }

    fun find(major: Int): JavaRuntime? = Prefs.runtimes().firstOrNull { it.major == major }

    fun envReady(): Boolean = Proot.isEnvReady()

    /**
     * Installs (or reuses) the portable Linux env and downloads a Temurin JRE for [major].
     */
    suspend fun install(
        ctx: Context,
        major: Int,
        source: String,
        onProgress: (stage: String, percent: Int) -> Unit
    ): JavaRuntime {
        if (android.os.Build.SUPPORTED_ABIS?.firstOrNull()?.contains("arm64") != true) {
            throw InstallException("当前设备不是 arm64 架构，内置的 Java 运行环境仅支持 arm64 手机")
        }
        ensureSpace()
        ensureEnv(ctx, source) { stage, percent -> onProgress(stage, map(percent, 0, 100, 0, 40)) }

        val spec = JavaCatalog.spec(major)
        val archive = File(Paths.downloads, "jre$major.tar.gz")
        if (!archive.exists() || archive.length() < 8 * 1024 * 1024) {
            archive.delete()
            onProgress("下载 ${spec.label}（约 ${spec.approxMb}MB）", 40)
            Downloader.download(JavaCatalog.jreUrls(major, source), archive) { done, total, _ ->
                val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                onProgress("下载 ${spec.label}  ${FileUtils.humanSize(done)} / ${if (total > 0) FileUtils.humanSize(total) else "?"}", map(pct, 0, 100, 40, 80))
            }
        }

        onProgress("解压 ${spec.label}", 82)
        val target = File(Paths.rootfs, "opt/jre$major")
        val temp = File(Paths.rootfs, "opt/.jre$major.tmp")
        FileUtils.deleteRecursive(temp)
        FileUtils.deleteRecursive(target)
        Extractor.extract(archive, temp, stripSingleRoot = true) { }
        FileUtils.ensureDir(File(Paths.rootfs, "opt"))
        if (!temp.renameTo(target)) {
            copyDir(temp, target)
            FileUtils.deleteRecursive(temp)
        }
        makeExecutable(target)

        onProgress("校验 Java $major", 92)
        val result = Proot.runJavaVersion("/opt/jre$major", 180)
        val version = Proot.parseVersion(result.output)
            ?: throw InstallException("Java $major 启动校验失败：${result.output.take(400)}")
        if (result.timedOut) throw InstallException("Java $major 校验超时")

        val rt = JavaRuntime(
            id = "temurin$major-proot",
            label = "Java $major · ${spec.label}",
            major = major,
            kind = JavaRuntime.KIND_PROOT,
            javaHome = "/opt/jre$major",
            rootfs = Paths.rootfs.absolutePath,
            sizeBytes = FileUtils.sizeOf(target),
            installedAt = System.currentTimeMillis(),
            source = source,
            versionText = version
        )
        Prefs.upsertRuntime(rt)
        onProgress("Java $major 已就绪", 100)
        return rt
    }

    suspend fun ensureEnv(ctx: Context, source: String, onProgress: (String, Int) -> Unit) {
        Paths.ensure()
        Proot.copyBundledBinaries(ctx)
        if (Proot.isEnvReady()) {
            Proot.prepareRuntimeDirs()
            Proot.updateResolvConf(ctx)
            onProgress("运行环境已就绪", 100)
            return
        }
        FileUtils.deleteRecursive(Paths.rootfs)
        FileUtils.ensureDir(Paths.rootfs)
        val archive = File(Paths.downloads, JavaCatalog.ROOTFS_FILE)
        if (!archive.exists() || archive.length() < 4 * 1024 * 1024) {
            archive.delete()
            onProgress("下载 Ubuntu 运行环境（约 27MB）", 0)
            Downloader.download(JavaCatalog.rootfsUrls(source), archive) { done, total, _ ->
                val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                onProgress("下载运行环境 ${FileUtils.humanSize(done)} / ${if (total > 0) FileUtils.humanSize(total) else "?"}", map(pct, 0, 100, 0, 70))
            }
        }
        onProgress("解压运行环境", 75)
        Extractor.extract(archive, Paths.rootfs) { pct ->
            onProgress("解压运行环境 $pct%", map(pct, 0, 100, 75, 95))
        }
        Proot.prepareRuntimeDirs()
        Proot.updateResolvConf(ctx)
        if (!Proot.isEnvReady()) throw InstallException("运行环境解压不完整（缺少 /bin/sh）")
        onProgress("运行环境已就绪", 100)
    }

    private fun ensureSpace() {
        try {
            val free = Paths.files.usableSpace
            if (free < 500L * 1024 * 1024) {
                throw InstallException("存储空间不足，至少需要 500MB 可用空间（当前 ${FileUtils.humanSize(free)}）")
            }
        } catch (e: InstallException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    private fun makeExecutable(javaHome: File) {
        try {
            val bin = File(javaHome, "bin")
            bin.listFiles()?.forEach { it.setExecutable(true, false) }
            javaHome.walkTopDown().forEach { f ->
                if (f.isFile && (f.name.endsWith(".so") || f.name.endsWith(".so.1") || f.name == "java")) {
                    f.setReadable(true, false)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun copyDir(src: File, dst: File) {
        FileUtils.ensureDir(dst)
        src.listFiles()?.forEach { child ->
            val target = File(dst, child.name)
            if (child.isDirectory) copyDir(child, target) else FileUtils.copy(child, target)
        }
    }

    /** Imports an already prepared Android (bionic) JRE archive selected by the user. */
    suspend fun importNative(
        ctx: Context,
        archive: File,
        onProgress: (String, Int) -> Unit
    ): JavaRuntime {
        onProgress("解压 Java 压缩包", 10)
        val dir = File(Paths.nativeDir, "jre-" + System.currentTimeMillis())
        FileUtils.deleteRecursive(dir)
        Extractor.extract(archive, dir, stripSingleRoot = true) { pct ->
            onProgress("解压 Java 压缩包 $pct%", map(pct, 0, 100, 10, 70))
        }
        val javaBin = findJavaBinary(dir)
            ?: run {
                FileUtils.deleteRecursive(dir)
                throw InstallException("压缩包内未找到 bin/java，请确认这是完整的 JRE 压缩包")
            }
        javaBin.setExecutable(true, false)
        val javaHome = javaBin.parentFile?.parentFile ?: dir
        onProgress("校验 Java", 80)
        val probe = JavaRuntime(
            id = "native-temp",
            major = 8,
            kind = JavaRuntime.KIND_NATIVE,
            javaHome = javaHome.absolutePath,
            javaBinHost = javaBin.absolutePath
        )
        val result = Shell.run(listOf(javaBin.absolutePath, "-version"), Proot.nativeEnv(probe), javaHome, 90)
        val version = Proot.parseVersion(result.output)
        if (version == null) {
            FileUtils.deleteRecursive(dir)
            throw InstallException("该 Java 无法在安卓上运行：${result.output.take(300)}")
        }
        val major = majorFromVersion(version)
        val rt = probe.copy(
            id = "native${major}-" + System.currentTimeMillis(),
            label = "Java $major · 本地导入",
            major = major,
            sizeBytes = FileUtils.sizeOf(dir),
            installedAt = System.currentTimeMillis(),
            source = "import",
            versionText = version
        )
        Prefs.upsertRuntime(rt)
        onProgress("Java $major 已就绪", 100)
        return rt
    }

    fun majorFromVersion(version: String): Int {
        val v = version.trim()
        if (v.startsWith("1.")) return v.substring(2).substringBefore('.').toIntOrNull() ?: 8
        return v.substringBefore('.').toIntOrNull() ?: 8
    }

    private fun findJavaBinary(root: File): File? {
        val direct = File(root, "bin/java")
        if (direct.exists()) return direct
        return root.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
    }

    fun verify(rt: JavaRuntime): String? {
        return if (rt.kind == JavaRuntime.KIND_PROOT) {
            val res = Proot.runJavaVersion(rt.javaHome, 120)
            Proot.parseVersion(res.output)
        } else {
            val res = Shell.run(listOf(rt.javaBinHost, "-version"), Proot.nativeEnv(rt), File(rt.javaHome), 90)
            Proot.parseVersion(res.output)
        }
    }

    fun delete(rt: JavaRuntime): Boolean {
        return try {
            if (rt.kind == JavaRuntime.KIND_PROOT) {
                val dir = File(Paths.rootfs, "opt/jre${rt.major}")
                FileUtils.deleteRecursive(dir)
            } else {
                val home = File(rt.javaHome)
                if (home.absolutePath.startsWith(Paths.nativeDir.absolutePath)) {
                    FileUtils.deleteRecursive(home)
                }
            }
            Prefs.removeRuntime(rt.id)
            // servers that referenced it lose their runtime id
            Prefs.servers().forEach { s ->
                if (s.javaId == rt.id) {
                    s.javaId = ""
                }
            }
            Prefs.saveServers()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun describe(rt: JavaRuntime): String =
        if (rt.kind == JavaRuntime.KIND_PROOT) "便携 Linux 运行时（proot）" else "安卓原生运行时"
}
