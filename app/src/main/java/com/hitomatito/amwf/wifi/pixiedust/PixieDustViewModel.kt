/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.hitomatito.amwf.LocaleHelper
import com.hitomatito.amwf.R
import com.hitomatito.amwf.ShellExecutor
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
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
    // El applicationContext conserva el locale del proceso y no refleja el
    // idioma elegido en MainActivity. Todo texto visible pasa por aquí.
    private val localeContext: Context = LocaleHelper.applyLocale(application.applicationContext)

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
                // Sin INTERNET (por diseño) NetworkInterface no enumera nada y
                // ConnectivityManager solo responde con WiFi conectado: una
                // lista vacía no es un error; la interfaz real se resuelve con
                // root al iniciar la auditoría.
                if (ifaces.isEmpty()) {
                    addConsoleLine(localeContext.getString(R.string.pixie_console_interfaces_none))
                } else {
                    addConsoleLine(localeContext.getString(R.string.pixie_console_interfaces_available, ifaces.joinToString(", ")))
                }
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
            // La conectividad manda: si hay red WiFi activa, esa interfaz abre
            // la lista (en dispositivos multi-radio puede no ser wlan0). Sin
            // red activa: wlan0 primero, después el resto de wlan* y por último
            // las auxiliares (p2p0/swlan0).
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
     * Resuelve con root la interfaz real cuando la detección sin root no
     * encontró nada: `ls /sys/class/net` no depende del permiso INTERNET ni de
     * una conexión activa. Se llama al iniciar la auditoría, no al abrir la
     * pantalla, para que el prompt de su aparezca cuando el usuario ya espera
     * actividad root. Devuelve la interfaz resuelta (y la aplica al estado,
     * incluido `interfacesAvailable`, para que la fila de la UI deje de
     * mostrar «No se encontró interfaz») o null si no hubo cambio.
     */
    private suspend fun resolveInterfaceWithRoot(): String? = withContext(ioDispatcher) {
        if (_uiState.value.interfacesAvailable) return@withContext null
        val result = PixieProcessSupport.execSu("ls /sys/class/net", timeoutSeconds = 3)
        if (result.exitCode != 0) return@withContext null
        val resolved = interfacesFromSysfsListing(result.output).firstOrNull()
            ?: return@withContext null
        if (resolved != _uiState.value.selectedInterface) {
            addConsoleLine(localeContext.getString(R.string.pixie_console_interface_resolved, resolved))
        }
        _uiState.update {
            it.copy(interfacesAvailable = true, selectedInterface = resolved)
        }
        resolved
    }

    /**
     * Comprueba los binarios al abrir la pantalla para avisar antes de que el
     * usuario elija una red, en lugar de fallar solo al lanzar el ataque.
     */
    private suspend fun nativeBinariesError(): String? = withContext(ioDispatcher) {
        NativeBinaries.ensure(appContext)
        val missing = NativeBinaries.missingCriticalBinaries(appContext)
        NativeBinaries.missingBinariesMessage(localeContext, missing)
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
            addConsoleLine(localeContext.getString(R.string.pixie_console_scan_started))
            try {
                val networks = withContext(ioDispatcher) {
                    scanWifiNetworks()
                }
                // Las redes auditables primero y, dentro de cada grupo, las de
                // mayor señal: el usuario ve antes lo que puede seleccionar.
                val sorted = sortNetworksForList(networks)
                _uiState.update { scanFinishedState(it, sorted) }
                addConsoleLine(
                    localeContext.resources.getQuantityString(
                        R.plurals.pixie_console_scan_finished,
                        sorted.size,
                        sorted.size
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShellExecutor.logE(TAG, "Scan failed", e)
                _uiState.update {
                    scanFailedState(
                        it,
                        localeContext.getString(
                            R.string.pixie_scan_failed_detail,
                            e.message ?: localeContext.getString(R.string.pixie_scan_failed)
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
                localeContext.getString(R.string.pixie_location_permission_required)
            )
        }
        if (!WifiScanPermissions.isLocationServicesEnabled(appContext)) {
            throw IllegalStateException(
                localeContext.getString(R.string.pixie_location_services_required)
            )
        }
        if (!wifiManager.isWifiEnabled) {
            // En Android 10+ una app normal no puede encender el WiFi
            // (setWifiEnabled es un no-op). Se pide al usuario que lo active
            // en lugar de fallar más tarde con un error críptico.
            throw IllegalStateException(
                localeContext.getString(R.string.pixie_wifi_disabled)
            )
        }
        if (!wifiManager.startScan()) {
            // startScan() devuelve false si hay un escaneo en curso o si el
            // sistema lo limita. Si hay resultados cacheados, se usan; si no,
            // se informa del fallo.
            val cached = wifiManager.scanResults
            if (cached.isEmpty()) {
                throw IllegalStateException(
                    localeContext.getString(R.string.pixie_scan_failed)
                )
            }
            return@withContext cached.map { it.toPixieDustNetwork() }
        }
        // En vez de un delay fijo: esperar el broadcast de resultados con un
        // plazo amplio. Un escaneo rápido devuelve antes de 3 s y uno lento
        // (firmware pausado, canales DFS) ya no reporta una lista vacía.
        if (!awaitScanResults(SCAN_RESULTS_TIMEOUT_MS)) {
            // El plazo venció o el sistema limitó el escaneo: se muestran los
            // resultados que haya (pueden ser parciales) o el error honesto.
            val partial = wifiManager.scanResults
            if (partial.isEmpty()) {
                throw IllegalStateException(
                    localeContext.getString(R.string.pixie_scan_failed)
                )
            }
            return@withContext partial.map { it.toPixieDustNetwork() }
        }
        val results = wifiManager.scanResults
        results.map { it.toPixieDustNetwork() }
    }

    /**
     * Espera el broadcast de resultados de escaneo con un plazo máximo.
     *
     * Antes esto era un `delay(3000)` fijo: un escaneo rápido perdía tiempo y
     * uno lento devolvía una lista vacía o rancia. Con el broadcast la espera
     * es exacta; el plazo cubre un broadcast que nunca llega (limitación
     * agresiva del sistema).
     *
     * Devuelve false si vence el plazo. El receiver se registra NOT_EXPORTED
     * (solo interesa el broadcast del sistema, que sigue llegando con ese
     * flag) y se da de baja en los tres caminos: broadcast recibido, plazo
     * vencido y cancelación de la corrutina.
     */
    private suspend fun awaitScanResults(timeoutMs: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                    runCatching { appContext.unregisterReceiver(this) }
                    // Pre-API 28 el broadcast no trae el extra y solo llegaba
                    // con resultados: su ausencia se lee como éxito, que es
                    // como siempre se comportaron esos dispositivos.
                    val updated = intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED,
                        true
                    )
                    if (continuation.isActive) continuation.resume(updated)
                }
            }
            continuation.invokeOnCancellation {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            handler.postDelayed(
                {
                    // Puede llegar tarde (el broadcast ya se recibió): el
                    // unregister falla controlado y el resume es no-op.
                    runCatching { appContext.unregisterReceiver(receiver) }
                    if (continuation.isActive) continuation.resume(false)
                },
                timeoutMs
            )
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
            hiddenSsidLabel = localeContext.getString(R.string.pixie_hidden_network)
        )
    }

    fun selectNetwork(network: PixieDustNetwork) {
        _uiState.update { selectionState(it, network) }
        addConsoleLine(localeContext.getString(R.string.pixie_console_network_selected, network.displayName, network.bssid))
    }

    fun startAttack() {
        val network = _uiState.value.scan.selectedNetwork
        when (attackStartError(network, _uiState.value.session, _uiState.value.scan, cleanupInProgress)) {
            AttackStartError.NO_NETWORK -> {
                _uiState.update {
                    it.copy(errorMessage = localeContext.getString(R.string.pixie_select_network))
                }
                return
            }
            AttackStartError.NOT_AUDITABLE -> {
                _uiState.update {
                    it.copy(errorMessage = localeContext.getString(R.string.pixie_wps_required))
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
            context = localeContext,
            ioDispatcher = ioDispatcher,
            onLog = { line ->
                // La consola muestra hitos; la salida cruda de los procesos
                // (supplicant y stderr de pixiewps) se queda en el log de
                // auditoría, no en la UI. Debe filtrar los mismos prefijos
                // que excluye persistAuditLog, o la consola se inunda.
                if (!line.startsWith("[supplicant]") &&
                    !line.startsWith("[pixiewps-stderr]")
                ) {
                    addConsoleLine(line)
                }
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
            // Una limpieza huérfana de una salida anterior (back a mitad de
            // auditoría y reentrada) puede seguir viva en su hilo: su pkill y
            // su rm -rf del ctrl dir matarían los procesos de esta auditoría.
            // Se espera a que termine antes de lanzar nada root.
            if (PixieCleanupGate.isOccupied()) {
                // La espera es rara pero puede durar: que la consola lo diga
                // desde el principio, no solo si vence el plazo.
                addConsoleLine(localeContext.getString(R.string.pixie_console_cleanup_wait))
                if (!PixieCleanupGate.awaitFree(ORPHAN_CLEANUP_WAIT_MS)) {
                    addConsoleLine(localeContext.getString(R.string.pixie_console_cleanup_busy))
                }
            }
            // La interfaz se resuelve aquí, con root, solo si la detección sin
            // root no la encontró (NetworkInterface exige INTERNET, que la app
            // no declara; ConnectivityManager solo responde conectado). Así el
            // prompt de su aparece cuando el usuario ya espera actividad root
            // y la consola registra la interfaz real antes de lanzar.
            resolveInterfaceWithRoot()
            addConsoleLine(localeContext.getString(R.string.pixie_console_attack_started, selectedNetwork.displayName))
            addConsoleLine(localeContext.getString(R.string.pixie_console_attack_target, selectedNetwork.bssid, _uiState.value.selectedInterface))

            try {
                val result = activeRunner.runAttack(
                    bssid = selectedNetwork.bssid,
                    interfaceName = _uiState.value.selectedInterface,
                    ssid = selectedNetwork.ssid.takeIf { it.isNotBlank() },
                    freqMHz = selectedNetwork.frequency,
                    levelDbm = selectedNetwork.level
                )

                val finishedAt = System.currentTimeMillis()
                val stage = attackOutcomeStage(result, lastActiveStage)
                _uiState.update { attackFinishedState(it, result, stage, finishedAt) }
                when (result) {
                    is PixieDustResult.Success -> {
                        addConsoleLine(localeContext.getString(R.string.pixie_console_attack_success, result.wpsPin ?: "N/A"))
                        result.wpaPsk?.let { addConsoleLine(localeContext.getString(R.string.pixie_console_wpa_key, it)) }
                    }
                    is PixieDustResult.Failure -> {
                        addConsoleLine(localeContext.getString(R.string.pixie_console_attack_failed, result.reason))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ShellExecutor.logE(TAG, "Attack failed", e)
                addConsoleLine(localeContext.getString(R.string.pixie_console_error, e.message ?: e.javaClass.simpleName))
                val failure = PixieDustResult.Failure(
                    bssid = selectedNetwork.bssid,
                    reason = localeContext.getString(
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
            addConsoleLine(localeContext.getString(R.string.pixie_console_stopping))
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
                addConsoleLine(localeContext.getString(R.string.pixie_console_stopped))
            }
        } else {
            runner = null
            _uiState.update {
                attackStoppedState(it, wasAttacking, null, System.currentTimeMillis())
            }
            if (wasScanning) addConsoleLine(localeContext.getString(R.string.pixie_console_stopped))
        }
    }

    private fun cancelRunnerInBackground(target: PixieDustRunner?) {
        if (target == null) return
        // El gate es visible para el siguiente ViewModel: una auditoría nueva
        // no debe lanzarse mientras esta limpieza siga matando procesos y
        // borrando el ctrl dir (startAttack la espera antes de arrancar).
        PixieCleanupGate.enter()
        Thread {
            try {
                target.cancel()
            } finally {
                PixieCleanupGate.exit()
            }
        }.apply {
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

        /**
         * Techo de espera de una limpieza huérfana antes de lanzar la auditoría
         * de todos modos: cubre pkill + rm -rf + la restauración del WiFi con
         * sus reintentos y relecturas de asentamiento.
         */
        private const val ORPHAN_CLEANUP_WAIT_MS = 45_000L

        /**
         * Plazo de espera del broadcast de resultados de escaneo: cubre un
         * escaneo lento del firmware sin dejar al usuario esperando para
         * siempre si el broadcast nunca llega.
         */
        private const val SCAN_RESULTS_TIMEOUT_MS = 10_000L
    }
}
