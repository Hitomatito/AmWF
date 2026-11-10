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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de los reducers puros de estado de la pantalla Pixie Dust.
 */
class PixieDustReducersTest {

    private fun network(
        ssid: String,
        bssid: String = "00:11:22:33:44:55",
        frequency: Int = 2437,
        level: Int = -50,
        wpsCapable: Boolean = true,
        wpsLocked: Boolean = false
    ) = PixieDustNetwork(
        ssid = ssid,
        bssid = bssid,
        frequency = frequency,
        level = level,
        wpsCapable = wpsCapable,
        wpsLocked = wpsLocked,
        capabilities = "",
        hiddenSsidLabel = "Red oculta"
    )

    private fun attackingState() = PixieDustUiState(
        scan = ScanState(isScanning = false, networks = listOf(network("Casa"))),
        session = SessionState(
            isAttacking = true,
            stage = PixieDustStage.CAPTURING_M3,
            startedAt = 1_000L,
            finishedAt = null,
            cancelled = false,
            result = null
        ),
        consoleLines = listOf("10:00:00  línea previa")
    )

    // ---------- parseo de capabilities ----------

    @Test
    fun `isWpsCapable detecta WPS entre las capabilities`() {
        assertTrue(isWpsCapable("[WPS][ESS]"))
        assertTrue(isWpsCapable("[wpa2-psk-ccmp][wps]"))
        assertFalse(isWpsCapable("[ESS]"))
        assertFalse(isWpsCapable(""))
    }

    @Test
    fun `isWpsLocked detecta las variantes de bloqueo`() {
        assertTrue(isWpsLocked("[WPS_LOCKED][ESS]"))
        assertTrue(isWpsLocked("[WPS][LOCKED][ESS]"))
        assertTrue(isWpsLocked("[wps][locked][ess]"))
        assertFalse(isWpsLocked("[WPS][ESS]"))
        assertFalse(isWpsLocked(""))
    }

    // ---------- attackStartError ----------

    @Test
    fun `sin red seleccionada no se puede atacar`() {
        assertEquals(
            AttackStartError.NO_NETWORK,
            attackStartError(null, SessionState(), ScanState(), cleanupInProgress = false)
        )
    }

    @Test
    fun `una red no auditable no se puede atacar`() {
        val noWps = network("Casa", wpsCapable = false)
        assertEquals(
            AttackStartError.NOT_AUDITABLE,
            attackStartError(noWps, SessionState(), ScanState(), cleanupInProgress = false)
        )
        val locked = network("Casa", wpsLocked = true)
        assertEquals(
            AttackStartError.NOT_AUDITABLE,
            attackStartError(locked, SessionState(), ScanState(), cleanupInProgress = false)
        )
    }

    @Test
    fun `con el ataque el escaneo o la limpieza en curso se reporta ocupado`() {
        val net = network("Casa")
        assertEquals(
            AttackStartError.BUSY,
            attackStartError(
                net,
                SessionState(isAttacking = true),
                ScanState(),
                cleanupInProgress = false
            )
        )
        assertEquals(
            AttackStartError.BUSY,
            attackStartError(
                net,
                SessionState(),
                ScanState(isScanning = true),
                cleanupInProgress = false
            )
        )
        assertEquals(
            AttackStartError.BUSY,
            attackStartError(net, SessionState(), ScanState(), cleanupInProgress = true)
        )
    }

    @Test
    fun `la prioridad de errores es red, auditabilidad y ocupación`() {
        val noWps = network("Casa", wpsCapable = false)
        // Sin red ni auditable: NO_NETWORK manda.
        assertEquals(
            AttackStartError.NO_NETWORK,
            attackStartError(null, SessionState(isAttacking = true), ScanState(), true)
        )
        // No auditable pero ocupado: NOT_AUDITABLE manda.
        assertEquals(
            AttackStartError.NOT_AUDITABLE,
            attackStartError(noWps, SessionState(isAttacking = true), ScanState(), true)
        )
        // Auditable y libre: null.
        assertNull(
            attackStartError(network("Casa"), SessionState(), ScanState(), cleanupInProgress = false)
        )
    }

    // ---------- canStartScan ----------

    @Test
    fun `canStartScan solo permite escanear en reposo`() {
        assertTrue(canStartScan(ScanState(), SessionState(), cleanupInProgress = false))
        assertFalse(canStartScan(ScanState(isScanning = true), SessionState(), false))
        assertFalse(canStartScan(ScanState(), SessionState(isAttacking = true), false))
        assertFalse(canStartScan(ScanState(), SessionState(), cleanupInProgress = true))
    }

