/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import kotlin.math.ceil

/**
 * Unica fuente de verdad de los presupuestos temporales de Pixie Dust.
 *
 * El watchdog del supplicant y el timeout de la extraccion de PSK limitan la
 * vida de procesos que otros colaboradores dirigen con SUS propios timeouts
 * (socket, PONG, wps_reg, parseo M3, ventana PSK). Con las cifras repartidas
 * por cada clase, los presupuestos se desincronizaban: el watchdog de 110 s
 * quedaba justo por debajo del peor caso legal de una ronda (~110.5 s), y el
 * timeout de PSK (45 s) era mas corto que su propio peor camino (~52 s), capaz
 * de cortar la extraccion DESPUES de un provisioning exitoso y antes de leer
 * el config guardado. Aqui los limites derivados se calculan a partir de las
 * constantes que consumen, igual que `PixiewpsPlan.timeoutSeconds` viaja con
 * el comando.
 */
internal object PixieTiming {

    /** Espera maxima del socket de control y de su primer PONG. */
    const val SOCKET_WAIT_MS = 8_000L

    /** Timeout de cada invocacion de `wpa_cli wps_reg`. */
    const val WPS_REG_TIMEOUT_S = 5

    /**
     * Timeout de las demas llamadas de control (`wps_cancel`, `save_config`,
     * `list_networks`, `get_network`). Comparte valor con `wps_reg` a proposito,
     * pero los presupuestos son conceptualmente independientes: mover el de
     * registro no debe re-tunar silenciosamente toda la interfaz de control.
     */
    const val CLI_TIMEOUT_S = 5

    /** Ejecuciones de setup del arranque del supplicant (rm, mkdir, chmod, stage). */
    private const val SETUP_EXECS_MS = 3_000L + 3_000L + 3_000L + 5_000L

    /** Cola final del extractor: save_config + lectura del config por `su`. */
    private const val PSK_TAIL_MS = CLI_TIMEOUT_S * 1000L + 3_000L

    /** Espera maxima de los seis campos M3 por intento. */
    const val M3_CAPTURE_TIMEOUT_MS = 25_000L

    /** Intentos de captura M3 por ronda de supplicant. */
    const val ATTEMPTS_PER_ROUND = 3

    /** Backoff progresivo maximo entre intentos (750 ms x intento 3). */
    const val MAX_ATTEMPT_BACKOFF_MS = 2_250L

    /** Espera del PSK por la interfaz de control tras aceptar el wps_reg. */
    const val PSK_WAIT_MS = 25_000L

    /** Margen fijo para I/O de shell, renegociaciones y sleeps intermedios. */
    private const val SAFETY_MARGIN_S = 15

    /** Retardo entre los dos intentos de wps_reg de la extraccion de PSK. */
    private const val PSK_REG_RETRY_MS = 1_000L

    /**
     * Watchdog del supplicant: debe superar el peor caso legal de una ronda
     * (arranque con socket+PONG + N intentos de wps_reg+parseo con backoffs).
     * Si expira en medio del parseo, `discardStale` reencola el EOF y la
     * auditoria reporta una "captura incompleta" sin explicar que el proceso
     * murio por presupuesto.
     */
    fun supplicantWatchdogSeconds(): Int {
        val launchMs = 2 * SOCKET_WAIT_MS
        val attemptsMs = ATTEMPTS_PER_ROUND *
            (WPS_REG_TIMEOUT_S * 1000L + M3_CAPTURE_TIMEOUT_MS) +
            (ATTEMPTS_PER_ROUND - 1) * MAX_ATTEMPT_BACKOFF_MS
        return ceil((launchMs + attemptsMs) / 1000.0).toInt() + SAFETY_MARGIN_S
    }

    /**
     * Timeout total de la extraccion de PSK: las ejecuciones de setup del
     * arranque, socket+PONG, dos intentos de wps_reg con su retardo, la ventana
     * de espera del PSK y la cola save_config/cat, mas margen. Debe superar la
     * suma de sus propias fases: en dispositivos donde las llamadas `su` se
     * rallan (el perfil del issue #5) un presupuesto mas corto que las fases
     * corta la extraccion justo antes de leer el config guardado, despues de un
     * provisioning exitoso, que es exactamente el fallo que esta cifra evita.
     */
    fun pskExtractTimeoutMs(): Long =
        SETUP_EXECS_MS +
            2 * SOCKET_WAIT_MS +
            2 * WPS_REG_TIMEOUT_S * 1000L + PSK_REG_RETRY_MS +
            PSK_WAIT_MS +
            PSK_TAIL_MS + SAFETY_MARGIN_S * 1000L
}
