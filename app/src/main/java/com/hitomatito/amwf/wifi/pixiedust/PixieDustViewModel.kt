/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.app.Application
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.hitomatito.amwf.R

/**
 * Estado de la pantalla de auditoría Pixie Dust.
 */
data class PixieDustUiState(
    val isScanning: Boolean = false,
    val isAttacking: Boolean = false,
    val networks: List<PixieDustNetwork> = emptyList(),
    val selectedNetwork: PixieDustNetwork? = null,
    val result: PixieDustResult? = null,
    val consoleLines: List<String> = emptyList(),
    val errorMessage: String? = null,
    val selectedInterface: String = "wlan0",
    val interfacesAvailable: Boolean = false
) {
    fun copyWithConsole(line: String): PixieDustUiState {
        val updated = mutableListOf<String>()
        updated.addAll(consoleLines)
        updated.add(line)
        if (updated.size > 500) updated.removeAt(0)
        return copy(consoleLines = updated)
    }
}

/**
 * Red detectada con información WPS para auditoría.
 */
data class PixieDustNetwork(
    val ssid: String,
    val bssid: String,
    val frequency: Int,
    val level: Int,
    val wpsCapable: Boolean,
    val wpsLocked: Boolean,
    val capabilities: String
) {
    val displayName: String
        get() = if (ssid.isNotEmpty()) ssid else "<hidden>"

    fun isAuditable(): Boolean = wpsCapable && !wpsLocked
}

/**
 * Eventos de una sola vez (navegación, diálogos, toasts).
 */
sealed class PixieDustEvent {
    data class ShowMessage(val message: String) : PixieDustEvent()
    data class AttackFinished(val success: Boolean, val message: String) : PixieDustEvent()
    data class CopyToClipboard(val text: String) : PixieDustEvent()
}

/**
 * ViewModel de la pantalla Pixie Dust.
 *
 * Sigue las pautas de [android-viewmodel]: StateFlow con explicit backing field para el
 * estado persistente, SharedFlow para eventos transitorios, y toda la lógica de E/S se
 * ejecuta en [ioDispatcher] (inyectado) vía [viewModelScope].
 */