    // ---------- sortNetworksForList ----------

    @Test
    fun `las redes auditables se ordenan primero y luego por señal`() {
        val networks = listOf(
            network("NoAuditableFuerte", "00:00:00:00:00:01", level = -30, wpsCapable = false),
            network("Débil", "00:00:00:00:00:02", level = -80),
            network("Fuerte", "00:00:00:00:00:03", level = -40)
        )

        val sorted = sortNetworksForList(networks)

        assertEquals(
            listOf("Fuerte", "Débil", "NoAuditableFuerte"),
            sorted.map { it.ssid }
        )
    }

    @Test
    fun `el orden de wpsLocked también excluye de las auditables`() {
        val networks = listOf(
            network("Bloqueada", level = -30, wpsLocked = true),
            network("Auditable", level = -80)
        )
        assertEquals(listOf("Auditable", "Bloqueada"), sortNetworksForList(networks).map { it.ssid })
    }

    // ---------- attackOutcomeStage ----------

    @Test
    fun `un exito marca COMPLETED y un fallo conserva la fase real`() {
        val success = PixieDustResult.Success("aa:bb:cc:dd:ee:ff", "Casa", "1234", null, "")
        val failure = PixieDustResult.Failure("aa:bb:cc:dd:ee:ff", "motivo", "")

        assertEquals(
            PixieDustStage.COMPLETED,
            attackOutcomeStage(success, PixieDustStage.CAPTURING_M3)
        )
        assertEquals(
            PixieDustStage.CAPTURING_M3,
            attackOutcomeStage(failure, PixieDustStage.CAPTURING_M3)
        )
        assertEquals(
            PixieDustStage.ANALYZING,
            attackOutcomeStage(failure, PixieDustStage.ANALYZING)
        )
    }

    // ---------- transiciones de estado ----------

    @Test
    fun `scanStartingState resetea sesion escaneo consola y error`() {
        val dirty = attackingState().copy(errorMessage = "algo falló antes")

        val state = scanStartingState(dirty)

        assertTrue(state.scan.isScanning)
        assertEquals(emptyList<PixieDustNetwork>(), state.scan.networks)
        assertFalse(state.session.isAttacking)
        assertNull(state.errorMessage)
        assertTrue(state.consoleLines.isEmpty())
    }

    @Test
    fun `scanFinishedState marca escaneado y guarda la lista ordenada`() {
        val scanning = PixieDustUiState(scan = ScanState(isScanning = true))
        val networks = listOf(network("A"), network("B"))

        val state = scanFinishedState(scanning, networks)

        assertFalse(state.scan.isScanning)
        assertTrue(state.scan.hasScanned)
        assertEquals(networks, state.scan.networks)
    }

    @Test
    fun `scanFailedState marca escaneado y guarda el mensaje`() {
        val scanning = PixieDustUiState(scan = ScanState(isScanning = true))

        val state = scanFailedState(scanning, "sin permiso")

        assertFalse(state.scan.isScanning)
        assertTrue(state.scan.hasScanned)
        assertEquals("sin permiso", state.errorMessage)
    }

    @Test
    fun `selectionState fija la red y resetea la sesion sin tocar la consola`() {
        val dirty = PixieDustUiState(
            scan = ScanState(selectedNetwork = null, networks = listOf(network("Vieja"))),
            session = SessionState(
                isAttacking = false,
                stage = PixieDustStage.RESTORING,
                startedAt = 1L,
                finishedAt = 2L,
                cancelled = true,
                result = PixieDustResult.Failure("x", "y", "")
            ),
            errorMessage = "fallo",
            consoleLines = listOf("log")
        )
        val nueva = network("Nueva", "aa:bb:cc:dd:ee:ff")

        val state = selectionState(dirty, nueva)

        assertEquals(nueva, state.scan.selectedNetwork)
        assertNull(state.session.stage)
        assertNull(state.session.startedAt)
        assertNull(state.session.finishedAt)
        assertFalse(state.session.cancelled)
        assertNull(state.session.result)
        assertNull(state.errorMessage)
        assertEquals(listOf("log"), state.consoleLines)
        assertEquals(dirty.scan.networks, state.scan.networks)
    }

