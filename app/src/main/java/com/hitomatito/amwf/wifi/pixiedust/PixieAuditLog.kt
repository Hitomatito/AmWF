/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import android.os.Build
import com.hitomatito.amwf.ShellExecutor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val interfaceName: String
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
     *   - BSSID, SSID, frecuencia e interfaz del objetivo
     *   - Resultado (éxito/falla) y razón
     *   - PIN WPS y PSK WPA si se obtuvieron
     *   - Eventos de la app y salida cruda de wpa_supplicant/wpa_cli/pixiewps
     */
    fun persist(
        context: Context,
        result: PixieDustResult?,
        target: Target,
        eventLines: List<String>,
        rawLines: List<String>,
        log: (String) -> Unit
    ) {
        // Milisegundos para que dos ataques que terminen en el mismo segundo
        // no se sobrescriban (el requisito es conservar todos los logs).
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val safeBssid = target.bssid.replace(":", "")
        val fileName = "pixie_${timestamp}_$safeBssid.log"

        val content = buildString {
            appendLine("=== AmWF Pixie Dust Audit Log ===")
            appendLine("AVISO: contiene credenciales WPS/WPA en texto claro; protégelo.")
            appendLine("Fecha: ${Date()}")
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
}
