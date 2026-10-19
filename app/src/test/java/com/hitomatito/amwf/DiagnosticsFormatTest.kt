package com.hitomatito.amwf

import com.hitomatito.amwf.diagnostics.DiagnosticsFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests del redactado de direcciones MAC en el informe de diagnostico.
 * Se conserva el prefijo OUI (3 primeros octetos) y se oculta el resto.
 */
class DiagnosticsFormatTest {

    @Test
    fun `redacts a full mac keeping the OUI prefix`() {
        assertEquals(
            "00:11:22:xx:xx:xx",
            DiagnosticsFormat.redactMacAddresses("00:11:22:aa:bb:cc")
        )
    }

    @Test
    fun `redacts every mac in the text`() {
        val input = "addr 3c:5a:b4:11:22:33 peer 00:11:22:44:55:66"
        assertEquals(
            "addr 3c:5a:b4:xx:xx:xx peer 00:11:22:xx:xx:xx",
            DiagnosticsFormat.redactMacAddresses(input)
        )
    }

    @Test
    fun `keeps uppercase OUI and redacts the rest`() {
        assertEquals(
            "AA:BB:CC:xx:xx:xx",
            DiagnosticsFormat.redactMacAddresses("AA:BB:CC:DD:EE:FF")
        )
    }

    @Test
    fun `does not touch timestamps or colon-separated triples`() {
        val input = "12:34:56.789 D/AmWF: checking"
        assertEquals(input, DiagnosticsFormat.redactMacAddresses(input))
    }

    @Test
    fun `leaves text without macs unchanged`() {
        val input = "con_mode path: /sys/module/wlan/parameters/con_mode"
        assertEquals(input, DiagnosticsFormat.redactMacAddresses(input))
    }

    @Test
    fun `redacts ssid value keeping the key`() {
        assertEquals("ssid <redacted>", DiagnosticsFormat.redactSsid("ssid ZERO TWO"))
    }

    @Test
    fun `redact applies both mac and ssid redaction`() {
        val input = "addr 06:af:bd:11:22:33\n\tssid ZERO TWO"
        assertEquals(
            "addr 06:af:bd:xx:xx:xx\n\tssid <redacted>",
            DiagnosticsFormat.redact(input)
        )
    }

    @Test
    fun `redact hides literal secrets anywhere in the text`() {
        val input = "Connecting to ZERO TWO 60:6c:63:11:22:33"
        assertEquals(
            "Connecting to <redacted> 60:6c:63:xx:xx:xx",
            DiagnosticsFormat.redact(input, listOf("ZERO TWO"))
        )
    }

    @Test
    fun `redact ignores short or blank secrets`() {
        val input = "value a and bb"
        assertEquals("value a and bb", DiagnosticsFormat.redact(input, listOf("", "a", "bb")))
    }

    @Test
    fun `extracts ssids from iw dev output`() {
        val iw = """
            phy#0
            	Interface wlan0
            		ifindex 90
            		ssid ZERO TWO
            		type managed
            	Interface wlan1
            		ssid OtherNet
            		type managed
        """.trimIndent()
        assertEquals(listOf("ZERO TWO", "OtherNet"), DiagnosticsFormat.ssidsFromIwDev(iw))
    }
}
