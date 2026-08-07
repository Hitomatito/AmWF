package com.hitomatito.amwf

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shared utility for executing shell commands via su or sh.
 * Eliminates code duplication between MonitorModeManager and DeviceCompatibilityChecker.
 */
object ShellExecutor {

    private const val DEFAULT_TIMEOUT = 10000L
    private const val POLL_INTERVAL = 50L

    data class Result(val exitCode: Int, val output: String, val error: String)

    /**
     * Executes a command with root privileges via su.
     * su internally runs through sh, so pipes/redirects work.
     */
    fun execRoot(command: String, timeoutMs: Long = DEFAULT_TIMEOUT): Result {
        return exec(arrayOf("su", "-c", command), timeoutMs)
    }

    /**
     * Executes a command without root privileges via sh.
     */
    fun execNoRoot(command: String, timeoutMs: Long = DEFAULT_TIMEOUT): Result {
        return exec(arrayOf("sh", "-c", command), timeoutMs)
    }

    private fun exec(cmdArray: Array<String>, timeoutMs: Long): Result {
        val process = try {
            Runtime.getRuntime().exec(cmdArray)
        } catch (e: Exception) {
            return Result(-1, "", e.message ?: "Unknown error")
        }

        val output = StringBuilder()
        val error = StringBuilder()

        val outputReader = BufferedReader(InputStreamReader(process.inputStream))
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))

        // Dos hilos lectores que consumen stdout/stderr hasta EOF. Evitan el
        // deadlock clásico (una pipe llena) y no pierden salida al terminar el
        // proceso, algo que el muestreo con ready() sí podía cortar. forEachLine
        // cierra el reader al llegar al final (sin fuga de descriptores).
        val outputThread = Thread {
            outputReader.forEachLine { output.appendLine(it) }
        }
        val errorThread = Thread {
            errorReader.forEachLine { error.appendLine(it) }
        }
        outputThread.isDaemon = true
        errorThread.isDaemon = true
        outputThread.start()
        errorThread.start()

        val timedOut = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                // API 24/25 no expone waitFor(timeout): se sondea exitValue con timeout propio.
                val startTime = System.currentTimeMillis()
                var finished = false
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    try {
                        process.exitValue()
                        finished = true
                        break
                    } catch (_: IllegalThreadStateException) {
                        Thread.sleep(POLL_INTERVAL)
                    }
                }
                !finished
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return Result(-1, output.toString().trim(), "Interrupted")
        }

        if (timedOut) {
            process.destroyForcibly()
        }

        // Deja que los lectores lleguen a EOF (acotado; son daemon threads).
        outputThread.join(1000)
        errorThread.join(1000)

        return if (timedOut) {
            Result(-1, output.toString().trim(), "Command timeout")
        } else {
            Result(process.exitValue(), output.toString().trim(), error.toString().trim())
        }
    }

    // --- Structured Logging ---

    fun logD(tag: String, message: String) = Log.d(tag, message)
    fun logW(tag: String, message: String) = Log.w(tag, message)
    fun logE(tag: String, message: String, error: Throwable? = null) = Log.e(tag, message, error)
}
