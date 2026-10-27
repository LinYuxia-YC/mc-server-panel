package com.mcpanel.core

/**
 * Minecraft version -> required Java runtime mapping.
 *
 * Historically (Mojang / Minecraft Wiki):
 *  - 1.16.5 and older  -> Java 8
 *  - 1.17              -> Java 16 (Java 17 is backwards compatible and is used instead)
 *  - 1.18 - 1.20.4     -> Java 17
 *  - 1.20.5 - 1.21.x   -> Java 21
 *  - 26.1 and newer    -> Java 25
 */
object McVersions {

    /** Curated list, newest first. Users may also type a custom version. */
    val versions: List<String> = listOf(
        "26.3", "26.2", "26.1",
        "1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7", "1.21.6", "1.21.5",
        "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21",
        "1.20.6", "1.20.5", "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20",
        "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19",
        "1.18.2", "1.18.1", "1.18",
        "1.17.1", "1.17",
        "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16",
        "1.15.2", "1.15.1", "1.15",
        "1.14.4", "1.14.3", "1.14.2", "1.14.1", "1.14",
        "1.13.2", "1.13.1", "1.13",
        "1.12.2", "1.12.1", "1.12",
        "1.11.2", "1.11",
        "1.10.2", "1.10",
        "1.9.4", "1.9.3", "1.9",
        "1.8.9", "1.8.8", "1.8.7", "1.8",
        "1.7.10", "1.7.9", "1.7.2",
        "1.6.4", "1.6.2", "1.5.2", "1.4.7"
    )

    /** All Java lines this app can install. */
    val supportedJavaMajors: List<Int> = listOf(8, 17, 21, 25)

    fun requiredJava(version: String): Int {
        val v = version.trim()
            .removePrefix("Minecraft")
            .removePrefix("minecraft")
            .trim()
            .removePrefix("v")
        if (v.isEmpty()) return 21

        // Year based versioning (26.1+)
        val year = Regex("^(\\d{2})\\.\\d+").find(v)?.groupValues?.get(1)?.toIntOrNull()
        if (year != null && year in 26..99) return 25

        // Snapshots like 24w14a -> Java 21, 26w01a -> Java 25
        val snap = Regex("^(\\d{2})w\\d+").find(v)?.groupValues?.get(1)?.toIntOrNull()
        if (snap != null) return if (snap >= 26) 25 else if (snap >= 24) 21 else 17

        val m = Regex("^1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?").find(v) ?: return 21
        val minor = m.groupValues[1].toIntOrNull() ?: return 21
        val patch = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return when {
            minor <= 16 -> 8
            minor <= 19 -> 17
            minor == 20 -> if (patch >= 5) 21 else 17
            else -> 21
        }
    }

    fun isJavaEnough(required: Int, actual: Int): Boolean {
        // Java 17 also runs 1.17 (which needs 16). Java 25 runs everything from 21 downwards,
        // but old servers (<=1.16) still require Java 8.
        return if (required <= 8) actual == 8
        else actual >= required || (required == 17 && actual == 21) || (required == 17 && actual == 25)
    }

    /** randomTickSpeed gamerule was added in 1.8; 1.7.10 and older do not know it. */
    fun supportsRandomTickSpeed(version: String): Boolean {
        val m = Regex("^1\\.(\\d{1,2})").find(version.trim()) ?: return true
        val minor = m.groupValues[1].toIntOrNull() ?: return true
        return minor >= 8
    }

    fun javaLabel(major: Int): String = "Java $major"

    /** Reasonable default RAM for a version. */
    fun suggestedRam(version: String): Int = when {
        requiredJava(version) >= 21 -> 2048
        requiredJava(version) >= 17 -> 2048
        else -> 1536
    }

    fun pretty(version: String): String =
        if (version.isBlank()) "未知版本" else "Minecraft $version"
}
