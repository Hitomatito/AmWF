/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import com.hitomatito.amwf.ShellExecutor
import com.hitomatito.amwf.destroyForciblyCompat
import com.hitomatito.amwf.isAliveCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * Ejecuta el binario `pixiewps` y extrae el PIN WPS de su salida.
 *
 * Se separa de [PixieDustRunner] porque tiene su propio ciclo de proceso
 * (timeout, kill, drenaje de stdout/stderr) independiente del supplicant.
 */
internal class PixiewpsExecutor(private val context: Context) {

    /** Expresión regular para extraer el PIN WPS de la salida de pixiewps. */
    private val pinRegex =
        Regex("""(\[\+\]\s*WPS\s*PIN:\s*|\[\+\]\s*WPS\s*pin:\s*)'?([\d]{4,8})'?""")

    /**
     * Construye el comando de línea para pixiewps a partir de los campos M3 capturados.
     */
    fun buildCommand(
        m3: M3Parser.M3Data,
        m5m7: M3Parser.M5M7Capture?
    ): String {
        val pixiePath = NativeBinaries.resolveBinaryPath(context, NativeBinaries.pixiewpsAssetName())
        return if (m5m7 != null && m5m7.hasBoth()) {
            "$pixiePath ${m5m7.toPixiewpsMode3Args(m3)}"
        } else {
            // `--force` solo aplica al modo 3 (bruteforce de fechas); en el
            // modo estándar se pasa únicamente el juego de claves M3.
            "$pixiePath ${m3.toPixiewpsArgs()}"
        }
    }

    /**
     * Lanza [command] como root, vuelca su salida en [rawOutput]/[log] y
     * devuelve el PIN encontrado (o null si no hubo o si falló).
     */
    suspend fun run(
        command: String,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ): String? {
        coroutineContext.ensureActive()
        log("[*] pixiewps started")
        var wpsPin: String? = null
        var process: Process? = null
        val stdout = LinkedBlockingQueue<String>()
        val stderr = LinkedBlockingQueue<String>()
        return try {
            val running = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process = running
            PixieProcessSupport.drainStream(running.inputStream, stdout)
            PixieProcessSupport.drainStream(running.errorStream, stderr)

            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            var timedOut = false
            while (true) {
                coroutineContext.ensureActive()
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    timedOut = true
                    break
                }
                val line = stdout.poll(minOf(remaining, 250L), TimeUnit.MILLISECONDS)
                    ?: continue
                if (line == PixieProcessSupport.PROCESS_EOF) break
                rawOutput.append(line).append('\n')
                log(line)
                pinRegex.find(line)?.let { wpsPin = it.groupValues[2] }
                PixieProcessSupport.drainQueue(stderr, rawOutput, "[pixiewps-stderr] ", log)
            }

            if (timedOut) {
                log("[-] pixiewps timed out after ${TIMEOUT_MS / 1000}s; killing it")
                running.destroyForciblyCompat()
            }
            PixieProcessSupport.drainQueue(stderr, rawOutput, "[pixiewps-stderr] ", log)
            try {
                running.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            PixieProcessSupport.drainQueue(stdout, rawOutput, log = log)
            PixieProcessSupport.drainQueue(stderr, rawOutput, "[pixiewps-stderr] ", log)
            val exitCode = runCatching { running.exitValue() }.getOrDefault(-1)
            log("[*] pixiewps exit code: $exitCode")
            wpsPin
        } catch (e: CancellationException) {
            process?.destroyForciblyCompat()
            throw e
        } catch (e: Exception) {
            ShellExecutor.logE(TAG, "Pixiewps execution failed", e)
            log("[-] Pixiewps execution error: ${e.message}")
            null
        } finally {
            process?.let { if (it.isAliveCompat()) it.destroyForciblyCompat() }
        }
    }

    companion object {
        private const val TAG = "PixiewpsExecutor"
        private const val TIMEOUT_MS = 60_000L
    }
}
