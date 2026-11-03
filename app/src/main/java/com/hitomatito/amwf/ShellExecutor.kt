/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import android.os.Build
import android.util.Log
import com.hitomatito.amwf.diagnostics.AppLogger
import java.io.BufferedReader
import java.io.File
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
     * Executes a command with root privileges via su, feeding [input] to its
     * stdin. Útil para copiar ficheros desde el almacenamiento privado de la
     * app a rutas que el dominio SELinux de `su` sí puede escribir.
     */
    fun execRootWithInput(
        command: String,
        input: File,
        timeoutMs: Long = DEFAULT_TIMEOUT
    ): Result {
        return exec(arrayOf("su", "-c", command), timeoutMs, input)
    }

    /**
     * Executes a command without root privileges via sh.
     */
    fun execNoRoot(command: String, timeoutMs: Long = DEFAULT_TIMEOUT): Result {
        return exec(arrayOf("sh", "-c", command), timeoutMs)
    }

    private fun exec(cmdArray: Array<String>, timeoutMs: Long, input: File? = null): Result {
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

        // Si hay entrada, se escribe en un hilo para no bloquear mientras el
        // proceso produce salida. El cierre de stdin es lo que marca el EOF.
        val inputThread = input?.let { source ->
            Thread {
                try {
                    process.outputStream.use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    }
                } catch (_: Exception) {
                    // El proceso pudo terminar antes de leer toda la entrada.
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

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
            process.destroyForciblyCompat()
            return Result(-1, output.toString().trim(), "Interrupted")
        }

        if (timedOut) {
            process.destroyForciblyCompat()
        }

        // Deja que los lectores lleguen a EOF (acotado; son daemon threads).
        outputThread.join(1000)
        errorThread.join(1000)
        inputThread?.join(1000)

        return if (timedOut) {
            Result(-1, output.toString().trim(), "Command timeout")
        } else {
            Result(process.exitValue(), output.toString().trim(), error.toString().trim())
        }
    }

    // --- Structured Logging ---
    // Ademas de Logcat, cada linea se guarda en el buffer en memoria de AppLogger
    // para que el informe de diagnostico pueda incluirla aunque logcat no sea accesible.

    fun logD(tag: String, message: String) {
        Log.d(tag, message)
        AppLogger.append('D', tag, message)
    }

    fun logW(tag: String, message: String) {
        Log.w(tag, message)
        AppLogger.append('W', tag, message)
    }

    fun logE(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        AppLogger.append('E', tag, message)
    }
}
