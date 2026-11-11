/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import com.hitomatito.amwf.ShellExecutor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del orquestador de la auditoría Pixie Dust.
 *
 * El runner se construye con fakes de todos sus colaboradores (supplicant,
 * pixiewps, extractor de PSK, binarios nativos, log de auditoría, shell y
 * estado WiFi), así que el flujo completo —validación, rondas de captura M3,
 * limpieza y persistencia del log— se ejercita sin procesos ni `Context`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PixieDustRunnerTest {

    // ---------- Fakes ----------

    /** Cola que devuelve EOF al agotarse, para que el parser termine pronto. */
    private class TestQueue(lines: List<String>) :
        LinkedBlockingQueue<String>(lines + M3Parser.END_OF_STREAM) {
        override fun poll(timeout: Long, unit: TimeUnit): String =
            super.poll(0, TimeUnit.MILLISECONDS) ?: M3Parser.END_OF_STREAM
    }

    private class FakeProcess : Process() {
        override fun destroy() {}
        override fun exitValue(): Int = 0
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun isAlive(): Boolean = false
    }

    private class FakeSupplicant(
        private val fixture: List<String> = emptyList(),
        private val wpsRegResult: ShellExecutor.Result = ShellExecutor.Result(0, "OK", ""),
        private val failLaunch: Boolean = false
    ) : SupplicantGateway {
        var launchCount = 0
        var sendWpsRegCount = 0
        var cancelWpsCount = 0
        var stopAndFlushCount = 0

        override suspend fun launch(
            iface: String,
            rawOutput: StringBuilder,
            log: (String) -> Unit
        ): SupplicantProcesses.Handle? {
            launchCount++
            if (failLaunch) return null
            return SupplicantProcesses.Handle(FakeProcess(), TestQueue(fixture), Thread {})
        }

        override fun stop(process: Process?) {}
        override fun stopAndFlush(
            handle: SupplicantProcesses.Handle?,
            rawOutput: StringBuilder,
            log: (String) -> Unit
        ) {
            stopAndFlushCount++
            flushLines(handle?.lines ?: return, rawOutput, log)
        }

        override fun flushLines(
            queue: BlockingQueue<String>,
            rawOutput: StringBuilder,
            log: (String) -> Unit
        ) {
            while (true) {
                val line = queue.poll() ?: return
                if (line == M3Parser.END_OF_STREAM || line == PixieProcessSupport.PROCESS_EOF) {
                    continue
                }
                rawOutput.append(line).append('\n')
            }
        }

        override fun discardStale(queue: BlockingQueue<String>) {}
        override fun recordLine(line: String, rawOutput: StringBuilder, log: (String) -> Unit) {
            log("[supplicant] $line")
            rawOutput.append(line).append('\n')
        }

        override fun cli(iface: String, args: String) = ShellExecutor.Result(0, "OK", "")
        override fun sendWpsReg(
            bssid: String,
            iface: String,
            log: (String) -> Unit
        ): ShellExecutor.Result {
            sendWpsRegCount++
            return wpsRegResult
        }

        override fun cancelWps(iface: String, log: (String) -> Unit) {
            cancelWpsCount++
        }
    }

    private class FakePixiewps(private val cannedPin: String? = "12345670") : PixiewpsGateway {
        override fun buildCommand(m3: M3Parser.M3Data, m5m7: M3Parser.M5M7Capture?): String =
            "pixiewps-fake"

        override suspend fun run(
            command: String,
            rawOutput: StringBuilder,
            log: (String) -> Unit
        ): String? = cannedPin
    }

    private class FakePskExtractor(
        private val cannedPsk: String? = "testpsk123",
        private val throwCancellation: Boolean = false
    ) : PskExtractorGateway {
        var extractCount = 0
        var lastPin: String? = null

        override suspend fun extract(
            bssid: String,
            iface: String,
            ssid: String,
            pin: String,
            rawOutput: StringBuilder,
            log: (String) -> Unit
        ): String? {
            extractCount++
            lastPin = pin
            if (throwCancellation) throw CancellationException("cancelada por el usuario")
            return cannedPsk
        }
    }

    private class FakeNativeBinaries(
        private val prepareResult: List<String> = emptyList(),
        private val prepareError: Exception? = null
    ) : NativeBinariesGateway {
        var prepareCount = 0
        override fun prepare(): List<String> {
            prepareCount++
            prepareError?.let { throw it }
            return prepareResult
        }

        override fun missingBinariesMessage(missing: List<String>): String? =
            "missing:${missing.joinToString(",")}"

        override fun binaryDir(): String = "/fake/binaries"
        override fun ctrlDir(): String = "/fake/ctrl"
        override fun archSuffix(): String = ""
    }

    private data class PersistedCall(
        val result: PixieDustResult?,
        val target: PixieAuditLog.Target,
        val eventLines: List<String>,
        val rawLines: List<String>
    )

    private class FakeAuditLog : AuditLogGateway {
        val calls = mutableListOf<PersistedCall>()
        override fun persist(
            result: PixieDustResult?,
            target: PixieAuditLog.Target,
            eventLines: List<String>,
            rawLines: List<String>,
            log: (String) -> Unit
        ) {
            calls += PersistedCall(result, target, eventLines, rawLines)
        }
    }

    private class ShellRecorder(
        var wifiEnabledState: Boolean = true
    ) : (String, Int) -> ShellExecutor.Result {
        val commands = mutableListOf<String>()
        override fun invoke(command: String, timeoutSeconds: Int): ShellExecutor.Result {
            commands += command
            return if (command.startsWith("dumpsys wifi")) {
                ShellExecutor.Result(
                    0,
                    if (wifiEnabledState) "Wi-Fi is enabled" else "Wi-Fi is disabled",
                    ""
                )
            } else {
                ShellExecutor.Result(0, "OK", "")
            }
        }
    }

    // ---------- Helpers ----------

    private fun fullM3Lines(): List<String> = listOf(
        "WPS: Enrollee Nonce - hexdump(len=16): ${hex(16, 1)}",
        "WPS: DH own Public Key - hexdump(len=192): ${hex(192, 2)}",
        "WPS: DH peer Public Key - hexdump(len=192): ${hex(192, 3)}",
        "WPS: AuthKey - hexdump(len=32): ${hex(32, 4)}",
        "WPS: E-Hash1 - hexdump(len=32): ${hex(32, 5)}",
        "WPS: E-Hash2 - hexdump(len=32): ${hex(32, 6)}"
    )

    private fun hex(bytes: Int, seed: Int): String =
        (0 until bytes).joinToString(" ") { "%02x".format((it + seed) and 0xff) }

    // ---------- Validación de entrada ----------

    @Test
    fun `rechaza un BSSID invalido sin tocar binarios ni supplicant y persiste el log`() = runTest {
        val supplicant = FakeSupplicant()
        val binaries = FakeNativeBinaries()
        val audit = FakeAuditLog()
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = supplicant,
            binaries = binaries,
            audit = audit
        )

        val result = runner.runAttack(bssid = "not-a-mac", interfaceName = "wlan0")

        assertTrue(result is PixieDustResult.Failure)
        assertEquals(0, supplicant.launchCount)
        assertEquals(0, binaries.prepareCount)
        assertEquals(1, audit.calls.size)
        assertTrue(audit.calls[0].result is PixieDustResult.Failure)
    }

    @Test
    fun `rechaza una interfaz invalida y persiste el log`() = runTest {
        val supplicant = FakeSupplicant()
        val audit = FakeAuditLog()
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = supplicant,
            audit = audit
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            interfaceName = "wlan0; rm -rf /"
        )

        assertTrue(result is PixieDustResult.Failure)
        assertEquals(0, supplicant.launchCount)
        assertEquals(1, audit.calls.size)
    }

    // ---------- Binarios nativos ----------

    @Test
    fun `si falta un binario critico falla sin lanzar el supplicant y persiste el log`() = runTest {
        val supplicant = FakeSupplicant()
        val binaries = FakeNativeBinaries(prepareResult = listOf("pixiewps"))
        val audit = FakeAuditLog()
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = supplicant,
            binaries = binaries,
            audit = audit
        )

        val result = runner.runAttack(bssid = "aa:bb:cc:dd:ee:ff")

        assertTrue(result is PixieDustResult.Failure)
        assertEquals("missing:pixiewps", (result as PixieDustResult.Failure).reason)
        assertEquals(0, supplicant.launchCount)
        assertEquals(1, audit.calls.size)
    }

    @Test
    fun `si la preparacion de binarios falla se persiste el log igualmente`() = runTest {
        val binaries = FakeNativeBinaries(prepareError = RuntimeException("boom"))
        val audit = FakeAuditLog()
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            binaries = binaries,
            audit = audit
        )

        val result = runner.runAttack(bssid = "aa:bb:cc:dd:ee:ff")

        assertTrue(result is PixieDustResult.Failure)
        assertTrue((result as PixieDustResult.Failure).reason.contains("boom"))
        assertEquals(1, audit.calls.size)
    }

    // ---------- Flujo de éxito ----------

    @Test
    fun `una captura M3 completa devuelve exito y persiste el log`() = runTest {
        val audit = FakeAuditLog()
        val stages = mutableListOf<PixieDustStage>()
        val supplicant = FakeSupplicant(fixture = fullM3Lines())
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = supplicant,
            audit = audit,
            onStage = { stages += it }
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            interfaceName = "wlan0",
            ssid = "Casa",
            disableWifiBeforeAttack = false
        )

        assertTrue(result is PixieDustResult.Success)
        val success = result as PixieDustResult.Success
        assertEquals("12345670", success.wpsPin)
        assertEquals("testpsk123", success.wpaPsk)
        assertEquals(1, supplicant.cancelWpsCount)
        assertEquals(PixieDustStage.PREPARING, stages.first())
        assertEquals(PixieDustStage.RESTORING, stages.last())
        assertTrue(stages.contains(PixieDustStage.ANALYZING))
        assertTrue(stages.contains(PixieDustStage.EXTRACTING_PSK))
        assertEquals(1, audit.calls.size)
        assertTrue(audit.calls[0].result is PixieDustResult.Success)
        assertTrue(success.rawOutput.contains("Enrollee Nonce"))
    }

    @Test
    fun `sin SSID no se intenta extraer el PSK pero el resultado es exito`() = runTest {
        val psk = FakePskExtractor()
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = FakeSupplicant(fixture = fullM3Lines()),
            psk = psk
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            ssid = null,
            disableWifiBeforeAttack = false
        )

        assertTrue(result is PixieDustResult.Success)
        assertNull((result as PixieDustResult.Success).wpaPsk)
        assertEquals(0, psk.extractCount)
    }

    @Test
    fun `si pixiewps no encuentra el pin se reporta como no vulnerable`() = runTest {
        val audit = FakeAuditLog()
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = FakeSupplicant(fixture = fullM3Lines()),
            pixiewps = FakePixiewps(cannedPin = null),
            audit = audit
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            ssid = "Casa",
            disableWifiBeforeAttack = false
        )

        assertTrue(result is PixieDustResult.Failure)
        assertEquals(1, audit.calls.size)
        assertTrue(audit.calls[0].result is PixieDustResult.Failure)
    }

    // ---------- Reintentos y abortos ----------

    @Test
    fun `una captura incompleta agota las tres rondas y persiste el log`() = runTest {
        val supplicant = FakeSupplicant(fixture = listOf("WPS: Received M1"))
        val audit = FakeAuditLog()
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = supplicant,
            audit = audit
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            disableWifiBeforeAttack = false
        )

        assertTrue(result is PixieDustResult.Failure)
        assertEquals(3, supplicant.launchCount)
        assertEquals(9, supplicant.sendWpsRegCount)
        assertEquals(1, audit.calls.size)
        assertTrue(audit.calls[0].result is PixieDustResult.Failure)
    }

    @Test
    fun `un AP que rechaza la asociación aborta antes de agotar las rondas`() = runTest {
        // Caso real de los logs de campo (CHASKY/PATIO): el AP devuelve
        // CTRL-EVENT-ASSOC-REJECT ... timeout=auth en cada intento. La auditoría
        // debe cortar en la primera ronda en vez de repetir rondas a ciegas.
        val supplicant = FakeSupplicant(
            fixture = listOf(
                "wlan0: CTRL-EVENT-ASSOC-REJECT bssid=aa:bb:cc:dd:ee:ff status_code=1 timeout=auth",
                "wlan0: CTRL-EVENT-ASSOC-REJECT bssid=aa:bb:cc:dd:ee:ff status_code=1 timeout=auth"
            )
        )
        val audit = FakeAuditLog()
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = supplicant,
            audit = audit
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            disableWifiBeforeAttack = false
        )

        assertTrue(result is PixieDustResult.Failure)
        // Con el fixture de 2 rechazos por ronda, el presupuesto global se
        // agota en la ronda 2: la ronda 3 nunca llega a lanzar supplicant.
        // Sin rastreador global cada parse() vería solo 2 rechazos y el
        // ataque se agotaría en las 3 rondas (launchCount == 3).
        assertEquals(2, supplicant.launchCount)
        assertEquals(4, supplicant.sendWpsRegCount)
        assertEquals(1, audit.calls.size)
        assertTrue(audit.calls[0].result is PixieDustResult.Failure)
    }

    @Test
    fun `un AP que solo admite PBC aborta sin agotar las rondas`() = runTest {
        val supplicant = FakeSupplicant(
            fixture = listOf(
                "WPS: Received M1",
                "WPS: Unsupported Device Password ID 4"
            )
        )
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = supplicant
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            disableWifiBeforeAttack = false
        )

        assertTrue(result is PixieDustResult.Failure)
        assertEquals(1, supplicant.launchCount)
        assertEquals(1, supplicant.sendWpsRegCount)
    }

    @Test
    fun `un WPS-FAIL de setup locked aborta sin agotar las rondas`() = runTest {
        val supplicant = FakeSupplicant(
            fixture = listOf(
                "WPS: Received M1",
                "wlan0: WPS-FAIL msg=5 config_error=15"
            )
        )
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = supplicant
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            disableWifiBeforeAttack = false
        )

        assertTrue(result is PixieDustResult.Failure)
        assertEquals(1, supplicant.launchCount)
        assertEquals(1, supplicant.sendWpsRegCount)
    }

    @Test
    fun `si el supplicant no arranca se falla y se persiste el log`() =
        runTest {
            val supplicant = FakeSupplicant(failLaunch = true)
            val audit = FakeAuditLog()
            val runner = runnerWith(
                dispatcher = StandardTestDispatcher(testScheduler),
                supplicant = supplicant,
                audit = audit
            )

            val result = runner.runAttack(
                bssid = "aa:bb:cc:dd:ee:ff",
                disableWifiBeforeAttack = false
            )

            assertTrue(result is PixieDustResult.Failure)
            // Un fallo de arranque corta el bucle de rondas en la primera.
            assertEquals(1, supplicant.launchCount)
            assertEquals(1, audit.calls.size)
        }

    // ---------- Estado del WiFi y cancelación ----------

    @Test
    fun `el WiFi se desactiva al empezar y se restaura al terminar`() = runTest {
        val shell = ShellRecorder(wifiEnabledState = true)
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = FakeSupplicant(fixture = fullM3Lines()),
            shell = shell,
            wifiOn = { true }
        )

        val result = runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            ssid = null,
            disableWifiBeforeAttack = true
        )

        assertTrue(result is PixieDustResult.Success)
        assertTrue(shell.commands.any { it == "svc wifi disable" })
        assertTrue(shell.commands.any { it.startsWith("svc wifi enable") })
    }

    @Test
    fun `el WiFi no se toca si estaba apagado antes del ataque`() = runTest {
        val shell = ShellRecorder(wifiEnabledState = false)
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = FakeSupplicant(fixture = fullM3Lines()),
            shell = shell,
            wifiOn = { false }
        )

        runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            disableWifiBeforeAttack = true
        )

        assertFalse(shell.commands.any { it == "svc wifi disable" })
        assertFalse(shell.commands.any { it.startsWith("svc wifi enable") })
    }

    @Test
    fun `cancel() es idempotente y no repite pkill ni el encendido del WiFi`() = runTest {
        val shell = ShellRecorder(wifiEnabledState = true)
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = FakeSupplicant(fixture = fullM3Lines()),
            shell = shell,
            wifiOn = { true }
        )

        runner.runAttack(
            bssid = "aa:bb:cc:dd:ee:ff",
            disableWifiBeforeAttack = true
        )

        val pkillCount = shell.commands.count { it.contains("pkill") }
        val enableCount = shell.commands.count { it.startsWith("svc wifi enable") }

        runner.cancel()
        runner.cancel()

        assertEquals(pkillCount, shell.commands.count { it.contains("pkill") })
        assertEquals(enableCount, shell.commands.count { it.startsWith("svc wifi enable") })
    }

    @Test
    fun `si la cancelación interrumpe la extracción se persiste un log sin resultado`() = runTest {
        val audit = FakeAuditLog()
        val psk = FakePskExtractor(throwCancellation = true)
        val runner = runnerWith(
            dispatcher = StandardTestDispatcher(testScheduler),
            supplicant = FakeSupplicant(fixture = fullM3Lines()),
            psk = psk,
            audit = audit
        )

        try {
            runner.runAttack(
                bssid = "aa:bb:cc:dd:ee:ff",
                ssid = "Casa",
                disableWifiBeforeAttack = false
            )
        } catch (e: CancellationException) {
            // Esperado: la cancelación propaga, pero el log debe quedar guardado.
        }

        assertEquals(1, audit.calls.size)
        assertNull(audit.calls[0].result)
    }

    // ---------- Constructor con fakes ----------

    private fun runnerWith(
        dispatcher: CoroutineDispatcher,
        supplicant: FakeSupplicant = FakeSupplicant(),
        pixiewps: FakePixiewps = FakePixiewps(),
        psk: FakePskExtractor = FakePskExtractor(),
        binaries: FakeNativeBinaries = FakeNativeBinaries(),
        audit: FakeAuditLog = FakeAuditLog(),
        shell: ShellRecorder = ShellRecorder(),
        wifiOn: () -> Boolean? = { false },
        onStage: (PixieDustStage) -> Unit = {}
    ): PixieDustRunner = PixieDustRunner(
        ioDispatcher = dispatcher,
        supplicant = supplicant,
        pixiewps = pixiewps,
        pskExtractor = psk,
        nativeBinaries = binaries,
        auditLog = audit,
        strings = { id, args ->
            "res:$id${if (args.isEmpty()) "" else args.joinToString(",", "(", ")")}"
        },
        shellExec = shell,
        isWifiEnabled = wifiOn,
        onStage = onStage
    )
}
