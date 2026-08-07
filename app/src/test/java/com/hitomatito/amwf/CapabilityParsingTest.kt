package com.hitomatito.amwf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the real capability capture parser.
 *
 * Bug: el test de captura trataba la salida de una orden (p. ej. `iw` no
 * instalado) como éxito cuando no contenía cadenas tipo "command failed",
 * produciendo falsos "Monitor completo" en dispositivos sin `iw`. El parsing del
 * resumen de tcpdump debe contar frames reales capturados.
 */
class CapabilityParsingTest {

    @Test
    fun `parses packets captured summary`() {
        assertEquals(3, DeviceCompatibilityChecker.countCapturedPackets("3 packets captured\n"))
    }

    @Test
    fun `parses single packet captured`() {
        assertEquals(1, DeviceCompatibilityChecker.countCapturedPackets("1 packet captured\n"))
    }

    @Test
    fun `returns zero when no capture summary`() {
        assertEquals(0, DeviceCompatibilityChecker.countCapturedPackets(
            "tcpdump: unknown interface\n"))
    }

    @Test
    fun `returns zero on empty output`() {
        assertEquals(0, DeviceCompatibilityChecker.countCapturedPackets(""))
    }

    @Test
    fun `returns zero when tool not found`() {
        assertEquals(0, DeviceCompatibilityChecker.countCapturedPackets(
            "timeout: exec iw: No such file or directory"))
    }
}