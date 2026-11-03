/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de resolución y presentación de binarios.
 *
 * Los assets de 32 bits llevan sufijo `_32` (p. ej. `wpa_cli_32`), pero los
 * binarios instalados en el sistema no. Si la resolución no quitara el sufijo,
 * el fallback a `/system/bin` o `/vendor/bin/hw` nunca encontraría los
 * binarios en dispositivos de 32 bits. La UI, además, debe mostrar el
 * componente que falta sin exponer el nombre exacto del asset.
 */
class NativeBinariesTest {

    @Test
    fun `strips 32-bit suffix from wpa_supplicant`() {
        assertEquals("wpa_supplicant", NativeBinaries.systemBinaryName("wpa_supplicant_32"))
    }

    @Test
    fun `strips 32-bit suffix from wpa_cli`() {
        assertEquals("wpa_cli", NativeBinaries.systemBinaryName("wpa_cli_32"))
    }

    @Test
    fun `strips 32-bit suffix from pixiewps`() {
        assertEquals("pixiewps", NativeBinaries.systemBinaryName("pixiewps_32"))
    }

    @Test
    fun `keeps 64-bit names unchanged`() {
        assertEquals("wpa_supplicant", NativeBinaries.systemBinaryName("wpa_supplicant"))
        assertEquals("wpa_cli", NativeBinaries.systemBinaryName("wpa_cli"))
        assertEquals("pixiewps", NativeBinaries.systemBinaryName("pixiewps"))
    }

    @Test
    fun `display names hide architecture suffix and library version`() {
        assertEquals("wpa_supplicant", NativeBinaries.displayName("wpa_supplicant_32"))
        assertEquals("wpa_cli", NativeBinaries.displayName("wpa_cli_32"))
        assertEquals("pixiewps", NativeBinaries.displayName("pixiewps_32"))
        assertEquals("libnl", NativeBinaries.displayName("libnl-3.so.200"))
    }

    @Test
    fun `display names deduplicate components`() {
        assertEquals(
            "wpa_supplicant, libnl",
            NativeBinaries.displayNames(
                listOf("wpa_supplicant", "libnl-3.so.200", "libnl-genl-3.so.200")
            )
        )
    }
}
