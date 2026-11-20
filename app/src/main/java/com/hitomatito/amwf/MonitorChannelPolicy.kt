/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import android.content.Context
import androidx.core.content.edit

/**
 * Canal 802.11 con su frecuencia en MHz (tal como lo reporta `iw dev info`).
 * Publico porque [MonitorResult] lo expone; las politicas siguen internas.
 */
data class WifiChannel(val number: Int, val frequencyMHz: Int) {
    val is5GHz: Boolean get() = frequencyMHz >= 5000
}

/**
 * Decisiones puras sobre la sintonía del monitor. En QCACLD, `con_mode=4` deja
 * la interfaz en monitor SIN canal: el PHY no escucha en ninguna frecuencia y
 * tcpdump ve 0 frames aunque haya decenas de redes alrededor. Por eso toda
 * activacion/verificacion de monitor debe sintonizar un canal explicitamente.
 *
 * Los canales elegibles evitan DFS (el driver no hace radar detection): 2.4 GHz
 * completo y U-NII-1 (36-48) en 5 GHz.
 */
internal object MonitorChannelPolicy {

    val DEFAULT: WifiChannel = WifiChannel(6, 2437)

    val SELECTABLE: List<WifiChannel> = listOf(
        WifiChannel(1, 2412),
        WifiChannel(6, 2437),
        WifiChannel(11, 2462),
        WifiChannel(36, 5180),
        WifiChannel(40, 5200),
        WifiChannel(44, 5220),
        WifiChannel(48, 5240)
    )

    /** Rotacion del test de captura: 2.4 GHz, donde beacon/probe nunca faltan. */
    val CAPTURE_ROTATION: List<WifiChannel> =
        SELECTABLE.filter { !it.is5GHz }.ifEmpty { listOf(DEFAULT) }

    fun findChannel(number: Int): WifiChannel? = SELECTABLE.firstOrNull { it.number == number }

    /** Rotacion que empieza por el canal preferido y sigue con el resto. */
    fun rotationFor(preferred: WifiChannel?): List<WifiChannel> =
        (listOfNotNull(preferred) + CAPTURE_ROTATION).distinctBy { it.number }

    /**
     * Rotacion para el test de captura. El canal preferido solo abre la rotacion
     * si es 2.4 GHz: ahi los beacon/probe nunca faltan y una ventana sin frames
     * si prueba algo; en una banda 5 GHz vacia la misma ventana diria "no hay
     * captura" aunque el radio este sano, que es justo el falso negativo que esta
     * rotacion existe para evitar.
     */
    fun captureRotationFor(preferred: WifiChannel?): List<WifiChannel> =
        rotationFor(preferred?.takeIf { !it.is5GHz })

    fun infoCommand(iface: String): String = "iw dev $iface info 2>/dev/null"

    fun setMonitorTypeCommand(iface: String): String = "iw dev $iface set type monitor 2>&1"

    fun setChannelCommand(iface: String, channel: WifiChannel): String =
        "iw dev $iface set channel ${channel.number} 2>&1"

    private val CHANNEL_LINE =
        Regex("channel\\s+(\\d+)\\s+\\((\\d+)\\s*MHz\\)", RegexOption.IGNORE_CASE)

    /**
     * Extrae el canal sintonizado de la salida de `iw dev info`. Muchos drivers
     * QCACLD NO reflejan la linea en modo monitor (iw imprime minusculas): null
     * aqui NO prueba falta de sintonia; la prueba definitiva es que lleguen frames.
     */
    fun parseTunedChannel(iwInfo: String): WifiChannel? {
        val match = CHANNEL_LINE.find(iwInfo) ?: return null
        val number = match.groupValues[1].toIntOrNull() ?: return null
        val frequency = match.groupValues[2].toIntOrNull() ?: return null
        return WifiChannel(number, frequency)
    }

    /**
     * Decide si un `iw set channel` se considero aplicado: el codigo de salida
     * manda; si ademas `iw dev info` reporta un canal, debe coincidir. Sin eco
     * de canal (QCACLD) el exit code 0 es aceptable y tcpdump es el juez final.
     */
    fun isTuneAccepted(setExitCode: Int, reported: WifiChannel?, target: WifiChannel): Boolean =
        setExitCode == 0 && (reported == null || reported.number == target.number)

    fun reportsMonitorType(iwInfo: String): Boolean =
        iwInfo.contains("type monitor", ignoreCase = true)

    /** Decision de UI: el selector de canal solo existe en modo monitor. */
    fun selectorVisible(mode: MonitorMode): Boolean = mode == MonitorMode.MONITOR
}

/**
 * Persistencia del ultimo canal elegido por el usuario para el monitor.
 * Commits sincronos (mismo patron que [PendingCompatRestore]): el valor debe
 * sobrevivir a un kill inmediato tras escribirlo.
 */
internal object MonitorChannelStore {

    private const val PREFS = "amwf_monitor"
    private const val KEY_CHANNEL = "monitor_channel"

    fun load(context: Context): WifiChannel =
        MonitorChannelPolicy.findChannel(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CHANNEL, MonitorChannelPolicy.DEFAULT.number)
        ) ?: MonitorChannelPolicy.DEFAULT

    fun save(context: Context, channel: WifiChannel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putInt(KEY_CHANNEL, channel.number)
        }
    }
}
