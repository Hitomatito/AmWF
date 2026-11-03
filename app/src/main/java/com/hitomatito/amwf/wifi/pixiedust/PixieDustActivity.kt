/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hitomatito.amwf.R
import com.hitomatito.amwf.databinding.ActivityPixieDustBinding
import com.hitomatito.amwf.databinding.SheetPixieNetworkPickerBinding
import kotlinx.coroutines.launch

/**
 * Pantalla de auditoría WPS Pixie Dust.
 *
 * Flujo en tres pasos visibles: escanear redes, buscar/elegir una red con WPS y
 * ejecutar la auditoría. La acción principal solo se habilita cuando hay un
 * objetivo auditable seleccionado.
 *
 * Requiere acceso root, permiso de escaneo WiFi (ACCESS_FINE_LOCATION en todas
 * las versiones y NEARBY_WIFI_DEVICES desde Android 13) y los binarios nativos
 * empaquetados en assets/wifi/ (ver BINARIES_SETUP.md).
 */
class PixieDustActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPixieDustBinding
    private lateinit var viewModel: PixieDustViewModel
    private lateinit var networkAdapter: PixieNetworkAdapter
    private lateinit var sessionRenderer: PixieSessionRenderer

    // Hoja inferior que contiene la búsqueda, los filtros y la lista de redes.
    private var sheetBinding: SheetPixieNetworkPickerBinding? = null
    private var sheetDialog: BottomSheetDialog? = null

    // Evita repetir auto-scroll y animaciones en cada emisión de estado.
    private var wasAttacking = false
    private var hadSession = false
    private var lastListSignature = ""
    private var activeFilter = NetworkFilter.ALL

    // Permite retomar el escaneo al volver de Ajustes (permisos o ubicación).
    // Se guarda en el estado de la instancia para sobrevivir a rotaciones y a
    // la muerte del proceso mientras el usuario está fuera de la app.
    private var awaitingSettingsResult = false

    // Distingue «nunca se pidió» de «el usuario bloqueó el permiso». Antes del
    // primer intento shouldShowRequestPermissionRationale también devuelve
    // false, así que no se puede consultar isPermanentlyDenied sin este flag.
    private var permissionRequestAttempted = false

    private val permissionLauncher = registerForActivityResult(
        RequestMultiplePermissions()
    ) { granted ->
        permissionRequestAttempted = true
        // Se valida solo lo obligatorio: COARSE se pide junto a FINE para que
        // Android 12 muestre la opción «precisa», pero el sistema puede
        // dejarlo sin conceder aunque FINE sí se haya concedido.
        val allGranted = WifiScanPermissions.requiredPermissions()
            .all { granted[it] == true }
        when {
            !allGranted -> handleDeniedScanPermissions()
            !WifiScanPermissions.isLocationServicesEnabled(this) ->
                showLocationServicesDialog()
            else -> startScanAndOpenPicker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingSettingsResult = savedInstanceState
            ?.getBoolean(STATE_AWAITING_SETTINGS) ?: false
        permissionRequestAttempted = savedInstanceState
            ?.getBoolean(STATE_PERMISSION_REQUESTED) ?: false
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityPixieDustBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sessionRenderer = PixieSessionRenderer(this, binding)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom)
            )
            insets
        }

        viewModel = ViewModelProvider(this).get(PixieDustViewModel::class.java)

        setupNetworkList()
        setupObservers()
        setupListeners()

        // La pantalla debe abrirse arriba y sin que el buscador robe el foco.
        binding.rootLayout.isFocusableInTouchMode = true
        binding.rootLayout.requestFocus()

        viewModel.loadInterfaces()

        binding.scrollContent.post { binding.scrollContent.scrollTo(0, 0) }
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingSettingsResult) return
        awaitingSettingsResult = false
        when {
            WifiScanPermissions.hasRuntimePermissions(this) &&
                WifiScanPermissions.isLocationServicesEnabled(this) ->
                startScanAndOpenPicker()
            !WifiScanPermissions.hasRuntimePermissions(this) ->
                handleDeniedScanPermissions()
            else -> showLocationServicesDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_SETTINGS, awaitingSettingsResult)
        outState.putBoolean(STATE_PERMISSION_REQUESTED, permissionRequestAttempted)
    }

    override fun onDestroy() {
        sheetDialog?.dismiss()
        super.onDestroy()
    }

    private fun setupNetworkList() {
        networkAdapter = PixieNetworkAdapter { network ->
            if (network.isAuditable()) {
                viewModel.selectNetwork(network)
                closeNetworkPicker()
                // Elegir la red es la acción: no hace falta un botón extra.
                sessionRenderer.consoleExpanded = true
                viewModel.startAttack(binding.swDisableWifi.isChecked)
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.pixie_wps_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Abre la hoja inferior con el buscador, los filtros y la lista de redes.
     * Se reutiliza la misma instancia mientras está visible para no perder el
     * scroll ni el estado de los filtros.
     */
    private fun openNetworkPicker() {
        if (sheetDialog?.isShowing == true) return

        val sheet = SheetPixieNetworkPickerBinding.inflate(layoutInflater)
        sheet.rvNetworks.layoutManager = LinearLayoutManager(this)
        sheet.rvNetworks.adapter = networkAdapter

        // Evita que el buscador robe el foco y abra el teclado al mostrar la hoja.
        sheet.root.isFocusableInTouchMode = true
        sheet.root.requestFocus()

        sheet.etSearch.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
        }

        // La hoja se infla de nuevo en cada apertura: refleja el filtro activo.
        when (activeFilter) {
            NetworkFilter.ALL -> sheet.chipGroupFilters.check(R.id.chipAll)
            NetworkFilter.AUDITABLE -> sheet.chipGroupFilters.check(R.id.chipAuditable)
            NetworkFilter.BAND_24 -> sheet.chipGroupFilters.check(R.id.chipBand24)
            NetworkFilter.BAND_5 -> sheet.chipGroupFilters.check(R.id.chipBand5)
            NetworkFilter.DUAL_BAND -> sheet.chipGroupFilters.check(R.id.chipDualBand)
        }

        sheet.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            val newFilter = when (checkedIds.firstOrNull()) {
                R.id.chipAuditable -> NetworkFilter.AUDITABLE
                R.id.chipBand24 -> NetworkFilter.BAND_24
                R.id.chipBand5 -> NetworkFilter.BAND_5
                R.id.chipDualBand -> NetworkFilter.DUAL_BAND
                else -> NetworkFilter.ALL
            }
            if (newFilter != activeFilter) {
                activeFilter = newFilter
                render(viewModel.uiState.value)
            }
        }

        sheet.btnCloseSheet.setOnClickListener { closeNetworkPicker() }
        sheet.btnRescanSheet.setOnClickListener {
            ensureScanPermissions()
        }
        sheet.btnScanEmpty.setOnClickListener {
            ensureScanPermissions()
        }

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener {
            sheetBinding = null
            sheetDialog = null
            lastListSignature = ""
        }
        dialog.setOnShowListener {
            val behavior = dialog.behavior
            // Deja ver la pantalla principal detrás: se comporta como hoja, no
            // como una pantalla completa.
            behavior.maxHeight = (resources.displayMetrics.heightPixels * 0.92f).toInt()
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        sheetBinding = sheet
        sheetDialog = dialog
        render(viewModel.uiState.value)
        dialog.show()
    }

    private fun closeNetworkPicker() {
        sheetDialog?.dismiss()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    when (event) {
                        is PixieDustEvent.ShowMessage -> Toast.makeText(
                            this@PixieDustActivity, event.message, Toast.LENGTH_LONG
                        ).show()
                        is PixieDustEvent.CopyToClipboard -> {
                            val clipboard =
                                getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("pixie_dust", event.text)
                            )
                            Toast.makeText(
                                this@PixieDustActivity,
                                getString(R.string.pixie_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnScan.setOnClickListener {
            // Si ya hay resultados, el botón solo abre el selector; volver a
            // escanear queda como acción explícita dentro de la hoja.
            val current = viewModel.uiState.value
            if (current.hasScanned && !current.isScanning) {
                openNetworkPicker()
            } else {
                ensureScanPermissions()
            }
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopAttack()
        }

        // El registro se queda "pegado" al final solo si el usuario no lo ha
        // desplazado hacia arriba; así no se mueve la vista mientras lee.
        // También se aísla del scroll de la página para que el gesto no salte
        // de la consola al contenido exterior.
        binding.svConsole.isNestedScrollingEnabled = false
        binding.svConsole.setOnScrollChangeListener { view, _, _, _, _ ->
            val scroll = view as NestedScrollView
            val content = scroll.getChildAt(0)
            sessionRenderer.consoleAtBottom = content == null ||
                scroll.scrollY + scroll.height >= content.height - 24
        }

        binding.btnToggleConsole.setOnClickListener {
            sessionRenderer.consoleExpanded = !sessionRenderer.consoleExpanded
            if (sessionRenderer.consoleExpanded) sessionRenderer.consoleAtBottom = true
            sessionRenderer.update(viewModel.uiState.value)
        }

        binding.btnClearConsole.setOnClickListener {
            sessionRenderer.consoleExpanded = true
            sessionRenderer.consoleAtBottom = true
            viewModel.clearConsole()
            sessionRenderer.update(viewModel.uiState.value)
        }

        binding.btnCopyLog.setOnClickListener {
            val log = viewModel.uiState.value.consoleLines.joinToString("\n")
            if (log.isNotBlank()) {
                viewModel.emitEvent(PixieDustEvent.CopyToClipboard(log))
            }
        }

        binding.btnCopyPin.setOnClickListener {
            val pin = (viewModel.uiState.value.result as? PixieDustResult.Success)?.wpsPin
            if (!pin.isNullOrBlank()) {
                viewModel.emitEvent(PixieDustEvent.CopyToClipboard(pin))
            }
        }

        binding.btnCopyPsk.setOnClickListener {
            val psk = (viewModel.uiState.value.result as? PixieDustResult.Success)?.wpaPsk
            if (!psk.isNullOrBlank()) {
                viewModel.emitEvent(PixieDustEvent.CopyToClipboard(psk))
            }
        }

        binding.btnCopyResult.setOnClickListener {
            val result = viewModel.uiState.value.result
            val text = resultText(result)
            if (text.isNotBlank()) {
                viewModel.emitEvent(PixieDustEvent.CopyToClipboard(text))
            }
        }

        binding.btnChangeNetworkResult.setOnClickListener {
            if (viewModel.uiState.value.networks.isEmpty()) {
                ensureScanPermissions()
            } else {
                openNetworkPicker()
            }
        }

        // Tras un fallo la acción prioritaria es buscar redes de nuevo, sin
        // pasar por el selector de una lista que ya no sirve.
        binding.btnRescanResult.setOnClickListener {
            ensureScanPermissions()
        }
    }

    /**
     * Comprueba permisos y servicios de ubicación antes de escanear. Si falta
     * algo, muestra el diálogo correspondiente; si todo está listo, arranca el
     * escaneo y abre el selector de redes.
     */
    private fun ensureScanPermissions() {
        if (!WifiScanPermissions.hasRuntimePermissions(this)) {
            // Antes del primer intento no se puede distinguir «nunca pedido» de
            // «bloqueado»: shouldShowRequestPermissionRationale devuelve false
            // en ambos casos. Solo tras un intento fallido se consulta.
            if (permissionRequestAttempted &&
                WifiScanPermissions.isPermanentlyDenied(this)
            ) {
                showPermissionSettingsDialog()
            } else {
                permissionRequestAttempted = true
                permissionLauncher.launch(WifiScanPermissions.runtimePermissions())
            }
            return
        }
        if (!WifiScanPermissions.isLocationServicesEnabled(this)) {
            showLocationServicesDialog()
            return
        }
        startScanAndOpenPicker()
    }

    private fun startScanAndOpenPicker() {
        viewModel.clearConsole()
        viewModel.scanNetworks()
        openNetworkPicker()
    }

    /**
     * El usuario denegó la solicitud. Distinguimos entre una denegación
     * recuperable (se puede volver a preguntar) y una definitiva (solo se
     * puede conceder desde Ajustes).
     */
    private fun handleDeniedScanPermissions() {
        if (WifiScanPermissions.isPermanentlyDenied(this)) {
            showPermissionSettingsDialog()
        } else {
            showPermissionRationaleDialog()
        }
    }

    private fun showPermissionRationaleDialog() {
        // El texto debe reflejar lo que realmente falta: en Android 13+ puede
        // estar concedido NEARBY y denegada solo la ubicación.
        val needsNearby = WifiScanPermissions.deniedPermissions(this)
            .contains(Manifest.permission.NEARBY_WIFI_DEVICES)
        val message = if (needsNearby) {
            getString(R.string.pixie_permission_rationale_nearby)
        } else {
            getString(R.string.pixie_permission_rationale_location)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pixie_permission_title)
            .setMessage(message)
            .setPositiveButton(R.string.pixie_permission_retry) { _, _ ->
                permissionLauncher.launch(WifiScanPermissions.runtimePermissions())
            }
            .setNegativeButton(R.string.pixie_permission_cancel, null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pixie_permission_blocked_title)
            .setMessage(R.string.pixie_permission_blocked_message)
            .setPositiveButton(R.string.pixie_open_settings) { _, _ ->
                awaitingSettingsResult = true
                startActivity(WifiScanPermissions.appSettingsIntent(this))
            }
            .setNegativeButton(R.string.pixie_permission_cancel, null)
            .show()
    }

    private fun showLocationServicesDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pixie_location_services_title)
            .setMessage(R.string.pixie_location_services_message)
            .setPositiveButton(R.string.pixie_enable_location) { _, _ ->
                awaitingSettingsResult = true
                startActivity(WifiScanPermissions.locationSettingsIntent())
            }
            .setNegativeButton(R.string.pixie_permission_cancel, null)
            .show()
    }

    private fun render(state: PixieDustUiState) {
        binding.btnScan.isEnabled = !state.isScanning && !state.isAttacking
        binding.btnScan.text = when {
            state.isScanning -> getString(R.string.pixie_scanning)
            state.selectedNetwork != null -> getString(R.string.pixie_change_network_button)
            state.hasScanned -> getString(R.string.pixie_choose_network_button)
            else -> getString(R.string.pixie_scan_networks)
        }

        binding.tvInterfaces.text = if (state.interfacesAvailable) {
            getString(R.string.pixie_interface_value, state.selectedInterface)
        } else {
            getString(R.string.pixie_no_interfaces)
        }

        val auditableCount = state.networks.count { it.isAuditable() }
        val bandsBySsid = state.networks
            .groupBy { it.displayName }
            .mapValues { (_, networks) -> networks.map { it.band() }.toSet() }
        val dualBandCount = bandsBySsid.count { it.value.size > 1 }
        if (state.isScanning && activeFilter != NetworkFilter.ALL) {
            activeFilter = NetworkFilter.ALL
            sheetBinding?.chipGroupFilters?.check(R.id.chipAll)
        }
        binding.tvScanStatus.text = when {
            state.isScanning -> getString(R.string.pixie_scan_status_scanning)
            state.hasScanned -> getString(
                R.string.pixie_scan_status_done,
                state.networks.size,
                auditableCount
            )
            else -> getString(R.string.pixie_scan_status_idle)
        }

        updateNetworkPicker(state, bandsBySsid, dualBandCount)

        // Durante la auditoría la pantalla se centra en la sesión: se ocultan
        // las tarjetas de escaneo/opciones para que el registro no compita ni
        // desplace la vista.
        val running = state.isAttacking
        binding.cardScan.visibility = if (running) View.GONE else View.VISIBLE
        binding.cardOptions.visibility = if (running) View.GONE else View.VISIBLE
        binding.tvLegalFooter.visibility = if (running) View.GONE else View.VISIBLE

        val busy = state.isAttacking || state.isScanning
        // La barra solo existe para poder detener lo que está en marcha.
        binding.bottomBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnStop.isEnabled = busy
        binding.btnStop.text = if (state.isScanning && !state.isAttacking) {
            getString(R.string.pixie_stop_scan)
        } else {
            getString(R.string.pixie_stop_attack)
        }

        binding.swDisableWifi.isEnabled = !busy

        binding.tvError.text = state.errorMessage ?: ""
        binding.tvError.visibility =
            if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE

        sessionRenderer.update(state)

        // Un único salto deliberado al empezar: la sesión queda arriba. Durante
        // la auditoría no se vuelve a mover la página. Al cerrar la sesión
        // (elegir otra red) se vuelve al inicio para mostrar "Redes cercanas".
        val sessionEnded = state.attackStartedAt == null && hadSession
        hadSession = state.attackStartedAt != null
        if ((state.isAttacking && !wasAttacking) || sessionEnded) {
            binding.scrollContent.post { binding.scrollContent.scrollTo(0, 0) }
        }
        wasAttacking = state.isAttacking
    }

    /**
     * Pinta la hoja inferior. Si no está abierta no hay nada que actualizar: la
     * lista se sincroniza al abrirla con [openNetworkPicker].
     */
    private fun updateNetworkPicker(
        state: PixieDustUiState,
        bandsBySsid: Map<String, Set<WifiBand>>,
        dualBandCount: Int
    ) {
        val sheet = sheetBinding ?: return

        // Mantener el campo sincronizado cuando el ViewModel limpia la búsqueda.
        val query = state.searchQuery
        if (sheet.etSearch.text?.toString() != query) {
            sheet.etSearch.setText(query)
            sheet.etSearch.setSelection(query.length)
        }

        val filtered = filterNetworks(state.networks, query, bandsBySsid)
        val searchVisible = state.networks.isNotEmpty() && !state.isScanning
        sheet.btnRescanSheet.isEnabled = !state.isScanning && !state.isAttacking
        sheet.btnRescanSheet.setText(
            if (state.isScanning) R.string.pixie_scanning else R.string.pixie_sheet_rescan_short
        )
        sheet.tilSearch.visibility = if (searchVisible) View.VISIBLE else View.GONE
        sheet.filterScroll.visibility = if (searchVisible) View.VISIBLE else View.GONE
        sheet.tvNetworksCount.visibility = if (searchVisible) View.VISIBLE else View.GONE
        if (searchVisible) {
            sheet.tvNetworksCount.text = when {
                activeFilter != NetworkFilter.ALL -> getString(
                    R.string.pixie_networks_filtered,
                    filtered.size,
                    state.networks.size
                )
                dualBandCount > 0 -> getString(
                    R.string.pixie_networks_summary,
                    state.networks.size,
                    dualBandCount
                )
                else -> getString(R.string.pixie_networks_count, state.networks.size)
            }
        }

        networkAdapter.submitList(
            filtered.map {
                val allBands = bandsBySsid[it.displayName].orEmpty()
                PixieNetworkAdapter.Item(
                    network = it,
                    selected = it.bssid == state.selectedNetwork?.bssid,
                    otherBands = allBands - it.band()
                )
            }
        )
        resetListScrollIfResultsChanged(sheet, filtered)
        updateEmptyState(sheet, state, filtered)
    }

    private fun updateEmptyState(
        sheet: SheetPixieNetworkPickerBinding,
        state: PixieDustUiState,
        filtered: List<PixieDustNetwork>
    ) {
        val isEmpty = filtered.isEmpty()
        sheet.layoutNetworksEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        sheet.rvNetworks.visibility = if (isEmpty) View.GONE else View.VISIBLE
        sheet.pbScanEmpty.visibility = if (state.isScanning) View.VISIBLE else View.GONE
        sheet.ivEmptyIcon.visibility = if (state.isScanning) View.GONE else View.VISIBLE

        // El botón solo tiene sentido cuando de verdad no hay redes: si el
        // vacío viene de un filtro o una búsqueda, repetir el escaneo no ayuda.
        sheet.btnScanEmpty.visibility =
            if (!state.isScanning && state.networks.isEmpty()) View.VISIBLE else View.GONE

        val titleRes: Int
        val messageRes: Int
        when {
            state.isScanning -> {
                titleRes = R.string.pixie_empty_scanning_title
                messageRes = R.string.pixie_empty_scanning_message
            }
            state.networks.isEmpty() && !state.hasScanned -> {
                titleRes = R.string.pixie_empty_idle_title
                messageRes = R.string.pixie_empty_idle_message
            }
            state.networks.isEmpty() -> {
                titleRes = R.string.pixie_empty_none_title
                messageRes = R.string.pixie_empty_none_message
            }
            else -> {
                titleRes = R.string.pixie_empty_no_match_title
                messageRes = R.string.pixie_empty_no_match_message
            }
        }
        sheet.tvEmptyTitle.setText(titleRes)
        sheet.tvEmptyMessage.setText(messageRes)
    }

    /**
     * La lista conserva su scroll entre emisiones. Si cambian los resultados (no
     * solo la selección), volvemos arriba para no dejar una fila recortada.
     */
    private fun resetListScrollIfResultsChanged(
        sheet: SheetPixieNetworkPickerBinding,
        filtered: List<PixieDustNetwork>
    ) {
        val signature = filtered.joinToString("|") { it.bssid }
        if (signature == lastListSignature) return
        lastListSignature = signature
        sheet.rvNetworks.post { sheet.rvNetworks.scrollToPosition(0) }
    }

    private fun filterNetworks(
        networks: List<PixieDustNetwork>,
        query: String,
        bandsBySsid: Map<String, Set<WifiBand>>
    ): List<PixieDustNetwork> {
        val byFilter = when (activeFilter) {
            NetworkFilter.ALL -> networks
            NetworkFilter.AUDITABLE -> networks.filter { it.isAuditable() }
            NetworkFilter.BAND_24 -> networks.filter { it.band() == WifiBand.BAND_24_GHZ }
            NetworkFilter.BAND_5 -> networks.filter { it.band() == WifiBand.BAND_5_GHZ }
            NetworkFilter.DUAL_BAND -> networks.filter {
                (bandsBySsid[it.displayName]?.size ?: 0) > 1
            }
        }

        val trimmed = query.trim()
        if (trimmed.isEmpty()) return byFilter

        val lower = trimmed.lowercase()
        val compactBssid = lower.replace(":", "").replace(" ", "")
        return byFilter.filter { network ->
            network.displayName.lowercase().contains(lower) ||
                network.bssid.lowercase().contains(lower) ||
                (compactBssid.length >= 3 &&
                    network.bssid.replace(":", "").lowercase().contains(compactBssid))
        }
    }

    private fun resultText(result: PixieDustResult?): String = when (result) {
        is PixieDustResult.Success -> getString(
            R.string.pixie_result_success,
            result.wpsPin ?: getString(R.string.pixie_not_available),
            result.wpaPsk ?: getString(R.string.pixie_not_available)
        )
        is PixieDustResult.Failure ->
            getString(R.string.pixie_result_failure, result.reason)
        null -> ""
    }

    private enum class NetworkFilter {
        ALL,
        AUDITABLE,
        BAND_24,
        BAND_5,
        DUAL_BAND
    }

    private companion object {
        private const val STATE_AWAITING_SETTINGS = "awaiting_settings_result"
        private const val STATE_PERMISSION_REQUESTED = "permission_request_attempted"
    }
}
