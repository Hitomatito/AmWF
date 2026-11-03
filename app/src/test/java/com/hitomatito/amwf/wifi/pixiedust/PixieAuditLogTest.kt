/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Test

class PixieAuditLogTest {

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
}
