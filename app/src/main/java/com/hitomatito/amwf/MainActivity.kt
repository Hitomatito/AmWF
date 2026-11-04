/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.hitomatito.amwf.wifi.pixiedust.NativeBinaries
import com.hitomatito.amwf.wifi.pixiedust.PixieDustActivity
import com.hitomatito.amwf.diagnostics.DiagnosticsCollector
import com.hitomatito.amwf.diagnostics.DiagnosticsExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvCompatStatus: TextView
    private lateinit var tvCompatTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnLanguage: MaterialButton
    private lateinit var btnExportDiagnostics: MaterialButton
    private lateinit var btnPixieDust: MaterialButton
    private lateinit var btnVerifyCompatibility: MaterialButton
    private lateinit var progressCompatibility: LinearProgressIndicator
    private lateinit var statusIndicator: View
    private lateinit var ivCompatIcon: ImageView
    private lateinit var layoutDeviceInfo: LinearLayout
    
    private lateinit var rowChipset: View
    private lateinit var rowDriver: View
    private lateinit var rowRoot: View
    private lateinit var rowSysfs: View
    private lateinit var rowCapabilities: View
    private lateinit var rowPixieDustBinaries: View

    private val monitorManager by lazy { MonitorModeManager() }
    private val compatibilityChecker by lazy { DeviceCompatibilityChecker(applicationContext) }
    private val diagnosticsCollector by lazy { DiagnosticsCollector() }
    
    private var currentMode = MonitorMode.UNKNOWN
    private var isDeviceCompatible = false
    // True solo después de que el usuario haya ejecutado la comprobación en esta
    // sesión de proceso (o haya resultado cacheado). Sin verificar, la activación
    // de monitor mode está bloqueada; desactivarlo siempre está permitido.
    private var isCompatVerified = false

    companion object {
        private const val TAG = "AmWF"

        // La comprobación de compatibilidad NUNCA se ejecuta sola: solo desde el
        // botón "Verificar compatibilidad" (interrumpe el WiFi). El resultado se
        // cachea en statics para que recreate() (cambio de idioma, rotación) no
        // repita el test ni la Activity nueva lo dispare de nuevo.
        @Volatile
        private var cachedCompatResult: CompatibilityResult? = null
        // True mientras hay una comprobación de compatibilidad en vuelo. Permite
        // que un recreate() durante la comprobación espere ese resultado en lugar
        // de lanzar un segundo test que volvería a cortar la WiFi.
        @Volatile
        private var compatCheckInProgress = false
    }

    override fun attachBaseContext(newBase: Context) {
        LocaleHelper.init(newBase)
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        initViews()
        setupWindowInsets()
        setupListeners()
        updateLanguageButton()

        // La comprobación de compatibilidad es manual: nunca se ejecuta sola al
        // abrir la app (interrumpe el WiFi). Si hay un resultado en caché (p. ej.
        // tras recreate() por cambio de idioma) se reutiliza; si hay un test en
        // vuelo se espera; si no, se muestra el estado "sin verificar".
        val cached = cachedCompatResult
        if (cached != null) {
            Log.d(TAG, "Using cached compatibility result")
            applyCompatResult(cached)
        } else if (compatCheckInProgress) {
            // Ya hay una comprobación en vuelo (p. ej. recreate() durante el test):
            // no relanzar el test, esperar el resultado que se está calculando.
            waitForCompatCheckInProgress()
        } else {
            showCompatNotVerified()
            // El estado del modo WiFi es de solo lectura: mostrarlo siempre.
            checkCurrentMode()
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)
        tvCompatStatus = findViewById(R.id.tvCompatStatus)
        tvCompatTitle = findViewById(R.id.tvCompatTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnToggle = findViewById(R.id.btnToggle)
        btnLanguage = findViewById(R.id.btnLanguage)
        btnExportDiagnostics = findViewById(R.id.btnExportDiagnostics)
        btnPixieDust = findViewById(R.id.btnPixieDust)
        btnVerifyCompatibility = findViewById(R.id.btnVerifyCompatibility)
        progressCompatibility = findViewById(R.id.progressCompatibility)
        statusIndicator = findViewById(R.id.statusIndicator)
        ivCompatIcon = findViewById(R.id.ivCompatIcon)
        layoutDeviceInfo = findViewById(R.id.layoutDeviceInfo)
        
        rowChipset = findViewById(R.id.rowChipset)
        rowDriver = findViewById(R.id.rowDriver)
        rowRoot = findViewById(R.id.rowRoot)
        rowSysfs = findViewById(R.id.rowSysfs)
        rowCapabilities = findViewById(R.id.rowCapabilities)
        rowPixieDustBinaries = findViewById(R.id.rowPixieDustBinaries)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.rootLayout)) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupListeners() {
        btnLanguage.setOnClickListener {
            val newLang = LocaleHelper.toggleLanguage(this)
            recreate()
        }

        btnExportDiagnostics.setOnClickListener {
            exportDiagnostics()
        }

        btnVerifyCompatibility.setOnClickListener {
            if (!compatCheckInProgress) {
                checkDeviceCompatibility()
            }
        }

        btnToggle.setOnClickListener {
            when (currentMode) {
                MonitorMode.MANAGED -> enableMonitorMode()
                MonitorMode.MONITOR -> disableMonitorMode()
                MonitorMode.UNKNOWN -> checkCurrentMode()
            }
        }

        btnPixieDust.setOnClickListener {
            // El botón debe abrir la auditoría con un solo toque. Solo se
            // bloquea si la comprobación ya confirmó que faltan binarios;
            // mientras está en curso (o si falló), se abre la pantalla y ella
            // muestra el error concreto al abrirse.
            val missing = cachedCompatResult?.deviceInfo?.pixieDustBinaries?.missing
            if (compatCheckInProgress || missing.isNullOrEmpty()) {
                startActivity(Intent(this, PixieDustActivity::class.java))
            } else {
                // El mensaje lo centraliza NativeBinaries para incluir también
                // la explicación de armeabi-v7a cuando aplica.
                NativeBinaries.missingBinariesSummary(this, missing)?.let { detail ->
                    Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateLanguageButton() {
        val currentLang = LocaleHelper.getCurrentLocale(this)
        btnLanguage.text = currentLang.uppercase()
    }

    // Genera el informe de diagnostico (datos pasivos + buffer de logs) y lo
    // comparte para que el usuario pueda adjuntarlo en un reporte.
    private fun exportDiagnostics() {
        btnExportDiagnostics.isEnabled = false
        val previousText = btnExportDiagnostics.text
        btnExportDiagnostics.text = getString(R.string.diagnostics_exporting)

        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val report = diagnosticsCollector.collect(this@MainActivity, cachedCompatResult)
                    DiagnosticsExporter.write(
                        this@MainActivity,
                        report,
                        diagnosticsCollector.ssidSecrets
                    )
                }
                DiagnosticsExporter.share(
                    this@MainActivity,
                    uri,
                    getString(R.string.diagnostics_subject)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export diagnostics: ${e.message}", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.diagnostics_error),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                btnExportDiagnostics.isEnabled = true
                btnExportDiagnostics.text = previousText
            }
        }
    }

    private fun checkDeviceCompatibility() {
        // El boton no debe verse clickeable mientras se verifica.
        setToggleEnabled(false)
        setVerifyButtonRunning(true)
        tvCompatStatus.text = getString(R.string.checking_compatibility)

        compatCheckInProgress = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Cachear desde el hilo IO: si llega un recreate() durante la
                    // comprobación, la nueva Activity podrá reutilizar el resultado.
                    compatibilityChecker.checkCompatibility { percent, phase ->
                        runOnUiThread { updateCompatProgress(percent, phase) }
                    }.also { cachedCompatResult = it }
                }
                applyCompatResult(result)
            } finally {
                compatCheckInProgress = false
                setVerifyButtonRunning(false)
            }
        }
    }

    // Refleja en la UI un hito real del check (llega desde Dispatchers.IO a través
    // de runOnUiThread). Los porcentajes son los hitos del flujo en el checker,
    // no una animación simulada.
    private fun updateCompatProgress(percent: Int, phase: String) {
        progressCompatibility.visibility = View.VISIBLE
        progressCompatibility.isIndeterminate = false
        progressCompatibility.setProgressCompat(percent, true)
        tvCompatStatus.text = "$phase · $percent %"
        btnVerifyCompatibility.text = "${getString(R.string.checking_compatibility)} $percent %"
    }

    // Espera a que termine una comprobación que ya está en vuelo (lanzada desde otra
    // instancia de la Activity) y aplica su resultado. Evita relanzar el test, que
    // volvería a cortar la WiFi.
    private fun waitForCompatCheckInProgress() {
        setToggleEnabled(false)
        setVerifyButtonRunning(true)
        tvCompatStatus.text = getString(R.string.checking_compatibility)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                while (compatCheckInProgress) {
                    delay(100)
                }
                cachedCompatResult
            }

            if (result != null) {
                applyCompatResult(result)
            } else {
                // La comprobación en vuelo terminó sin resultado (p. ej. cancelada):
                // reintentar.
                checkDeviceCompatibility()
            }
        }
    }

    // Estado inicial cuando el usuario aún no ha solicitado la comprobación: la
    // tarjeta muestra "sin verificar" y el resultado detallado queda oculto.
    private fun showCompatNotVerified() {
        isCompatVerified = false
        layoutDeviceInfo.visibility = View.GONE
        ivCompatIcon.setImageResource(R.drawable.ic_info)
        ivCompatIcon.setColorFilter(getColor(R.color.md_theme_onSurfaceVariant))
        tvCompatTitle.text = getString(R.string.device_compatibility)
        tvCompatStatus.text = getString(R.string.compat_not_verified)
        tvCompatStatus.setTextColor(getColor(R.color.md_theme_onSurfaceVariant))
        setVerifyButtonRunning(false)
    }

    // Estado del botón "Verificar compatibilidad": mientras hay un test en vuelo el
    // botón se atenúa, y la barra de progreso se muestra indeterminada hasta que
    // llega el primer hito real (updateCompatProgress la pasa a determinada).
    private fun setVerifyButtonRunning(running: Boolean) {
        btnVerifyCompatibility.isEnabled = !running
        btnVerifyCompatibility.alpha = if (running) 0.6f else 1f
        progressCompatibility.isIndeterminate = running
        progressCompatibility.visibility = if (running) View.VISIBLE else View.GONE
        if (!running) {
            progressCompatibility.setProgressCompat(0, false)
        }
        btnVerifyCompatibility.text = getString(
            if (running) R.string.checking_compatibility else R.string.verify_compatibility
        )
    }

    private fun applyCompatResult(result: CompatibilityResult) {
        isCompatVerified = true
        isDeviceCompatible = result.isCompatible
        setVerifyButtonRunning(false)
        updateCompatibilityUI(result)

        if (isDeviceCompatible) {
            checkCurrentMode()
        } else {
            tvStatus.text = getString(R.string.device_incompatible)
            setToggleEnabled(false)
            btnToggle.text = getString(R.string.not_compatible)
            btnToggle.setBackgroundColor(getColor(R.color.unknown))

            val reason = result.issues.find { it.severity == Severity.CRITICAL }
                ?.let { it.suggestion.ifBlank { it.message } }
                ?: getString(R.string.not_compatible)
            tvInfo.text = reason
            Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateCompatibilityUI(result: CompatibilityResult) {
        layoutDeviceInfo.visibility = View.VISIBLE
        
        if (result.isCompatible) {
            ivCompatIcon.setImageResource(R.drawable.ic_check_circle)
            ivCompatIcon.setColorFilter(getColor(R.color.monitor_active))
            tvCompatTitle.text = getString(R.string.device_compatible)
            tvCompatStatus.text = getString(R.string.ready_to_use)
            tvCompatStatus.setTextColor(getColor(R.color.monitor_active))
        } else {
            ivCompatIcon.setImageResource(R.drawable.ic_close_circle)
            ivCompatIcon.setColorFilter(getColor(R.color.managed))
            tvCompatTitle.text = getString(R.string.device_incompatible)
            tvCompatStatus.text = getString(R.string.not_compatible)
            tvCompatStatus.setTextColor(getColor(R.color.managed))
        }
        
        updateInfoRow(rowChipset, getString(R.string.label_chipset), result.deviceInfo.chipset, 
            result.issues.none { it.type == IssueType.CHIPSET })
        
        updateInfoRow(rowDriver, getString(R.string.label_driver), result.deviceInfo.wlanDriver.ifEmpty { "Unknown" },
            result.issues.none { it.type == IssueType.DRIVER })
        
        val rootText = if (result.deviceInfo.rootInfo.isRooted) {
            "${result.deviceInfo.rootInfo.rootName} ${result.deviceInfo.rootInfo.version}"
        } else getString(R.string.no)
        updateInfoRow(rowRoot, getString(R.string.label_root), rootText, result.deviceInfo.rootInfo.isRooted)
        
        updateInfoRow(rowSysfs, getString(R.string.label_sysfs), result.deviceInfo.conModePath?.substringAfterLast('/') ?: getString(R.string.not_found),
            result.deviceInfo.conModePath != null)
        
        val capInfo = result.deviceInfo.capabilities
        val capText = when {
            result.deviceInfo.conModePath == null -> getString(R.string.capability_unavailable)
            !capInfo.tested -> getString(R.string.capability_unknown)
            capInfo.canInject == true && capInfo.canCapture == true -> getString(R.string.capability_full)
            capInfo.canInject == true -> getString(R.string.capability_injection)
            capInfo.canCapture == true -> getString(R.string.capability_capture)
            else -> getString(R.string.capability_not_verified)
        }
        val capPassed = capInfo.canInject == true && capInfo.canCapture == true
        updateInfoRow(rowCapabilities, getString(R.string.label_capabilities), capText, capPassed)

        // Mostrar estado de los binarios Pixie Dust (wpa_supplicant, wpa_cli, pixiewps)
        val pdb = result.deviceInfo.pixieDustBinaries
        val pdbText = NativeBinaries.missingBinariesSummary(this, pdb.missing)
            ?: getString(R.string.pixie_binaries_available)
        updateInfoRow(rowPixieDustBinaries, getString(R.string.label_pixie_dust_binaries), pdbText, pdb.allAvailable)

        // El botón permanece siempre habilitado y con un solo listener: si la
        // comprobación ya confirmó que faltan binarios, el toque explica cuáles
        // en vez de fallar en silencio o abrir una pantalla inutilizable.
        // El alpha reducido solo comunica «aún no listo»; no deshabilita nada.
        btnPixieDust.alpha = if (pdb.allAvailable) 1f else 0.45f
    }

    private fun updateInfoRow(row: View, label: String, value: String, passed: Boolean) {
        row.findViewById<TextView>(R.id.tvLabel)?.text = label
        row.findViewById<TextView>(R.id.tvValue)?.text = value
        row.findViewById<ImageView>(R.id.ivStatus)?.visibility = View.VISIBLE
        
        val checkIcon = if (passed) R.drawable.ic_check_circle else R.drawable.ic_close_circle
        val color = if (passed) R.color.monitor_active else R.color.managed
        
        row.findViewById<ImageView>(R.id.ivStatus)?.apply {
            setImageResource(checkIcon)
            setColorFilter(getColor(color))
        }
        row.findViewById<ImageView>(R.id.ivIcon)?.setColorFilter(getColor(color))
        
        if (!passed) {
            row.findViewById<TextView>(R.id.tvValue)?.setTextColor(getColor(color))
        }
    }

    private fun checkCurrentMode() {
        lifecycleScope.launch {
            setToggleEnabled(false)
            tvStatus.text = getString(R.string.checking)

            val result = withContext(Dispatchers.IO) {
                monitorManager.getCurrentMode()
            }

            currentMode = result.type
            updateUI(result)
        }
    }

    private fun enableMonitorMode() {
        // Sin comprobación manual no se activa el modo monitor (interrumpe el WiFi);
        // desactivarlo sí está siempre permitido para poder recuperar el dispositivo.
        if (!isCompatVerified) {
            Toast.makeText(this, getString(R.string.verify_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (!isDeviceCompatible) {
            Toast.makeText(this, getString(R.string.not_compatible), Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            setLoading(true, isActivating = true)
            tvStatus.text = getString(R.string.activating)
            tvInfo.text = ""

            val result = withContext(Dispatchers.IO) {
                monitorManager.enableMonitorMode()
            }

            currentMode = result.type
            updateUI(result)
            setLoading(false)

            if (result.type == MonitorMode.UNKNOWN) {
                Toast.makeText(this@MainActivity, getString(result.statusRes), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disableMonitorMode() {
        lifecycleScope.launch {
            setLoading(true, isActivating = false)
            tvStatus.text = getString(R.string.deactivating)
            tvInfo.text = ""

            val result = withContext(Dispatchers.IO) {
                monitorManager.disableMonitorMode()
            }

            currentMode = result.type
            updateUI(result)
            setLoading(false)

            if (result.type == MonitorMode.UNKNOWN) {
                Toast.makeText(this@MainActivity, getString(result.statusRes), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Centraliza el estado del boton: ademas de bloquear el click, lo atenua
    // visualmente para que el usuario vea que no esta disponible.
    private fun setToggleEnabled(enabled: Boolean) {
        btnToggle.isEnabled = enabled
        btnToggle.alpha = if (enabled) 1f else 0.45f
    }

    private fun setLoading(loading: Boolean, isActivating: Boolean = true) {
        setToggleEnabled(!loading)
        if (loading) {
            btnToggle.text = if (isActivating) getString(R.string.activating) else getString(R.string.deactivating)
        }
    }

    private fun updateUI(result: MonitorResult) {
        tvStatus.text = getString(result.statusRes)
        tvInfo.text = if (result.infoRes != 0) {
            if (result.infoArg.isNotEmpty()) {
                getString(result.infoRes, result.infoArg)
            } else {
                getString(result.infoRes)
            }
        } else {
            result.info
        }

        when (currentMode) {
            MonitorMode.MONITOR -> {
                tvStatus.setTextColor(getColor(R.color.monitor_active))
                btnToggle.text = getString(R.string.disable_monitor)
                btnToggle.setBackgroundColor(getColor(R.color.managed))
                btnToggle.setTextColor(android.graphics.Color.WHITE)
                setToggleEnabled(true)
            }
            MonitorMode.MANAGED -> {
                tvStatus.setTextColor(getColor(R.color.managed))
                btnToggle.text = getString(R.string.enable_monitor)
                btnToggle.setBackgroundColor(getColor(R.color.monitor_active))
                btnToggle.setTextColor(android.graphics.Color.WHITE)
                setToggleEnabled(true)
            }
            MonitorMode.UNKNOWN -> {
                tvStatus.setTextColor(getColor(R.color.unknown))
                btnToggle.text = getString(R.string.retry)
                btnToggle.setBackgroundColor(getColor(R.color.unknown))
                btnToggle.setTextColor(android.graphics.Color.WHITE)
                setToggleEnabled(true)
            }
        }
    }
}
