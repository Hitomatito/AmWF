/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

/**
 * Reductores puros de estado para la pantalla Pixie Dust.
 *
 * Toda decisión de estado que el ViewModel aplica sobre [PixieDustUiState]
 * vive aquí como función pura, igual que [shouldApplyStageUpdate],
 * [filterNetworks] o [PixieDustNetworkFilter.countDualBandNetworks]. El
 * ViewModel solo orquesta IO (escaneo, procesos, corutinas) y delega en estas
 * funciones, de modo que la máquina de estados se pueda testear en la JVM sin
 * Android ni procesos.
 */

/** Motivo por el que no se puede iniciar un ataque; null si todo está en orden. */
enum class AttackStartError {
    /** No hay red seleccionada. */
    NO_NETWORK,

    /** La red no es auditable (sin WPS o con WPS bloqueado). */
    NOT_AUDITABLE,

    /** Ya hay una auditoría/escaneo/limpieza en curso. */
    BUSY
}

/** true si WPS aparece en las capabilities del ScanResult. */
fun isWpsCapable(capabilities: String): Boolean =
    capabilities.contains("WPS", ignoreCase = true)

/** true si el router tiene el WPS bloqueado (basado en las capabilities). */
fun isWpsLocked(capabilities: String): Boolean =
    capabilities.contains("WPS_LOCKED", ignoreCase = true) ||
        (capabilities.contains("[WPS]", ignoreCase = true) &&
            capabilities.contains("LOCKED", ignoreCase = true))

/**
 * Pre-condición para iniciar un ataque. El orden importa: se informa primero
 * de la ausencia de red, después de su no auditabilidad y por último de la
 * ocupación, porque es el orden en que la UI debe mostrarlos.
 */
fun attackStartError(
    network: PixieDustNetwork?,
    session: SessionState,
    scan: ScanState,
    cleanupInProgress: Boolean
): AttackStartError? {
    if (network == null) return AttackStartError.NO_NETWORK
    if (!network.isAuditable()) return AttackStartError.NOT_AUDITABLE
    if (session.isAttacking || scan.isScanning || cleanupInProgress) return AttackStartError.BUSY
    return null
}

/** true si se puede arrancar un escaneo: no hay uno en curso, ni ataque, ni limpieza pendiente. */
fun canStartScan(
    scan: ScanState,
    session: SessionState,
    cleanupInProgress: Boolean
): Boolean = !scan.isScanning && !session.isAttacking && !cleanupInProgress

/**
 * Ordena los resultados del escaneo: auditables primero y, dentro de cada
 * grupo, las de mayor nivel. Lo que el usuario puede auditar aparece antes.
 */
fun sortNetworksForList(networks: List<PixieDustNetwork>): List<PixieDustNetwork> =
    networks.sortedWith(
        compareByDescending<PixieDustNetwork> { it.isAuditable() }
            .thenByDescending { it.level }
    )

/**
 * Etapa con la que termina una sesión: COMPLETED si el ataque tuvo éxito; en
 * caso contrario se conserva la última fase real (el tracker no debe marcar
 * CREDENTIALS para un fallo en CAPTURE o ANALYZE).
 */
fun attackOutcomeStage(
    result: PixieDustResult,
    lastActiveStage: PixieDustStage
): PixieDustStage =
    if (result is PixieDustResult.Success) PixieDustStage.COMPLETED else lastActiveStage

// ---------- Transiciones de PixieDustUiState ----------

/** Estado con el que arranca el escaneo: resetea la sesión y la consola. */
fun scanStartingState(state: PixieDustUiState): PixieDustUiState = state.copy(
    scan = ScanState(isScanning = true),
    session = SessionState(),
    errorMessage = null,
    consoleLines = emptyList()
)

/** Estado con los resultados del escaneo ya ordenados. */
fun scanFinishedState(
    state: PixieDustUiState,
    sorted: List<PixieDustNetwork>
): PixieDustUiState = state.copy(
    scan = state.scan.copy(isScanning = false, networks = sorted, hasScanned = true)
)

/** Estado tras un escaneo fallido. */
fun scanFailedState(
    state: PixieDustUiState,
    message: String
): PixieDustUiState = state.copy(
    scan = state.scan.copy(isScanning = false, hasScanned = true),
    errorMessage = message
)

/** Seleccionar otra red reinicia la sesión mostrada y limpia el error. */
fun selectionState(state: PixieDustUiState, network: PixieDustNetwork): PixieDustUiState =
    state.copy(
        scan = state.scan.copy(selectedNetwork = network),
        errorMessage = null,
        session = state.session.copy(
            stage = null,
            startedAt = null,
            finishedAt = null,
            cancelled = false,
            result = null
        )
    )

/** Estado con el que arranca un ataque. */
fun attackStartingState(state: PixieDustUiState, now: Long): PixieDustUiState = state.copy(
    session = state.session.copy(
        isAttacking = true,
        stage = PixieDustStage.PREPARING,
        startedAt = now,
        finishedAt = null,
        cancelled = false,
        result = null
    ),
    errorMessage = null,
    consoleLines = emptyList()
)

/** Estado con el que termina el ataque (éxito o fallo retornado por el runner). */
fun attackFinishedState(
    state: PixieDustUiState,
    result: PixieDustResult,
    stage: PixieDustStage,
    finishedAt: Long
): PixieDustUiState = state.copy(
    session = state.session.copy(
        isAttacking = false,
        stage = stage,
        finishedAt = finishedAt,
        result = result
    )
)

/** Estado con el que termina el ataque por excepción inesperada. */
fun attackFailedState(
    state: PixieDustUiState,
    failure: PixieDustResult.Failure,
    stage: PixieDustStage,
    finishedAt: Long
): PixieDustUiState = state.copy(
    session = state.session.copy(
        isAttacking = false,
        stage = stage,
        finishedAt = finishedAt,
        result = failure
    )
)

/**
 * Estado con el que termina la limpieza tras una cancelación. Conserva la
 * fase capturada antes de que cleanup() emitiera RESTORING.
 */
fun attackStoppedState(
    state: PixieDustUiState,
    wasAttacking: Boolean,
    stageBeforeStop: PixieDustStage?,
    now: Long
): PixieDustUiState =
    if (wasAttacking) {
        state.copy(
            scan = state.scan.copy(isScanning = false),
            session = state.session.copy(
                isAttacking = false,
                stage = stageForCancelledSession(stageBeforeStop, state.session.stage),
                finishedAt = now,
                cancelled = true
            )
        )
    } else {
        state.copy(
            session = state.session.copy(isAttacking = false),
            scan = state.scan.copy(isScanning = false)
        )
    }

/** Estado con el que carga la pantalla: interfaces e interfaz elegida. */
fun interfacesLoadedState(
    state: PixieDustUiState,
    ifaces: List<String>,
    binariesError: String?
): PixieDustUiState = state.copy(
    interfacesAvailable = ifaces.isNotEmpty(),
    selectedInterface =
        if (ifaces.isNotEmpty()) ifaces.first() else state.selectedInterface,
    nativeError = binariesError
)

/** Estado tras «Limpiar resultado»: borra el marcador de auditoría visible. */
fun clearResultState(state: PixieDustUiState): PixieDustUiState = state.copy(
    session = state.session.copy(
        stage = null,
        startedAt = null,
        finishedAt = null,
        cancelled = false,
        result = null
    ),
    errorMessage = null
)
