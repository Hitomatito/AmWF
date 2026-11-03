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

class PixieNetworkFilterTest {

    private fun network(
        ssid: String,
        bssid: String = "00:11:22:33:44:55",
        frequency: Int = 2437,
        wpsCapable: Boolean = true,
        wpsLocked: Boolean = false
    ) = PixieDustNetwork(
        ssid = ssid,
        bssid = bssid,
        frequency = frequency,
        level = -50,
        wpsCapable = wpsCapable,
        wpsLocked = wpsLocked,
        capabilities = "",
        hiddenSsidLabel = "Red oculta"
    )

    private val networks = listOf(
        network("Casa", "aa:bb:cc:dd:ee:01", 2437),
        network("Casa", "aa:bb:cc:dd:ee:02", 5180),
        network("Oficina", "aa:bb:cc:dd:ee:03", 5180),
        network("Invitados", "aa:bb:cc:dd:ee:04", 2437, wpsLocked = true),
        network("Libre", "aa:bb:cc:dd:ee:05", 2437, wpsCapable = false)
    )

    private val bands = bandsBySsid(networks)

    @Test
    fun `todos los filtros de banda y auditables`() {
        assertEquals(5, filterNetworks(networks, NetworkFilter.ALL, "", bands).size)
        assertEquals(
            listOf("Casa", "Casa", "Oficina"),
            filterNetworks(networks, NetworkFilter.AUDITABLE, "", bands).map { it.ssid }
        )
        assertEquals(
            listOf("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:04", "aa:bb:cc:dd:ee:05"),
            filterNetworks(networks, NetworkFilter.BAND_24, "", bands).map { it.bssid }
        )
        assertEquals(
            listOf("aa:bb:cc:dd:ee:02", "aa:bb:cc:dd:ee:03"),
            filterNetworks(networks, NetworkFilter.BAND_5, "", bands).map { it.bssid }
        )
    }

    @Test
    fun `el filtro de doble banda agrupa por SSID visible`() {
        assertEquals(1, countDualBandNetworks(bands))
        assertEquals(
            listOf("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"),
            filterNetworks(networks, NetworkFilter.DUAL_BAND, "", bands).map { it.bssid }
        )
    }

    @Test
    fun `las redes ocultas no se agrupan entre si`() {
        val hidden = listOf(
            network("", "aa:bb:cc:dd:ee:10", 2437),
            network("", "aa:bb:cc:dd:ee:11", 5180)
        )
        val hiddenBands = bandsBySsid(hidden)

        assertTrue(hiddenBands.isEmpty())
        assertEquals(0, countDualBandNetworks(hiddenBands))
        assertTrue(filterNetworks(hidden, NetworkFilter.DUAL_BAND, "", hiddenBands).isEmpty())
        // La etiqueta localizada sigue funcionando para buscar y mostrar.
        assertEquals(2, filterNetworks(hidden, NetworkFilter.ALL, "red oculta", hiddenBands).size)
    }

    @Test
    fun `la busqueda por SSID ignora mayusculas`() {
        assertEquals(
            listOf("Casa", "Casa"),
            filterNetworks(networks, NetworkFilter.ALL, "  cAsA ", bands).map { it.ssid }
        )
    }

    @Test
    fun `la busqueda por BSSID acepta con y sin separadores`() {
        assertEquals(
            listOf("Oficina"),
            filterNetworks(networks, NetworkFilter.ALL, "ee:03", bands).map { it.ssid }
        )
        assertEquals(
            listOf("Oficina"),
            filterNetworks(networks, NetworkFilter.ALL, "ee03", bands).map { it.ssid }
        )
    }

    @Test
    fun `las consultas compactas de menos de tres caracteres no buscan en el BSSID`() {
        // "ab" solo aparece si se quitan los separadores; "aa" y "bb" ya
        // aparecen en el BSSID con separadores y cubren la comparación directa.
        assertTrue(filterNetworks(networks, NetworkFilter.ALL, "ab", bands).isEmpty())
        assertTrue(filterNetworks(networks, NetworkFilter.ALL, "aa", bands).isEmpty())
        assertTrue(filterNetworks(networks, NetworkFilter.ALL, "bb", bands).isEmpty())
        assertEquals(5, filterNetworks(networks, NetworkFilter.ALL, "abb", bands).size)
    }

    @Test
    fun `el filtro y la busqueda se combinan`() {
        assertEquals(
            listOf("aa:bb:cc:dd:ee:02"),
            filterNetworks(networks, NetworkFilter.BAND_5, "casa", bands).map { it.bssid }
        )
        assertTrue(filterNetworks(networks, NetworkFilter.BAND_24, "oficina", bands).isEmpty())
    }
}
