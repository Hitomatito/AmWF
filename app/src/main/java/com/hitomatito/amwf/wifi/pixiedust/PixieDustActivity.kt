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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hitomatito.amwf.R
import com.hitomatito.amwf.databinding.ActivityPixieDustBinding
import kotlinx.coroutines.launch

/**
 * Pantalla de auditoría WPS Pixie Dust.
 *
 * Permite escanear redes WiFi con WPS, seleccionar una objetivo y ejecutar la
 * auditoría Pixie Dust nativa (wpa_supplicant + pixiewps) con root.
 *
 * Requiere:
 *   - Acceso root.
 *   - Permiso ACCESS_FINE_LOCATION (Android exige ubicación para escaneo WiFi).
 *   - Binarios nativos empaquetados en assets/wifi/ (ver BINARIES_SETUP.md).
 */
class PixieDustActivity : AppCompatActivity() {

    private val TAG = "PixieDustActivity"

    private lateinit var binding: ActivityPixieDustBinding
    private lateinit var viewModel: PixieDustViewModel

    // Launcher de permisos de ubicación (necesario para escaneo WiFi en Android 6+).
    private val permissionLauncher = registerForActivityResult(
        RequestMultiplePermissions()
    ) { granted ->
        val locationGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (locationGranted) {
            viewModel.scanNetworks()
        } else {
            Toast.makeText(
                this,
                "Se requiere permiso de ubicación para escanear redes WiFi",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPixieDustBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this).get(PixieDustViewModel::class.java)

        setupObservers()
        setupListeners()

        // Verificar permiso de ubicación antes de permitir escaneo.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        } else {
            viewModel.loadInterfaces()
        }
    }

    private fun setupObservers() {
        // StateFlow: recolectar de forma lifecycle-aware.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.btnScan.isEnabled = !state.isScanning && !state.isAttacking
                    binding.btnAttack.isEnabled =
                        !state.isAttacking && !state.isScanning && state.selectedNetwork != null
                    binding.btnStop.isEnabled = state.isAttacking || state.isScanning
                    binding.edBssid.isEnabled = !state.isAttacking
                    binding.edSsid.isEnabled = !state.isAttacking
                    binding.cbDisableWifi.isEnabled = !state.isAttacking

                    binding.tvInterfaces.text = if (state.interfacesAvailable)
                        "Interfaz: ${state.selectedInterface}"
                    else
                        "No se detectaron interfaces WiFi"

                    binding.spInterface.adapter =
                        ArrayAdapter(this@PixieDustActivity, android.R.layout.simple_spinner_dropdown_item,
                            listOf(state.selectedInterface))

                    updateNetworkList(state.networks)

                    binding.tvSelectedNetwork.text =
                        state.selectedNetwork?.let { "${it.displayName} (${it.bssid})" } ?: "—"

                    binding.tvResult.text = when (val result = state.result) {
                        is PixieDustResult.Success -> {
                            val pin = result.wpsPin ?: "N/A"
                            val psk = result.wpaPsk ?: "N/A"
                            "✔ PIN WPS: $pin\nClave WPA: $psk"
                        }
                        is PixieDustResult.Failure -> "✖ Falló: ${result.reason}"
                        null -> "—"
                    }

                    binding.tvConsole.text = state.consoleLines.joinToString("\n")
                    binding.tvError.text = state.errorMessage ?: ""
                    binding.tvError.visibility =
                        if (state.errorMessage.isNullOrBlank()) android.view.View.GONE
                        else android.view.View.VISIBLE

                    binding.pbAttack.visibility =
                        if (state.isAttacking || state.isScanning) android.view.View.VISIBLE
                        else android.view.View.GONE
                }
            }
        }

        // Eventos transitorios (SharedFlow: recolectar de forma lifecycle-aware).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    when (event) {
                        is PixieDustEvent.ShowMessage -> Toast.makeText(
                            this@PixieDustActivity, event.message, Toast.LENGTH_LONG
                        ).show()
                        is PixieDustEvent.AttackFinished -> {
                            Toast.makeText(
                                this@PixieDustActivity,
                                if (event.success) "Auditoría completada" else "Auditoría falló: ${event.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is PixieDustEvent.CopyToClipboard -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("pixie_dust", event.text))
                            Toast.makeText(this@PixieDustActivity, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnScan.setOnClickListener {
            viewModel.clearConsole()
            viewModel.scanNetworks()
        }

        binding.btnAttack.setOnClickListener {
            viewModel.startAttack(binding.cbDisableWifi.isChecked)
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopAttack()
        }

        binding.btnCopyResult.setOnClickListener {
            val result = viewModel.uiState.value.result
            val text = when (result) {
                is PixieDustResult.Success ->
                    "BSSID: ${result.bssid}\nPIN: ${result.wpsPin ?: "N/A"}\nPSK: ${result.wpaPsk ?: "N/A"}"
                is PixieDustResult.Failure -> "Fallo: ${result.reason}"
                else -> ""
            }
            if (text.isNotBlank()) {
                viewModel.emitEvent(PixieDustEvent.CopyToClipboard(text))
            }
        }
    }

    private fun updateNetworkList(networks: List<PixieDustNetwork>) {
        val lines = networks.map { net ->
            val flag = if (net.wpsLocked) " [WPS LOCKED]"
            else if (net.wpsCapable) " [WPS]"
            else ""
            "${net.displayName} — ${net.bssid} ch${net.frequency} ${net.level}dBm$flag"
        }
        binding.tvNetworks.text = lines.joinToString("\n")
        binding.tvNetworks.visibility = if (lines.isEmpty()) android.view.View.GONE
        else android.view.View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopAttack()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}
