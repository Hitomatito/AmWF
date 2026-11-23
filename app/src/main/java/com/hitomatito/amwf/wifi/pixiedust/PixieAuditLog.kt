/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import android.os.Build
import android.os.Environment
import com.hitomatito.amwf.ShellExecutor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Persistencia del log de auditoría de Pixie Dust.
 *
 * El fichero contiene credenciales WPS/WPA en texto claro, así que solo se
 * escribe en la ruta pública si hay root y, si no, en el directorio privado de
 * la app. [PixieDustRunner] debe llamar a [persist] en *todos* los caminos de
 * salida del ataque.
 */
internal object PixieAuditLog {

    /** Datos del objetivo que acompañan a todo log de auditoría. */
    data class Target(
        val bssid: String,
        val ssid: String?,
        val freqMHz: Int?,
        val interfaceName: String,
        /** Señal (RSSI) del objetivo al seleccionarlo, en dBm. */
        val levelDbm: Int? = null
    )

    /**
     * Un hito de tiempo del ataque (nanosegundos monotónicos de
     * `System.nanoTime`). El resumen del log deriva duraciones entre hitos;
     * si una etiqueta se repite entre rondas, gana la última ocurrencia.
     */
    data class AuditTiming(val label: String, val elapsedNs: Long)

    /**
     * Datos estructurados del ataque que de otro modo quedan dispersos en
     * los eventos: rondas usadas, tipo de escaneo y modo de pixiewps. Los
     * pinta [buildAuditSummaryLines] en la sección «Resumen» para que el
     * análisis cruzado de muchos logs sea un simple grep.
     */
    data class Summary(
        val timings: List<AuditTiming>,
        val roundsUsed: Int,
        val maxRounds: Int,
        /** true si el objetivo traía frecuencia (escaneo dirigido `freq_list`). */
        val directedScan: Boolean,
        /** true si se consumió la ronda de rescate full-band. */
        val fullBandFallbackUsed: Boolean,
        /** Modo de pixiewps de la última ronda que llegó a ejecutarse. */
        val pixiewpsMode: Int?
    )

