/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorChannelPolicyTest {

    private val iwInfoMonitorUntuned = """
        Interface wlan0
        	ifindex 39
        	wdev 0x10
        	addr 8c:7a:3d:cc:39:50
        	type monitor
        	wiphy 0
        	txpower 0.00 dBm
    """.trimIndent()

    private val iwInfoMonitorTuned = """
        Interface wlan0
        	ifindex 35
        	wdev 0xc
        	addr 8c:7a:3d:cc:39:50
        	type monitor
        	wiphy 0
        	channel 6 (2437 MHz), width: 20 MHz, center1: 2437 MHz
        	txpower 0.00 dBm
    """.trimIndent()

    @Test
    fun `parsea el canal en minusculas como imprime iw`() {
        assertEquals(WifiChannel(6, 2437), MonitorChannelPolicy.parseTunedChannel(iwInfoMonitorTuned))
    }

    @Test
    fun `sin linea de canal el parseo devuelve null`() {
        assertNull(MonitorChannelPolicy.parseTunedChannel(iwInfoMonitorUntuned))
        assertNull(MonitorChannelPolicy.parseTunedChannel(""))
    }

    @Test
    fun `detecta el tipo monitor en la salida de iw`() {
        assertTrue(MonitorChannelPolicy.reportsMonitorType(iwInfoMonitorTuned))
        assertFalse(MonitorChannelPolicy.reportsMonitorType("type managed"))
    }

    @Test
    fun `la rotacion de captura cubre 2_4 sin duplicados`() {
        assertEquals(listOf(1, 6, 11), MonitorChannelPolicy.CAPTURE_ROTATION.map { it.number })
        assertTrue(MonitorChannelPolicy.CAPTURE_ROTATION.none { it.is5GHz })
    }

    @Test
    fun `la rotacion empieza por el canal preferido respeta 5g y no repite`() {
        val preferred = MonitorChannelPolicy.findChannel(36)!!
        val rotation = MonitorChannelPolicy.rotationFor(preferred)
        assertEquals(listOf(36, 1, 6, 11), rotation.map { it.number })
        assertEquals(1, rotation.count { it.number == 36 })
        assertEquals(
            listOf(6, 1, 11),
            MonitorChannelPolicy.rotationFor(MonitorChannelPolicy.findChannel(6)).map { it.number }
        )
        assertEquals(MonitorChannelPolicy.CAPTURE_ROTATION, MonitorChannelPolicy.rotationFor(null))
    }

    @Test
    fun `la rotacion de captura jamas empieza ni mide en 5 GHz`() {
        // Un canal 5 GHz persistido NO debe liderar la rotacion del test de
        // captura: en una banda 5 GHz vacia la ventana diria "no hay captura"
        // aunque el radio este sano (el falso negativo detector de radio muda).
        val rotation = MonitorChannelPolicy.captureRotationFor(MonitorChannelPolicy.findChannel(36))
        assertEquals(listOf(1, 6, 11), rotation.map { it.number })
        assertTrue(rotation.none { it.is5GHz })
    }

    @Test
    fun `la rotacion de captura respeta un preferido 2_4 y no repite`() {
        val rotation = MonitorChannelPolicy.captureRotationFor(MonitorChannelPolicy.findChannel(11))
        assertEquals(listOf(11, 1, 6), rotation.map { it.number })
        assertEquals(
            MonitorChannelPolicy.CAPTURE_ROTATION,
            MonitorChannelPolicy.captureRotationFor(null)
        )
    }

    @Test
    fun `los canales elegibles evitan dfs`() {
        val five = MonitorChannelPolicy.SELECTABLE.filter { it.is5GHz }.map { it.number }
        assertEquals(listOf(36, 40, 44, 48), five)
        assertNull(MonitorChannelPolicy.findChannel(52))
        assertNull(MonitorChannelPolicy.findChannel(149))
        assertNull(MonitorChannelPolicy.findChannel(14))
    }

    @Test
    fun `un set aceptado sin eco de canal vale (drivers QCACLD mudos)`() {
        val target = MonitorChannelPolicy.findChannel(6)!!
        assertTrue(MonitorChannelPolicy.isTuneAccepted(0, null, target))
        assertTrue(MonitorChannelPolicy.isTuneAccepted(0, WifiChannel(6, 2437), target))
    }

    @Test
    fun `rechaza set fallido o canal reportado distinto`() {
        val target = MonitorChannelPolicy.findChannel(6)!!
        assertFalse(MonitorChannelPolicy.isTuneAccepted(1, null, target))
        assertFalse(MonitorChannelPolicy.isTuneAccepted(0, WifiChannel(1, 2412), target))
    }

    @Test
    fun `comandos shell con la interfaz y el canal`() {
        val target = MonitorChannelPolicy.findChannel(11)!!
        assertEquals("iw dev wlan0 set channel 11 2>&1", MonitorChannelPolicy.setChannelCommand("wlan0", target))
        assertEquals("iw dev wlan0 info 2>/dev/null", MonitorChannelPolicy.infoCommand("wlan0"))
        assertEquals("iw dev wlan0 set type monitor 2>&1", MonitorChannelPolicy.setMonitorTypeCommand("wlan0"))
    }

    @Test
    fun `el selector solo existe en modo monitor`() {
        assertTrue(MonitorChannelPolicy.selectorVisible(MonitorMode.MONITOR))
        assertFalse(MonitorChannelPolicy.selectorVisible(MonitorMode.MANAGED))
        assertFalse(MonitorChannelPolicy.selectorVisible(MonitorMode.UNKNOWN))
    }

    @Test
    fun `el mapa chip-canal cubre exactamente los canales elegibles`() {
        assertEquals(
            MonitorChannelPolicy.SELECTABLE.map { it.number }.toSet(),
            CHANNEL_CHIP_IDS.keys
        )
        for (channel in MonitorChannelPolicy.SELECTABLE) {
            assertEquals(channel.number, channelForChipId(chipIdForChannel(channel.number)))
        }
        assertNull(channelForChipId(-1))
        assertNull(chipIdForChannel(null))
    }
}
