/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

/**
 * Decide cuándo AmWF debe forzar el modo de pixiewps, con qué presupuesto de
 * tiempo y cuándo debe dejar que el propio pixiewps elija en automático.
 *
 * pixiewps 1.4.2 ya inspecciona el E-Nonce en modo auto (comprueba
 * `e_nonce[0/4/8/12] & 0x80`). Duplicar esa lógica aquí sería contraproducente:
 * pasar `--mode` desactiva además los casos especiales rápidos que pixiewps
 * prueba antes del bruteforce. Solo forzamos el modo cuando AmWF tiene un dato
 * que pixiewps no ve: M5/M7 capturados. El OUI del BSSID es solo informativo:
 * identifica al fabricante del router, no al chipset, y forzar un modo desde él
 * puede descartar un ataque válido.
 *
 * No se pasa `--force`: recorre desde la fecha actual hasta el epoch y en la
 * práctica tarda del orden de 30 minutos, incompatible con una auditoría móvil.
 * Cuando pixiewps sugiere "--force o datos nuevos", AmWF elige datos nuevos.
 */
internal data class PixiewpsPlan(
    val args: String,
    val reason: String,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
) {
    val isExplicit: Boolean get() = args.startsWith("--mode ")

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60
        const val MODE3_TIMEOUT_SECONDS = 30

        fun auto(reason: String, timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS) =
            PixiewpsPlan("", reason, timeoutSeconds)
    }
}

internal object PixieModeSelector {
    /** Solo se usan como pista en el log; nunca para forzar un modo. */
    private val REALTEK_OUIS = setOf("00E04C")
    private val RT_MT_OUIS = setOf("000C43", "000E8E", "200DB8")

    fun select(
        m3: M3Parser.M3Data,
        m5m7: M3Parser.M5M7Capture?,
        bssid: String
    ): PixiewpsPlan {
        // M5/M7 solo son útiles en RTL819x y pixiewps exige --mode 3 explícito.
        if (m5m7 != null && m5m7.hasBoth()) {
            return PixiewpsPlan(
                m5m7.toPixiewpsMode3Args(m3),
                "M5/M7 capturados: modo 3 (RTL819x)",
                PixiewpsPlan.MODE3_TIMEOUT_SECONDS
            )
        }

        val oui = M3Parser.normalizeBssid(bssid)?.take(6)
        val reason = when {
            oui in REALTEK_OUIS && isRtl819xNonceCompatible(m3.enrolleeNonce) ->
                "OUI Realtek y E-Nonce compatible; pixiewps auto decidirá"
            oui in RT_MT_OUIS ->
                "OUI RT/MT; pixiewps auto prueba el modo 1 primero"
            else ->
                "sin pista de chipset; pixiewps decide en auto"
        }
        return PixiewpsPlan.auto(reason)
    }

    /** Misma condición que pixiewps aplica en auto antes de intentar RTL819x. */
    private fun isRtl819xNonceCompatible(hex: String): Boolean {
        if (hex.length != 32) return false
        return listOf(0, 8, 16, 24).all { index ->
            val nibble = hex[index].digitToIntOrNull(16) ?: return false
            nibble and 0x8 == 0
        }
    }
}
