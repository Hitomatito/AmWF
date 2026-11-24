/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

/**
 * Payload del QR de conexión WiFi (formato WIFI: de ZXing, entendido por el
 * escáner de Android e iOS y por la mayoría de apps de cámara).
 *
 * El escape es el que exige la especificación: `\`, `"`, `;`, `,` y `:` se
 * prefijan con barra invertida. Sin él, una clave con cualquiera de esos
 * caracteres generaría un QR que el dispositivo lector no puede decodificar
 * (o peor: decodifica con la clave truncada y la conexión falla sin motivo
 * visible).
 *
 * Es una función pura: los tests cubren claves y SSID hostiles sin Android.
 */
internal fun wifiQrPayload(ssid: String, psk: String): String =
    "WIFI:T:WPA;S:${qrEscape(ssid)};P:${qrEscape(psk)};;"

/** El orden importa: la barra invertida se escapa primero. */
private fun qrEscape(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '\\', '"', ';', ',', ':' -> {
                append('\\')
                append(ch)
            }
            else -> append(ch)
        }
    }
}
