package com.mcpanel.core

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object CoreDetector {

    private val versionRegex = Regex("(\\d{1,2}\\.\\d{1,2}(?:\\.\\d{1,2})?)")
    private val mcVersionRegex = Regex("^((1\\.\\d{1,2}(?:\\.\\d{1,2})?)|(2[6-9]\\.\\d{1,2}))$")

    fun inspect(file: File): CoreInfo {
        val lowerName = file.name.lowercase()
        val entries = HashSet<String>()
        var manifest = ""
        var versionJson = ""
        var pomVersion = ""
        var bundlerMain = ""
        var hasBundler = false
        var typeFromName: CoreType? = null

        try {
            ZipFile(file).use { zip ->
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    entries.add(e.name)
                    if (e.name == "META-INF/MANIFEST.MF") {
                        manifest = readEntry(zip, e.name, 64 * 1024)
                    } else if (e.name == "version.json") {
                        versionJson = readEntry(zip, e.name, 32 * 1024)
                    } else if (e.name == "META-INF/main-class") {
                        bundlerMain = readEntry(zip, e.name, 4096).trim()
                    } else if (e.name == "META-INF/versions.list") {
                        hasBundler = true
                    } else if (e.name.startsWith("META-INF/maven/") && e.name.endsWith("pom.properties")) {
                        val t = readEntry(zip, e.name, 16 * 1024)
                        versionRegex.find(t.substringAfter("version=", ""))?.let { pomVersion = it.value }
                    }
                }
            }
        } catch (_: Throwable) {
            return CoreInfo(CoreType.OTHER, null, false, null, file.length())
        }

        val mainClass = Regex("(?m)^Main-Class:\\s*(.+)$")
            .find(manifest)?.groupValues?.get(1)?.trim()
        val implVersion = Regex("(?m)^Implementation-Version:\\s*(.+)$")
            .find(manifest)?.groupValues?.get(1)?.trim()
        val specVersion = Regex("(?m)^Specification-Version:\\s*(.+)$")
            .find(manifest)?.groupValues?.get(1)?.trim()

        if (lowerName.contains("purpur")) typeFromName = CoreType.PURPUR
        else if (lowerName.contains("paper")) typeFromName = CoreType.PAPER
        else if (lowerName.contains("spigot")) typeFromName = CoreType.SPIGOT
        else if (lowerName.contains("craftbukkit")) typeFromName = CoreType.BUKKIT
        else if (lowerName.contains("neoforge")) typeFromName = CoreType.NEOFORGE
        else if (lowerName.contains("forge")) typeFromName = CoreType.FORGE
        else if (lowerName.contains("fabric")) typeFromName = CoreType.FABRIC
        else if (lowerName.contains("quilt")) typeFromName = CoreType.QUILT
        else if (lowerName.contains("cauldron") || lowerName.contains("thermos") || lowerName.contains("kcauldron")) typeFromName = CoreType.CAULDRON

        val hasServerClass = entries.any {
            it == "net/minecraft/server/MinecraftServer.class" ||
                it == "net/minecraft/server/Main.class" ||
                it.startsWith("net/minecraft/server/") && it.endsWith("MinecraftServer.class")
        }
        val hasClientClass = entries.any { it == "net/minecraft/client/main/Main.class" }
        val isClient = hasClientClass && !hasServerClass

        val type = when {
            entries.any { it.startsWith("io/papermc/paperclip/") } || mainClass?.contains("paperclip") == true ->
                if (lowerName.contains("purpur")) CoreType.PURPUR else CoreType.PAPER
            bundlerMain.contains("paperclip", true) ->
                if (lowerName.contains("purpur")) CoreType.PURPUR else CoreType.PAPER
            entries.any { it.startsWith("net/fabricmc/loader/") } -> CoreType.FABRIC
            bundlerMain.contains("fabric", true) -> CoreType.FABRIC
            entries.any { it.startsWith("org/quiltmc/") } -> CoreType.QUILT
            entries.any { it.startsWith("cpw/mods/bootstraplauncher/") } ->
                if (lowerName.contains("neoforge")) CoreType.NEOFORGE else CoreType.FORGE
            entries.any { it.startsWith("net/minecraftforge/") } -> CoreType.FORGE
            entries.any { it == "org/bukkit/craftbukkit/Main.class" } ->
                if (lowerName.contains("spigot") || entries.any { e -> e.startsWith("org/spigotmc/") }) CoreType.SPIGOT else CoreType.BUKKIT
            hasServerClass -> CoreType.VANILLA
            // Modern vanilla servers are "bundler" jars: META-INF/versions.list + libraries.list
            hasBundler || bundlerMain.contains("net.minecraft", true) -> CoreType.VANILLA
            typeFromName != null -> typeFromName
            else -> CoreType.OTHER
        }

        val version = extractVersion(versionJson, implVersion, specVersion, pomVersion, file.name)
        return CoreInfo(type, version, isClient, mainClass, file.length())
    }

    private fun readEntry(zip: ZipFile, name: String, max: Int): String {
        return try {
            val entry = zip.getEntry(name) ?: return ""
            val bytes = zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(max)
                var read = 0
                while (read < max) {
                    val n = input.read(buffer, read, max - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractVersion(
        versionJson: String,
        implVersion: String?,
        specVersion: String?,
        pomVersion: String,
        fileName: String
    ): String? {
        if (versionJson.isNotBlank()) {
            try {
                val o = JSONObject(versionJson)
                val name = o.optString("name").ifBlank { o.optString("id") }
                if (name.isNotBlank()) return name
            } catch (_: Throwable) {
            }
        }
        listOfNotNull(implVersion, specVersion).forEach { raw ->
            versionRegex.findAll(raw).forEach { m ->
                if (mcVersionRegex.matches(m.value)) return m.value
            }
        }
        if (pomVersion.isNotBlank()) {
            versionRegex.findAll(pomVersion).forEach { m ->
                if (mcVersionRegex.matches(m.value)) return m.value
            }
        }
        versionRegex.findAll(fileName).forEach { m ->
            if (mcVersionRegex.matches(m.value)) return m.value
        }
        return null
    }
}
