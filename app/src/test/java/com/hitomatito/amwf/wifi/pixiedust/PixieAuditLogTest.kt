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

    @Test
    fun `la redaccion de vecinos conserva al objetivo y reduce al resto a OUI`() {
        val target = "74:da:88:13:ce:a0"
        val ssid = "familiachoque"

        // Línea del vecino con formato real del supplicant (log de campo).
        val neighbor = redactNeighborMentions(
            "wlan0: BSS: Add new id 2 BSSID d8:31:39:53:e7:1a SSID 'PARIGUANA' freq 2437",
            target,
            ssid
        )
        assertEquals(
            "wlan0: BSS: Add new id 2 BSSID d8:31:39:xx:xx:xx SSID <redacted> freq 2437",
            neighbor
        )

        // La línea del objetivo queda intacta: es el propósito del log.
        val own = "wlan0: BSS: Add new id 7 BSSID 74:da:88:13:ce:a0 SSID 'familiachoque' freq 2417"
        assertEquals(own, redactNeighborMentions(own, target, ssid))
    }

    @Test
    fun `la redaccion cubre la lista de seleccion y no toca los hex M3`() {
        val target = "74:da:88:13:ce:a0"
        val ssid = "familiachoque"

        // Formato de la lista de selección: ssid='...' con comillas simples.
        val selection = redactNeighborMentions(
            "wlan0: 3: d8:31:39:53:e7:1a ssid='PARIGUANA' wps",
            target,
            ssid
        )
        assertEquals("wlan0: 3: d8:31:39:xx:xx:xx ssid=<redacted> wps", selection)

        // El objetivo en la misma lista se conserva (comparación de MAC sin
        // distinguir mayúsculas, como lo imprime el supplicant).
        val ownSelection = "wlan0: 4: 74:DA:88:13:CE:A0 ssid='familiachoque' wps"
        assertEquals(ownSelection, redactNeighborMentions(ownSelection, target, ssid))

        // Los campos M3 usan espacios, no dos-puntos: el regex de MAC no puede
        // tocarlos, y tampoco hay clave «ssid» que redactar.
        val hexdump = "WPS: AuthKey - hexdump(len=32): 52 cc c8 55 96 9e ab 6b 70 4f a5 ce da 39 e1 86"
        assertEquals(hexdump, redactNeighborMentions(hexdump, target, ssid))
    }

    @Test
    fun `con SSID oculto se redactan todas las menciones de SSID`() {
        // El objetivo es una red oculta: su propio SSID también se redacta
        // (la cabecera ya dice «(desconocido)»); el BSSID sí se conserva.
        val target = "74:da:88:13:ce:a0"
        val line = redactNeighborMentions(
            "wlan0: BSS: Add new id 7 BSSID 74:da:88:13:ce:a0 SSID 'cualquiera' freq 2417",
            target,
            null
        )
        assertEquals(
            "wlan0: BSS: Add new id 7 BSSID 74:da:88:13:ce:a0 SSID <redacted> freq 2417",
            line
        )
    }
}
