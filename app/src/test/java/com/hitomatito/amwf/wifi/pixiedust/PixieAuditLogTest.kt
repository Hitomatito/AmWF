/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixieAuditLogTest {

    private fun timing(label: String, sec: Double) =
        PixieAuditLog.AuditTiming(label, (sec * 1_000_000_000).toLong())

    private fun summary(
        timings: List<PixieAuditLog.AuditTiming>,
        roundsUsed: Int = 1,
        directedScan: Boolean = true,
        fullBandFallbackUsed: Boolean = false,
        pixiewpsMode: Int? = null
    ) = PixieAuditLog.Summary(
        timings = timings,
        roundsUsed = roundsUsed,
        maxRounds = 3,
        directedScan = directedScan,
        fullBandFallbackUsed = fullBandFallbackUsed,
        pixiewpsMode = pixiewpsMode
    )

    @Test
    fun `normaliza un BSSID valido para el nombre de fichero`() {
        assertEquals("AABBCCDDEEFF", auditBssidForFileName("AA:BB:CC:DD:EE:FF"))
        assertEquals("aabbccddeeff", auditBssidForFileName("aa:bb:cc:dd:ee:ff"))
        assertEquals("001122334455", auditBssidForFileName("00:11:22:33:44:55"))
        assertEquals("aabbccddeeff", auditBssidForFileName("aabbccddeeff"))
    }

    @Test
    fun `rechaza BSSID invalidos o intentos de path traversal`() {
        assertEquals("invalid", auditBssidForFileName(""))
        assertEquals("invalid", auditBssidForFileName("../../etc/passwd"))
        assertEquals("invalid", auditBssidForFileName("aa:bb:cc:dd:ee"))
        assertEquals("invalid", auditBssidForFileName("gg:bb:cc:dd:ee:ff"))
        assertEquals("invalid", auditBssidForFileName("aa-bb-cc-dd-ee-ff"))
        assertEquals("invalid", auditBssidForFileName("aa:bb:cc:dd:ee:ff; rm -rf /"))
    }

    @Test
    fun `el resumen de un exito completo calcula cada duracion`() {
        val lines = buildAuditSummaryLines(
            summary(
                timings = listOf(
                    timing(AuditTimingLabels.START, 0.0),
                    timing(AuditTimingLabels.BINARIES_READY, 2.0),
                    timing(AuditTimingLabels.M3_CAPTURED, 10.0),
                    timing(AuditTimingLabels.PIXIEWPS_START, 10.5),
                    timing(AuditTimingLabels.PIXIEWPS_DONE, 12.0),
                    timing(AuditTimingLabels.PSK_START, 13.0),
                    timing(AuditTimingLabels.PSK_DONE, 20.0),
                    timing(AuditTimingLabels.CLEANUP_START, 20.5),
                    timing(AuditTimingLabels.CLEANUP_DONE, 22.0),
                    timing(AuditTimingLabels.END, 22.0)
                ),
                pixiewpsMode = 1
            )
        )
        assertEquals(
            listOf(
                "Duración total: 22.0 s",
                "Preparación: 2.0 s",
                "Hasta M3: 10.0 s",
                "Pixiewps: 1.5 s",
                "Extracción PSK: 7.0 s",
                "Cleanup: 1.5 s",
                "Rondas: 1/3",
                "Escaneo: dirigido (freq_list)",
                "Modo pixiewps: 1"
            ),
            lines
        )
    }

    @Test
    fun `un fallo temprano solo muestra las duraciones existentes`() {
        val lines = buildAuditSummaryLines(
            summary(
                timings = listOf(
                    timing(AuditTimingLabels.START, 0.0),
                    timing(AuditTimingLabels.BINARIES_READY, 0.5),
                    timing(AuditTimingLabels.END, 0.6)
                ),
                roundsUsed = 0,
                directedScan = false
            )
        )
        assertEquals(
            listOf(
                "Duración total: 0.6 s",
                "Preparación: 0.5 s",
                "Rondas: 0/3",
                "Escaneo: banda completa"
            ),
            lines
        )
    }

    @Test
    fun `un hito repetido entre rondas usa la ultima ocurrencia`() {
        val lines = buildAuditSummaryLines(
            summary(
                timings = listOf(
                    timing(AuditTimingLabels.START, 0.0),
                    // Dos rondas: la captura que cuenta es la última.
                    timing(AuditTimingLabels.M3_CAPTURED, 5.0),
                    timing(AuditTimingLabels.M3_CAPTURED, 15.0),
                    timing(AuditTimingLabels.END, 20.0)
                ),
                roundsUsed = 2,
                fullBandFallbackUsed = true
            )
        )
        assertTrue("Hasta M3: 15.0 s" in lines)
        assertTrue("Rondas: 2/3" in lines)
        assertTrue("Escaneo: dirigido (freq_list) con fallback a banda completa" in lines)
        // Sin pixiewps en el resumen no hay línea de modo.
        assertTrue(lines.none { it.startsWith("Modo pixiewps") })
    }
}
