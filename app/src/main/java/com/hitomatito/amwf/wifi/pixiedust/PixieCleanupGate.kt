/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gate de proceso para las limpiezas huérfanas: las que quedan corriendo en un
 * hilo aparte cuando el ViewModel desaparece con una auditoría a medias
 * (salir de la pantalla y volver a entrar).
 *
 * Esa limpieza sigue viva unos segundos (`pkill` por directorio de binarios,
 * `rm -rf` del ctrl dir y la restauración del WiFi con reintentos) y mata
 * cualquier proceso que arranque desde ese directorio, incluidos los de una
 * auditoría recién lanzada. Por eso `startAttack` espera a que el contador
 * vuelva a cero antes de soltar su propio supplicant.
 *
 * Es un contador (no un booleano) porque pueden solaparse dos salidas rápidas
 * del ViewModel: cada hilo huérfano empareja [enter]/[exit].
 */
internal object PixieCleanupGate {
    private val active = AtomicInteger(0)

    /** Marca el inicio de una limpieza huérfana; la llama `onCleared`. */
    fun enter() {
        active.incrementAndGet()
    }

    /** Marca su fin; se llama en el `finally` del hilo huérfano. */
    fun exit() {
        active.decrementAndGet()
    }

    /**
     * Espera a que no queden limpiezas huérfanas. Devuelve false si vence
     * [timeoutMs]: la auditoría se lanza igualmente, pero la consola deja
     * constancia de que la limpieza anterior podía interferir.
     */
    suspend fun awaitFree(timeoutMs: Long, pollMs: Long = 250L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (active.get() > 0) {
            if (System.currentTimeMillis() >= deadline) return false
            delay(pollMs)
        }
        return true
    }
}
