/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixiePskExtractorTest {

    @Test
    fun `descarta el PSK enmascarado por wpa_supplicant`() {
        assertNull(parsePskFromControlOutput("*"))
        assertNull(parsePskFromControlOutput("psk=*"))
        assertNull(parsePskFromControlOutput("\"*\""))
    }

    @Test
    fun `extrae el PSK real del control interface`() {
        assertEquals("12345678", parsePskFromControlOutput("psk=\"12345678\""))
        assertEquals("12345678", parsePskFromControlOutput("12345678"))
        assertEquals(
            "12345678",
            parsePskFromControlOutput("Selected interface 'wlan0'\nOK\npsk=\"12345678\"")
        )
    }

    @Test
    fun `ignora lineas de estado y errores`() {
        assertNull(parsePskFromControlOutput("OK"))
        assertNull(parsePskFromControlOutput("Selected interface 'wlan0'"))
        assertNull(parsePskFromControlOutput("Using interface 'wlan0'"))
        assertNull(parsePskFromControlOutput("FAIL"))
        assertNull(parsePskFromControlOutput("UNKNOWN COMMAND"))
        assertNull(parsePskFromControlOutput(""))
    }

    @Test
    fun `detecta la salida enmascarada`() {
        assertTrue(isMaskedPskOutput("*"))
        assertTrue(isMaskedPskOutput("psk=*"))
        assertTrue(isMaskedPskOutput("OK\npsk=*"))
        assertFalse(isMaskedPskOutput("psk=\"12345678\""))
        assertFalse(isMaskedPskOutput("OK"))
    }

    @Test
    fun `elige el PSK de la red objetivo entre varias`() {
        val config = """
            network={
                ssid="Otra"
                psk="otraclave"
            }
            network={
                ssid="Casa"
                psk="12345678"
            }
        """.trimIndent()

        assertEquals("12345678", parsePskFromConfigText(config, "Casa"))
        assertEquals("otraclave", parsePskFromConfigText(config, "Otra"))
        // No debe devolver la clave de otra red cuando la objetivo no está.
        assertNull(parsePskFromConfigText(config, "NoExiste"))
    }

    @Test
    fun `sin SSID devuelve el primer PSK no enmascarado`() {
        val config = """
            network={
                ssid="A"
                psk="claveA"
            }
            network={
                ssid="B"
                psk="claveB"
            }
        """.trimIndent()

        assertEquals("claveA", parsePskFromConfigText(config, null))
        assertEquals("claveA", parsePskFromConfigText(config, ""))
    }

    @Test
    fun `decodifica SSID hexadecimal y comillas escapadas`() {
        val hexSsid = "Casa".toByteArray()
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val hexConfig = """
            network={
                ssid=$hexSsid
                psk="12345678"
            }
        """.trimIndent()
        assertEquals("12345678", parsePskFromConfigText(hexConfig, "Casa"))

        val escapedConfig = """
            network={
                ssid="Mi \"Red\""
                psk="abcdefgh"
            }
        """.trimIndent()
        assertEquals("abcdefgh", parsePskFromConfigText(escapedConfig, "Mi \"Red\""))
    }

    @Test
    fun `con una sola red usa su PSK aunque el SSID no case`() {
        val config = """
            network={
                ssid="RedRara"
                psk="12345678"
            }
        """.trimIndent()

        assertEquals("12345678", parsePskFromConfigText(config, "Otra"))
    }

    @Test
    fun `ignora el PSK enmascarado de la red objetivo`() {
        val config = """
            network={
                ssid="Casa"
                psk=*
            }
            network={
                ssid="Otra"
                psk="otraclave"
            }
        """.trimIndent()

        assertNull(parsePskFromConfigText(config, "Casa"))
    }
}
