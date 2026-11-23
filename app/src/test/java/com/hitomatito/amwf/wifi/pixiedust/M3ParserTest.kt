/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import com.hitomatito.amwf.R
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del parser de M3.
 *
 * El wpa_supplicant de Android trunca los hexdump a 32 bytes y los manda a
 * logcat. El binario propio de AmWF (sin CONFIG_ANDROID_LOG) imprime el
 * volcado completo por stdout con el formato:
 *
 *   WPS: Enrollee Nonce - hexdump(len=16): 00 01 ...
 *
 * Estos tests fijan ese formato para que la auditoría no vuelva a romperse.
 */
class M3ParserTest {

    @Test
    fun `parses full hexdump format from bundled supplicant`() {
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Complete)
        val data = (capture as M3Parser.M3Capture.Complete).data
        assertEquals(hex(16, 1).replace(" ", ""), data.enrolleeNonce)
        assertEquals(hex(192, 2).replace(" ", ""), data.ownPublicKey)
        assertEquals(hex(192, 3).replace(" ", ""), data.peerPublicKey)
        assertEquals(hex(32, 4).replace(" ", ""), data.authKey)
        assertEquals(hex(32, 5).replace(" ", ""), data.eHash1)
        assertEquals(hex(32, 6).replace(" ", ""), data.eHash2)
    }

    @Test
    fun `accepts timestamp prefix before hexdump line`() {
        val text = buildString {
            appendLine("1699999999.123456: WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("1699999999.123456: WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("1699999999.123456: WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("1699999999.123456: WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("1699999999.123456: WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("1699999999.123456: WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
        }

        assertTrue(parse(text) is M3Parser.M3Capture.Complete)
    }

    @Test
    fun `keeps supporting legacy label colon format`() {
        val text = buildString {
            appendLine("WPS: Enrollee Nonce: ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key: ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key: ${hex(192, 3)}")
            appendLine("WPS: AuthKey: ${hex(32, 4)}")
            appendLine("WPS: E-Hash1: ${hex(32, 5)}")
            appendLine("WPS: E-Hash2: ${hex(32, 6)}")
        }

        assertTrue(parse(text) is M3Parser.M3Capture.Complete)
    }

    @Test
    fun `rejects truncated android log hexdump`() {
        // El supplicant de Android imprime solo 32 bytes y añade "..." al final.
        val truncated = hex(32, 1)
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): $truncated ...")
            appendLine("WPS: DH own Public Key - hexdump(len=192): $truncated ...")
        }

        val capture = parse(text)
        assertTrue(capture is M3Parser.M3Capture.Incomplete)
        assertEquals(0, (capture as M3Parser.M3Capture.Incomplete).capturedCount)
    }

    @Test
    fun `accepts timestamp prefix before wps-m3 key-value line`() {
        val line = "1699999999.123456: WPS-M3: " +
            "ENONCE=${hex(16, 1).replace(" ", "")} " +
            "PKE=${hex(192, 2).replace(" ", "")} " +
            "PKR=${hex(192, 3).replace(" ", "")} " +
            "AUTHKEY=${hex(32, 4).replace(" ", "")} " +
            "EHASH1=${hex(32, 5).replace(" ", "")} " +
            "EHASH2=${hex(32, 6).replace(" ", "")}"

        assertTrue(parse("$line\n") is M3Parser.M3Capture.Complete)
    }

    @Test
    fun `keeps complete m3 when ap nacks the probe pin`() {
        // Flujo normal de Pixie Dust: el AP entrega el M3 completo y después
        // rechaza el PIN de sonda con config_error=18. Ese NACK no debe
        // descartar las claves ya capturadas.
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
            appendLine("wlan0: WPS-FAIL msg=5 config_error=18")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Complete)
        assertEquals(
            hex(16, 1).replace(" ", ""),
            (capture as M3Parser.M3Capture.Complete).data.enrolleeNonce
        )
    }

    @Test
    fun `treats probe nack without m3 as retryable incomplete`() {
        // Si el PIN de sonda se rechaza antes de capturar los seis campos, no
        // hay que declarar el AP como no vulnerable: el runner debe reintentar.
        val text = buildString {
            appendLine("WPS: Received M1")
            appendLine("wlan0: WPS-FAIL msg=5 config_error=18")
        }

        assertTrue(parse(text) is M3Parser.M3Capture.Incomplete)
    }

    @Test
    fun `detects setup locked nack before m3`() {
        val text = buildString {
            appendLine("WPS: Received M1")
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("wlan0: WPS-FAIL msg=5 config_error=15")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Failed)
        capture as M3Parser.M3Capture.Failed
        assertEquals(15, capture.configError)
        assertEquals(
            R.string.pixie_wps_error_setup_locked,
            M3Parser.configErrorReasonRes(capture.configError)
        )
    }

    @Test
    fun `maps unknown and null config errors to their own resources`() {
        assertEquals(
            R.string.pixie_wps_error_unknown,
            M3Parser.configErrorReasonRes(null)
        )
        assertEquals(
            R.string.pixie_wps_error_config_code,
            M3Parser.configErrorReasonRes(99)
        )
        assertEquals(
            R.string.pixie_wps_error_weak_signal,
            M3Parser.configErrorReasonRes(5)
        )
    }

    @Test
    fun `detects negotiation error line from ap`() {
        val text = "WPS: Enrollee terminated negotiation with Configuration Error 15\n"

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Failed)
        assertEquals(15, (capture as M3Parser.M3Capture.Failed).configError)
    }

    @Test
    fun `aborts when ap only supports push button`() {
        // AP que solo admite PBC: el supplicant no puede construir el M2 con
        // PIN y el NACK final llega como "Configuration Error 0".
        val text = buildString {
            appendLine("WPS: Received M1")
            appendLine("WPS: Device Password ID 4")
            appendLine("WPS: Unsupported Device Password ID 4")
            appendLine("WPS: Building Message M2D")
            appendLine("wlan0: WPS-FAIL msg=5 config_error=0")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Aborted)
        assertEquals(
            R.string.pixie_reason_pbc_only,
            (capture as M3Parser.M3Capture.Aborted).reasonRes
        )
    }

    @Test
    fun `aborts after repeated association rejects`() {
        val text = buildString {
            repeat(3) {
                appendLine("wlan0: CTRL-EVENT-ASSOC-REJECT bssid=aa:bb:cc:dd:ee:ff status_code=1")
            }
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Aborted)
        assertEquals(
            R.string.pixie_reason_assoc_rejected,
            (capture as M3Parser.M3Capture.Aborted).reasonRes
        )
    }

    @Test
    fun `m1 m2 exchange captures four of six fields without e-hashes`() {
        // Caso real de los logs de campo: el AP nunca llega a enviar M3, pero
        // el supplicant ya volcó en M1/M2 el nonce del enrollee, las claves
        // DH propias y ajenas y la AuthKey. Quedan 4/6 y faltan las E-Hashes.
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH Private Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 4)}")
            appendLine("WPS: DH shared key - hexdump(len=192): ${hex(192, 5)}")
            appendLine("WPS: DHKey - hexdump(len=32): ${hex(32, 6)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 7)}")
            appendLine("WPS: Unexpected state (15) for receiving M1")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Incomplete)
        capture as M3Parser.M3Capture.Incomplete
        assertEquals(4, capture.capturedCount)
        assertEquals(listOf("E_HASH_1", "E_HASH_2"), capture.missing)
    }

    @Test
    fun `aborts on timeout-auth association rejects as seen in field logs`() {
        val text = buildString {
            repeat(3) {
                appendLine(
                    "wlan0: CTRL-EVENT-ASSOC-REJECT bssid=aa:bb:cc:dd:ee:ff " +
                        "status_code=1 timeout=auth"
                )
            }
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Aborted)
        assertEquals(
            R.string.pixie_reason_assoc_rejected,
            (capture as M3Parser.M3Capture.Aborted).reasonRes
        )
    }

    @Test
    fun `returns NoResponse when deadline expires without activity`() {
        // Caso real (COPITO 5G): el AP nunca llega a generar actividad ni a
        // rechazar asociación; la ventana vence sin ninguna línea de progreso
        // (ni asociación, ni M1, ni WPS-FAIL, ni rechazos). Distinto de una
        // cola drenada con EOF (supplicant terminado), que sigue siendo
        // Incomplete para compatibilidad.
        val text = buildString {
            appendLine("wlan0: Starting AP scan for wildcard SSID")
            appendLine("Scan requested (ret=0) - scan timeout 30 seconds")
            appendLine("WPS: Order scan results with WPS provisioning rules")
            appendLine("EAPOL: disable timer tick")
        }
        val queue = queueOf(text)

        val capture = M3Parser.parse(lines = queue, timeoutMs = 0L)

        assertTrue(capture is M3Parser.M3Capture.NoResponse)
    }

    @Test
    fun `returns Incomplete for eof drained queue without activity`() {
        // Cola agotada con EOF y sin actividad: el supplicant terminó antes
        // de tiempo; se conserva el contrato clásico Incomplete.
        val text = buildString {
            appendLine("Scan requested (ret=0) - scan timeout 30 seconds")
            appendLine("WPS: Order scan results with WPS provisioning rules")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Incomplete)
    }

    @Test
    fun `returns Incomplete when progress lines exist but fields missing`() {
        // Hubo actividad real (asociación intentada o rechazada): no es un
        // AP silencioso aunque falten los seis campos.
        val text = buildString {
            appendLine("wlan0: Trying to associate with aa:bb:cc:dd:ee:ff")
            appendLine("wlan0: CTRL-EVENT-ASSOC-REJECT bssid=aa:bb:cc:dd:ee:ff status_code=1 timeout=auth")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Incomplete)
        assertEquals(0, (capture as M3Parser.M3Capture.Incomplete).capturedCount)
    }

    @Test
    fun `shared tracker aborts across separate parse calls`() {
        // Sin rastreador compartido cada parse() tolera 2 rechazos y la auditoría
        // nunca aborta; con él, el tercero global corta la corrida.
        val tracker = M3Parser.AssocRejectTracker()
        val twoRejects = buildString {
            repeat(2) {
                appendLine(
                    "wlan0: CTRL-EVENT-ASSOC-REJECT bssid=aa:bb:cc:dd:ee:ff " +
                        "status_code=1 timeout=auth"
                )
            }
        }

        val first = M3Parser.parse(
            lines = queueOf(twoRejects),
            timeoutMs = 2_000L,
            assocRejectTracker = tracker
        )
        assertTrue(first is M3Parser.M3Capture.Incomplete)
        assertEquals(2, tracker.count())
        assertTrue(!tracker.exhausted())

        val second = M3Parser.parse(
            lines = queueOf(twoRejects),
            timeoutMs = 2_000L,
            assocRejectTracker = tracker
        )
        assertTrue(second is M3Parser.M3Capture.Aborted)
        assertEquals(
            R.string.pixie_reason_assoc_rejected,
            (second as M3Parser.M3Capture.Aborted).reasonRes
        )
        assertTrue(tracker.exhausted())
    }

    private fun queueOf(text: String): LinkedBlockingQueue<String> {
        val queue = LinkedBlockingQueue<String>()
        text.lineSequence().forEach { queue.offer(it) }
        queue.offer(M3Parser.END_OF_STREAM)
        return queue
    }

    @Test
    fun `tolerates isolated association rejects`() {
        // Un rechazo puntual no debe abortar la auditoría.
        val text = buildString {
            appendLine("wlan0: CTRL-EVENT-ASSOC-REJECT bssid=aa:bb:cc:dd:ee:ff status_code=1")
            appendLine("wlan0: CTRL-EVENT-ASSOC-REJECT bssid=aa:bb:cc:dd:ee:ff status_code=1")
        }

        assertTrue(parse(text) is M3Parser.M3Capture.Incomplete)
    }

    @Test
    fun `parses lines from blocking queue used by runner`() {
        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        val text = buildString {
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
        }
        text.lineSequence().forEach { queue.offer(it) }
        queue.offer(M3Parser.END_OF_STREAM)

        val capture = M3Parser.parse(lines = queue, timeoutMs = 2_000L)

        assertTrue(capture is M3Parser.M3Capture.Complete)
    }

    @Test
    fun `detects target bssid in scan and bss selection lines`() {
        // Líneas reales de los logs de campo (PUCHIPU, -77 dBm): el AP estaba
        // en alcance aunque nunca respondió al wps_reg. Cada formato de línea
        // del supplicant que delata visibilidad debe contar como visto.
        val raw = buildString {
            appendLine("wlan0: BSS: Add new id 17 BSSID 5c:a6:e6:cc:eb:82 SSID 'PUCHIPU' freq 2457")
            appendLine("wlan0: 17: 5c:a6:e6:cc:eb:82 ssid='PUCHIPU' wpa_ie_len=22 rsn_ie_len=20 caps=0x411 level=-77 freq=2457  wps")
            appendLine("WPS: AP[56] 5c:a6:e6:cc:eb:82 type=1 tries=0 last_attempt=-1 sec ago bssid_ignore=0")
            appendLine("wlan0: selected BSS 5c:a6:e6:cc:eb:82 ssid='PUCHIPU'")
        }

        assertTrue(M3Parser.bssidVisibleInSupplicantOutput(raw, "5c:a6:e6:cc:eb:82"))
    }

    @Test
    fun `the wps_reg command echo does not count as a visible ap`() {
        // El supplicant imprime el comando de control siempre, incluso si el AP
        // jamás apareció en un escaneo: no puede usarse como prueba de alcance.
        val raw = buildString {
            appendLine("wlan0: Control interface command 'WPS_REG 5c:a6:e6:cc:eb:82 12345670'")
            appendLine("wlan0: Starting AP scan for wildcard SSID")
            appendLine("wlan0: No suitable network found")
        }

        assertTrue(!M3Parser.bssidVisibleInSupplicantOutput(raw, "5c:a6:e6:cc:eb:82"))
    }

    @Test
    fun `no target lines means no visibility`() {
        // AP realmente fuera de alcance: el escaneo solo vio a otras redes.
        val raw = buildString {
            appendLine("wlan0: BSS: Add new id 0 BSSID 44:3b:14:10:e3:c8 SSID 'OTRA' freq 2412")
            appendLine("wlan0: 0: 44:3b:14:10:e3:c8 ssid='OTRA' level=-61 freq=2412  wps")
        }

        assertTrue(!M3Parser.bssidVisibleInSupplicantOutput(raw, "5c:a6:e6:cc:eb:82"))
        assertTrue(M3Parser.bssidVisibleInSupplicantOutput(raw, "44:3b:14:10:e3:c8"))
    }

    @Test
    fun `captures registrar nonce as optional mode-3 data`() {
        val text = buildString {
            // El M2 (nuestro nonce de registrador) precede al M3 en el flujo real.
            appendLine("WPS: Registrar Nonce - hexdump(len=16): ${hex(16, 9)}")
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
        }

        val capture = parse(text)

        assertTrue(capture is M3Parser.M3Capture.Complete)
        val data = (capture as M3Parser.M3Capture.Complete).data
        assertEquals(hex(16, 9).replace(" ", ""), data.registrarNonce)
    }

    @Test
    fun `registrar nonce never counts toward the six M3 fields`() {
        val text = buildString {
            appendLine("WPS: Registrar Nonce - hexdump(len=16): ${hex(16, 9)}")
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
        }

        // Sin E-Hash2 la captura sigue incompleta: el nonce no infla `counted`,
        // una captura "completa" por su cuenta sería un M3 roto.
        val capture = parse(text)
        val incomplete = capture as M3Parser.M3Capture.Incomplete
        assertEquals(5, incomplete.capturedCount)
    }

    @Test
    fun `ignores a truncated registrar nonce dump`() {
        val truncated = hex(8, 9)
        val text = buildString {
            appendLine("WPS: Registrar Nonce - hexdump(len=16): $truncated ...")
            appendLine("WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}")
            appendLine("WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}")
            appendLine("WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}")
            appendLine("WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}")
            appendLine("WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}")
            appendLine("WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}")
        }

        // Un dump truncado (len=16 declarado, 8 bytes impresos) no puede llegar
        // a pixiewps: envenenaría el cálculo offline del modo 3.
        val capture = parse(text)
        val data = (capture as M3Parser.M3Capture.Complete).data
        assertNull(data.registrarNonce)
    }

    @Test
    fun `mode-3 args include the captured registrar nonce`() {
        val m3 = M3Parser.M3Data(
            enrolleeNonce = "aa",
            ownPublicKey = "bb",
            peerPublicKey = "cc",
            authKey = "dd",
            eHash1 = "ee",
            eHash2 = "ff",
            registrarNonce = "11223344556677889900aabbccddeeff"
        )
        val m5m7 = M3Parser.M5M7Capture(bssid = "00:e0:4c:12:34:56", m5Enc = "m5", m7Enc = "m7")

        // Con el nonce capturado, el modo 3 queda completo para pixiewps
        // (exige --pke/--pkr/--e-nonce/--r-nonce/--bssid/--mode 3 con M7).
        assertTrue(
            m5m7.toPixiewpsMode3Args(m3)
                .contains("--r-nonce 11223344556677889900aabbccddeeff")
        )

        // Sin nonce capturado el argumento se omite: pixiewps dará su error
        // de uso, exactamente como hasta ahora (sin regresión).
        assertFalse(
            m5m7.toPixiewpsMode3Args(m3.copy(registrarNonce = null))
                .contains("--r-nonce")
        )
    }

    private fun parse(text: String): M3Parser.M3Capture {
        val queue = LinkedBlockingQueue<String>()
        text.lineSequence().forEach { queue.offer(it) }
        queue.offer(M3Parser.END_OF_STREAM)
        return M3Parser.parse(lines = queue, timeoutMs = 2_000L)
    }

    private fun hex(bytes: Int, seed: Int): String =
        (0 until bytes).joinToString(" ") { index ->
            "%02x".format((index + seed) and 0xff)
        }
}
