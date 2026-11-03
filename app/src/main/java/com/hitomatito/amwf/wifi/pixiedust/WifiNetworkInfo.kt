/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import com.hitomatito.amwf.R
import java.util.Locale

/** Banda de radio en la que emite un punto de acceso. */
enum class WifiBand {
    BAND_24_GHZ,
    BAND_5_GHZ,
    BAND_6_GHZ,
    OTHER;

    fun label(context: Context): String = when (this) {
        BAND_24_GHZ -> context.getString(R.string.pixie_band_24ghz)
        BAND_5_GHZ -> context.getString(R.string.pixie_band_5ghz)
        BAND_6_GHZ -> context.getString(R.string.pixie_band_6ghz)
        OTHER -> context.getString(R.string.pixie_band_other)
    }

    companion object {
        fun fromFrequency(frequency: Int): WifiBand = when {
            frequency in 2400..2500 -> BAND_24_GHZ
            frequency in 4900..5900 -> BAND_5_GHZ
            frequency in 5925..7125 -> BAND_6_GHZ
            else -> OTHER
        }
    }
}

/** Seguridad anunciada por el punto de acceso en sus capabilities. */
enum class WifiSecurity {
    OPEN,
    WEP,
    WPA,
    WPA_WPA2,
    WPA2,
    WPA2_WPA3,
    WPA3,
    OWE,
    UNKNOWN;

    fun label(context: Context): String = when (this) {
        OPEN -> context.getString(R.string.pixie_security_open)
        WEP -> "WEP"
        WPA -> "WPA"
        WPA_WPA2 -> "WPA/WPA2"
        WPA2 -> "WPA2"
        WPA2_WPA3 -> "WPA2/WPA3"
        WPA3 -> "WPA3"
        OWE -> "OWE"
        UNKNOWN -> context.getString(R.string.pixie_security_unknown)
    }
}

/** Banda del punto de acceso. */
fun PixieDustNetwork.band(): WifiBand = WifiBand.fromFrequency(frequency)

/** Canal primario derivado de la frecuencia, o null si no se puede calcular. */
fun PixieDustNetwork.channel(): Int? = channelForFrequency(frequency)

/** Tipo de seguridad derivado de las capabilities del scan. */
fun PixieDustNetwork.security(): WifiSecurity = securityFromCapabilities(capabilities)

internal fun channelForFrequency(frequency: Int): Int? = when {
    frequency == 2484 -> 14
    frequency in 2412..2472 -> (frequency - 2407) / 5
    frequency in 5000..5900 -> (frequency - 5000) / 5
    frequency in 5950..7125 -> (frequency - 5950) / 5
    else -> null
}

internal fun securityFromCapabilities(capabilities: String): WifiSecurity {
    val caps = capabilities.uppercase(Locale.ROOT)
    if (caps.isBlank()) return WifiSecurity.UNKNOWN

    val hasWpa3 = caps.contains("WPA3") || caps.contains("SAE")
    val hasWpa2 = caps.contains("WPA2") || caps.contains("RSN")
    val hasWpa = caps.contains("WPA-") || caps.contains("WPA-PSK") ||
        caps.contains("[WPA]") || (caps.contains("WPA") && !hasWpa2 && !hasWpa3)
    val hasWep = caps.contains("WEP")
    val hasOwe = caps.contains("OWE")

    return when {
        hasWpa3 && hasWpa2 -> WifiSecurity.WPA2_WPA3
        hasWpa3 -> WifiSecurity.WPA3
        hasOwe -> WifiSecurity.OWE
        hasWpa2 && hasWpa -> WifiSecurity.WPA_WPA2
        hasWpa2 -> WifiSecurity.WPA2
        hasWpa -> WifiSecurity.WPA
        hasWep -> WifiSecurity.WEP
        caps.contains("[ESS]") || caps.contains("[IBSS]") -> WifiSecurity.OPEN
        else -> WifiSecurity.UNKNOWN
    }
}
