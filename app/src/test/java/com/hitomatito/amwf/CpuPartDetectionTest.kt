package com.hitomatito.amwf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the Snapdragon "CPU part" lookup.
 *
 * Bug: el valor de /proc/cpuinfo se pasaba con .uppercase() sobre toda la
 * cadena, convirtiendo el prefijo "0x" en "0X"; las claves del mapa usan
 * "0x", por lo que el lookup nunca coincidía.
 */
class CpuPartDetectionTest {

    @Test
    fun `matches lowercase cpu part as found in cpuinfo`() {
        assertEquals("Snapdragon 870", DeviceCompatibilityChecker.resolveSnapdragonByCpuPart("0x073"))
    }

    @Test
    fun `matches cpu part with uppercase 0X prefix`() {
        assertEquals("Snapdragon 865/865+", DeviceCompatibilityChecker.resolveSnapdragonByCpuPart("0X072"))
    }

    @Test
    fun `matches cpu part with leading and trailing whitespace`() {
        assertEquals("Snapdragon 8 Gen 3", DeviceCompatibilityChecker.resolveSnapdragonByCpuPart(" 0x086 "))
    }

    @Test
    fun `unknown cpu part returns null`() {
        assertNull(DeviceCompatibilityChecker.resolveSnapdragonByCpuPart("0x999"))
    }
}
