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
import com.hitomatito.amwf.ShellExecutor
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * Estado de la pantalla de auditoría Pixie Dust.
 *
 * Se agrupa por área (escaneo y sesión) para que cada actualización copie solo
 * el bloque que cambia, en lugar de un `copy` con diez campos sueltos.
 */
data class PixieDustUiState(
    val scan: ScanState = ScanState(),
    val session: SessionState = SessionState(),
    val consoleLines: List<String> = emptyList(),
    val errorMessage: String? = null,
    // Aviso persistente de binarios nativos. Se mantiene aunque un escaneo o
    // una selección sobrescriban el error transitorio de errorMessage.
    val nativeError: String? = null,
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

/** Resultados del escaneo y selección de red de la hoja inferior. */
data class ScanState(
    val isScanning: Boolean = false,
    val networks: List<PixieDustNetwork> = emptyList(),
    val selectedNetwork: PixieDustNetwork? = null,
    val searchQuery: String = "",
    val hasScanned: Boolean = false
)

/** Sesión de auditoría en curso o la última ejecutada. */
data class SessionState(
    val isAttacking: Boolean = false,
    val stage: PixieDustStage? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val cancelled: Boolean = false,
    val result: PixieDustResult? = null
)

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
    val capabilities: String,
    // Etiqueta localizada para SSID vacío. Se inyecta desde el ViewModel para
    // que este modelo siga siendo independiente de Android/recursos.
    val hiddenSsidLabel: String = ""
) {
    val displayName: String
        get() = ssid.ifEmpty { hiddenSsidLabel }

    fun isAuditable(): Boolean = wpsCapable && !wpsLocked
}

/**
 * Eventos de una sola vez (navegación, diálogos, toasts).
 */
sealed class PixieDustEvent {
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
     *
     * Se escribe desde el hilo principal y se lee en `onStage`, que llega desde
     * el dispatcher de E/S del runner: necesita visibilidad entre hilos.
     */
    @Volatile
    private var cleanupInProgress = false

    // ========================================================================
    // Interfaces
    // ========================================================================

