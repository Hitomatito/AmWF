package com.hitomatito.amwf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for con_mode path resolution (issue #2).
 *
 * Bug: en dispositivos como el Galaxy Z Fold7 (Snapdragon 8 Elite) el modulo
 * del driver es "qca_cld3_kiwi_v2", que no estaba en la lista de nombres
 * exactos y no se encontraba por el escaneo directo (SELinux bloquea sysfs
 * para el UID de la app). El resultado era "con_mode no encontrado".
 */
class ConModePathSelectionTest {

    private val fold7 = "/sys/module/qca_cld3_kiwi_v2/parameters/con_mode"

    @Test
    fun `selects qca_cld3 unknown-out-of-tree module by hint`() {
        assertEquals(fold7, selectConModePath(listOf(fold7)))
    }

    @Test
    fun `selects known exact module`() {
        assertEquals(
            "/sys/module/wlan/parameters/con_mode",
            selectConModePath(listOf("/sys/module/wlan/parameters/con_mode"))
        )
    }

    @Test
    fun `prefers known module over hint-only module`() {
        assertEquals(
            "/sys/module/hdd/parameters/con_mode",
            selectConModePath(listOf(fold7, "/sys/module/hdd/parameters/con_mode"))
        )
    }

    @Test
    fun `matches out-of-tree variant that is not in the exact list`() {
        val variant = "/sys/module/qca_cld3_wlan/parameters/con_mode"
        assertEquals(variant, selectConModePath(listOf(variant)))
    }

    @Test
    fun `ignores non-wifi modules exposing con_mode`() {
        assertNull(selectConModePath(listOf("/sys/module/foo/parameters/con_mode")))
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(selectConModePath(emptyList()))
    }
}
