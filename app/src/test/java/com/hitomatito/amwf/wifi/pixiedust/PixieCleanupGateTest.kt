/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixieCleanupGateTest {

    @Test
    fun `sin limpiezas activas awaitFree pasa enseguida`() = runBlocking {
        assertTrue(PixieCleanupGate.awaitFree(timeoutMs = 1_000L))
    }

    @Test
    fun `awaitFree espera a que la limpieza huerfana termine`() = runBlocking {
        PixieCleanupGate.enter()
        assertTrue(PixieCleanupGate.isOccupied())
        val finished = CompletableDeferred<Unit>()
        launch {
            PixieCleanupGate.awaitFree(timeoutMs = 5_000L, pollMs = 10L)
            finished.complete(Unit)
        }
        try {
            // Deja correr la espera: sigue bloqueada por el gate.
            yield()
            assertFalse(finished.isCompleted)
        } finally {
            PixieCleanupGate.exit()
        }
        // El gate queda libre y la espera completa.
        finished.await()
        assertFalse(PixieCleanupGate.isOccupied())
    }

    @Test
    fun `awaitFree vence el timeout y lo informa para no bloquear la auditoria`() = runBlocking {
        PixieCleanupGate.enter()
        try {
            assertFalse(PixieCleanupGate.awaitFree(timeoutMs = 60L, pollMs = 20L))
        } finally {
            PixieCleanupGate.exit()
        }
    }
}
