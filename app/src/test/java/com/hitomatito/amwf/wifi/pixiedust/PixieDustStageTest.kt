/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixieDustStageTest {

    @Test
    fun `las etapas normales se aplican mientras el ataque esta activo`() {
        assertTrue(
            shouldApplyStageUpdate(
                isAttacking = true,
                cleanupInProgress = false,
                newStage = PixieDustStage.CAPTURING_M3
            )
        )
        assertTrue(
            shouldApplyStageUpdate(
                isAttacking = true,
                cleanupInProgress = true,
                newStage = PixieDustStage.ANALYZING
            )
        )
    }

    @Test
    fun `no se aplican etapas cuando el ataque ya no esta activo`() {
        assertFalse(
            shouldApplyStageUpdate(
                isAttacking = false,
                cleanupInProgress = false,
                newStage = PixieDustStage.ANALYZING
            )
        )
        assertFalse(
            shouldApplyStageUpdate(
                isAttacking = false,
                cleanupInProgress = true,
                newStage = PixieDustStage.RESTORING
            )
        )
    }

    @Test
    fun `RESTORING se ignora durante la cancelacion`() {
        assertFalse(
            shouldApplyStageUpdate(
                isAttacking = true,
                cleanupInProgress = true,
                newStage = PixieDustStage.RESTORING
            )
        )
    }

    @Test
    fun `RESTORING se aplica cuando no hay cancelacion en curso`() {
        assertTrue(
            shouldApplyStageUpdate(
                isAttacking = true,
                cleanupInProgress = false,
                newStage = PixieDustStage.RESTORING
            )
        )
    }

    @Test
    fun `la fase capturada antes de detener manda sobre RESTORING`() {
        assertEquals(
            PixieDustStage.CAPTURING_M3,
            stageForCancelledSession(
                stageBeforeStop = PixieDustStage.CAPTURING_M3,
                currentStage = PixieDustStage.RESTORING
            )
        )
    }

    @Test
    fun `sin fase previa se conserva la etapa actual`() {
        assertEquals(
            PixieDustStage.RESTORING,
            stageForCancelledSession(
                stageBeforeStop = null,
                currentStage = PixieDustStage.RESTORING
            )
        )
        assertEquals(
            PixieDustStage.ANALYZING,
            stageForCancelledSession(
                stageBeforeStop = null,
                currentStage = PixieDustStage.ANALYZING
            )
        )
    }

    @Test
    fun `sin ninguna referencia se cae a RESTORING`() {
        assertEquals(
            PixieDustStage.RESTORING,
            stageForCancelledSession(stageBeforeStop = null, currentStage = null)
        )
    }
}
