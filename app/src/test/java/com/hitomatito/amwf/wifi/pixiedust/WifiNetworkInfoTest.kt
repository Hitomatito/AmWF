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

class WifiNetworkInfoTest {

    private fun network(
        frequency: Int,
        capabilities: String
    ) = PixieDustNetwork(
        ssid = "Test",
        bssid = "00:11:22:33:44:55",
        frequency = frequency,
        level = -50,
        wpsCapable = true,
        wpsLocked = false,
        capabilities = capabilities
    )

    @Test
    fun `band se deriva de la frecuencia`() {
        assertEquals(WifiBand.BAND_24_GHZ, network(2437, "").band())
        assertEquals(WifiBand.BAND_5_GHZ, network(5180, "").band())
        assertEquals(WifiBand.BAND_6_GHZ, network(5955, "").band())
        assertEquals(WifiBand.OTHER, network(915, "").band())
    }

    @Test
    fun `canal se calcula para 2_4 y 5 GHz`() {
        assertEquals(1, channelForFrequency(2412))
        assertEquals(6, channelForFrequency(2437))
        assertEquals(14, channelForFrequency(2484))
        assertEquals(36, channelForFrequency(5180))
        assertEquals(149, channelForFrequency(5745))
        assertEquals(1, channelForFrequency(5955))
        assertNull(channelForFrequency(900))
    }

    @Test
    fun `seguridad se interpreta desde capabilities`() {
        assertEquals(
            WifiSecurity.WPA2,
            securityFromCapabilities("[WPA2-PSK-CCMP][WPS][ESS]")
        )
        assertEquals(
            WifiSecurity.WPA3,
            securityFromCapabilities("[WPA3-SAE-CCMP][ESS]")
        )
        assertEquals(
            WifiSecurity.WPA2_WPA3,
            securityFromCapabilities("[WPA2-PSK-CCMP][WPA3-SAE-CCMP][ESS]")
        )
        assertEquals(
            WifiSecurity.WPA_WPA2,
            securityFromCapabilities("[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]")
        )
        assertEquals(WifiSecurity.WEP, securityFromCapabilities("[WEP][ESS]"))
        assertEquals(WifiSecurity.OPEN, securityFromCapabilities("[ESS]"))
        assertEquals(WifiSecurity.OWE, securityFromCapabilities("[OWE][ESS]"))
        assertEquals(WifiSecurity.UNKNOWN, securityFromCapabilities(""))
    }
}
