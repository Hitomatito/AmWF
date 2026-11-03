/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * Estado de la pantalla de auditoría Pixie Dust.
 */
data class PixieDustUiState(
    val isScanning: Boolean = false,
    val isAttacking: Boolean = false,
    val networks: List<PixieDustNetwork> = emptyList(),
    val selectedNetwork: PixieDustNetwork? = null,
    val searchQuery: String = "",
    val hasScanned: Boolean = false,
    val result: PixieDustResult? = null,
    val consoleLines: List<String> = emptyList(),
    val errorMessage: String? = null,
    val attackStage: PixieDustStage? = null,
    val attackStartedAt: Long? = null,
    val attackFinishedAt: Long? = null,
    val attackCancelled: Boolean = false,
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
    data class CopyToClipboard(val text: String) : PixieDustEvent()
}

/**
 * ViewModel de la pantalla Pixie Dust.
 *
 * Sigue las pautas de [android-viewmodel]: StateFlow con explicit backing field para el
 * estado persistente, SharedFlow para eventos transitorios, y toda la lógica de E/S se
 * ejecuta en [ioDispatcher] (inyectado) vía [viewModelScope].
 *
 * No ejecuta procesos de shell directamente: toda la ejecución root está encapsulada en
 * [PixieDustRunner] y el ejecutor de shell de la app, evitando Feature Envy.
 */
