/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

/**
 * Interpreta el estado real del framework WiFi a partir de la salida de
 * `dumpsys wifi`/`cmd wifi status`. Se usa para confirmar que `svc wifi
 * enable` surtió efecto antes de dar la restauración por buena.
 */
internal object WifiRestore {

    /**
     * Devuelve `true` si el framework reporta el WiFi encendido, `false` si lo
     * reporta apagado y `null` si no se reconoce la salida (versión distinta,
     * error del comando). Se normaliza el guion porque `dumpsys wifi` escribe
     * "Wi-Fi is enabled" y `cmd wifi status` escribe "Wifi is enabled".
     */
    fun isEnabled(statusOutput: String): Boolean? {
        val text = statusOutput.lowercase().replace("-", "")
        return when {
            "wifi is enabled" in text -> true
            "wifi is disabled" in text -> false
            else -> null
        }
    }

    /**
     * Interpreta `settings get global wifi_on`: 0/2 (apagado/transicion) es
     * apagado, 1 es encendido y cualquier otra salida es desconocida. En MIUI
     * el toggle global sigue escribiendose aunque `svc wifi enable` no haya
     * terminado de levantar el radio, por eso es una fuente secundaria.
     */
    fun parseWifiOnSetting(raw: String): Boolean? = when (raw.trim()) {
        "1" -> true
        "0", "2" -> false
        else -> null
    }

    /**
     * Confirmacion combinada del estado WiFi para la restauración del ataque:
     * solo `true` cuando ambas fuentes coinciden en encendido (el estado de
     * `dumpsys` puede ser instantaneo durante el arranque async de la radio);
     * cualquiera que reporte apagado lo invalida; el resto es desconocido.
     */
    fun combinedEnabled(framework: Boolean?, setting: Boolean?): Boolean? = when {
        framework == true && setting == true -> true
        framework == false || setting == false -> false
        else -> null
    }
}