    fun loadInterfaces() {
        viewModelScope.launch {
            try {
                val ifaces = availableWifiInterfaces()
                val binariesError = nativeBinariesError()
                _uiState.value = interfacesLoadedState(_uiState.value, ifaces, binariesError)
                addConsoleLine(appContext.getString(R.string.pixie_console_interfaces_available, ifaces.joinToString(", ")))
            } catch (e: Exception) {
                ShellExecutor.logE(TAG, "Failed to load interfaces", e)
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
        NativeBinaries.missingBinariesMessage(appContext, missing)
    }

    // ========================================================================
    // Selección de red y ataque
    // ========================================================================

    fun scanNetworks() {
        if (!canStartScan(_uiState.value.scan, _uiState.value.session, cleanupInProgress)) {
            return
        }

        // Se marca el estado antes de lanzar la corrutina para que un doble
        // toque no arranque dos escaneos simultáneos.
        _uiState.update { scanStartingState(it) }
        scanJob = viewModelScope.launch {
            addConsoleLine(appContext.getString(R.string.pixie_console_scan_started))
            try {
                val networks = withContext(ioDispatcher) {
                    scanWifiNetworks()
                }
                // Las redes auditables primero y, dentro de cada grupo, las de
                // mayor señal: el usuario ve antes lo que puede seleccionar.
                val sorted = sortNetworksForList(networks)
                _uiState.update { scanFinishedState(it, sorted) }
                addConsoleLine(appContext.getString(R.string.pixie_console_scan_finished, sorted.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShellExecutor.logE(TAG, "Scan failed", e)
                _uiState.update {
                    scanFailedState(
                        it,
                        appContext.getString(
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
        if (_uiState.value.scan.searchQuery == query) return
        _uiState.update { it.copy(scan = it.scan.copy(searchQuery = query)) }
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
        val wpsCapable = isWpsCapable(caps)
        val wpsLocked = isWpsLocked(caps)
        return PixieDustNetwork(
            ssid = SSID,
            bssid = BSSID,
            frequency = frequency,
            level = level,
            wpsCapable = wpsCapable,
            wpsLocked = wpsLocked,
            capabilities = caps,
            hiddenSsidLabel = appContext.getString(R.string.pixie_hidden_network)
        )
    }

    fun selectNetwork(network: PixieDustNetwork) {
        _uiState.update { selectionState(it, network) }
        addConsoleLine(appContext.getString(R.string.pixie_console_network_selected, network.displayName, network.bssid))
    }

    fun startAttack(disableWifi: Boolean) {
        val network = _uiState.value.scan.selectedNetwork
        when (attackStartError(network, _uiState.value.session, _uiState.value.scan, cleanupInProgress)) {
            AttackStartError.NO_NETWORK -> {
                _uiState.update {
                    it.copy(errorMessage = appContext.getString(R.string.pixie_select_network))
                }
                return
            }
            AttackStartError.NOT_AUDITABLE -> {
                _uiState.update {
                    it.copy(errorMessage = appContext.getString(R.string.pixie_wps_required))
                }
                return
            }
            AttackStartError.BUSY -> return
            null -> Unit
        }
        // NO_NETWORK/NOT_AUDITABLE ya retornan; aquí red no-nula garantizada.
        val selectedNetwork = network ?: return

        // La consola muestra los hitos del ataque; las líneas crudas del
        // supplicant se filtran para no inundar la UI (siguen en logcat).
        // Última etapa real del ataque: RESTORING se emite siempre en el
        // cleanup, así que no sirve para saber dónde falló la sesión.
        var lastActiveStage = PixieDustStage.PREPARING
        val activeRunner = PixieDustRunner.create(
            context = appContext,
            ioDispatcher = ioDispatcher,
            onLog = { line ->
                if (!line.startsWith("[supplicant]")) addConsoleLine(line)
            },
            onStage = { newStage ->
                if (newStage != PixieDustStage.RESTORING) lastActiveStage = newStage
                // cleanup() emite RESTORING también al cancelar: no debe pisar
                // la fase real que se estaba ejecutando (ver el helper).
                _uiState.update {
                    if (shouldApplyStageUpdate(
                            it.session.isAttacking,
                            cleanupInProgress,
                            newStage
                        )
                    ) {
                        it.copy(session = it.session.copy(stage = newStage))
                    } else {
                        it
                    }
                }
            }
        )
        runner = activeRunner

        // Marcar el estado antes de lanzar la corrutina: un doble toque no
        // debe crear dos runners que se maten los procesos entre sí.
        _uiState.update { attackStartingState(it, System.currentTimeMillis()) }

        attackJob = viewModelScope.launch {
            addConsoleLine(appContext.getString(R.string.pixie_console_attack_started, selectedNetwork.displayName))
            addConsoleLine(appContext.getString(R.string.pixie_console_attack_target, selectedNetwork.bssid, _uiState.value.selectedInterface))

            try {
                val result = activeRunner.runAttack(
                    bssid = selectedNetwork.bssid,
                    interfaceName = _uiState.value.selectedInterface,
                    ssid = selectedNetwork.ssid.takeIf { it.isNotBlank() },
                    freqMHz = selectedNetwork.frequency,
                    disableWifiBeforeAttack = disableWifi
                )

                val finishedAt = System.currentTimeMillis()
                val stage = attackOutcomeStage(result, lastActiveStage)
                _uiState.update { attackFinishedState(it, result, stage, finishedAt) }
                when (result) {
                    is PixieDustResult.Success -> {
                        addConsoleLine(appContext.getString(R.string.pixie_console_attack_success, result.wpsPin ?: "N/A"))
                        result.wpaPsk?.let { addConsoleLine(appContext.getString(R.string.pixie_console_wpa_key, it)) }
                    }
                    is PixieDustResult.Failure -> {
                        addConsoleLine(appContext.getString(R.string.pixie_console_attack_failed, result.reason))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShellExecutor.logE(TAG, "Attack failed", e)
                addConsoleLine(appContext.getString(R.string.pixie_console_error, e.message ?: e.javaClass.simpleName))
                val failure = PixieDustResult.Failure(
                    bssid = selectedNetwork.bssid,
                    reason = appContext.getString(
                        R.string.pixie_error_detail,
                        e.message ?: ""
                    ),
                    rawOutput = ""
                )
                _uiState.update {
                    attackFailedState(it, failure, lastActiveStage, System.currentTimeMillis())
                }
            } finally {
                if (runner === activeRunner) runner = null
            }
        }
    }

    fun stopAttack() {
        if (cleanupInProgress) return
        val wasAttacking = _uiState.value.session.isAttacking
        val wasScanning = _uiState.value.scan.isScanning
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
            // Guardamos la fase real antes de que cleanup() emita RESTORING.
            val stageBeforeStop = _uiState.value.session.stage
                ?.takeIf { it != PixieDustStage.RESTORING }
            cleanupInProgress = true
            addConsoleLine(appContext.getString(R.string.pixie_console_stopping))
            jobToCancel.cancel()
            viewModelScope.launch {
                try {
                    withContext(ioDispatcher) { runnerToCancel?.cancel() }
                    jobToCancel.join()
                } finally {
                    if (runner === runnerToCancel) runner = null
                    cleanupInProgress = false
                    _uiState.update {
                        attackStoppedState(
                            it,
                            wasAttacking,
                            stageBeforeStop,
                            System.currentTimeMillis()
                        )
                    }
                }
                addConsoleLine(appContext.getString(R.string.pixie_console_stopped))
            }
        } else {
            runner = null
            _uiState.update {
                attackStoppedState(it, wasAttacking, null, System.currentTimeMillis())
            }
            if (wasScanning) addConsoleLine(appContext.getString(R.string.pixie_console_stopped))
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
        _uiState.update { clearResultState(it) }
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
