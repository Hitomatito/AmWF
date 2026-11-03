/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import com.hitomatito.amwf.ShellExecutor
import com.hitomatito.amwf.waitForCompat
import java.io.File
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** PIN conocido usado para el registro wps_reg (provoca el intercambio M3). */
internal const val WPS_REG_PIN_DEFAULT = "12345670"

/** true si wpa_cli aceptó el `wps_reg` (exit 0 y un OK en la salida). */
internal fun isWpsRegAccepted(result: ShellExecutor.Result): Boolean =
    result.exitCode == 0 && result.output.lines().any { it.trim() == "OK" }

/**
 * Ciclo de vida del `wpa_supplicant` empaquetado y de su interfaz de control.
 *
 * Agrupa el arranque (ctrl dir, config, socket, PONG), el envío de comandos
 * vía `wpa_cli`, la parada de procesos y el volcado de las líneas que quedan
 * en la cola del lector. Lo comparten el ataque Pixie Dust y la extracción de
 * PSK, que necesitan exactamente el mismo arranque y la misma limpieza.
 */
internal class SupplicantProcesses(private val context: Context) {

    /** Proceso del supplicant junto con su cola de líneas y su hilo lector. */
    class Handle(
        val process: Process,
        val lines: BlockingQueue<String>,
        val reader: Thread
    )

    /**
     * Prepara el ctrl dir, arranca wpa_supplicant y espera a que acepte
     * comandos. Lo comparten el ataque Pixie Dust y la extracción de PSK.
     *
     * No emite ninguna [PixieDustStage]: cada flujo conserva su propia fase
     * (STARTING_SUPPLICANT o EXTRACTING_PSK) y decide cuándo avanzar.
     */
    suspend fun launch(
        iface: String,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ): Handle? {
        val ctrlDir = NativeBinaries.ctrlDir()
        log("[*] Preparing ctrl dir: $ctrlDir")
        PixieProcessSupport.execSu("rm -rf $ctrlDir 2>/dev/null", timeoutSeconds = 3)
        PixieProcessSupport.execSu("mkdir -p $ctrlDir 2>/dev/null", timeoutSeconds = 3)
        PixieProcessSupport.execSu("chmod 777 $ctrlDir 2>/dev/null", timeoutSeconds = 3)

        if (!stageConfig(ctrlDir, log)) {
            log("[-] No se pudo preparar el config del supplicant en $ctrlDir")
            return null
        }

        log("[*] Starting own wpa_supplicant (debug -d -K)...")
        val suppCmd = buildCommand(iface, ctrlDir)
        log("[*] Supplicant command: $suppCmd")

        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", suppCmd))
        val lines = LinkedBlockingQueue<String>()
        val reader = PixieProcessSupport.drainStream(
            process.inputStream,
            lines,
            M3Parser.END_OF_STREAM
        )
        val handle = Handle(process, lines, reader)

        try {
            val socketPath = "$ctrlDir/$iface"
            if (!waitForSocket(socketPath, SOCKET_WAIT_MS, log)) {
                log("[-] wpa_supplicant socket not created (SELinux may block): $socketPath")
                stopAndFlush(handle, rawOutput, log)
                return null
            }
            if (!waitForInterfaceReady(iface, ctrlDir, log)) {
                log("[-] wpa_supplicant ctrl interface not ready to serve commands")
                stopAndFlush(handle, rawOutput, log)
                return null
            }
            return handle
        } catch (e: CancellationException) {
            // Si cancelan mientras esperamos el socket/PONG, el handle aún no
            // ha salido de esta función: hay que pararlo y volcar su cola aquí.
            stopAndFlush(handle, rawOutput, log)
            throw e
        }
    }

    private fun buildCommand(iface: String, ctrlDir: String): String {
        val dir = NativeBinaries.binaryDir(context)
        val supplicantPath =
            NativeBinaries.resolveBinaryPath(context, NativeBinaries.wpaSupplicantAssetName())
        val conf = NativeBinaries.runtimeConfigPath()
        // El watchdog comprueba el starttime de /proc/<pid>/stat (campo 22)
        // antes de matar. Así no puede terminar un proceso ajeno si el PID se
        // reutiliza durante los 110 s de espera, ni siquiera otro supplicant
        // de una ronda posterior que casualmente reciba el mismo PID.
        // Su stdout/stderr se redirigen a /dev/null: si heredara el pipe del
        // supplicant, el lector no recibiría EOF hasta 110 s después de que
        // el proceso terminase.
        // 2>&1: si el enlazador dinámico o la inicialización del driver fallan,
        // el motivo llega por stderr. Sin esto, el hilo lector solo ve stdout y
        // el arranque fallido queda invisible en la consola de la app.
        return "export LD_LIBRARY_PATH=$dir; $supplicantPath -d -Dnl80211 " +
                "-i $iface -c$conf -K -O$ctrlDir 2>&1 & " +
                PixieProcessSupport.pidWatchdogTail(SUPPLICANT_WATCHDOG_SECONDS)
    }

