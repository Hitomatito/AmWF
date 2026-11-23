/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatResultStoreTest {

    private fun sampleResult(
        isCompatible: Boolean = true,
        message: String = "Falta con_mode en este kernel",
        suggestion: String = "Prueba con un kernel que exponga el parámetro"
    ): CompatibilityResult = CompatibilityResult(
        isCompatible = isCompatible,
        issues = listOf(
            CompatibilityIssue(
                type = IssueType.SYSFS,
                severity = Severity.CRITICAL,
                message = message,
                suggestion = suggestion
            )
        ),
        deviceInfo = DeviceInfo(
            manufacturer = "Xiaomi",
            model = "2107113SG",
            chipset = "Snapdragon 732G",
            kernelArch = "aarch64",
            cpuAbi = "arm64-v8a",
            wlanDriver = "qcacld-3.0",
            conModePath = "/sys/module/wlan/parameters/con_mode",
            rootInfo = RootInfo(
                isRooted = true,
                rootType = RootType.MAGISK,
                rootName = "Magisk",
                version = "28.1"
            ),
            capabilities = CapabilitiesInfo(
                canInject = null,
                canCapture = true,
                isPassiveOnly = false,
                tested = true,
                conModeWriteOk = true
            ),
            pixieDustBinaries = PixieDustBinariesInfo(missing = listOf("wpa_cli"))
        )
    )

    private fun snapshotOf(result: CompatibilityResult): CompatResultStore.Snapshot =
        CompatResultStore.Snapshot(
            result = result,
            checkedAtMs = 1_764_000_000_000L,
            appUpdateTimeMs = 1_763_000_000_000L
        )

    @Test
    fun `el round trip conserva el resultado completo`() {
        val snapshot = snapshotOf(sampleResult())

        val restored = decodeSnapshot(encodeSnapshot(snapshot))

        assertEquals(snapshot, restored)
    }

    @Test
    fun `los valores hostiles sobreviven el round trip`() {
        // Saltos de línea, tuberías, barras, '=' (estructural de las líneas) y
        // unicode: nada de eso puede romper el formato ni corromper el vecino.
        val hostile = "linea1\nlinea2 | con | tuberias \\ y = igual \\n literal"
        val snapshot = snapshotOf(
            sampleResult(
                message = hostile,
                suggestion = "ñandú raro ✓ \\p \\n"
            )
        )

        val restored = decodeSnapshot(encodeSnapshot(snapshot))!!

        assertEquals(hostile, restored.result.issues[0].message)
        assertEquals("ñandú raro ✓ \\p \\n", restored.result.issues[0].suggestion)

        // El peligro clásico de los escapes: un valor que CONTIENE el carácter
        // de escape (barra invertida + 'p' aquí). El unescape de un solo pase
        // debe devolverlo byte a byte, no comerse la barra.
        val tricky = "a\\p"
        val restoredTricky = decodeSnapshot(
            encodeSnapshot(snapshotOf(sampleResult(message = tricky)))
        )!!
        assertEquals(tricky, restoredTricky.result.issues[0].message)
    }

    @Test
    fun `un resultado sin issues y con nulos round trip limpio`() {
        val result = CompatibilityResult(
            isCompatible = false,
            issues = emptyList(),
            deviceInfo = DeviceInfo(
                manufacturer = "m",
                model = "m",
                chipset = "c",
                kernelArch = "k",
                cpuAbi = "abi",
                wlanDriver = "d",
                conModePath = null,
                rootInfo = RootInfo(false, RootType.NONE, "No Root", ""),
                capabilities = CapabilitiesInfo(
                    canInject = null,
                    canCapture = null,
                    isPassiveOnly = true,
                    tested = false,
                    conModeWriteOk = null
                ),
                pixieDustBinaries = PixieDustBinariesInfo(missing = emptyList())
            )
        )

        val restored = decodeSnapshot(encodeSnapshot(snapshotOf(result)))!!

        assertEquals(result, restored.result)
        assertNull(restored.result.deviceInfo.conModePath)
        assertTrue(restored.result.issues.isEmpty())
        assertTrue(restored.result.deviceInfo.pixieDustBinaries.missing.isEmpty())
    }

    @Test
    fun `el orden de los issues se conserva`() {
        val result = CompatibilityResult(
            isCompatible = true,
            issues = listOf(
                CompatibilityIssue(IssueType.ROOT, Severity.CRITICAL, "uno", "s1"),
                CompatibilityIssue(IssueType.PIXIE_DUST, Severity.INFO, "dos", "s2"),
                CompatibilityIssue(IssueType.CHIPSET, Severity.WARNING, "tres", "s3")
            ),
            deviceInfo = sampleResult().deviceInfo
        )

        val restored = decodeSnapshot(encodeSnapshot(snapshotOf(result)))!!

        assertEquals(listOf("uno", "dos", "tres"), restored.result.issues.map { it.message })
        assertEquals(
            listOf(Severity.CRITICAL, Severity.INFO, Severity.WARNING),
            restored.result.issues.map { it.severity }
        )
    }

    @Test
    fun `todo lo malformado se trata como ausente`() {
        val good = encodeSnapshot(snapshotOf(sampleResult()))

        assertNull(decodeSnapshot(""))
        assertNull(decodeSnapshot("otro-formato\ncompatible=1"))
        // Cabecera imprevista.
        assertNull(decodeSnapshot(good.replaceFirst("compat-v1", "compat-v0")))
        // Clave desconocida: estricta, el snapshot entero se anula.
        assertNull(decodeSnapshot("$good\nsurprise=1"))
        // Enum inválido.
        assertNull(decodeSnapshot(good.replace("rootType=MAGISK", "rootType=NOPE")))
        // Flag inválido.
        assertNull(decodeSnapshot(good.replace("compatible=1", "compatible=x")))
        // Falta una clave requerida.
        assertNull(decodeSnapshot(good.replaceFirst(Regex("manufacturer=.*\n"), "")))
    }
}
