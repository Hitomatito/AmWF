/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

/**
 * Resultado de una auditoría Pixie Dust.
 *
 * Cada auditoría puede terminar en éxito (PIN y/o PSK recuperados) o en error
 * (objetivo no vulnerable, timeout, interfaz no disponible, fallo de binarios, etc.).
 */
sealed interface PixieDustResult {
    data class Success(
        val bssid: String,
        val ssid: String?,
        val wpsPin: String?,
        val wpaPsk: String?,
        val rawOutput: String,
    ) : PixieDustResult

    data class Failure(
        val bssid: String,
        val reason: String,
        val rawOutput: String,
    ) : PixieDustResult

    data object InProgress : PixieDustResult

    data object Cancelled : PixieDustResult
}

/**
 * Estado de la interfaz WiFi durante la auditoría.
 */
enum class PixieDustInterfaceState {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
    IN_USE
}
