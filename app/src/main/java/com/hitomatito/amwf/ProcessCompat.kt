/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Compatibilidad de procesos para minSdk 24.
 *
 * `destroyForcibly()`, `isAlive` y `waitFor(timeout)` son API 26. En API 24-25
 * se degrada a `destroy()` y a sondeo de `exitValue()`. Centralizarlo evita
 * que cada consumidor mantenga su propia copia de las mismas comprobaciones.
 */
internal fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
    } else {
        destroy()
    }
}

internal fun Process.isAliveCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        isAlive
    } else {
        try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

internal fun Process.waitForCompat(timeoutMs: Long): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            Thread.sleep(50)
        }
    }
    return false
}
