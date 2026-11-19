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

class PixieDiagnosisParserTest {

    @Test
    fun `pin encontrado incluye el modo detectado`() {
        val diagnosis = PixieDiagnosisParser.parse(
            """
            | Pixiewps 1.4.2
            |
            | [?] Mode:     1 (RT/MT/CL)
            | [+] WPS pin:  12345670
            |
            | [*] Time taken: 0 s 12 ms
            """.trimMargin()
        )

        assertEquals(PixieDiagnosisKind.PIN_FOUND, diagnosis.kind)
        assertEquals(PixieNextAction.NONE, diagnosis.action)
        assertEquals(1, diagnosis.mode)
    }

    @Test
    fun `aviso de posible vulnerabilidad pide datos nuevos`() {
        val diagnosis = PixieDiagnosisParser.parse(
            """
            | [-] WPS pin not found!
            |
            | [!] The AP /might be/ vulnerable. Try again with --force or with another (newer) set of data.
            """.trimMargin()
        )

        assertEquals(PixieDiagnosisKind.MIGHT_BE_VULNERABLE, diagnosis.kind)
        assertEquals(PixieNextAction.RETRY_WITH_NEW_DATA, diagnosis.action)
    }

    @Test
    fun `small DH con modo 3 detiene la auditoria`() {
        val diagnosis = PixieDiagnosisParser.parse(
            """
            | [?] Mode:     3 (RTL819x)
            | [!] Small DH keys is not supported for mode 3!
            """.trimMargin()
        )

        assertEquals(PixieDiagnosisKind.UNSUPPORTED_SETUP, diagnosis.kind)
        assertEquals(PixieNextAction.STOP, diagnosis.action)
        assertEquals(3, diagnosis.mode)
    }

    @Test
    fun `fallo explicito termina la auditoria`() {
        val diagnosis = PixieDiagnosisParser.parse(
            """
            | [x] Unexpected error (--m5-enc)!
            """.trimMargin()
        )

        assertEquals(PixieDiagnosisKind.PROCESS_ERROR, diagnosis.kind)
        assertEquals(PixieNextAction.STOP, diagnosis.action)
    }

    @Test
    fun `sin pin termina la auditoria`() {
        val diagnosis = PixieDiagnosisParser.parse(
            """
            | [-] WPS pin not found!
            """.trimMargin()
        )

        assertEquals(PixieDiagnosisKind.NOT_FOUND, diagnosis.kind)
        assertEquals(PixieNextAction.STOP, diagnosis.action)
    }

    @Test
    fun `salida vacia es desconocida`() {
        val diagnosis = PixieDiagnosisParser.parse("")

        assertEquals(PixieDiagnosisKind.UNKNOWN, diagnosis.kind)
        assertEquals(PixieNextAction.STOP, diagnosis.action)
        assertNull(diagnosis.mode)
    }

    @Test
    fun `ruido del supplicant no cambia el diagnostico`() {
        val diagnosis = PixieDiagnosisParser.parse(
            """
            | wlan0: CTRL-EVENT-CONNECTED
            | WPS-M3: ENONCE=00112233445566778899aabbccddeeff
            | [+] pixiewps started
            | [?] Mode:     3 (RTL819x)
            | [+] WPS pin:  87654321
            """.trimMargin()
        )

        assertEquals(PixieDiagnosisKind.PIN_FOUND, diagnosis.kind)
        assertEquals(3, diagnosis.mode)
    }
}
