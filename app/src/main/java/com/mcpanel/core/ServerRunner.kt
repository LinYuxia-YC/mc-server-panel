package com.mcpanel.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServerRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<LogLine>(replay = 600, extraBufferCapacity = 1024)
    val logs: SharedFlow<LogLine> = _logs.asSharedFlow()

    private val buffer = ArrayDeque<String>()
    private val bufferLock = Any()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var logWriter: BufferedWriter? = null
    private var activeProfile: ServerProfile? = null
    private var activeRuntime: JavaRuntime? = null
    private var stopRequested = false
    private var appContext: Context? = null
    private var watchJob: Job? = null
    private var readerJob: Job? = null
    private var tailJob: Job? = null
    @Volatile
    private var stdoutSeen = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun snapshot(): List<String> = synchronized(bufferLock) { buffer.toList() }

    fun isRunning(serverId: String? = null): Boolean {
        val s = _state.value
        if (!s.active) return false
        return serverId == null || s.serverId == serverId
    }

    fun activeServerId(): String? = _state.value.serverId

    fun start(ctx: Context, profile: ServerProfile, runtime: JavaRuntime): Result<Unit> {
        if (isRunning()) return Result.failure(IllegalStateException("已有服务器在运行"))
        val dir = File(profile.dir)
        if (!dir.exists() || !dir.isDirectory) return Result.failure(IllegalStateException("服务器目录不存在"))
        val jar = File(dir, profile.jar)
        if (!jar.exists()) return Result.failure(IllegalStateException("服务端核心 ${profile.jar} 不存在"))
        if (runtime.kind == JavaRuntime.KIND_PROOT && !Proot.isEnvReady()) {
            return Result.failure(IllegalStateException("Java 运行环境不完整，请重新安装 Java"))
        }
        if (runtime.kind == JavaRuntime.KIND_NATIVE && !File(runtime.javaBinHost).exists()) {
            return Result.failure(IllegalStateException("Java 可执行文件不存在"))
        }

        appContext = ctx.applicationContext
        stopRequested = false
        activeProfile = profile
        activeRuntime = runtime
        synchronized(bufferLock) { buffer.clear() }

        return try {
            Proot.updateResolvConf(ctx)
            val command = if (runtime.kind == JavaRuntime.KIND_PROOT) {
                Proot.serverCommand(profile, runtime)
            } else {
                Proot.nativeServerCommand(profile, runtime)
            }
            val env = if (runtime.kind == JavaRuntime.KIND_PROOT) Proot.env() else Proot.nativeEnv(runtime)
            // KCauldron/Thermos append to logs/latest.log instead of writing to stdout.
            // Rotate the previous log so the tailer only sees this session's output.
            try {
                val latest = File(dir, "logs/latest.log")
                if (latest.exists() && latest.length() > 0) {
                    val previous = File(dir, "logs/latest.log.prev")
                    previous.delete()
                    latest.renameTo(previous)
                }
            } catch (_: Throwable) {
            }
            val pb = ProcessBuilder(command)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val startedAt = System.currentTimeMillis()
            val proc = pb.start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            logWriter = try {
                FileUtils.ensureDir(File(dir, "logs"))
                BufferedWriter(OutputStreamWriter(File(profile.consoleLogFile.path).outputStream(), Charsets.UTF_8))
            } catch (_: Throwable) {
                null
            }
            _state.value = RunState(
                status = RunStatus.STARTING,
                serverId = profile.id,
                serverName = profile.displayTitle,
                startedAt = startedAt
            )
            appendSystem("启动 ${profile.displayTitle} · ${McVersions.pretty(profile.mcVersion)} · Java ${runtime.major}")
            appendSystem("内存 ${profile.ramMb}MB · 端口 ${profile.port} · ${Proot.javaArgs(profile).joinToString(" ")}")
            startService(ctx, profile)

            readerJob = scope.launch { readLoop(proc) }
            watchJob = scope.launch { watchLoop(proc, profile) }
            // KCauldron / Forge 1.7.10 redirect stdout into logs/latest.log; if nothing
            // arrives on stdout, mirror the log file into the in-app console instead.
            stdoutSeen = false
            tailJob?.cancel()
            tailJob = scope.launch {
                delay(8000)
                if (!stdoutSeen && proc.isAlive) {
                    appendSystem("服务端将日志写入 logs/latest.log，已自动同步到控制台")
                    tailLatestLog(profile)
                }
            }
            Result.success(Unit)
        } catch (e: Throwable) {
            _state.value = RunState(
                status = RunStatus.CRASHED,
                serverId = profile.id,
                serverName = profile.displayTitle,
                message = e.message
            )
            Result.failure(e)
        }
    }

    private suspend fun readLoop(proc: Process) {
        try {
            proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { raw ->
                    if (!stdoutSeen) {
                        stdoutSeen = true
                        tailJob?.cancel()
                    }
                    val line = raw.replace(Regex("\u001B\\[[;\\d]*[A-Za-z]"), "")
                    handleLine(line)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleLine(line: String) {
        appendLog(line)
        if (line.contains("Done (")) {
            if (_state.value.status != RunStatus.RUNNING) {
                _state.value = _state.value.copy(status = RunStatus.RUNNING)
                scheduleAutoCommands()
            }
        }
    }

    /**
     * Follows logs/latest.log and pushes new lines to the console. Used by servers that
     * replace System.out with a log file appender (KCauldron, Thermos, Forge 1.7.10...).
     */
    private suspend fun tailLatestLog(profile: ServerProfile) {
        val file = File(profile.dirFile, "logs/latest.log")
        val waitUntil = System.currentTimeMillis() + 30_000
        while (!file.exists() && System.currentTimeMillis() < waitUntil) {
            if (process?.isAlive != true) return
            delay(500)
        }
        if (!file.exists()) return
        var position = 0L
        val pending = StringBuilder()
        while (process?.isAlive == true) {
            if (stdoutSeen) return
            try {
                val length = file.length()
                if (length > position) {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(position)
                        val toRead = (length - position).coerceAtMost(512L * 1024L).toInt()
                        if (toRead > 0) {
                            val bytes = ByteArray(toRead)
                            val read = raf.read(bytes)
                            if (read > 0) {
                                position += read
                                pending.append(String(bytes, 0, read, Charsets.UTF_8))
                                var newline = pending.indexOf("\n")
                                while (newline >= 0) {
                                    val line = pending.substring(0, newline).trimEnd('\r')
                                    pending.delete(0, newline + 1)
                                    if (line.isNotEmpty()) handleLine(line)
                                    newline = pending.indexOf("\n")
                                }
                                if (pending.length > 16_384) pending.setLength(0)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            delay(400)
        }
    }

    private fun scheduleAutoCommands() {
        val profile = activeProfile ?: return
        scope.launch {
            delay(2000)
            if (procAlive()) {
                if (profile.randomTickSpeed >= 0) {
                    if (McVersions.supportsRandomTickSpeed(profile.mcVersion)) {
                        sendCommandInternal("gamerule randomTickSpeed ${profile.randomTickSpeed}")
                    } else {
                        appendSystem("Minecraft ${profile.mcVersion} 不支持 randomTickSpeed，已跳过随机刻设置")
                    }
                }
                if (profile.onlineMode) {
                    appendSystem("已开启正版验证（online-mode=true）")
                }
            }
        }
    }

    private suspend fun watchLoop(proc: Process, profile: ServerProfile) {
        val exit = try {
            proc.waitFor()
        } catch (_: Throwable) {
            -1
        }
        tailJob?.cancel()
        tailJob = null
        val wasStopping = stopRequested
        val status = if (wasStopping) RunStatus.STOPPED else if (exit != 0) RunStatus.CRASHED else RunStatus.STOPPED
        val message = when {
            wasStopping -> "服务器已停止"
            exit != 0 -> "服务器异常退出（代码 $exit），可在错误日志中查看原因"
            else -> "服务器已退出"
        }
        _state.value = RunState(
            status = status,
            serverId = profile.id,
            serverName = profile.displayTitle,
            exitCode = exit,
            message = message,
            startedAt = _state.value.startedAt
        )
        appendSystem(message)
        saveLastLog(profile)
        runCatching { logWriter?.close() }
        logWriter = null
        process = null
        writer = null
        stopService()
    }

    private fun saveLastLog(profile: ServerProfile) {
        try {
            val text = StringBuilder()
            synchronized(bufferLock) { buffer.takeLast(1500).forEach { text.append(it).append('\n') } }
            FileUtils.writeText(File(Paths.logs, "${profile.id}-last.log"), text.toString())
        } catch (_: Throwable) {
        }
    }

    private fun procAlive(): Boolean = process?.isAlive == true

    private fun startService(ctx: Context, profile: ServerProfile) {
        try {
            val intent = Intent(ctx, ServerService::class.java).apply {
                action = ServerService.ACTION_START
                putExtra(ServerService.EXTRA_NAME, profile.displayTitle)
                putExtra(ServerService.EXTRA_ID, profile.id)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopService() {
        val ctx = appContext ?: return
        try {
            ctx.startService(Intent(ctx, ServerService::class.java).apply {
                action = ServerService.ACTION_STOP_SERVICE
            })
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        val proc = process ?: return
        if (!proc.isAlive) return
        stopRequested = true
        _state.value = _state.value.copy(status = RunStatus.STOPPING, message = "正在停止服务器…")
        sendCommandInternal("stop")
        scope.launch {
            val finished = withTimeoutOrNull(30_000) {
                while (proc.isAlive) delay(300)
                true
            } ?: false
            if (!finished) {
                appendSystem("服务器未在 30 秒内退出，正在强制结束")
                proc.destroy()
                delay(3000)
                if (proc.isAlive) proc.destroyForcibly()
            }
        }
    }

    fun sendCommand(command: String): Boolean {
        if (command.isBlank()) return false
        if (_state.value.status != RunStatus.RUNNING && _state.value.status != RunStatus.STARTING) {
            return false
        }
        appendLocal("> $command")
        return sendCommandInternal(command)
    }

    private fun sendCommandInternal(command: String): Boolean {
        return try {
            val w = writer ?: return false
            synchronized(this) {
                w.write(command)
                w.newLine()
                w.flush()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun appendLocal(text: String) {
        _logs.tryEmit(LogLine(System.currentTimeMillis(), text))
    }

    private fun appendSystem(text: String) {
        val line = "[面板] $text"
        appendLog(line)
    }

    private fun appendLog(line: String) {
        val stamped = "[${timeFormat.format(Date())}] $line"
        synchronized(bufferLock) {
            buffer.addLast(stamped)
            while (buffer.size > 4000) buffer.removeFirst()
        }
        _logs.tryEmit(LogLine(System.currentTimeMillis(), line))
        try {
            logWriter?.let {
                synchronized(this) {
                    it.write(stamped)
                    it.newLine()
                    it.flush()
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun forceReset() {
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        writer = null
        _state.value = RunState()
        stopService()
    }
}
