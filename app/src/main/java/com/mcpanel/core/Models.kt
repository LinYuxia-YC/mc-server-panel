package com.mcpanel.core

/** A managed Minecraft server instance. */
data class ServerProfile(
    var id: String = "",
    var name: String = "",
    var dir: String = "",
    var jar: String = "server.jar",
    var mcVersion: String = "",
    var coreType: String = "",
    var javaId: String = "",
    var ramMb: Int = 2048,
    var port: Int = 25565,
    var onlineMode: Boolean = false,
    var gamemode: String = "survival",
    var difficulty: String = "normal",
    var seed: String = "",
    var randomTickSpeed: Int = 3,
    var motd: String = "",
    var maxPlayers: Int = 20,
    var extraArgs: String = "",
    var viewDistance: Int = 8,
    var eula: Boolean = false,
    var createdAt: Long = 0L,
    var lastStart: Long = 0L
) {
    val dirFile: java.io.File get() = java.io.File(dir)
    val jarFile: java.io.File get() = java.io.File(dir, jar)
    val propertiesFile: java.io.File get() = java.io.File(dir, "server.properties")
    val eulaFile: java.io.File get() = java.io.File(dir, "eula.txt")
    val consoleLogFile: java.io.File get() = java.io.File(dir, "logs/panel-console.log")

    val displayTitle: String get() = if (name.isNotBlank()) name else dirFile.name
}

/**
 * An installed Java runtime. kind = "proot" (glibc Temurin inside an Ubuntu rootfs) or "native".
 * For proot runtimes [javaHome] is the path *inside* the rootfs, e.g. "/opt/jre8".
 */
data class JavaRuntime(
    var id: String = "",
    var label: String = "",
    var major: Int = 8,
    var kind: String = KIND_PROOT,
    var javaHome: String = "",
    var rootfs: String = "",
    var javaBinHost: String = "",
    var sizeBytes: Long = 0L,
    var installedAt: Long = 0L,
    var source: String = "",
    var versionText: String = ""
) {
    companion object {
        const val KIND_PROOT = "proot"
        const val KIND_NATIVE = "native"
    }

    val shortLabel: String get() = "Java $major"
    val detail: String get() = versionText.ifBlank { label }
}

enum class CoreType(val label: String) {
    VANILLA("原版"),
    PAPER("Paper"),
    PURPUR("Purpur"),
    SPIGOT("Spigot"),
    BUKKIT("CraftBukkit"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    CAULDRON("Cauldron"),
    OTHER("未知核心")
}

data class CoreInfo(
    val type: CoreType,
    val version: String?,
    val isClient: Boolean,
    val mainClass: String? = null,
    val sizeBytes: Long = 0L,
    /** false when the selected file is not a readable jar/zip at all. */
    val validJar: Boolean = true,
    /** Manifest Class-Path entries (KCauldron / Forge wrapper jars rely on these). */
    val classPath: List<String> = emptyList()
)

data class LogLine(val time: Long, val text: String)

enum class RunStatus { IDLE, STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

data class RunState(
    val status: RunStatus = RunStatus.IDLE,
    val serverId: String? = null,
    val serverName: String = "",
    val exitCode: Int? = null,
    val message: String? = null,
    val startedAt: Long = 0L
) {
    val active: Boolean get() = status == RunStatus.STARTING || status == RunStatus.RUNNING || status == RunStatus.STOPPING
}
