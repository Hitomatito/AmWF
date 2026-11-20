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

class PixieModeSelectorTest {

    private fun m3(enonce: String = COMPATIBLE_NONCE) = M3Parser.M3Data(
        enrolleeNonce = enonce,
        ownPublicKey = "a".repeat(384),
        peerPublicKey = "b".repeat(384),
        authKey = "c".repeat(64),
        eHash1 = "d".repeat(64),
        eHash2 = "e".repeat(64)
    )

    @Test
    fun `normaliza el BSSID con dos puntos a doce hexadecimales`() {
        assertEquals("00E04C123456", M3Parser.normalizeBssid("00:e0:4c:12:34:56"))
        assertEquals("00E04C123456", M3Parser.normalizeBssid("00E04C123456"))
    }

    @Test
    fun `rechaza BSSID incompleto o no hexadecimal`() {
        assertNull(M3Parser.normalizeBssid("00:e0:4c"))
        assertNull(M3Parser.normalizeBssid("00:e0:4c:12:34:zz"))
    }

    @Test
    fun `M5 y M7 capturados fuerzan modo 3 con BSSID normalizado`() {
        val m5m7 = M3Parser.M5M7Capture(
            bssid = "00:e0:4c:12:34:56",
            m5Enc = "aa",
            m7Enc = "bb"
        )
        val plan = PixieModeSelector.select(m3(), m5m7, "00:e0:4c:12:34:56")

        assertTrue(plan.isExplicit)
        assertTrue(plan.args.startsWith("--mode 3 "))
        assertTrue(plan.args.contains("--e-bssid 00E04C123456"))
        assertFalse(plan.args.contains("--e-bssid 00:e0:4c"))
        assertTrue(plan.args.contains("-5 aa"))
        assertTrue(plan.args.contains("-7 bb"))
    }

    @Test
    fun `OUI Realtek es solo una pista y no fuerza el modo`() {
        val plan = PixieModeSelector.select(m3(), null, "00:e0:4c:12:34:56")

        assertFalse(plan.isExplicit)
        assertEquals("", plan.args)
        assertTrue(plan.reason.contains("Realtek"))
    }

    @Test
    fun `OUI de TP LINK no fuerza modo aunque el nonce sea compatible`() {
        // D8150D pertenece a TP-LINK, no a Realtek: muchos routers con ese OUI
        // usan chipsets MediaTek/Atheros. Forzar RTL819x daría un falso negativo.
        val plan = PixieModeSelector.select(m3(), null, "d8:15:0d:12:34:56")

        assertFalse(plan.isExplicit)
        assertEquals("", plan.args)
    }

    @Test
    fun `OUI Realtek con nonce incompatible deja el modo automatico`() {
        val incompatible = "8" + COMPATIBLE_NONCE.substring(1)
        val plan = PixieModeSelector.select(m3(incompatible), null, "00:e0:4c:12:34:56")

        assertFalse(plan.isExplicit)
        assertEquals("", plan.args)
    }

    @Test
    fun `OUI Ralink no forcing porque el auto ya prueba el modo 1`() {
        val plan = PixieModeSelector.select(m3(), null, "00:0c:43:12:34:56")

        assertFalse(plan.isExplicit)
        assertEquals("", plan.args)
        assertTrue(plan.reason.contains("modo 1"))
    }

    @Test
    fun `OUI desconocido deja el modo automatico`() {
        val plan = PixieModeSelector.select(m3(), null, "aa:bb:cc:12:34:56")

        assertFalse(plan.isExplicit)
        assertEquals("", plan.args)
    }

    @Test
    fun `nonce malformado no fuerza ningun modo`() {
        val plan = PixieModeSelector.select(m3("zz"), null, "00:e0:4c:12:34:56")

        assertFalse(plan.isExplicit)
        assertEquals("", plan.args)
    }

    @Test
    fun `M5 y M7 usan timeout corto y auto el por defecto`() {
        val m5m7 = M3Parser.M5M7Capture(
            bssid = "aa:bb:cc:dd:ee:ff", m5Enc = "aa", m7Enc = "bb"
        )
        val mode3 = PixieModeSelector.select(m3(), m5m7, "aa:bb:cc:dd:ee:ff")
        val auto = PixieModeSelector.select(m3(), null, "aa:bb:cc:dd:ee:ff")

        assertEquals(PixiewpsPlan.MODE3_TIMEOUT_SECONDS, mode3.timeoutSeconds)
        assertEquals(PixiewpsPlan.DEFAULT_TIMEOUT_SECONDS, auto.timeoutSeconds)
        assertFalse(auto.args.contains("--force"))
    }

    @Test
    fun `el aviso real sin linea de modo pide reintentar con datos frescos`() {
        // pixiewps solo imprime `[?] Mode:` cuando encontró el PIN, así que en
        // el aviso "might be vulnerable" el modo es siempre null.
        val output = listOf(
            "[-] WPS pin not found!",
            "[!] The AP /might be/ vulnerable. Try again with --force or with another (newer) set of data."
        ).joinToString("\n")
        val diagnosis = PixieDiagnosisParser.parse(output)

        assertNull(diagnosis.mode)
        assertEquals(PixieDiagnosisKind.MIGHT_BE_VULNERABLE, diagnosis.kind)
        assertEquals(PixieNextAction.RETRY_WITH_NEW_DATA, diagnosis.action)
    }

    private companion object {
        const val COMPATIBLE_NONCE = "000102030405060708090a0b0c0d0e0f"
    }
}
