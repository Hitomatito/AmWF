/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del contenido del config runtime del supplicant.
 *
 * El asset `wpa_supplicant.conf` solo fija opciones globales (update_config,
 * ap_scan, auto_uuid); la ruta de control se inyecta en runtime y, desde los
 * logs de campo, también un `freq_list` global con el canal del objetivo para
 * que cada `wps_reg` no barra toda la banda 2.4+5 GHz.
 */
class SupplicantProcessesTest {

    private val assetLines = listOf(
        "ctrl_interface_group=wifi",
        "update_config=1",
        "ap_scan=1",
        "auto_uuid=1"
    )

    @Test
    fun `reemplaza las directivas ctrl_interface del asset por la ruta runtime`() {
        val lines = assetLines + "ctrl_interface=DIR=/data/misc/wifi/amwf"

        val content = buildRuntimeConfigContent(lines, ctrlDir = "/data/vendor/wifi/amwf", freqMHz = null)

        assertEquals(
            listOf(
                "ctrl_interface=DIR=/data/vendor/wifi/amwf GROUP=wifi",
                "update_config=1",
                "ap_scan=1",
                "auto_uuid=1"
            ),
            content.lineSequence().filter { it.isNotBlank() }.toList()
        )
    }

    @Test
    fun `añade freq_list con la frecuencia del objetivo cuando se conoce`() {
        val content = buildRuntimeConfigContent(
            assetLines,
            ctrlDir = "/data/vendor/wifi/amwf",
            freqMHz = 5180
        )

        assertTrue(content.lineSequence().any { it == "freq_list=5180" })
        // Las opciones imprescindibles no desaparecen.
        assertTrue(content.lineSequence().any { it == "update_config=1" })
    }

    @Test
    fun `sin frecuencia o fuera de rango se omite freq_list`() {
        // freqMHz desconocida (entrada manual sin escaneo) o absurda: el config
        // debe seguir siendo válido y el supplicant vuelve a escanear full-band.
        for (freq in listOf(null, 0, 999, 100_000)) {
            val content = buildRuntimeConfigContent(assetLines, "/data/vendor/wifi/amwf", freq)

            assertFalse(
                "freq=$freq no debía incluir freq_list",
                content.lineSequence().any { it.startsWith("freq_list=") }
            )
        }
    }
}