    private suspend fun waitForInterfaceReady(
        iface: String,
        ctrlDir: String,
        log: (String) -> Unit
    ): Boolean {
        log("[*] Waiting for wpa_supplicant ctrl interface readiness (wpa_cli ping)...")
        val start = System.currentTimeMillis()
        val deadline = start + SOCKET_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            // `-s` fija el directorio del socket cliente de wpa_cli. Sin él,
            // wpa_cli intenta crearlo en su ruta por defecto (p. ej.
            // /data/misc/wifi/sockets), que no existe o no es escribible en
            // algunos dispositivos, y el ping falla con ENOENT/EACCES aunque
            // el supplicant esté sano.
            val r = PixieProcessSupport.runSuWithTimeout(
                cliCommand(iface, ctrlDir, "ping"),
                timeoutSeconds = 3
            )
            if (r.exitCode == 0 && r.output.lines().any { it.trim() == "PONG" }) {
                log("[+] wpa_supplicant ctrl interface ready (PONG)")
                return true
            }
            delay(250)
        }
        log("[-] wpa_supplicant ctrl interface not ready (no PONG)")
        return false
    }

    /**
     * Construye una invocación de wpa_cli con el entorno del supplicant
     * empaquetado: el binario y libnl viven en el directorio de binarios, y
     * `-p`/`-s` apuntan al directorio de control para no depender de rutas del
     * sistema.
     */
    private fun cliCommand(iface: String, ctrlDir: String, args: String): String {
        val dir = NativeBinaries.binaryDir(context)
        val wpaCli = NativeBinaries.resolveBinaryPath(context, NativeBinaries.wpaCliAssetName())
        return "cd $dir && export LD_LIBRARY_PATH=$dir && " +
            "$wpaCli -p$ctrlDir -s$ctrlDir -i $iface $args"
    }

    /** Ejecuta un comando de wpa_cli con el timeout corto usado por el ataque. */
    fun cli(iface: String, args: String): ShellExecutor.Result =
        PixieProcessSupport.runSuWithTimeout(
            cliCommand(iface, NativeBinaries.ctrlDir(), args),
            timeoutSeconds = 5
        )

    fun sendWpsReg(
        bssid: String,
        iface: String,
        log: (String) -> Unit
    ): ShellExecutor.Result {
        // wpa_cli 2.11 solo acepta `wps_reg <BSSID> <PIN>` (o 6 argumentos
        // para reconfigurar el AP). No existe un argumento `freq=`: la
        // frecuencia la deduce wpa_supplicant del BSS escaneado.
        val cmd = cliCommand(iface, NativeBinaries.ctrlDir(), "wps_reg $bssid $WPS_REG_PIN_DEFAULT")
        log("[*] wpa_cli (wps_reg): $cmd")
        return PixieProcessSupport.runSuWithTimeout(cmd, timeoutSeconds = 5)
    }

    private suspend fun waitForSocket(
        socketPath: String,
        timeoutMs: Long,
        log: (String) -> Unit
    ): Boolean {
        log("[*] Waiting for wpa_supplicant socket: $socketPath ($timeoutMs ms)...")
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            attempts++
            try {
                val r = PixieProcessSupport.execSu(
                    "test -S $socketPath && echo OK",
                    timeoutSeconds = 2
                )
                if (r.output.lines().any { it.trim() == "OK" }) {
                    log("[+] Socket ready: $socketPath (attempt $attempts)")
                    return true
                }
            } catch (_: Exception) {}
            if (attempts % 4 == 0) {
                log("[*]   ... still waiting for socket (${System.currentTimeMillis() - start} ms elapsed)")
            }
            delay(300)
        }
        log("[-] Socket wait timed out: $socketPath ($attempts attempts)")
        return false
    }

    /**
     * Prepara el config del supplicant en el directorio de control.
     *
     * No basta con `-O`: ese flag solo *sobrescribe* el parámetro
     * `ctrl_interface`; si el config no lo declara, wpa_supplicant no crea
     * ningún socket. El asset solo trae `ctrl_interface_group`, así que aquí
     * se genera un config completo con la ruta real (vendor o legacy).
     *
     * El fichero se envía por stdin porque el dominio SELinux de `su` no
     * puede leer filesDir.
     */
    private fun stageConfig(ctrlDir: String, log: (String) -> Unit): Boolean {
        val source = File(
            NativeBinaries.binaryDir(context),
            NativeBinaries.WPA_SUPPLICANT_CONF
        )
        if (!source.exists() || source.length() == 0L) {
            log("[-] Falta ${source.absolutePath}")
            return false
        }

        // Conservar otras opciones del asset, pero descartar las variantes de
        // ctrl_interface para no duplicar directivas con la ruta runtime.
        val extraOptions = source.readLines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("ctrl_interface=") &&
                    !it.startsWith("ctrl_interface_group=")
            }
        val content = buildString {
            appendLine("ctrl_interface=DIR=$ctrlDir GROUP=wifi")
            extraOptions.forEach { appendLine(it) }
        }

        val target = NativeBinaries.runtimeConfigPath()
        val staged = File(context.cacheDir, "wpa_supplicant.runtime.conf")
        return try {
            staged.writeText(content)
            val result = ShellExecutor.execRootWithInput(
                command = "cat > $target && chmod 644 $target",
                input = staged,
                timeoutMs = 5000
            )
            if (result.exitCode != 0) {
                val detail = result.error.ifBlank { result.output }.ifBlank { "exit=${result.exitCode}" }
                log("[-] No se pudo copiar el config a $target: $detail")
                false
            } else {
                log("[+] Config del supplicant preparado en $target (ctrl_interface=$ctrlDir)")
                true
            }
        } catch (e: Exception) {
            ShellExecutor.logE(TAG, "No se pudo preparar el config del supplicant", e)
            log("[-] No se pudo preparar el config del supplicant: ${e.message}")
            false
        } finally {
            staged.delete()
        }
    }

    /**
     * Mata el supplicant de una ronda y cualquier hijo que haya quedado vivo.
     * `Process.destroy()` solo termina el wrapper `su`; el binario real sigue
     * en ejecución hasta que expira su `sleep 110`, lo que dejaría dos
     * supplicants compitiendo por la misma interfaz en la ronda siguiente.
     */
    fun stop(process: Process?) {
        process?.let {
            it.destroy()
            try {
                it.waitForCompat(1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // Se ancla al inicio del cmdline para no matar al wrapper `su` que
        // ejecuta este mismo pkill (su cmdline contiene la ruta como texto).
        val supplicantPath = NativeBinaries.resolveBinaryPath(
            context,
            NativeBinaries.wpaSupplicantAssetName()
        )
        try {
            PixieProcessSupport.killNativeProcess(supplicantPath)
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /**
     * Detiene el supplicant y vuelca en [rawOutput] las líneas que aún
     * estaban en la cola del lector. Sin este volcado, las últimas líneas de
     * la captura M3 se pierden al morir el proceso y el log de auditoría queda
     * incompleto.
     */
    fun stopAndFlush(
        handle: Handle?,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ) {
        if (handle == null) return
        stop(handle.process)
        PixieProcessSupport.joinReader(handle.reader)
        flushLines(handle.lines, rawOutput, log)
    }

    /**
     * Registra una línea cruda del supplicant en la consola y en el volcado
     * que acaba en el log de auditoría. Solo se llama desde la corrutina del
     * ataque: el hilo lector se limita a encolar líneas.
     */
    fun recordLine(
        line: String,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ) {
        log("[supplicant] $line")
        rawOutput.append(line).append('\n')
    }

    /** Vuelca en la corrutina las líneas ya recibidas del supplicant. */
    fun flushLines(
        queue: BlockingQueue<String>,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ) {
        while (true) {
            val line = queue.poll() ?: return
            // Ambos centinelas marcan fin de stream: nunca son salida real.
            if (line == PixieProcessSupport.PROCESS_EOF || line == M3Parser.END_OF_STREAM) continue
            recordLine(line, rawOutput, log)
        }
    }

    /**
     * Descarta las líneas que quedaron sin consumir de una ronda anterior.
     * Se conserva el centinela de fin de stream: si el supplicant murió, el
     * parser debe enterarse en lugar de esperar hasta el timeout.
     */
    fun discardStale(queue: BlockingQueue<String>) {
        var sawEof = false
        while (true) {
            val line = queue.poll() ?: break
            if (line == M3Parser.END_OF_STREAM) sawEof = true
        }
        if (sawEof) queue.offer(M3Parser.END_OF_STREAM)
    }

    companion object {
        private const val TAG = "SupplicantProcesses"

        /** Espera máxima del socket de control y de su primer PONG. */
        private const val SOCKET_WAIT_MS = 8_000L

        /** Watchdog del proceso supplicant: si sigue vivo, se mata a los 110 s. */
        private const val SUPPLICANT_WATCHDOG_SECONDS = 110
    }
}
