/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los límites derivados (watchdog del supplicant, timeout de extracción de PSK)
 * deben superar SIEMPRE la suma de las fases que limitan. Estos tests fijan esa
 * desigualdad con la misma fórmula que usa [PixieTiming], de modo que bajar un
 * timeout suelto sin revalidar el presupuesto se corta en la JVM y no en campo.
 */
class PixieTimingTest {

    @Test
    fun `el watchdog del supplicant supera el peor caso legal de una ronda`() {
        val worstRoundMs = 2 * PixieTiming.SOCKET_WAIT_MS +
            PixieTiming.ATTEMPTS_PER_ROUND *
            (PixieTiming.WPS_REG_TIMEOUT_S * 1000L + PixieTiming.M3_CAPTURE_TIMEOUT_MS) +
            (PixieTiming.ATTEMPTS_PER_ROUND - 1) * PixieTiming.MAX_ATTEMPT_BACKOFF_MS
        assertTrue(
            "watchdog must outlive the slowest round",
            PixieTiming.supplicantWatchdogSeconds() * 1000L > worstRoundMs
        )
    }

    @Test
    fun `el watchdog del supplicant cubre los backoffs del rechazo de wps_reg`() {
        // Camino alternativo: intentos en los que wps_reg se rechaza (no llega
        // el parseo de 25 s) pero suma su propio backoff de 2 s por intento.
        val regRejectMs = 2 * PixieTiming.SOCKET_WAIT_MS +
            PixieTiming.ATTEMPTS_PER_ROUND *
            (PixieTiming.WPS_REG_TIMEOUT_S * 1000L + 2_000L)
        assertTrue(
            PixieTiming.supplicantWatchdogSeconds() * 1000L > regRejectMs
        )
    }

    @Test
    fun `el timeout de PSK supera la suma de sus propias fases`() {
        // Fases reales: rm+mkdir+chmod+stage del arranque, socket+PONG, dos
        // wps_reg con su retardo, la ventana PSK y la cola save_config+cat.
        val worstPskMs = 3 * 3_000L + 5_000L +
            2 * PixieTiming.SOCKET_WAIT_MS +
            2 * PixieTiming.WPS_REG_TIMEOUT_S * 1000L + 1_000L +
            PixieTiming.PSK_WAIT_MS +
            PixieTiming.CLI_TIMEOUT_S * 1000L + 3_000L
        assertTrue(
            "PSK timeout must not cut a successful provisioning",
            PixieTiming.pskExtractTimeoutMs() > worstPskMs
        )
    }
}
