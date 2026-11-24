/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiQrCodecTest {

    @Test
    fun `el payload WPA tiene la forma esperada`() {
        assertEquals(
            "WIFI:T:WPA;S:CHASKY;P:clave-segura;;",
            wifiQrPayload("CHASKY", "clave-segura")
        )
    }

    @Test
    fun `los caracteres estructurales se escapan con barra invertida`() {
        // SSID real: a \ b " c ; d , e : f — cada estructural lleva su barra.
        val payload = wifiQrPayload("""a\b"c;d,e:f""", """x\y"z;w,v:u""")

        assertEquals(
            """WIFI:T:WPA;S:a\\b\"c\;d\,e\:f;P:x\\y\"z\;w\,v\:u;;""",
            payload
        )
    }

    @Test
    fun `los caracteres unicode y espacios no se tocan`() {
        val payload = wifiQrPayload("Ñandú ñoño ✓", "pa s e o 123 ñ")

        assertEquals(
            "WIFI:T:WPA;S:Ñandú ñoño ✓;P:pa s e o 123 ñ;;",
            payload
        )
    }

    @Test
    fun `el escape no puede corromper un SSID con secuencia parecida a un escape`() {
        // SSID real: p r e \ ; p o s t. La barra se dobla (\\) y el punto y
        // coma se escapa (\;) → tres barras seguidas en el payload. Si el
        // escape fuera secuencial en el orden inverso, la barra introducida
        // por el escape del ';' se doblaría también y el lector vería basura.
        val payload = wifiQrPayload("pre\\;post", "k")

        assertEquals("""WIFI:T:WPA;S:pre\\\;post;P:k;;""", payload)
    }
}
