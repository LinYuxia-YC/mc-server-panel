package com.mcpanel.core

import java.io.File
import java.util.concurrent.TimeUnit

object Shell {

    data class ProcResult(val exit: Int, val output: String, val timedOut: Boolean)

    fun run(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        workDir: File? = null,
        timeoutSec: Long = 120
    ): ProcResult {
        if (command.isEmpty()) return ProcResult(-1, "empty command", false)
        return try {
            val pb = ProcessBuilder(command)
            if (workDir != null) pb.directory(workDir)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            val process = pb.start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                if (output.length < 200_000) output.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                try {
                    process.waitFor(3, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                }
                process.destroyForcibly()
                reader.join(1000)
                return ProcResult(-1, output.toString(), true)
            }
            reader.join(2000)
            ProcResult(process.exitValue(), output.toString(), false)
        } catch (e: Throwable) {
            ProcResult(-1, e.message ?: e.javaClass.simpleName, false)
        }
    }
}
