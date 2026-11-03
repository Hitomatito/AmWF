/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import com.hitomatito.amwf.ShellExecutor
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.BlockingQueue

/**
 * Utilidades compartidas por los colaboradores de Pixie Dust: ejecución de
 * comandos como root y drenaje de los streams de los procesos nativos.
 *
 * Viven fuera de [PixieDustRunner] para que el orquestador y los ejecutores
 * (supplicant, pixiewps, extracción de PSK) usen exactamente el mismo watchdog
 * y los mismos centinelas de fin de stream.
 */
internal object PixieProcessSupport {

    /** Centinela que marca el fin de un stream drenado. */
    const val PROCESS_EOF = "\u0000AMWF_PROCESS_EOF"

    private const val TAG = "PixieProcessSupport"

    /**
     * Ejecuta un comando vía su y retorna el resultado.
     * Reusa ShellExecutor.execRoot (eliminada duplicación con PixieDustViewModel).
     */
    fun execSu(command: String, timeoutSeconds: Int): ShellExecutor.Result =
        ShellExecutor.execRoot(command, timeoutSeconds * 1000L)

    fun runSuWithTimeout(command: String, timeoutSeconds: Int): ShellExecutor.Result {
        return try {
            // El watchdog mata el comando si supera el timeout, pero solo si
            // el PID sigue siendo el mismo (starttime de /proc/<pid>/stat).
            // Sin esa comprobación, un PID reutilizado tras terminar el comando
            // podría recibir un kill como root. Su salida va a /dev/null para
            // no mantener abierto el pipe que lee ShellExecutor.
            val wrapped = "($command & ${pidWatchdogTail(timeoutSeconds)})"
            ShellExecutor.execRoot(wrapped, timeoutSeconds * 1000L)
        } catch (e: Exception) {
            ShellExecutor.logW(TAG, "Command failed/timed out: $command (${e.message})")
            ShellExecutor.Result(-1, "", e.message ?: "Unknown error")
        }
    }

    /**
     * Cola de shell que espera a `$pid` y lo mata tras [timeoutSeconds], pero
     * solo si su starttime de /proc/<pid>/stat (campo 22) no cambió. Así no
     * puede terminar un proceso ajeno si el PID se reutiliza durante la espera.
     * La salida del watchdog va a /dev/null para no mantener abierto el pipe
     * del proceso vigilado.
     */
    fun pidWatchdogTail(timeoutSeconds: Int): String =
        "pid=\$!; start=\$(cut -d' ' -f22 /proc/\$pid/stat 2>/dev/null); " +
            "( sleep $timeoutSeconds; [ -n \"\$start\" ] && " +
            "[ \"\$(cut -d' ' -f22 /proc/\$pid/stat 2>/dev/null)\" = \"\$start\" ] && " +
            "kill \$pid 2>/dev/null ) >/dev/null 2>&1 & wait \$pid"

    /**
     * Cita un valor para que el shell lo reciba como un único argumento.
     * El patrón `'` → `'\''` es la forma POSIX de meter una comilla simple
     * dentro de una cadena entrecomillada con comillas simples.
     */
    fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Lee un stream línea a línea en un hilo daemon. [onEnd] se ejecuta al
     * llegar a EOF o si el pipe se cierra, para encolar el centinela adecuado.
     */
    fun drainLines(
        input: InputStream,
        onLine: (String) -> Unit,
        onEnd: () -> Unit = {}
    ): Thread =
        Thread {
            try {
                BufferedReader(InputStreamReader(input)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                }
            } catch (_: Exception) {
                // El proceso terminó o el pipe se cerró.
            } finally {
                onEnd()
            }
        }.apply {
            isDaemon = true
            start()
        }

    /** Drena un stream en un hilo daemon y encola [eofMarker] al terminar. */
    fun drainStream(
        input: InputStream,
        queue: BlockingQueue<String>,
        eofMarker: String = PROCESS_EOF
    ): Thread = drainLines(input, { queue.offer(it) }, { queue.offer(eofMarker) })

    /** Vacía una cola de salida; [prefix] distingue stderr en el registro. */
    fun drainQueue(
        queue: BlockingQueue<String>,
        rawOutput: StringBuilder,
        prefix: String = "",
        log: (String) -> Unit
    ) {
        while (true) {
            val line = queue.poll() ?: return
            if (line == PROCESS_EOF) continue
            rawOutput.append(line).append('\n')
            log(if (prefix.isEmpty()) line else "$prefix$line")
        }
    }

    /** Espera al hilo lector tras cerrar el proceso, sin propagar la interrupción. */
    fun joinReader(reader: Thread) {
        try {
            reader.join(1_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
