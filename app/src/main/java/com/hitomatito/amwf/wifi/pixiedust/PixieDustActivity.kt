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
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    // Hoja inferior que contiene la búsqueda, los filtros y la lista de redes.
    private var sheetBinding: SheetPixieNetworkPickerBinding? = null
    private var sheetDialog: BottomSheetDialog? = null

    // Evita repetir auto-scroll y animaciones en cada emisión de estado.
    private var wasAttacking = false
    private var hadSession = false
    private var lastListSignature = ""
    private var consoleExpanded = true
    private var consoleAtBottom = true
    private var lastConsoleSignature = 0
    private var chronometerRunning = false
    private var activeFilter = NetworkFilter.ALL

    // Permite retomar el escaneo al volver de Ajustes (permisos o ubicación).
    private var awaitingSettingsResult = false

    private val permissionLauncher = registerForActivityResult(
        RequestMultiplePermissions()
    ) { granted ->
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
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityPixieDustBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                consoleExpanded = true
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
            consoleAtBottom = content == null ||
                scroll.scrollY + scroll.height >= content.height - 24
        }

        binding.btnToggleConsole.setOnClickListener {
            consoleExpanded = !consoleExpanded
            if (consoleExpanded) consoleAtBottom = true
            updateSession(viewModel.uiState.value)
        }

        binding.btnClearConsole.setOnClickListener {
            consoleExpanded = true
            consoleAtBottom = true
            viewModel.clearConsole()
            updateSession(viewModel.uiState.value)
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
            permissionLauncher.launch(WifiScanPermissions.runtimePermissions())
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

        updateSession(state)

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

    /**
     * Pinta la tarjeta de sesión: estado global, porcentaje por etapas, tiempo,
     * fases macro, resultado final y registro en vivo.
     *
     * El porcentaje es una estimación por hitos ([PixieDustStage.progress]) y se
     * acompaña siempre de la etapa actual para que el valor no se lea como un
     * progreso continuo.
     */
    private fun updateSession(state: PixieDustUiState) {
        val startedAt = state.attackStartedAt
        if (startedAt == null) {
            binding.cardSession.visibility = View.GONE
            stopChronometer()
            return
        }
        binding.cardSession.visibility = View.VISIBLE

        val running = state.isAttacking
        val cancelled = !running && state.attackCancelled
        val success = !running && !cancelled && state.result is PixieDustResult.Success
        val failure = !running && !cancelled && state.result is PixieDustResult.Failure

        bindSessionTarget(state)
        bindSessionStatus(running, success, cancelled)
        bindSessionProgress(state, success, cancelled, failure)
        bindSessionChronometer(startedAt, state.attackFinishedAt, running)
        bindSessionPhases(state, running, success, failure, cancelled)
        bindSessionResult(state, success, cancelled)
        bindConsole(state)
    }

    private fun bindSessionTarget(state: PixieDustUiState) {
        val target = state.selectedNetwork
        binding.tvSessionTarget.text = target?.displayName
            ?: getString(R.string.pixie_target_none_title)
        binding.tvSessionBssid.text = if (target != null) {
            getString(
                R.string.pixie_session_target_meta,
                target.bssid,
                target.band().label(this)
            )
        } else {
            getString(R.string.pixie_target_none_message)
        }
    }

    private fun bindSessionStatus(running: Boolean, success: Boolean, cancelled: Boolean) {
        val labelRes: Int
        val backgroundRes: Int
        val foregroundRes: Int
        when {
            running -> {
                labelRes = R.string.pixie_session_status_running
                backgroundRes = R.color.md_theme_primaryContainer
                foregroundRes = R.color.md_theme_onPrimaryContainer
            }
            success -> {
                labelRes = R.string.pixie_session_status_success
                backgroundRes = R.color.monitor_active
                foregroundRes = R.color.md_theme_onPrimary
            }
            cancelled -> {
                labelRes = R.string.pixie_session_status_cancelled
                backgroundRes = R.color.md_theme_surfaceVariant
                foregroundRes = R.color.md_theme_onSurfaceVariant
            }
            else -> {
                labelRes = R.string.pixie_session_status_failure
                backgroundRes = R.color.error_container
                foregroundRes = R.color.on_error_container
            }
        }
        binding.tvSessionStatus.setText(labelRes)
        binding.tvSessionStatus.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, backgroundRes)
        )
        binding.tvSessionStatus.setTextColor(ContextCompat.getColor(this, foregroundRes))
    }

    private fun bindSessionProgress(
        state: PixieDustUiState,
        success: Boolean,
        cancelled: Boolean,
        failure: Boolean
    ) {
        val stage = state.attackStage
        val progress = when {
            success -> 100
            failure -> 100
            cancelled -> stage?.progress ?: 0
            else -> stage?.progress ?: PixieDustStage.PREPARING.progress
        }
        binding.progressSession.setProgressCompat(progress, true)
        binding.progressSession.setIndicatorColor(
            ContextCompat.getColor(
                this,
                when {
                    success -> R.color.monitor_active
                    failure -> R.color.managed
                    cancelled -> R.color.warning
                    else -> R.color.md_theme_primary
                }
            )
        )
        binding.tvSessionPercent.text = getString(R.string.pixie_percent, progress)

        val titleRes: Int
        val detailRes: Int
        when {
            success -> {
                titleRes = R.string.pixie_stage_complete
                detailRes = R.string.pixie_stage_complete_detail
            }
            cancelled -> {
                titleRes = R.string.pixie_stage_cancelled
                detailRes = R.string.pixie_stage_cancelled_detail
            }
            failure -> {
                titleRes = R.string.pixie_stage_failed
                detailRes = R.string.pixie_stage_failed_detail
            }
            else -> {
                titleRes = stageTitleRes(stage)
                detailRes = stageDetailRes(stage)
            }
        }
        binding.tvSessionStage.setText(titleRes)
        binding.tvSessionStageDetail.setText(detailRes)
        binding.progressSession.contentDescription = getString(
            R.string.pixie_session_progress_description,
            progress,
            getString(titleRes)
        )
    }

    private fun stageTitleRes(stage: PixieDustStage?): Int = when (stage) {
        PixieDustStage.DISABLING_WIFI -> R.string.pixie_stage_interface
        PixieDustStage.STARTING_SUPPLICANT -> R.string.pixie_stage_supplicant
        PixieDustStage.REGISTERING_WPS -> R.string.pixie_stage_registering
        PixieDustStage.CAPTURING_M3 -> R.string.pixie_stage_capturing
        PixieDustStage.ANALYZING -> R.string.pixie_stage_analyzing
        PixieDustStage.EXTRACTING_PSK -> R.string.pixie_stage_psk
        PixieDustStage.RESTORING -> R.string.pixie_stage_restoring
        PixieDustStage.COMPLETED -> R.string.pixie_stage_complete
        PixieDustStage.PREPARING, null -> R.string.pixie_stage_preparing
    }

    private fun stageDetailRes(stage: PixieDustStage?): Int = when (stage) {
        PixieDustStage.DISABLING_WIFI -> R.string.pixie_stage_interface_detail
        PixieDustStage.STARTING_SUPPLICANT -> R.string.pixie_stage_supplicant_detail
        PixieDustStage.REGISTERING_WPS -> R.string.pixie_stage_registering_detail
        PixieDustStage.CAPTURING_M3 -> R.string.pixie_stage_capturing_detail
        PixieDustStage.ANALYZING -> R.string.pixie_stage_analyzing_detail
        PixieDustStage.EXTRACTING_PSK -> R.string.pixie_stage_psk_detail
        PixieDustStage.RESTORING -> R.string.pixie_stage_restoring_detail
        PixieDustStage.COMPLETED -> R.string.pixie_stage_complete_detail
        PixieDustStage.PREPARING, null -> R.string.pixie_stage_preparing_detail
    }

    private fun bindSessionChronometer(startedAt: Long, finishedAt: Long?, running: Boolean) {
        if (running) {
            if (!chronometerRunning) {
                // Chronometer usa la base de elapsedRealtime, no la hora de
                // pared: convertimos el inicio guardado (wall clock) a esa base.
                val sinceStart = System.currentTimeMillis() - startedAt
                binding.chronSession.base = SystemClock.elapsedRealtime() - sinceStart
                binding.chronSession.start()
                chronometerRunning = true
            }
        } else {
            stopChronometer()
            // Con la sesión terminada el cronómetro debe mostrar el total, no
            // reiniciarse a 00:00 al volver a pintar o recrear la pantalla.
            val elapsed = (finishedAt ?: System.currentTimeMillis()) - startedAt
            binding.chronSession.base =
                SystemClock.elapsedRealtime() - elapsed.coerceAtLeast(0L)
        }
    }

    private fun stopChronometer() {
        if (!chronometerRunning) return
        binding.chronSession.stop()
        chronometerRunning = false
    }

    private fun bindSessionPhases(
        state: PixieDustUiState,
        running: Boolean,
        success: Boolean,
        failure: Boolean,
        cancelled: Boolean
    ) {
        val current = if (success) {
            PixieDustPhase.CREDENTIALS
        } else {
            state.attackStage?.phase ?: PixieDustPhase.PREPARE
        }
        val bars = listOf(
            binding.phase1Bar,
            binding.phase2Bar,
            binding.phase3Bar,
            binding.phase4Bar,
            binding.phase5Bar
        )
        val labels = listOf(
            binding.phase1Label,
            binding.phase2Label,
            binding.phase3Label,
            binding.phase4Label,
            binding.phase5Label
        )
        val doneColor = ContextCompat.getColor(this, R.color.md_theme_primaryContainer)
        val activeColor = ContextCompat.getColor(this, R.color.md_theme_primary)
        val failedColor = ContextCompat.getColor(this, R.color.managed)
        val stoppedColor = ContextCompat.getColor(this, R.color.warning)
        val pendingColor = ContextCompat.getColor(this, R.color.md_theme_outlineVariant)
        val reached = current.ordinal

        bars.forEachIndexed { index, bar ->
            when {
                index < reached -> bar.setBackgroundColor(doneColor)
                index == reached && failure -> bar.setBackgroundColor(failedColor)
                index == reached && cancelled -> bar.setBackgroundColor(stoppedColor)
                index == reached && running -> bar.setBackgroundColor(activeColor)
                index == reached && success -> bar.setBackgroundColor(doneColor)
                else -> bar.setBackgroundColor(pendingColor)
            }
        }
        labels.forEachIndexed { index, label ->
            label.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (index <= reached) R.color.md_theme_onSurface
                    else R.color.md_theme_onSurfaceVariant
                )
            )
        }
    }

    private fun bindSessionResult(
        state: PixieDustUiState,
        success: Boolean,
        cancelled: Boolean
    ) {
        val result = state.result
        val show = success || cancelled || !state.isAttacking
        binding.layoutSessionResult.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val bannerTint: Int
        val bannerText: Int
        val iconRes: Int
        val iconTint: Int
        when {
            success -> {
                bannerTint = R.color.md_theme_primaryContainer
                bannerText = R.color.md_theme_onPrimaryContainer
                iconRes = R.drawable.ic_check_circle
                iconTint = R.color.monitor_active
            }
            cancelled -> {
                bannerTint = R.color.md_theme_surfaceVariant
                bannerText = R.color.md_theme_onSurface
                iconRes = R.drawable.ic_close_circle
                iconTint = R.color.warning
            }
            else -> {
                bannerTint = R.color.error_container
                bannerText = R.color.on_error_container
                iconRes = R.drawable.ic_warning
                iconTint = R.color.managed
            }
        }
        binding.layoutResultBanner.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, bannerTint)
        )
        binding.ivResultIcon.setImageResource(iconRes)
        binding.ivResultIcon.setColorFilter(ContextCompat.getColor(this, iconTint))
        binding.tvResultStatus.setTextColor(ContextCompat.getColor(this, bannerText))
        binding.tvResultSubtitle.setTextColor(
            ContextCompat.getColor(
                this,
                if (success) R.color.md_theme_onPrimaryContainer else bannerText
            )
        )

        when {
            success -> bindSuccessResult(result as PixieDustResult.Success)
            cancelled -> bindStoppedResult()
            else -> bindFailureResult(result as? PixieDustResult.Failure)
        }
    }

    private fun bindSuccessResult(result: PixieDustResult.Success) {
        binding.tvResultStatus.setText(R.string.pixie_success_title)
        binding.tvResultSubtitle.setText(
            if (result.wpaPsk.isNullOrBlank()) R.string.pixie_success_subtitle_pin_only
            else R.string.pixie_success_subtitle
        )
        binding.layoutResultCredentials.visibility = View.VISIBLE
        binding.tvResultReason.visibility = View.GONE

        binding.tvResultPin.text = result.wpsPin ?: getString(R.string.pixie_not_available)
        binding.btnCopyPin.isEnabled = !result.wpsPin.isNullOrBlank()
        binding.btnCopyPin.alpha = if (binding.btnCopyPin.isEnabled) 1f else 0.4f

        val hasPsk = !result.wpaPsk.isNullOrBlank()
        binding.rowResultPsk.visibility = if (hasPsk) View.VISIBLE else View.GONE
        binding.tvResultPsk.text = result.wpaPsk ?: getString(R.string.pixie_not_available)

        binding.btnCopyResult.visibility = View.VISIBLE
        binding.btnCopyResult.isEnabled = true
    }

    private fun bindFailureResult(result: PixieDustResult.Failure?) {
        binding.tvResultStatus.setText(R.string.pixie_failure_title)
        binding.tvResultSubtitle.setText(R.string.pixie_failure_subtitle)
        binding.layoutResultCredentials.visibility = View.GONE
        binding.tvResultReason.text = result?.reason.orEmpty()
        binding.tvResultReason.visibility = if (result == null) View.GONE else View.VISIBLE
        binding.btnCopyResult.visibility = View.GONE
    }

    private fun bindStoppedResult() {
        binding.tvResultStatus.setText(R.string.pixie_result_stopped_title)
        binding.tvResultSubtitle.setText(R.string.pixie_result_stopped_subtitle)
        binding.layoutResultCredentials.visibility = View.GONE
        binding.tvResultReason.visibility = View.GONE
        binding.btnCopyResult.visibility = View.GONE
    }

    /**
     * El registro es el protagonista de la sesión: se pinta siempre, con
     * timestamps tenues y el prefijo de nivel coloreado para que se pueda
     * seguir de un vistazo.
     */
    private fun bindConsole(state: PixieDustUiState) {
        val lines = state.consoleLines
        val signature = lines.hashCode()
        binding.tvConsoleCount.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.btnCopyLog.isEnabled = lines.isNotEmpty()
        binding.btnCopyLog.alpha = if (lines.isNotEmpty()) 1f else 0.4f

        if (signature != lastConsoleSignature) {
            binding.tvConsoleCount.text = getString(R.string.pixie_console_lines, lines.size)
            binding.tvConsole.setTextColor(
                ContextCompat.getColor(this, R.color.md_theme_onSurface)
            )
            binding.tvConsole.text = if (lines.isEmpty()) {
                getString(R.string.pixie_console_empty)
            } else {
                formatLog(lines)
            }
            lastConsoleSignature = signature

            // Solo seguimos el final si el usuario no está leyendo más arriba.
            // Se usa scrollTo (no fullScroll) para que el gesto no arrastre
            // también la página exterior.
            if (lines.isNotEmpty() && consoleExpanded && consoleAtBottom) {
                binding.svConsole.post {
                    val content = binding.svConsole.getChildAt(0) ?: return@post
                    binding.svConsole.scrollTo(
                        0,
                        (content.height - binding.svConsole.height).coerceAtLeast(0)
                    )
                }
            }
        }

        binding.svConsole.visibility = if (consoleExpanded) View.VISIBLE else View.GONE
        binding.btnToggleConsole.contentDescription = getString(
            if (consoleExpanded) R.string.pixie_console_hide
            else R.string.pixie_console_show
        )
        binding.btnToggleConsole.rotation = if (consoleExpanded) 180f else 0f
    }

    /** Colorea timestamp y prefijo sin tocar el texto seleccionable. */
    private fun formatLog(lines: List<String>): Spannable {
        val builder = SpannableStringBuilder()
        val timestampColor = ContextCompat.getColor(this, R.color.md_theme_outline)
        lines.forEachIndexed { index, line ->
            if (index > 0) builder.append('\n')
            val lineStart = builder.length
            builder.append(line)

            if (line.length >= 8 && line[2] == ':' && line[5] == ':') {
                builder.setSpan(
                    ForegroundColorSpan(timestampColor),
                    lineStart,
                    lineStart + 8,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val prefixStart = line.indexOf('[')
            if (prefixStart >= 0 && prefixStart + 2 < line.length) {
                val colorRes = when (line.substring(prefixStart, prefixStart + 3)) {
                    "[+]" -> R.color.monitor_active
                    "[-]" -> R.color.managed
                    "[!]" -> R.color.warning
                    "[*]" -> R.color.md_theme_primary
                    else -> null
                }
                if (colorRes != null) {
                    builder.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(this, colorRes)),
                        lineStart + prefixStart,
                        lineStart + prefixStart + 3,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        return builder
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
}
