package com.hitomatito.amwf

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

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
        return try {
            val process = Runtime.getRuntime().exec(cmdArray)

            val output = StringBuilder()
            val error = StringBuilder()

            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (outputReader.ready()) {
                    val line = outputReader.readLine()
                    if (line != null) output.appendLine(line)
                }
                if (errorReader.ready()) {
                    val line = errorReader.readLine()
                    if (line != null) error.appendLine(line)
                }
                try {
                    val exitCode = process.exitValue()
                    // Process finished: drain remaining data
                    while (outputReader.ready()) {
                        val line = outputReader.readLine()
                        if (line != null) output.appendLine(line)
                    }
                    while (errorReader.ready()) {
                        val line = errorReader.readLine()
                        if (line != null) error.appendLine(line)
                    }
                    return Result(exitCode, output.toString().trim(), error.toString().trim())
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(POLL_INTERVAL)
                }
            }

            process.destroyForcibly()
            Result(-1, output.toString().trim(), "Command timeout")
        } catch (e: Exception) {
            Result(-1, "", e.message ?: "Unknown error")
        }
    }

    // --- Structured Logging ---

    fun logD(tag: String, message: String) = Log.d(tag, message)
    fun logW(tag: String, message: String) = Log.w(tag, message)
    fun logE(tag: String, message: String, error: Throwable? = null) = Log.e(tag, message, error)
}
