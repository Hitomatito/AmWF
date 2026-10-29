/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.diagnostics

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Buffer circular en memoria con las ultimas lineas de log de la app.
 *
 * Se usa en lugar de `logcat` para el informe de diagnostico: desde Android 4.1
 * una app solo puede leer sus propios logs, y el buffer de logcat es volatil y
 * se pierde por la rotacion de otras apps. Este buffer es acotado (no crece sin
 * limite) y siempre esta disponible para exportar.
 */
object AppLogger {

    private const val MAX_LINES = 2000

    private val lines = ArrayDeque<String>(MAX_LINES + 1)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Anade una entrada al buffer. Los mensajes multilinea se parten en una
     * entrada por linea para mantener el buffer y el informe legibles.
     */
    @Synchronized
    fun append(level: Char, tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        message.lineSequence().forEach { line ->
            val entry = "$timestamp $level/$tag: $line"
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(entry)
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}
