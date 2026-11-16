package com.hitomatito.amwf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests para la validación de la restauración pendiente de con_mode (issue #5).
 *
 * Bug: en algunos dispositivos la escritura de con_mode (parte de la
 * verificación de compatibilidad) conmuta el driver WiFi y la app muere antes
 * de poder restaurar el valor original. El marcador de restauración se aplica
 * en el siguiente arranque y su pareja (ruta, valor) se vuelve a validar antes
 * de interpolarla en un comando root.
 */
class ConModeRestoreValidationTest {

    private val path = "/sys/module/qca_cld3_kiwi_v2/parameters/con_mode"

    @Test
    fun `accepts sysfs con_mode path with numeric value`() {
        assertTrue(isValidConModeRestoreTarget(path, "0"))
        assertTrue(isValidConModeRestoreTarget("/sys/module/wlan/parameters/con_mode", "4"))
        assertTrue(isValidConModeRestoreTarget(path, "00"))
    }

    @Test
    fun `rejects path outside sysfs con_mode`() {
        assertFalse(isValidConModeRestoreTarget("/sys/module/wlan/parameters/other", "0"))
        assertFalse(isValidConModeRestoreTarget("/data/system/con_mode", "0"))
        assertFalse(isValidConModeRestoreTarget("/sys/module/wlan/parameters/con_mode/x", "0"))
        assertFalse(isValidConModeRestoreTarget("../module/wlan/parameters/con_mode", "0"))
        assertFalse(isValidConModeRestoreTarget("/sys/module/wlan/parameters/con_mode;id", "0"))
        assertFalse(isValidConModeRestoreTarget("/sys/module/../parameters/con_mode", "0"))
        assertFalse(isValidConModeRestoreTarget("/sys/module/.hidden/parameters/con_mode", "0"))
        assertFalse(isValidConModeRestoreTarget("/sys/module//parameters/con_mode", "0"))
    }

    @Test
    fun `accepts module names with inner dots or dashes`() {
        assertTrue(isValidConModeRestoreTarget("/sys/module/wlan.ko/parameters/con_mode", "0"))
        assertTrue(isValidConModeRestoreTarget("/sys/module/qca-cld3/parameters/con_mode", "0"))
    }

    @Test
    fun `rejects non numeric or oversized value`() {
        assertFalse(isValidConModeRestoreTarget(path, ""))
        assertFalse(isValidConModeRestoreTarget(path, "x"))
        assertFalse(isValidConModeRestoreTarget(path, "0 4"))
        assertFalse(isValidConModeRestoreTarget(path, "0\n4"))
        assertFalse(isValidConModeRestoreTarget(path, "123"))
        assertFalse(isValidConModeRestoreTarget(path, "-1"))
    }

    @Test
    fun `rejects null path or value`() {
        assertFalse(isValidConModeRestoreTarget(null, "0"))
        assertFalse(isValidConModeRestoreTarget(path, null))
        assertFalse(isValidConModeRestoreTarget(null, null))
    }

    @Test
    fun `con_mode write command prints the real redirection exit code`() {
        assertEquals(
            "echo 4 > $path 2>/dev/null; echo \$?",
            conModeWriteCommand(path, "4")
        )
    }

    @Test
    fun `parses the echoed exit code from the write test output`() {
        assertEquals(0, parseEchoedExitCode("0"))
        assertEquals(0, parseEchoedExitCode("some noise\n0\n"))
        assertEquals(1, parseEchoedExitCode("0\n1"))
        assertEquals(2, parseEchoedExitCode(" 2 "))
    }

    @Test
    fun `returns null when the write test printed no exit code`() {
        assertNull(parseEchoedExitCode(""))
        assertNull(parseEchoedExitCode("Operation not permitted"))
        assertNull(parseEchoedExitCode("line\n\n"))
    }

    @Test
    fun `plans nothing when there is no pending marker`() {
        val plan = planPendingRestore(
            PendingRestoreState(
                pending = false,
                radioTouched = true,
                path = path,
                value = "4"
            )
        )
        assertFalse(plan.hasWork)
        assertFalse(plan.restoreConMode)
        assertFalse(plan.restartRadio)
    }

    @Test
    fun `plans a full restore when con_mode and radio were touched`() {
        val plan = planPendingRestore(
            PendingRestoreState(
                pending = true,
                radioTouched = true,
                path = path,
                value = " 4 "
            )
        )
        assertTrue(plan.hasWork)
        assertTrue(plan.restoreConMode)
        assertTrue(plan.restartRadio)
        assertEquals(path, plan.path)
        assertEquals("4", plan.value)
    }

    @Test
    fun `restarts the radio without writing con_mode when the target is invalid`() {
        val plan = planPendingRestore(
            PendingRestoreState(
                pending = true,
                radioTouched = true,
                path = "/sys/module/../parameters/con_mode",
                value = "4"
            )
        )
        assertTrue(plan.hasWork)
        assertFalse(plan.restoreConMode)
        assertTrue(plan.restartRadio)
        assertNull(plan.path)
        assertNull(plan.value)
    }

    @Test
    fun `does not restart the radio when the crash happened before disabling it`() {
        val plan = planPendingRestore(
            PendingRestoreState(
                pending = true,
                radioTouched = false,
                path = null,
                value = null
            )
        )
        assertFalse(plan.hasWork)
        assertFalse(plan.restartRadio)
    }

    @Test
    fun `still restores con_mode if a marker was recorded without radio flag`() {
        val plan = planPendingRestore(
            PendingRestoreState(
                pending = true,
                radioTouched = false,
                path = path,
                value = "0"
            )
        )
        assertTrue(plan.hasWork)
        assertTrue(plan.restoreConMode)
        assertFalse(plan.restartRadio)
    }

    @Test
    fun `wifi restore is confirmed only when framework explicitly reports enabled`() {
        assertTrue(isWifiEnabledAfterRestore(true))
        assertFalse(isWifiEnabledAfterRestore(false))
        assertFalse(isWifiEnabledAfterRestore(null))
    }
}
