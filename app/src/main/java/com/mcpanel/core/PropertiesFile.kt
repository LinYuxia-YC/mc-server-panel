package com.mcpanel.core

import java.io.File

/**
 * Minimal, order preserving server.properties reader/writer.
 * Comments and unknown keys are kept intact.
 */
class PropertiesFile(val file: File) {

    private val lines = mutableListOf<String>()

    fun load() {
        lines.clear()
        if (!file.exists()) return
        val text = FileUtils.readText(file)
        text.split("\n").forEach { raw ->
            lines.add(raw.removeSuffix("\r"))
        }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
    }

    fun get(key: String, default: String = ""): String {
        lines.forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return@forEach
            val idx = t.indexOf('=')
            if (idx > 0 && t.substring(0, idx).trim() == key) {
                return fixMojibake(unescape(t.substring(idx + 1).trim()))
            }
        }
        return default
    }

    /**
     * Older Minecraft versions read server.properties as ISO-8859-1, so a UTF-8 Chinese MOTD
     * gets stored as \\u00E6\\u0088\\u0091... by the server. Recover the original text.
     */
    private fun fixMojibake(value: String): String {
        if (value.isEmpty()) return value
        if (value.any { it.code > 0xFF }) return value
        if (value.all { it.code < 0x80 }) return value
        return try {
            val bytes = value.toByteArray(Charsets.ISO_8859_1)
            Charsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Throwable) {
            value
        }
    }

    fun getInt(key: String, default: Int): Int = get(key, default.toString()).toIntOrNull() ?: default

    fun getBool(key: String, default: Boolean): Boolean {
        val v = get(key, default.toString()).lowercase()
        return v == "true"
    }

    fun set(key: String, value: String) {
        val encoded = escape(value)
        for (i in lines.indices) {
            val t = lines[i].trim()
            if (t.startsWith("#") || t.startsWith("!")) continue
            val idx = t.indexOf('=')
            if (idx > 0 && t.substring(0, idx).trim() == key) {
                lines[i] = "$key=$encoded"
                return
            }
        }
        lines.add("$key=$encoded")
    }

    fun setInt(key: String, value: Int) = set(key, value.toString())

    fun setBool(key: String, value: Boolean) = set(key, value.toString())

    fun remove(key: String) {
        lines.removeAll { line ->
            val t = line.trim()
            val idx = t.indexOf('=')
            idx > 0 && !t.startsWith("#") && !t.startsWith("!") && t.substring(0, idx).trim() == key
        }
    }

    fun save(): Boolean {
        return FileUtils.writeText(file, lines.joinToString("\n") + "\n")
    }

    companion object {
        /**
         * Minecraft reads server.properties with java.util.Properties, which is byte/ISO-8859-1
         * oriented. Non ASCII values must therefore be written as \\uXXXX escapes, otherwise
         * Chinese MOTDs turn into mojibake in game and in the panel.
         */
        fun escape(value: String): String {
            val sb = StringBuilder(value.length)
            value.forEach { c ->
                when {
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c.code < 0x20 || c.code > 0x7E ->
                        sb.append("\\u").append(String.format("%04X", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        fun unescape(value: String): String {
            if (!value.contains('\\')) return value
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    val n = value[i + 1]
                    when {
                        n == 'u' && i + 5 < value.length -> {
                            val hex = value.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                            sb.append(c)
                        }
                        n == 'n' -> {
                            sb.append('\n')
                            i += 2
                            continue
                        }
                        n == 'r' -> {
                            sb.append('\r')
                            i += 2
                            continue
                        }
                        n == 't' -> {
                            sb.append('\t')
                            i += 2
                            continue
                        }
                        n == '\\' -> {
                            sb.append('\\')
                            i += 2
                            continue
                        }
                        else -> {
                            sb.append(n)
                            i += 2
                            continue
                        }
                    }
                } else {
                    sb.append(c)
                }
                i++
            }
            return sb.toString()
        }

        fun defaults(): LinkedHashMap<String, String> = linkedMapOf(
            "server-port" to "25565",
            "online-mode" to "false",
            "server-ip" to "",
            "motd" to "Minecraft Server",
            "gamemode" to "survival",
            "difficulty" to "normal",
            "level-seed" to "",
            "level-name" to "world",
            "max-players" to "20",
            "view-distance" to "8",
            "simulation-distance" to "6",
            "spawn-protection" to "0",
            "allow-flight" to "true",
            "enable-command-block" to "true",
            "white-list" to "false",
            "pvp" to "true",
            "allow-nether" to "true",
            "force-gamemode" to "false"
        )
    }
}
