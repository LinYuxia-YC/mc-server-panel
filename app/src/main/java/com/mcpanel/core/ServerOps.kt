package com.mcpanel.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ServerOps {

    fun create(ctx: Context, name: String, dir: File): ServerProfile {
        dir.mkdirs()
        File(dir, "logs").mkdirs()
        File(dir, "mods").mkdirs()
        File(dir, "plugins").mkdirs()
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            dir = dir.absolutePath,
            port = 25565,
            ramMb = suggestedRamForDevice(),
            createdAt = System.currentTimeMillis()
        )
        Prefs.upsert(profile)
        return profile
    }

    fun suggestedRamForDevice(): Int {
        val totalMb = try {
            val info = android.app.ActivityManager.MemoryInfo()
            val am = Prefs.ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(info)
            (info.totalMem / (1024 * 1024)).toInt()
        } catch (_: Throwable) {
            4096
        }
        val max = (totalMb - 1400).coerceAtLeast(1024)
        return when {
            max >= 3072 -> 2048
            max >= 2048 -> 2048
            max >= 1536 -> 1536
            else -> 1024
        }
    }

    fun maxRamForDevice(): Int {
        val totalMb = try {
            val info = android.app.ActivityManager.MemoryInfo()
            val am = Prefs.ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(info)
            (info.totalMem / (1024 * 1024)).toInt()
        } catch (_: Throwable) {
            4096
        }
        return (totalMb - 1200).coerceIn(512, 8192)
    }

    fun prepareDirectory(profile: ServerProfile) {
        val dir = profile.dirFile
        dir.mkdirs()
        File(dir, "logs").mkdirs()
        File(dir, "mods").mkdirs()
        File(dir, "plugins").mkdirs()
        File(dir, "crash-reports").mkdirs()
        if (!profile.eulaFile.exists()) {
            FileUtils.writeText(profile.eulaFile, "# Minecraft EULA\n# https://aka.ms/MinecraftEULA\neula=false\n")
        }
        if (!profile.propertiesFile.exists()) {
            FileUtils.writeText(profile.propertiesFile, defaultsContent(profile))
        }
    }

    fun defaultsContent(profile: ServerProfile): String {
        val sb = StringBuilder()
        sb.append("#Minecraft server properties\n")
        sb.append("#Created by 方块开服\n")
        val defaults = PropertiesFile.defaults()
        defaults["server-port"] = profile.port.toString()
        defaults["online-mode"] = profile.onlineMode.toString()
        defaults["gamemode"] = encodeGamemode(profile.mcVersion, profile.gamemode)
        defaults["difficulty"] = encodeDifficulty(profile.mcVersion, profile.difficulty)
        defaults["motd"] = profile.motd.ifBlank { profile.displayTitle }
        defaults["max-players"] = profile.maxPlayers.toString()
        defaults["view-distance"] = profile.viewDistance.toString()
        defaults["level-seed"] = profile.seed
        defaults.forEach { (k, v) -> sb.append(k).append('=').append(PropertiesFile.escape(v)).append('\n') }
        return sb.toString()
    }

    fun applyToProperties(profile: ServerProfile) {
        prepareDirectory(profile)
        val props = PropertiesFile(profile.propertiesFile)
        props.load()
        props.set("server-port", profile.port.toString())
        props.set("online-mode", profile.onlineMode.toString())
        props.set("gamemode", encodeGamemode(profile.mcVersion, profile.gamemode))
        props.set("difficulty", encodeDifficulty(profile.mcVersion, profile.difficulty))
        props.set("motd", profile.motd.ifBlank { profile.displayTitle })
        props.set("max-players", profile.maxPlayers.toString())
        props.set("view-distance", profile.viewDistance.toString())
        if (profile.seed.isNotBlank()) props.set("level-seed", profile.seed)
        props.save()
    }

    fun setEula(profile: ServerProfile, accepted: Boolean) {
        val text = if (accepted) {
            "#By changing the setting below to TRUE you are indicating your agreement to our EULA.\n" +
                "#https://aka.ms/MinecraftEULA\n" +
                "eula=true\n"
        } else {
            "#By changing the setting below to TRUE you are indicating your agreement to our EULA.\n" +
                "#https://aka.ms/MinecraftEULA\n" +
                "eula=false\n"
        }
        FileUtils.writeText(profile.eulaFile, text)
        profile.eula = accepted
        Prefs.upsert(profile)
    }

    fun isEulaAccepted(profile: ServerProfile): Boolean {
        if (!profile.eulaFile.exists()) return false
        val text = FileUtils.readText(profile.eulaFile)
        return text.lineSequence().any { it.trim().startsWith("eula=true") }
    }

    fun setOnlineMode(profile: ServerProfile, enabled: Boolean) {
        profile.onlineMode = enabled
        val props = PropertiesFile(profile.propertiesFile)
        props.load()
        props.set("online-mode", enabled.toString())
        props.save()
        Prefs.upsert(profile)
    }

    fun syncOnlineMode(profile: ServerProfile) {
        val props = PropertiesFile(profile.propertiesFile)
        props.load()
        props.set("online-mode", profile.onlineMode.toString())
        props.save()
    }

    fun readFromProperties(profile: ServerProfile) {
        if (!profile.propertiesFile.exists()) return
        val props = PropertiesFile(profile.propertiesFile)
        props.load()
        profile.port = props.getInt("server-port", profile.port)
        profile.onlineMode = props.get("online-mode", profile.onlineMode.toString()).equals("true", true)
        profile.gamemode = decodeGamemode(profile.mcVersion, props.get("gamemode", "survival"))
        profile.difficulty = decodeDifficulty(profile.mcVersion, props.get("difficulty", "normal"))
        profile.motd = props.get("motd", profile.motd)
        profile.maxPlayers = props.getInt("max-players", profile.maxPlayers)
        profile.viewDistance = props.getInt("view-distance", profile.viewDistance)
        profile.seed = props.get("level-seed", profile.seed)
        profile.eula = isEulaAccepted(profile)
    }

    fun levelName(profile: ServerProfile): String {
        if (!profile.propertiesFile.exists()) return "world"
        val props = PropertiesFile(profile.propertiesFile)
        props.load()
        return props.get("level-name", "world").ifBlank { "world" }
    }

    fun worldDirs(profile: ServerProfile): List<File> {
        val dir = profile.dirFile
        val base = levelName(profile)
        val candidates = mutableListOf(File(dir, base))
        if (base == "world") {
            candidates.add(File(dir, "world_nether"))
            candidates.add(File(dir, "world_the_end"))
        } else {
            candidates.add(File(dir, "${base}_nether"))
            candidates.add(File(dir, "${base}_the_end"))
        }
        return candidates.filter { it.exists() && it.isDirectory }
    }

    fun isLegacyVersion(version: String): Boolean {
        val m = Regex("^1\\.(\\d{1,2})").find(version) ?: return false
        val minor = m.groupValues[1].toIntOrNull() ?: return false
        return minor <= 12
    }

    fun encodeGamemode(version: String, name: String): String {
        val n = name.lowercase()
        if (!isLegacyVersion(version)) return n
        return when (n) {
            "creative", "1" -> "1"
            "adventure", "2" -> "2"
            "spectator", "3" -> "3"
            else -> "0"
        }
    }

    fun decodeGamemode(version: String, raw: String): String {
        val v = raw.trim().lowercase()
        if (v in listOf("survival", "creative", "adventure", "spectator")) return v
        return when (v) {
            "1" -> "creative"
            "2" -> "adventure"
            "3" -> "spectator"
            else -> "survival"
        }
    }

    fun encodeDifficulty(version: String, name: String): String {
        val n = name.lowercase()
        if (!isLegacyVersion(version)) return n
        return when (n) {
            "peaceful" -> "0"
            "easy" -> "1"
            "hard" -> "3"
            else -> "2"
        }
    }

    fun decodeDifficulty(version: String, raw: String): String {
        val v = raw.trim().lowercase()
        if (v in listOf("peaceful", "easy", "normal", "hard")) return v
        return when (v) {
            "0" -> "peaceful"
            "1" -> "easy"
            "3" -> "hard"
            else -> "normal"
        }
    }

    private fun collectWorldEntries(dirs: List<File>): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        dirs.forEach { dir ->
            val base = (dir.parentFile?.absolutePath?.length ?: 0) + 1
            dir.walkTopDown().forEach { f ->
                if (f.isFile) out.add(f to f.absolutePath.substring(base).replace('\\', '/'))
            }
        }
        return out
    }

    fun createBackup(profile: ServerProfile, dirs: List<File>): File? {
        if (dirs.isEmpty()) return null
        val backupDir = File(profile.dirFile, "backups")
        FileUtils.ensureDir(backupDir)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(backupDir, "world-backup-$stamp.zip")
        val entries = collectWorldEntries(dirs)
        if (entries.isEmpty()) return null
        return if (FileUtils.zipFiles(entries, zip)) zip else null
    }

    fun resetWorld(profile: ServerProfile, backup: Boolean): Result<String> {
        val dirs = worldDirs(profile)
        if (dirs.isEmpty()) return Result.failure(IllegalStateException("没有找到世界文件夹"))
        return try {
            val backupFile = if (backup) createBackup(profile, dirs) else null
            dirs.forEach { FileUtils.deleteRecursive(it) }
            val msg = buildString {
                append("世界已重置")
                if (backupFile != null) append("，备份：${backupFile.name}")
            }
            Result.success(msg)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun exportWorld(profile: ServerProfile, destDir: File, onProgress: (Int) -> Unit): Result<File> {
        val dirs = worldDirs(profile)
        if (dirs.isEmpty()) return Result.failure(IllegalStateException("没有找到世界文件夹"))
        return try {
            FileUtils.ensureDir(destDir)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = FileUtils.sanitizeName(profile.displayTitle) + "-world-" + stamp + ".zip"
            val zip = File(destDir, name)
            val entries = collectWorldEntries(dirs)
            val ok = FileUtils.zipFiles(entries, zip) { onProgress(it) }
            if (ok) Result.success(zip) else Result.failure(IllegalStateException("导出失败"))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun importExisting(ctx: Context, dir: File): ServerProfile {
        val profile = create(ctx, dir.name, dir)
        var jar: File? = null
        val preferred = File(dir, "server.jar")
        if (preferred.exists()) jar = preferred
        if (jar == null) {
            jar = FileUtils.listFiles(dir, listOf(".jar")).maxByOrNull { it.length() }
        }
        if (jar != null) {
            val info = CoreDetector.inspect(jar)
            profile.jar = jar.name
            profile.coreType = info.type.label
            profile.mcVersion = info.version ?: ""
        }
        readFromProperties(profile)
        profile.eula = isEulaAccepted(profile)
        val runtime = if (profile.mcVersion.isNotBlank()) {
            JavaManager.find(McVersions.requiredJava(profile.mcVersion))
        } else null
        if (runtime != null) profile.javaId = runtime.id
        profile.ramMb = suggestedRamForDevice()
        Prefs.upsert(profile)
        return profile
    }

    fun deleteServer(profile: ServerProfile, deleteFiles: Boolean) {
        if (ServerRunner.isRunning(profile.id)) ServerRunner.stop()
        val root = Paths.externalRoot.absolutePath
        val dangerous = profile.dirFile.absolutePath == root ||
            profile.dirFile.absolutePath == File(root, "Download").absolutePath ||
            profile.dirFile.absolutePath == root + "/DCIM" ||
            profile.dirFile.absolutePath == root + "/Pictures"
        if (deleteFiles && !dangerous) {
            FileUtils.deleteRecursive(profile.dirFile)
        }
        Prefs.removeServer(profile.id)
    }

    fun humanUptime(startedAt: Long): String {
        if (startedAt <= 0) return ""
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "${h}时${m}分" else "${m}分${s}秒"
    }
}
