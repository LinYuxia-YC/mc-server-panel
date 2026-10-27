package com.mcpanel.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Prefs {
    private lateinit var appCtx: Context
    private val gson = Gson()
    private var serversCache: MutableList<ServerProfile>? = null
    private var runtimesCache: MutableList<JavaRuntime>? = null
    private val lock = Any()

    const val SOURCE_CN = "cn"
    const val SOURCE_OFFICIAL = "official"

    fun init(c: Context) {
        appCtx = c.applicationContext
    }

    val ctx: Context get() = appCtx

    private val sp get() = appCtx.getSharedPreferences("mcpanel", Context.MODE_PRIVATE)

    fun servers(): MutableList<ServerProfile> = synchronized(lock) {
        serversCache ?: loadServers().also { serversCache = it }
    }

    private fun loadServers(): MutableList<ServerProfile> {
        val raw = sp.getString("servers", null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ServerProfile>>() {}.type
            gson.fromJson<MutableList<ServerProfile>>(raw, type) ?: mutableListOf()
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun saveServers() = synchronized(lock) {
        val list = serversCache ?: return
        sp.edit().putString("servers", gson.toJson(list)).apply()
    }

    fun server(id: String?): ServerProfile? {
        if (id.isNullOrBlank()) return null
        return servers().firstOrNull { it.id == id }
    }

    fun upsert(server: ServerProfile) = synchronized(lock) {
        val list = servers()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        saveServers()
    }

    fun removeServer(id: String) = synchronized(lock) {
        servers().removeAll { it.id == id }
        saveServers()
    }

    fun runtimes(): MutableList<JavaRuntime> = synchronized(lock) {
        runtimesCache ?: loadRuntimes().also { runtimesCache = it }
    }

    private fun loadRuntimes(): MutableList<JavaRuntime> {
        val raw = sp.getString("runtimes", null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<JavaRuntime>>() {}.type
            gson.fromJson<MutableList<JavaRuntime>>(raw, type) ?: mutableListOf()
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun saveRuntimes() = synchronized(lock) {
        val list = runtimesCache ?: return
        sp.edit().putString("runtimes", gson.toJson(list)).apply()
    }

    fun upsertRuntime(rt: JavaRuntime) = synchronized(lock) {
        val list = runtimes()
        val idx = list.indexOfFirst { it.id == rt.id }
        if (idx >= 0) list[idx] = rt else list.add(rt)
        saveRuntimes()
    }

    fun removeRuntime(id: String) = synchronized(lock) {
        runtimes().removeAll { it.id == id }
        saveRuntimes()
    }

    fun runtime(id: String?): JavaRuntime? {
        if (id.isNullOrBlank()) return null
        return runtimes().firstOrNull { it.id == id }
    }

    fun runtimeByMajor(major: Int): JavaRuntime? = runtimes().firstOrNull { it.major == major }

    var downloadSource: String
        get() = sp.getString("download_source", SOURCE_CN) ?: SOURCE_CN
        set(value) = sp.edit().putString("download_source", value).apply()

    var serverRoot: String
        get() = sp.getString("server_root", null) ?: Paths.defaultServerRoot().absolutePath
        set(value) = sp.edit().putString("server_root", value).apply()

    var lastServerId: String
        get() = sp.getString("last_server", "") ?: ""
        set(value) = sp.edit().putString("last_server", value).apply()

    var eulaNoticeShown: Boolean
        get() = sp.getBoolean("eula_notice", false)
        set(value) = sp.edit().putBoolean("eula_notice", value).apply()
}
