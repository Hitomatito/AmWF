/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de la confirmación del estado WiFi tras la restauración.
 *
 * `svc wifi enable` puede devolver 0 sin encender el radio; cleanup comprueba
 * la salida de `dumpsys wifi`/`cmd wifi status` para decidir si reintenta.
 */
class WifiRestoreTest {

    @Test
    fun `detects enabled from dumpsys output`() {
        assertEquals(true, WifiRestore.isEnabled("Wi-Fi is enabled"))
    }

    @Test
    fun `detects disabled from dumpsys output`() {
        assertEquals(false, WifiRestore.isEnabled("Wi-Fi is disabled"))
    }

    @Test
    fun `detects enabled from cmd wifi status output`() {
        assertEquals(true, WifiRestore.isEnabled("Wifi is enabled"))
    }

    @Test
    fun `detects disabled from cmd wifi status output`() {
        assertEquals(false, WifiRestore.isEnabled("Wifi is disabled"))
    }

    @Test
    fun `ignores surrounding lines and case`() {
        val text = """
            Dump of WifiService
            WI-FI IS ENABLED
            mWifiInfo=...
        """.trimIndent()
        assertEquals(true, WifiRestore.isEnabled(text))
    }

    @Test
    fun `returns null when the state is unknown`() {
        assertNull(WifiRestore.isEnabled(""))
        assertNull(WifiRestore.isEnabled("Wi-Fi is enabling"))
        assertNull(WifiRestore.isEnabled("command not found"))
    }
}