    private const val TAG = "PixieAuditLog"

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
     *   - BSSID, SSID, frecuencia, señal e interfaz del objetivo
     *   - Versión de la app y Android/dispositivo (correlaciona logs con código)
     *   - Resultado (éxito/falla) y razón
     *   - PIN WPS y PSK WPA si se obtuvieron
     *   - Eventos de la app y salida cruda de wpa_supplicant/wpa_cli/pixiewps
     *   - Resumen final con duraciones por fase, rondas y modo de pixiewps
     */
    fun persist(
        context: Context,
        result: PixieDustResult?,
        target: Target,
        eventLines: List<String>,
        rawLines: List<String>,
        summary: Summary,
        log: (String) -> Unit
    ) {
        // UUID además de milisegundos: dos ataques no deben sobrescribirse
        // aunque terminen en la misma fracción de segundo.
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName =
            "pixie_${timestamp}_${auditBssidForFileName(target.bssid)}_${UUID.randomUUID()}.log"

        val content = buildString {
            appendLine("=== AmWF Pixie Dust Audit Log ===")
            appendLine("AVISO: contiene credenciales WPS/WPA en texto claro; protégelo.")
            appendLine("Fecha: ${Date()}")
            appendLine("BSSID: ${target.bssid}")
            appendLine("SSID: ${target.ssid?.takeIf { it.isNotBlank() } ?: "(desconocido)"}")
            appendLine("Frecuencia: ${target.freqMHz?.let { "$it MHz" } ?: "(desconocida)"}")
            appendLine("Señal: ${target.levelDbm?.let { "$it dBm" } ?: "(desconocida)"}")
            appendLine("Interfaz: ${target.interfaceName}")
            appendLine("Arquitectura: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "desconocida"}")
            appendLine("Versión: ${appVersionLabel(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Dispositivo: ${Build.MODEL}")
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
            appendLine("=== Resumen ===")
            buildAuditSummaryLines(summary).forEach { appendLine(it) }
            appendLine()
            appendLine("=== Fin del log ===")
        }

        // 1) Ruta principal: almacenamiento compartido (/sdcard/AmWF/pixie-logs/)
        //    mediante root. Se construye con la API pública (equivalente a /sdcard
        //    en almacenamiento emulado) para no depender del literal.
        @Suppress("DEPRECATION")
        val sdcardDir = File(Environment.getExternalStorageDirectory(), "AmWF/pixie-logs").absolutePath
        val sdcardPath = "$sdcardDir/$fileName"
        val tempPath = "$sdcardPath.tmp"
        val staged = File(context.cacheDir, fileName)
        var writtenToSdcard = false
        try {
            staged.writeText(content)
            // El nombre ya es seguro, pero las rutas también se citan como
            // defensa en profundidad. mv deja el log completo de forma atómica.
            val dirArg = PixieProcessSupport.shellQuote(sdcardDir)
            val tempArg = PixieProcessSupport.shellQuote(tempPath)
            val pathArg = PixieProcessSupport.shellQuote(sdcardPath)
            val written = ShellExecutor.execRootWithInput(
                command = "mkdir -p $dirArg && cat > $tempArg && " +
                    "chmod 600 $tempArg && mv -f $tempArg $pathArg",
                input = staged,
                timeoutMs = 10_000
            )
            if (written.exitCode == 0) {
                writtenToSdcard = true
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
            if (!writtenToSdcard) {
                // Si el shell falla o expira antes del mv, el temporal puede
                // quedar con credenciales en el almacenamiento compartido; se elimina best-effort.
                ShellExecutor.execRoot(
                    "rm -f ${PixieProcessSupport.shellQuote(tempPath)}",
                    timeoutMs = 3_000
                )
            }
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

    /**
     * Versión de la app tal como la muestra el log («name (code)»). Se lee
     * del PackageManager en lugar de habilitar buildConfig solo para esto;
     * si el paquete no responde, el log queda con un marcador honesto.
     */
    private fun appVersionLabel(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    } catch (_: Exception) {
        "desconocida"
    }
}

private val AUDIT_BSSID_REGEX = Regex("^[0-9A-Fa-f]{12}$")

/**
 * Etiquetas de los hitos de tiempo que [PixieDustRunner] registra vía
 * [PixieAuditLog.AuditTiming]. Viven aquí para que el runner y
 * [buildAuditSummaryLines] no puedan divergir.
 */
internal object AuditTimingLabels {
    const val START = "start"
    const val BINARIES_READY = "binaries_ready"
    const val M3_CAPTURED = "m3_captured"
    const val PIXIEWPS_START = "pixiewps_start"
    const val PIXIEWPS_DONE = "pixiewps_done"
    const val PSK_START = "psk_start"
    const val PSK_DONE = "psk_done"
    const val CLEANUP_START = "cleanup_start"
    const val CLEANUP_DONE = "cleanup_done"
    const val END = "end"
}

/**
 * Líneas de la sección «Resumen» del log de auditoría.
 *
 * Función pura: solo decide qué duraciones pueden calcularse con los hitos
 * presentes (un fallo temprano no tiene M3 ni pixiewps, y sus líneas simplemente
 * no aparecen) y las formatea con punto decimal para que `grep` no dependa del
 * idioma. Si una etiqueta se repitió entre rondas, gana la última ocurrencia.
 */
internal fun buildAuditSummaryLines(summary: PixieAuditLog.Summary): List<String> {
    val ns = summary.timings.associate { it.label to it.elapsedNs }
    fun durationSec(from: String, to: String): String? {
        val start = ns[from] ?: return null
        val end = ns[to] ?: return null
        return String.format(Locale.US, "%.1f", (end - start) / 1_000_000_000.0)
    }
    return buildList {
        durationSec(AuditTimingLabels.START, AuditTimingLabels.END)
            ?.let { add("Duración total: $it s") }
        durationSec(AuditTimingLabels.START, AuditTimingLabels.BINARIES_READY)
            ?.let { add("Preparación: $it s") }
        durationSec(AuditTimingLabels.START, AuditTimingLabels.M3_CAPTURED)
            ?.let { add("Hasta M3: $it s") }
        durationSec(AuditTimingLabels.PIXIEWPS_START, AuditTimingLabels.PIXIEWPS_DONE)
            ?.let { add("Pixiewps: $it s") }
        durationSec(AuditTimingLabels.PSK_START, AuditTimingLabels.PSK_DONE)
            ?.let { add("Extracción PSK: $it s") }
        durationSec(AuditTimingLabels.CLEANUP_START, AuditTimingLabels.CLEANUP_DONE)
            ?.let { add("Cleanup: $it s") }
        add("Rondas: ${summary.roundsUsed}/${summary.maxRounds}")
        add(
            "Escaneo: " + when {
                summary.directedScan && summary.fullBandFallbackUsed ->
                    "dirigido (freq_list) con fallback a banda completa"
                summary.directedScan -> "dirigido (freq_list)"
                else -> "banda completa"
            }
        )
        summary.pixiewpsMode?.let { add("Modo pixiewps: $it") }
    }
}

/**
 * Componente de nombre de fichero derivado del BSSID.
 *
 * La validación del ataque informa del error pero conserva el valor original
 * en el log. Por eso la persistencia no puede confiar en que el BSSID ya sea
 * válido: solo un MAC exacto pasa al nombre; cualquier otra entrada usa un
 * marcador constante y nunca llega a un comando shell.
 */
internal fun auditBssidForFileName(bssid: String): String =
    bssid.replace(":", "").takeIf { AUDIT_BSSID_REGEX.matches(it) } ?: "invalid"