class PixieDustViewModel @JvmOverloads constructor(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val TAG = "PixieDustViewModel"
    private val appContext: Context = application.applicationContext

    // SimpleDateFormat no es thread-safe: el runner registra líneas desde
    // varios hilos (IO y lector del supplicant).
    private val consoleTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val consoleTimeLock = Any()

    // --- Estado persistente (StateFlow, explicit backing field) ---
    private val _uiState = MutableStateFlow(PixieDustUiState())
    val uiState: StateFlow<PixieDustUiState> = _uiState.asStateFlow()

    // --- Eventos transitorios (SharedFlow, no se replayan) ---
    private val _uiEvent = MutableSharedFlow<PixieDustEvent>(replay = 0, extraBufferCapacity = 1)
    val uiEvent: SharedFlow<PixieDustEvent> = _uiEvent

    private val wifiManager: WifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivityManager: ConnectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var attackJob: Job? = null
    private var scanJob: Job? = null
    private var runner: PixieDustRunner? = null

    /**
     * true mientras se están matando procesos/restaurando el WiFi tras un
     * ataque detenido. Bloquea nuevos ataques hasta que la limpieza termine:
     * dos runners concurrentes se matarían los procesos entre sí.
     */
    private var cleanupInProgress = false

    // ========================================================================
    // Interfaces
    // ========================================================================

    fun loadInterfaces() {
        viewModelScope.launch {
            try {
                val ifaces = availableWifiInterfaces()
                val binariesError = nativeBinariesError()
                val state = _uiState.value
                _uiState.value = state.copy(
                    interfacesAvailable = ifaces.isNotEmpty(),
                    selectedInterface = if (ifaces.isNotEmpty()) ifaces.first() else state.selectedInterface,
                    errorMessage = binariesError ?: state.errorMessage
                )
                addConsoleLine("Interfaces disponibles: ${ifaces.joinToString(", ")}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load interfaces", e)
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    /**
     * Detecta interfaces WiFi sin root. `NetworkInterface` enumera wlan0,
     * wlan1, p2p0, swlan0… aunque no haya una red activa; si la hay, su
     * interfaz se coloca primero.
     */
    private suspend fun availableWifiInterfaces(): List<String> = withContext(ioDispatcher) {
        val interfaces = linkedSetOf<String>()
        try {
            val network = connectivityManager.activeNetwork
            if (network != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    connectivityManager.getLinkProperties(network)?.interfaceName
                        ?.takeIf { it.isNotBlank() }
                        ?.let(interfaces::add)
                }
            }
        } catch (_: Exception) {
            // Sin red WiFi activa: se usan las interfaces del sistema.
        }
        try {
            val names = NetworkInterface.getNetworkInterfaces()?.toList()
                ?.map { it.name }
                ?.filter { name ->
                    name.startsWith("wlan") || name == "p2p0" || name.startsWith("swlan")
                }
                .orEmpty()
            // wlan0 primero; después el resto de wlan* y por último las
            // interfaces auxiliares (p2p0/swlan0), como hacía el sondeo root.
            names.filter { it == "wlan0" }.forEach(interfaces::add)
            names.filter { it != "wlan0" && it.startsWith("wlan") }
                .sorted()
                .forEach(interfaces::add)
            names.filter { !it.startsWith("wlan") }
                .sorted()
                .forEach(interfaces::add)
        } catch (_: Exception) {
            // La enumeración puede fallar en algunos dispositivos; se conserva
            // lo detectado vía ConnectivityManager.
        }
        interfaces.toList()
    }

    /**
     * Comprueba los binarios al abrir la pantalla para avisar antes de que el
     * usuario elija una red, en lugar de fallar solo al lanzar el ataque.
     */
    private suspend fun nativeBinariesError(): String? = withContext(ioDispatcher) {
        NativeBinaries.ensure(appContext)
        val missing = NativeBinaries.missingCriticalBinaries(appContext)
        if (missing.isEmpty()) return@withContext null
        val reasonRes = if (NativeBinaries.archSuffix() == "_32") {
            R.string.pixie_native_missing_32
        } else {
            R.string.pixie_native_missing
        }
        appContext.getString(reasonRes, NativeBinaries.displayNames(missing))
    }

    // ========================================================================
    // Selección de red y ataque
    // ========================================================================

    fun scanNetworks() {
        if (_uiState.value.isScanning || _uiState.value.isAttacking || cleanupInProgress) return

        // Se marca el estado antes de lanzar la corrutina para que un doble
        // toque no arranque dos escaneos simultáneos.
        _uiState.update {
            it.copy(
                isScanning = true,
                networks = emptyList(),
                selectedNetwork = null,
                searchQuery = "",
                hasScanned = false,
                result = null,
                errorMessage = null,
                consoleLines = emptyList(),
                attackStage = null,
                attackStartedAt = null,
                attackFinishedAt = null,
                attackCancelled = false
            )
        }
        scanJob = viewModelScope.launch {
            addConsoleLine("Iniciando escaneo de redes WiFi...")
            try {
                val networks = withContext(ioDispatcher) {
                    scanWifiNetworks()
                }
                // Las redes auditables primero y, dentro de cada grupo, las de
                // mayor señal: el usuario ve antes lo que puede seleccionar.
                val sorted = networks.sortedWith(
                    compareByDescending<PixieDustNetwork> { it.isAuditable() }
                        .thenByDescending { it.level }
                )
                _uiState.update {
                    it.copy(isScanning = false, networks = sorted, hasScanned = true)
                }
                addConsoleLine("Escaneo completado: ${sorted.size} red(es) encontrada(s).")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        hasScanned = true,
                        errorMessage = appContext.getString(
                            R.string.pixie_scan_failed_detail,
                            e.message ?: appContext.getString(R.string.pixie_scan_failed)
                        )
                    )
                }
            }
        }
    }

    /** Actualiza el filtro de búsqueda de la lista de redes. */
    fun setSearchQuery(query: String) {
        if (_uiState.value.searchQuery == query) return
        _uiState.update { it.copy(searchQuery = query) }
    }

    // WifiScanPermissions valida ubicación precisa en todas las versiones y
    // NEARBY_WIFI_DEVICES en Android 13+. Lint no puede inferirlo del helper.
    @SuppressLint("MissingPermission")
    private suspend fun scanWifiNetworks(): List<PixieDustNetwork> = withContext(ioDispatcher) {
        if (!WifiScanPermissions.hasRuntimePermissions(appContext)) {
            throw SecurityException(
                appContext.getString(R.string.pixie_location_permission_required)
            )
        }
        if (!WifiScanPermissions.isLocationServicesEnabled(appContext)) {
            throw IllegalStateException(
                appContext.getString(R.string.pixie_location_services_required)
            )
        }
        if (!wifiManager.isWifiEnabled) {
            // En Android 10+ una app normal no puede encender el WiFi
            // (setWifiEnabled es un no-op). Se pide al usuario que lo active
            // en lugar de fallar más tarde con un error críptico.
            throw IllegalStateException(
                appContext.getString(R.string.pixie_wifi_disabled)
            )
        }
        if (!wifiManager.startScan()) {
            // startScan() devuelve false si hay un escaneo en curso o si el
            // sistema lo limita. Si hay resultados cacheados, se usan; si no,
            // se informa del fallo.
            val cached = wifiManager.scanResults
            if (cached.isEmpty()) {
                throw IllegalStateException(
                    appContext.getString(R.string.pixie_scan_failed)
                )
            }
            return@withContext cached.map { it.toPixieDustNetwork() }
        }
        delay(3000) // Esperar resultados
        val results = wifiManager.scanResults
        results.map { it.toPixieDustNetwork() }
    }

    private fun ScanResult.toPixieDustNetwork(): PixieDustNetwork {
        val caps = capabilities
        val wpsCapable = caps.contains("WPS", ignoreCase = true)
        val wpsLocked = caps.contains("WPS_LOCKED", ignoreCase = true) ||
            (caps.contains("[WPS]", ignoreCase = true) && caps.contains("LOCKED", ignoreCase = true))
        return PixieDustNetwork(
            ssid = SSID,
            bssid = BSSID,
            frequency = frequency,
            level = level,
            wpsCapable = wpsCapable,
            wpsLocked = wpsLocked,
            capabilities = caps
        )
    }

    fun selectNetwork(network: PixieDustNetwork) {
        _uiState.update {
            it.copy(
                selectedNetwork = network,
                errorMessage = null,
                result = null,
                attackStage = null,
                attackStartedAt = null,
                attackFinishedAt = null,
                attackCancelled = false
            )
        }
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
        if (_uiState.value.isAttacking || _uiState.value.isScanning || cleanupInProgress) return

        // La consola muestra los hitos del ataque; las líneas crudas del
        // supplicant se filtran para no inundar la UI (siguen en logcat).
        // Última etapa real del ataque: RESTORING se emite siempre en el
        // cleanup, así que no sirve para saber dónde falló la sesión.
        var lastActiveStage = PixieDustStage.PREPARING
        val activeRunner = PixieDustRunner(
            context = appContext,
            ioDispatcher = ioDispatcher,
            onLog = { line ->
                if (!line.startsWith("[supplicant]")) addConsoleLine(line)
            },
            onStage = { newStage ->
                if (newStage != PixieDustStage.RESTORING) lastActiveStage = newStage
                // La etapa también puede llegar desde cleanup() al cancelar.
                _uiState.update { if (it.isAttacking) it.copy(attackStage = newStage) else it }
            }
        )
        runner = activeRunner

        // Marcar el estado antes de lanzar la corrutina: un doble toque no
        // debe crear dos runners que se maten los procesos entre sí.
        _uiState.update {
            it.copy(
                isAttacking = true,
                result = null,
                errorMessage = null,
                consoleLines = emptyList(),
                attackStage = PixieDustStage.PREPARING,
                attackStartedAt = System.currentTimeMillis(),
                attackFinishedAt = null,
                attackCancelled = false
            )
        }

        attackJob = viewModelScope.launch {
            addConsoleLine("Iniciando auditoría Pixie Dust contra ${network.displayName}...")
            addConsoleLine("BSSID: ${network.bssid} | Interfaz: ${_uiState.value.selectedInterface}")

            try {
                val result = activeRunner.runAttack(
                    bssid = network.bssid,
                    interfaceName = _uiState.value.selectedInterface,
                    ssid = network.ssid.takeIf { it.isNotBlank() },
                    freqMHz = network.frequency,
                    disableWifiBeforeAttack = disableWifi
                )

                val finishedAt = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        isAttacking = false,
                        result = result,
                        // Solo un éxito completa todas las fases. En un fallo se
                        // conserva la etapa real para que el tracker no pinte la
                        // fase de credenciales como la que falló.
                        attackStage = if (result is PixieDustResult.Success) {
                            PixieDustStage.COMPLETED
                        } else {
                            lastActiveStage
                        },
                        attackFinishedAt = finishedAt
                    )
                }
                when (result) {
                    is PixieDustResult.Success -> {
                        addConsoleLine("[+] Auditoría exitosa — PIN: ${result.wpsPin ?: "N/A"}")
                        result.wpaPsk?.let { addConsoleLine("[+] Clave WPA: $it") }
                    }
                    is PixieDustResult.Failure -> {
                        addConsoleLine("[-] Auditoría falló: ${result.reason}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Attack failed", e)
                addConsoleLine("[-] Error: ${e.message}")
                _uiState.update {
                    it.copy(
                        isAttacking = false,
                        result = PixieDustResult.Failure(
                            bssid = network.bssid,
                            reason = appContext.getString(
                                R.string.pixie_error_detail,
                                e.message ?: ""
                            ),
                            rawOutput = ""
                        ),
                        attackStage = lastActiveStage,
                        attackFinishedAt = System.currentTimeMillis()
                    )
                }
            } finally {
                if (runner === activeRunner) runner = null
            }
        }
    }

    fun stopAttack() {
        if (cleanupInProgress) return
        val wasAttacking = _uiState.value.isAttacking
        val wasScanning = _uiState.value.isScanning
        val jobToCancel = attackJob
        val runnerToCancel = runner
        attackJob = null
        scanJob?.cancel()
        scanJob = null

        if (wasAttacking && jobToCancel != null) {
            // La limpieza ejecuta `su` y puede tardar varios segundos. Se
            // mantiene isAttacking hasta que termine para que no se pueda
            // lanzar otro ataque mientras el anterior aún mata procesos.
            // `runner` se conserva hasta el final para que onCleared() también
            // pueda cancelarlo si la pantalla se cierra durante la limpieza.
            cleanupInProgress = true
            addConsoleLine("[!] Deteniendo auditoría y limpiando procesos...")
            jobToCancel.cancel()
            viewModelScope.launch {
                try {
                    withContext(ioDispatcher) { runnerToCancel?.cancel() }
                    jobToCancel.join()
                } finally {
                    if (runner === runnerToCancel) runner = null
                    cleanupInProgress = false
                    _uiState.update {
                        if (wasAttacking) {
                            it.copy(
                                isAttacking = false,
                                isScanning = false,
                                attackStage = it.attackStage ?: PixieDustStage.RESTORING,
                                attackFinishedAt = System.currentTimeMillis(),
                                attackCancelled = true
                            )
                        } else {
                            it.copy(isAttacking = false, isScanning = false)
                        }
                    }
                }
                addConsoleLine("[!] Detenido por el usuario")
            }
        } else {
            runner = null
            _uiState.update { it.copy(isAttacking = false, isScanning = false) }
            if (wasScanning) addConsoleLine("[!] Detenido por el usuario")
        }
    }

    private fun cancelRunnerInBackground(target: PixieDustRunner?) {
        if (target == null) return
        Thread { target.cancel() }.apply {
            isDaemon = true
            start()
        }
    }

    override fun onCleared() {
        attackJob?.cancel()
        scanJob?.cancel()
        val runnerToCancel = runner
        runner = null
        attackJob = null
        scanJob = null
        // El ViewModel desaparece: la limpieza no puede esperar en
        // viewModelScope (ya cancelado), así que se hace en un hilo aparte.
        cancelRunnerInBackground(runnerToCancel)
        super.onCleared()
    }

    // ========================================================================
    // Consola
    // ========================================================================

    fun addConsoleLine(line: String) {
        val stamp = synchronized(consoleTimeLock) {
            consoleTimeFormat.format(Date())
        }
        _uiState.update { it.copyWithConsole("$stamp  $line") }
    }

    fun clearConsole() {
        _uiState.update { it.copy(consoleLines = emptyList()) }
    }

    fun clearResult() {
        _uiState.update {
            it.copy(
                result = null,
                errorMessage = null,
                attackStage = null,
                attackStartedAt = null,
                attackFinishedAt = null,
                attackCancelled = false
            )
        }
    }

    // ========================================================================
    // Utilidades privadas
    // ========================================================================

    /** Emite un evento transitorio desde fuera de un coroutine (p. ej. desde la UI). */
    fun emitEvent(event: PixieDustEvent) = viewModelScope.launch { _uiEvent.emit(event) }

    companion object {
        private const val TAG = "PixieDustViewModel"
    }
}