    @Test
    fun `attackStartingState arma la sesion y limpia consola y error`() {
        val state = attackStartingState(attackingState().copy(errorMessage = "error previo"), 5_000L)

        assertTrue(state.session.isAttacking)
        assertEquals(PixieDustStage.PREPARING, state.session.stage)
        assertEquals(5_000L, state.session.startedAt)
        assertNull(state.session.finishedAt)
        assertFalse(state.session.cancelled)
        assertNull(state.session.result)
        assertNull(state.errorMessage)
        assertTrue(state.consoleLines.isEmpty())
    }

    @Test
    fun `attackFinishedState guarda resultado fase y hora fin`() {
        val result = PixieDustResult.Success("aa:bb:cc:dd:ee:ff", "Casa", "1234", null, "raw")

        val state = attackFinishedState(
            attackingState(),
            result,
            PixieDustStage.COMPLETED,
            9_000L
        )

        assertFalse(state.session.isAttacking)
        assertEquals(PixieDustStage.COMPLETED, state.session.stage)
        assertEquals(9_000L, state.session.finishedAt)
        assertSame(result, state.session.result)
    }

    @Test
    fun `attackFailedState guarda el fallo y conserva la fase`() {
        val failure = PixieDustResult.Failure("aa:bb:cc:dd:ee:ff", "motivo", "raw")

        val state = attackFailedState(
            attackingState(),
            failure,
            PixieDustStage.ANALYZING,
            7_000L
        )

        assertFalse(state.session.isAttacking)
        assertEquals(PixieDustStage.ANALYZING, state.session.stage)
        assertSame(failure, state.session.result)
    }

    @Test
    fun `attackStoppedState conserva la fase previa a la cancelacion`() {
        val current = attackingState().copy(
            session = attackingState().session.copy(stage = PixieDustStage.RESTORING)
        )

        val state = attackStoppedState(
            current,
            wasAttacking = true,
            stageBeforeStop = PixieDustStage.CAPTURING_M3,
            now = 3_000L
        )

        assertFalse(state.session.isAttacking)
        assertEquals(PixieDustStage.CAPTURING_M3, state.session.stage)
        assertEquals(3_000L, state.session.finishedAt)
        assertTrue(state.session.cancelled)
        assertFalse(state.scan.isScanning)
    }

    @Test
    fun `attackStoppedState cae a RESTORING sin fase previa`() {
        val current = attackingState()

        val state = attackStoppedState(current, wasAttacking = true, stageBeforeStop = null, now = 3_000L)

        // El estado actual de la sesión era CAPTURING_M3: se conserva.
        assertEquals(PixieDustStage.CAPTURING_M3, state.session.stage)
    }

    @Test
    fun `attackStoppedState sin ataque solo apaga los indicadores`() {
        val idle = PixieDustUiState()

        val state = attackStoppedState(idle, wasAttacking = false, stageBeforeStop = null, now = 1L)

        assertFalse(state.session.isAttacking)
        assertFalse(state.scan.isScanning)
        assertFalse(state.session.cancelled)
    }

    @Test
    fun `interfacesLoadedState elige la primera interfaz o conserva la actual`() {
        val state = PixieDustUiState(selectedInterface = "wlan9")

        val conIfaces = interfacesLoadedState(state, listOf("wlan0", "wlan1"), "error binarios")
        assertTrue(conIfaces.interfacesAvailable)
        assertEquals("wlan0", conIfaces.selectedInterface)
        assertEquals("error binarios", conIfaces.nativeError)

        val sinIfaces = interfacesLoadedState(state, emptyList(), null)
        assertFalse(sinIfaces.interfacesAvailable)
        assertEquals("wlan9", sinIfaces.selectedInterface)
        assertNull(sinIfaces.nativeError)
    }

    @Test
    fun `clearResultState borra el marcador de auditoria y el error`() {
        val dirty = attackingState().copy(
            errorMessage = "fallo",
            session = attackingState().session.copy(
                isAttacking = false,
                stage = PixieDustStage.RESTORING,
                startedAt = 1L,
                finishedAt = 2L,
                cancelled = true,
                result = PixieDustResult.Failure("x", "y", "")
            )
        )

        val state = clearResultState(dirty)

        assertNull(state.session.stage)
        assertNull(state.session.startedAt)
        assertNull(state.session.finishedAt)
        assertFalse(state.session.cancelled)
        assertNull(state.session.result)
        assertNull(state.errorMessage)
        // La consola no se toca.
        assertEquals(dirty.consoleLines, state.consoleLines)
    }
}
