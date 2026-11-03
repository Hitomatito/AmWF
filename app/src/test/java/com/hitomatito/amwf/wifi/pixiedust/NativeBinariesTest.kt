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
 * Regression tests for [NativeBinaries.systemBinaryName].
 *
 * Los assets de 32 bits llevan sufijo `_32` (p. ej. `wpa_cli_32`), pero los
 * binarios instalados en el sistema no. Si la resolución no quitara el sufijo,
 * el fallback a `/system/bin` o `/vendor/bin/hw` nunca encontraría los
 * binarios en dispositivos de 32 bits.
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
}
