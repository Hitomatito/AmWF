/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import com.hitomatito.amwf.R
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del parser de M3.
 *
 * El wpa_supplicant de Android trunca los hexdump a 32 bytes y los manda a
 * logcat. El binario propio de AmWF (sin CONFIG_ANDROID_LOG) imprime el
 * volcado completo por stdout con el formato:
 *
 *   WPS: Enrollee Nonce - hexdump(len=16): 00 01 ...
 *
 * Estos tests fijan ese formato para que la auditoría no vuelva a romperse.
 */
class M3ParserTest {

    @Test
    fun `parses full hexdump format from bundled supplicant`() {
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Complete)
        val data = (capture as M3Parser.M3Capture.Complete).data
        assertEquals(hex(16, 1).replace(" ", ""), data.enrolleeNonce)
        assertEquals(hex(192, 2).replace(" ", ""), data.ownPublicKey)
        assertEquals(hex(192, 3).replace(" ", ""), data.peerPublicKey)
        assertEquals(hex(32, 4).replace(" ", ""), data.authKey)
        assertEquals(hex(32, 5).replace(" ", ""), data.eHash1)
        assertEquals(hex(32, 6).replace(" ", ""), data.eHash2)
    }

    @Test
    fun `accepts timestamp prefix before hexdump line`() {
        val text = buildString {
            appendLine("1699999999.123456: WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("1699999999.123456: WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("1699999999.123456: WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("1699999999.123456: WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("1699999999.123456: WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("1699999999.123456: WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
        }

        assertTrue(parse(text) is M3Parser.M3Capture.Complete)
    }

    @Test
    fun `keeps supporting legacy label colon format`() {
        val text = buildString {
            appendLine("WPS: Enrollee Nonce: ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key: ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key: ${hex(192, 3)}")
            appendLine("WPS: AuthKey: ${hex(32, 4)}")
            appendLine("WPS: E-Hash1: ${hex(32, 5)}")
            appendLine("WPS: E-Hash2: ${hex(32, 6)}")
        }

        assertTrue(parse(text) is M3Parser.M3Capture.Complete)
    }

    @Test
    fun `rejects truncated android log hexdump`() {
        // El supplicant de Android imprime solo 32 bytes y añade "..." al final.
        val truncated = hex(32, 1)
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): $truncated ...")
            appendLine("WPS: DH own Public Key - hexdump(len=192): $truncated ...")
        }

        val capture = parse(text)
        assertTrue(capture is M3Parser.M3Capture.Incomplete)
        assertEquals(0, (capture as M3Parser.M3Capture.Incomplete).capturedCount)
    }

    @Test
    fun `accepts timestamp prefix before wps-m3 key-value line`() {
        val line = "1699999999.123456: WPS-M3: " +
            "ENONCE=${hex(16, 1).replace(" ", "")} " +
            "PKE=${hex(192, 2).replace(" ", "")} " +
            "PKR=${hex(192, 3).replace(" ", "")} " +
            "AUTHKEY=${hex(32, 4).replace(" ", "")} " +
            "EHASH1=${hex(32, 5).replace(" ", "")} " +
            "EHASH2=${hex(32, 6).replace(" ", "")}"

        assertTrue(parse("$line\n") is M3Parser.M3Capture.Complete)
    }

    @Test
    fun `keeps complete m3 when ap nacks the probe pin`() {
        // Flujo normal de Pixie Dust: el AP entrega el M3 completo y después
        // rechaza el PIN de sonda con config_error=18. Ese NACK no debe
        // descartar las claves ya capturadas.
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
            appendLine("wlan0: WPS-FAIL msg=5 config_error=18")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Complete)
        assertEquals(
            hex(16, 1).replace(" ", ""),
            (capture as M3Parser.M3Capture.Complete).data.enrolleeNonce
        )
    }

    @Test
    fun `treats probe nack without m3 as retryable incomplete`() {
        // Si el PIN de sonda se rechaza antes de capturar los seis campos, no
        // hay que declarar el AP como no vulnerable: el runner debe reintentar.
        val text = buildString {
            appendLine("WPS: Received M1")
            appendLine("wlan0: WPS-FAIL msg=5 config_error=18")
        }

        assertTrue(parse(text) is M3Parser.M3Capture.Incomplete)
    }

    @Test
    fun `detects setup locked nack before m3`() {
        val text = buildString {
            appendLine("WPS: Received M1")
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("wlan0: WPS-FAIL msg=5 config_error=15")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Failed)
        capture as M3Parser.M3Capture.Failed
        assertEquals(15, capture.configError)
        assertEquals(
            R.string.pixie_wps_error_setup_locked,
            M3Parser.configErrorReasonRes(capture.configError)
        )
    }

    @Test
    fun `maps unknown and null config errors to their own resources`() {
        assertEquals(
            R.string.pixie_wps_error_unknown,
            M3Parser.configErrorReasonRes(null)
        )
        assertEquals(
            R.string.pixie_wps_error_config_code,
            M3Parser.configErrorReasonRes(99)
        )
        assertEquals(
            R.string.pixie_wps_error_weak_signal,
            M3Parser.configErrorReasonRes(5)
        )
    }

    @Test
    fun `detects negotiation error line from ap`() {
        val text = "WPS: Enrollee terminated negotiation with Configuration Error 15\n"

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Failed)
        assertEquals(15, (capture as M3Parser.M3Capture.Failed).configError)
    }

    @Test
    fun `parses lines from blocking queue used by runner`() {
        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
        }
        text.lineSequence().forEach { queue.offer(it) }
        queue.offer(M3Parser.END_OF_STREAM)

        val capture = M3Parser.parse(lines = queue, timeoutMs = 2_000L)

        assertTrue(capture is M3Parser.M3Capture.Complete)
    }

    private fun parse(text: String): M3Parser.M3Capture {
        val queue = LinkedBlockingQueue<String>()
        text.lineSequence().forEach { queue.offer(it) }
        queue.offer(M3Parser.END_OF_STREAM)
        return M3Parser.parse(lines = queue, timeoutMs = 2_000L)
    }

    private fun hex(bytes: Int, seed: Int): String =
        (0 until bytes).joinToString(" ") { index ->
            "%02x".format((index + seed) and 0xff)
        }
}
