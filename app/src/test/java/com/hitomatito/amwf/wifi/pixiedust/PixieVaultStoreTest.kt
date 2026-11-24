/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixieVaultStoreTest {

    private fun entry(
        ssid: String = "CHASKY",
        psk: String = "clave-segura",
        at: Long = 1_764_000_000_000L
    ) = PixieVaultEntry(
        bssid = "ec:a2:a0:7e:37:19",
        ssid = ssid,
        wpaPsk = psk,
        obtainedAtMs = at
    )

    @Test
    fun `el round trip conserva la entrada completa`() {
        val restored = decodeEntry(encodeEntry(entry()))!!.copy(bssid = entry().bssid)

        assertEquals(entry(), restored)
    }

    @Test
    fun `claves y SSID hostiles sobreviven el round trip`() {
        // Contraseñas reales: saltos, tuberías, barras, «=» (estructural de la
        // línea, tolerado porque solo la PRIMERA «=» separa) y unicode.
        val hostilePsk = "pa\nterminada | con | tuberias \\ y = igual \\n literal"
        val hostileSsid = "red ñandú ✓ con ; y : especiales"

        val restored = decodeEntry(encodeEntry(entry(ssid = hostileSsid, psk = hostilePsk)))!!

        assertEquals(hostileSsid, restored.ssid)
        assertEquals(hostilePsk, restored.wpaPsk)
    }

    @Test
    fun `las entradas guardadas con pin se leen ignorando el pin`() {
        // Migración: las entradas anteriores tenían 5 líneas con pin=...; el
        // PIN ya no se persiste ni se modela, pero la clave recuperada debe
        // seguir apareciendo en la bóveda del usuario.
        val legacy = encodeEntry(entry()).replace(
            "at=1764000000000",
            "pin=12345670\nat=1764000000000"
        )

        assertEquals(entry().copy(bssid = ""), decodeEntry(legacy))
    }

    @Test
    fun `un campo requerido duplicado se trata como ausente sin lanzar`() {
        // Sin la guarda explícita de claves, getValue lanzaría
        // NoSuchElementException al cargar una entrada corrupta desde prefs.
        val roto = encodeEntry(entry()).replace("psk=clave-segura", "ssid=otra")

        assertNull(decodeEntry(roto))
    }

    @Test
    fun `la indentacion del XmlSerializer no rompe el decode`() {
        // SharedPreferences indenta con espacios la última línea del valor
        // (regresión de campo del códec de compatibilidad): el terminador en
        // blanco debe tolerarse también aquí.
        val fromXml = encodeEntry(entry()) + "    "

        assertEquals(entry().copy(bssid = ""), decodeEntry(fromXml))
    }

    @Test
    fun `todo lo malformado se trata como ausente`() {
        val good = encodeEntry(entry())

        assertNull(decodeEntry(""))
        assertNull(decodeEntry("otro-formato\nssid=x"))
        assertNull(decodeEntry(good.replaceFirst("vault-v1", "vault-v0")))
        assertNull(decodeEntry(good + "\nsorpresa=1"))
        assertNull(decodeEntry(good.replace("psk=clave-segura", "psk="))) // PSK vacío
        assertNull(decodeEntry(good.replace("ssid=CHASKY", "ssid="))) // SSID vacío
        assertNull(decodeEntry(good.replace("at=1764000000000", "at=no-numero")))
    }

    @Test
    fun `el bssid se valida antes de tocar prefs`() {
        assertTrue(PixieVaultStore.isValidBssid("EC:A2:A0:7E:37:19"))
        assertTrue(PixieVaultStore.isValidBssid("60:6c:63:df:7e:be"))
        assertFalse(PixieVaultStore.isValidBssid("60:6c:63:df:7e"))
        assertFalse(PixieVaultStore.isValidBssid("60-6c-63-df-7e-be"))
        assertFalse(PixieVaultStore.isValidBssid(""))
        assertFalse(PixieVaultStore.isValidBssid("gg:6c:63:df:7e:be"))
    }
}
