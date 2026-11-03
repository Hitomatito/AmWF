/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Test

class PixieProcessSupportTest {

    @Test
    fun `escapa metacaracteres de ERE`() {
        val input = "a.b^c\$d|e(f)g{h}i*j+k?l[m]n"
        val expected = "a\\.b\\^c\\\$d\\|e\\(f\\)g\\{h\\}i\\*j\\+k\\?l\\[m\\]n"
        assertEquals(expected, PixieProcessSupport.posixRegexEscape(input))
    }

    @Test
    fun `escapa la barra invertida`() {
        assertEquals("a\\\\b", PixieProcessSupport.posixRegexEscape("a\\b"))
    }

    @Test
    fun `no modifica caracteres normales`() {
        assertEquals("ruta/segura_123", PixieProcessSupport.posixRegexEscape("ruta/segura_123"))
    }
}
