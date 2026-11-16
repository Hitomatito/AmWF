/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import android.util.Log
import com.hitomatito.amwf.diagnostics.AppLogger
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Shared utility for executing shell commands via su or sh.
 * Eliminates code duplication between MonitorModeManager and DeviceCompatibilityChecker.
 */
object ShellExecutor {

    private const val DEFAULT_TIMEOUT = 10000L

    /**
     * Escribir en con_mode dispara una recarga del driver WiFi que puede tardar
     * ~10 s antes de devolver el control (visto en el issue #5 en un SM-S938B).
     * El timeout genérico de 10 s daba lugar a una lectura interrumpida; estas
     * escrituras necesitan margen.
     */
    internal const val CON_MODE_WRITE_TIMEOUT_MS = 30000L

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

        // StringBuffer: los hilos lectores pueden seguir activos cuando se
        // devuelve el resultado si un nieto conservó la pipe (issue #5), así que
        // el buffer debe ser seguro para append/toString concurrentes.
        val output = StringBuffer()
        val error = StringBuffer()

        val outputReader = BufferedReader(InputStreamReader(process.inputStream))
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))

        // Dos hilos lectores que consumen stdout/stderr hasta EOF. Evitan el
        // deadlock clásico (una pipe llena) y no pierden salida al terminar el
        // proceso, algo que el muestreo con ready() sí podía cortar. forEachLine
        // cierra el reader al llegar al final (sin fuga de descriptores).
        val outputThread = Thread { drainLines(outputReader, output) }
        val errorThread = Thread { drainLines(errorReader, error) }
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
            // waitFor(timeout) es API 26; en API 24-25 se sondea exitValue.
            !process.waitForCompat(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForciblyCompat()
            return Result(-1, output.toString().trim(), "Interrupted")
        }

        if (timedOut) {
            process.destroyForciblyCompat()
        }

        // Da un plazo corto y compartido a los lectores para que drenen lo que
        // quede tras la salida del proceso. Si un nieto heredó la pipe, pueden
        // seguir bloqueados en la lectura nativa: ni close() en el
        // BufferedReader (toma el lock de readLine) ni close() en el stream
        // crudo (no interrumpe un read() nativo en Linux) despiertan a este
        // hilo de forma fiable. No se espera más: son daemon threads y los
        // StringBuffer sincronizados no corrompen el resultado (issue #5).
        val joinDeadlineNs = System.nanoTime() + 500L * 1_000_000L
        fun remainingJoinMs(): Long =
            ((joinDeadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)

        outputThread.joinQuietly(remainingJoinMs())
        errorThread.joinQuietly(remainingJoinMs())
        inputThread?.joinQuietly(remainingJoinMs())

        return if (timedOut) {
            Result(-1, output.toString().trim(), "Command timeout")
        } else {
            Result(process.exitValue(), output.toString().trim(), error.toString().trim())
        }
    }

    /**
     * Consume un stream hasta EOF. Cuando un comando agota el timeout se mata
     * el proceso y las pipes se cierran desde otro hilo mientras readLine()
     * está bloqueado: Android lanza InterruptedIOException. Sin este catch, la
     * excepción no capturada en el thread daemon terminaba el proceso de la app
     * (issue #5). Lo ya leído se conserva.
     */
    internal fun drainLines(reader: BufferedReader, sink: StringBuffer) {
        try {
            reader.forEachLine { sink.appendLine(it) }
        } catch (_: IOException) {
            // Cierre concurrente del pipe: salida parcial, sin crashing.
        }
    }

    private fun Thread.joinQuietly(millis: Long) {
        // Thread.join(0) espera indefinidamente, no devuelve enseguida.
        if (millis <= 0) return
        try {
            join(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
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