class PixieDustViewModel(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val TAG = "PixieDustViewModel"
    private val appContext: Context = application.applicationContext

    // --- Estado persistente (StateFlow, explicit backing field) ---
    private val _uiState = MutableStateFlow(PixieDustUiState())
    val uiState: StateFlow<PixieDustUiState> = _uiState.asStateFlow()

    // --- Eventos transitorios (SharedFlow, no se replayan) ---
    private val _uiEvent = MutableSharedFlow<PixieDustEvent>(replay = 0, extraBufferCapacity = 1)
    val uiEvent: SharedFlow<PixieDustEvent> = _uiEvent

    private val wifiManager: WifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var attackJob: Job? = null
    private var scanJob: Job? = null
    private var runner: PixieDustRunner? = null

    init {
        loadInterfaces()
    }

    // ========================================================================
    // Interfaces
    // ========================================================================

    fun loadInterfaces() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, errorMessage = null) }
            try {
                val ifaces = availableWifiInterfaces()
                val state = _uiState.value
                _uiState.value = state.copy(
                    isScanning = false,
                    interfacesAvailable = ifaces.isNotEmpty(),
                    selectedInterface = if (ifaces.isNotEmpty()) ifaces.first() else state.selectedInterface
                )
                addConsoleLine("Interfaces disponibles: ${ifaces.joinToString(", ")}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load interfaces", e)
                _uiState.update { it.copy(isScanning = false, errorMessage = e.message) }
            }
        }
    }

    private suspend fun availableWifiInterfaces(): List<String> = withContext(ioDispatcher) {
        val candidates = listOf("wlan0", "wlan1", "p2p0", "swlan0")
        val result = mutableListOf<String>()
        for (iface in candidates) {
            val cmd = "ip link show $iface 2>/dev/null"
            val res = execSuRaw(cmd)
            if (res.exitCode == 0 && res.output.isNotEmpty()) {
                result.add(iface)
            }
        }
        result
    }

    // ========================================================================
    // Selección de red y ataque
    // ========================================================================

    fun scanNetworks() {
        if (_uiState.value.isScanning || _uiState.value.isAttacking) return

        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, networks = emptyList(), errorMessage = null) }
            addConsoleLine("Iniciando escaneo de redes WiFi...")
            try {
                val networks = withContext(ioDispatcher) {
                    scanWifiNetworks()
                }
                val state = _uiState.value
                _uiState.value = state.copy(
                    isScanning = false,
                    networks = networks
                )
                addConsoleLine("Escaneo completado: ${networks.size} red(es) encontrada(s).")
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                _uiState.update { it.copy(isScanning = false, errorMessage = "Escaneo falló: ${e.message}") }
            }
        }
    }

    private suspend fun scanWifiNetworks(): List<PixieDustNetwork> = withContext(ioDispatcher) {
        if (!wifiManager.isWifiEnabled) {
            // Intentar activar WiFi para escanear (requiere CHANGE_WIFI_STATE).
            wifiManager.setWifiEnabled(true)
            delay(2000)
        }
        val success = wifiManager.startScan()
        if (!success) {
            throw IllegalStateException("No se pudo iniciar el escaneo WiFi")
        }
        delay(3000) // Esperar resultados
        val results = wifiManager.scanResults
        results.mapNotNull { result ->
            val caps = result.capabilities
            val wpsCapable = caps.contains("WPS", ignoreCase = true)
            val wpsLocked = caps.contains("WPS_LOCKED", ignoreCase = true) ||
                (caps.contains("[WPS]", ignoreCase = true) && caps.contains("LOCKED", ignoreCase = true))
            PixieDustNetwork(
                ssid = result.SSID,
                bssid = result.BSSID,
                frequency = result.frequency,
                level = result.level,
                wpsCapable = wpsCapable,
                wpsLocked = wpsLocked,
                capabilities = caps
            )
        }
    }

    fun selectNetwork(network: PixieDustNetwork) {
        _uiState.update { it.copy(selectedNetwork = network, errorMessage = null, result = null) }
        addConsoleLine("Red seleccionada: ${network.displayName} (${network.bssid})")
    }

    fun startAttack(disableWifi: Boolean) {
        val network = _uiState.value.selectedNetwork
        if (network == null) {
            _uiState.update { it.copy(errorMessage = "Selecciona una red primero") }
            return
        }
        if (!network.isAuditable()) {
            _uiState.update { it.copy(errorMessage = appContext.getString(R.string.pixie_wps_required)) }
            return
        }
        if (_uiState.value.isAttacking) return

        runner = PixieDustRunner(appContext, ioDispatcher)

        viewModelScope.launch {
            _uiState.update { it.copy(isAttacking = true, result = null, errorMessage = null) }
            addConsoleLine("Iniciando auditoría Pixie Dust contra ${network.displayName}...")
            addConsoleLine("BSSID: ${network.bssid} | Interfaz: ${_uiState.value.selectedInterface}")

            try {
                val result = runner!!.runAttack(
                    bssid = network.bssid,
                    interfaceName = _uiState.value.selectedInterface,
                    ssid = network.displayName.takeIf { it != "<hidden>" },
                    freqMHz = network.frequency,
                    disableWifiBeforeAttack = disableWifi
                )

                _uiState.update { it.copy(isAttacking = false, result = result) }
                when (result) {
                    is PixieDustResult.Success -> {
                        addConsoleLine("[+] Auditoría exitosa — PIN: ${result.wpsPin ?: "N/A"}")
                        result.wpaPsk?.let { addConsoleLine("[+] Clave WPA: $it") }
                        emitEvent(PixieDustEvent.AttackFinished(true,
                            "PIN WPS: ${result.wpsPin}\n${result.wpaPsk?.let { "Clave WPA: $it" } ?: ""}"))
                    }
                    is PixieDustResult.Failure -> {
                        addConsoleLine("[-] Auditoría falló: ${result.reason}")
                        emitEvent(PixieDustEvent.AttackFinished(false, result.reason))
                    }
                    is PixieDustResult.InProgress -> {}
                    is PixieDustResult.Cancelled -> {
                        addConsoleLine("[!] Auditoría cancelada por el usuario")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Attack failed", e)
                _uiState.update {
                    it.copy(isAttacking = false, errorMessage = "Error: ${e.message}")
                }
                addConsoleLine("[-] Error: ${e.message}")
            }
        }
    }

    fun stopAttack() {
        attackJob?.cancel()
        scanJob?.cancel()
        runner?.cancel(_uiState.value.errorMessage != null) { addConsoleLine(it) }
        _uiState.update { it.copy(isAttacking = false, isScanning = false) }
        addConsoleLine("[!] Detenido por el usuario")
    }

    // ========================================================================
    // Consola
    // ========================================================================

    fun addConsoleLine(line: String) {
        _uiState.update { it.copyWithConsole(line) }
    }

    fun clearConsole() {
        _uiState.update { it.copy(consoleLines = emptyList()) }
    }

    fun clearResult() {
        _uiState.update { it.copy(result = null, errorMessage = null) }
    }

    // ========================================================================
    // Utilidades privadas
    // ========================================================================

    private suspend fun execSuRaw(command: String): SuResult = withContext(ioDispatcher) {
        var process: java.lang.Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = StringBuilder()
            val error = StringBuilder()
            val outThread = Thread {
                BufferedReader(InputStreamReader(process!!.inputStream)).forEachLine { output.appendLine(it) }
            }
            val errThread = Thread {
                BufferedReader(InputStreamReader(process!!.errorStream)).forEachLine { error.appendLine(it) }
            }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()
            val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            outThread.join(1000)
            errThread.join(1000)
            if (!finished) process.destroyForcibly()
            SuResult(if (finished) process.exitValue() else -1, output.toString().trim(), error.toString().trim())
        } catch (e: Exception) {
            SuResult(-1, "", e.message ?: "Unknown error")
        } finally {
            try { process?.destroyForcibly() } catch (_: Exception) {}
        }
    }

    private data class SuResult(
        val exitCode: Int,
        val output: String,
        val error: String = ""
    )

    /** Emite un evento transitorio desde fuera de un coroutine (p. ej. desde la UI). */
    fun emitEvent(event: PixieDustEvent) = viewModelScope.launch { _uiEvent.emit(event) }

    companion object {
        private const val TAG = "PixieDustViewModel"
    }
}
