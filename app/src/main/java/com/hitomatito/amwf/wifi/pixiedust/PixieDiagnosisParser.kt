/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

/**
 * Traduce la salida de pixiewps en una decisión accionable para AmWF.
 *
 * El executor solo extrae el PIN; el resto del veredicto queda enterrado en el
 * log crudo. pixiewps 1.4.2 imprime en modo normal (verbosidad 3) el modo
 * detectado, avisos como "might be/ vulnerable" y errores `--m5-enc`/`--m7-enc`.
 * Las líneas DEBUG_PRINT no aparecen en el binario de release, así que este
 * parser no depende de ellas.
 */
internal enum class PixieDiagnosisKind {
    PIN_FOUND,
    MIGHT_BE_VULNERABLE,
    NOT_FOUND,
    UNSUPPORTED_SETUP,
    PROCESS_ERROR,
    UNKNOWN
}

internal enum class PixieNextAction {
    NONE,
    RETRY_WITH_NEW_DATA,
    STOP
}

internal data class PixieDiagnosis(
    val kind: PixieDiagnosisKind,
    val action: PixieNextAction,
    val mode: Int? = null
)

internal object PixieDiagnosisParser {
    private val MODE_LINE = Regex("""\[\?\]\s*Mode:\s*(\d+)""")
    private val PIN_LINE = Regex("""\[\+\]\s*WPS\s+PIN:""", RegexOption.IGNORE_CASE)
    private val NOT_FOUND_LINE = Regex("""\[-\]\s*WPS pin not found""", RegexOption.IGNORE_CASE)
    private val MIGHT_BE_VULNERABLE = "might be/ vulnerable"
    private val SMALL_DH_UNSUPPORTED = "Small DH keys is not supported for mode 3"

    fun parse(output: String): PixieDiagnosis {
        val lines = output.lineSequence().map { it.trim() }.toList()
        val mode = lines.firstNotNullOfOrNull { line ->
            MODE_LINE.find(line)?.groupValues?.get(1)?.toIntOrNull()
        }

        fun diagnosis(kind: PixieDiagnosisKind, action: PixieNextAction) =
            PixieDiagnosis(kind, action, mode)

        if (lines.any { PIN_LINE.containsMatchIn(it) }) {
            return diagnosis(PixieDiagnosisKind.PIN_FOUND, PixieNextAction.NONE)
        }
        if (lines.any { it.contains(MIGHT_BE_VULNERABLE, ignoreCase = true) }) {
            return diagnosis(PixieDiagnosisKind.MIGHT_BE_VULNERABLE, PixieNextAction.RETRY_WITH_NEW_DATA)
        }
        // Con claves DH pequeñas pixiewps no puede usar el modo 3. AmWF no
        // captura el R-Nonce que exigen -5/-7, así que no hay recaptura útil.
        if (lines.any { it.contains(SMALL_DH_UNSUPPORTED, ignoreCase = true) }) {
            return diagnosis(PixieDiagnosisKind.UNSUPPORTED_SETUP, PixieNextAction.STOP)
        }
        if (lines.any { it.contains("[x]") || it.contains("[X]") }) {
            return diagnosis(PixieDiagnosisKind.PROCESS_ERROR, PixieNextAction.STOP)
        }
        if (lines.any { NOT_FOUND_LINE.containsMatchIn(it) }) {
            return diagnosis(PixieDiagnosisKind.NOT_FOUND, PixieNextAction.STOP)
        }
        return PixieDiagnosis(PixieDiagnosisKind.UNKNOWN, PixieNextAction.STOP, mode)
    }
}
