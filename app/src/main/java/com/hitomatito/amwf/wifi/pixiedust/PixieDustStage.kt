/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

/**
 * Fases visibles del flujo de auditoría. Agrupan los hitos técnicos para que la
 * interfaz pueda mostrar un avance compacto sin exponer detalles internos.
 */
enum class PixieDustPhase {
    PREPARE,
    CONNECT,
    CAPTURE,
    ANALYZE,
    CREDENTIALS
}

/**
 * Hitos del ataque Pixie Dust y porcentaje orientativo de avance.
 *
 * El porcentaje es una estimación por etapas: no representa tiempo restante ni
 * un progreso continuo. La etapa actual se muestra siempre junto al porcentaje
 * para que el usuario sepa qué está ocurriendo aunque el valor se mantenga
 * unos segundos.
 */
enum class PixieDustStage(val progress: Int, val phase: PixieDustPhase) {
    PREPARING(6, PixieDustPhase.PREPARE),
    DISABLING_WIFI(12, PixieDustPhase.PREPARE),
    STARTING_SUPPLICANT(22, PixieDustPhase.CONNECT),
    REGISTERING_WPS(34, PixieDustPhase.CONNECT),
    CAPTURING_M3(52, PixieDustPhase.CAPTURE),
    ANALYZING(72, PixieDustPhase.ANALYZE),
    EXTRACTING_PSK(88, PixieDustPhase.CREDENTIALS),
    RESTORING(96, PixieDustPhase.CREDENTIALS),
    COMPLETED(100, PixieDustPhase.CREDENTIALS)
}

/**
 * Decide si una etapa recibida del runner debe reflejarse en la UI.
 *
 * `cleanup()` emite [PixieDustStage.RESTORING] también al cancelar. Durante una
 * cancelación no debe pisar la fase real que se estaba ejecutando cuando el
 * usuario pulsó «Detener», o el tracker marcaría CREDENTIALS aunque
 * estuviéramos capturando el M3.
 */
fun shouldApplyStageUpdate(
    isAttacking: Boolean,
    cleanupInProgress: Boolean,
    newStage: PixieDustStage
): Boolean = isAttacking &&
    !(cleanupInProgress && newStage == PixieDustStage.RESTORING)

/**
 * Etapa que debe quedar en la tarjeta al detener la auditoría.
 *
 * [stageBeforeStop] es la fase real capturada antes de que `cleanup()` emitiera
 * RESTORING; se prefiere para que el tracker no marque CREDENTIALS cuando la
 * cancelación llegó durante CAPTURE o ANALYZE. Si la sesión ya estaba en
 * RESTORING (el ataque había terminado y solo faltaba limpiar), se mantiene esa
 * etapa; sin ninguna referencia se cae a RESTORING.
 */
fun stageForCancelledSession(
    stageBeforeStop: PixieDustStage?,
    currentStage: PixieDustStage?
): PixieDustStage = stageBeforeStop ?: currentStage ?: PixieDustStage.RESTORING
