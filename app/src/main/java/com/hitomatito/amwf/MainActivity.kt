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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.hitomatito.amwf.ShellExecutor.logE
import com.hitomatito.amwf.wifi.pixiedust.NativeBinaries
import com.hitomatito.amwf.wifi.pixiedust.PixieDustActivity
import com.hitomatito.amwf.diagnostics.DiagnosticsCollector
import com.hitomatito.amwf.diagnostics.DiagnosticsExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decisión pura: sin verificación solo se permite desactivar o reintentar. */
internal fun toggleEnabledForState(isCompatVerified: Boolean, mode: MonitorMode): Boolean =
    isCompatVerified || mode != MonitorMode.MANAGED

/** Fuente única chip ↔ canal del selector de monitor (espejo del patrón de filtros de Pixie). */
internal val CHANNEL_CHIP_IDS: Map<Int, Int> = mapOf(
    1 to R.id.chipChannel1,
    6 to R.id.chipChannel6,
    11 to R.id.chipChannel11,
    36 to R.id.chipChannel36,
    40 to R.id.chipChannel40,
    44 to R.id.chipChannel44,
    48 to R.id.chipChannel48
)

internal fun chipIdForChannel(number: Int?): Int? = number?.let(CHANNEL_CHIP_IDS::get)

internal fun channelForChipId(chipId: Int?): Int? =
    CHANNEL_CHIP_IDS.entries.firstOrNull { it.value == chipId }?.key

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
    private lateinit var layoutDeviceInfo: LinearLayout
    
    private lateinit var rowChipset: View
    private lateinit var rowDriver: View
    private lateinit var rowRoot: View
    private lateinit var rowSysfs: View
    private lateinit var rowCapabilities: View
    private lateinit var rowPixieDustBinaries: View
    private lateinit var tvChannelHeader: TextView
    private lateinit var chipGroupChannels: ChipGroup
    private lateinit var tvChannelHint: TextView
    
    private val monitorManager by lazy { MonitorModeManager() }
    private val compatibilityChecker by lazy { DeviceCompatibilityChecker(applicationContext) }
    private val diagnosticsCollector by lazy { DiagnosticsCollector() }
    
    private var currentMode = MonitorMode.UNKNOWN
    // Canal verificado en iw dev info durante el monitor; null = sin sintonizar.
    private var currentTunedChannel: WifiChannel? = null
    // false solo cuando el dispositivo no tiene `iw`: no hay forma de sintonizar.
    private var currentIwAvailable = true
    // Evita que un check() programático de los chips re-dispare el retune.
    private var updatingChannelChips = false
    private var isDeviceCompatible = false
    // True solo después de que el usuario haya ejecutado la comprobación en esta
    // sesión de proceso (o haya resultado cacheado). Sin verificar, la activación
    // de monitor mode está bloqueada; desactivarlo siempre está permitido.
    private var isCompatVerified = false

    companion object {
        private const val TAG = "AmWF"

        private const val TAG_ROW_ICON = "rowIcon"
        private const val TAG_ROW_LABEL = "rowLabel"
        private const val TAG_ROW_VALUE = "rowValue"
        private const val TAG_ROW_STATUS = "rowStatus"

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
        // True mientras se aplica una restauración pendiente del check anterior;
        // impide que un toque en "Verificar compatibilidad" interleavingue root
        // commands con la restauración y borre el marcador a mitad.
        @Volatile
        private var pendingRestoreInProgress = false
        // Serializa los retunes de los chips con todo lo demas que toca la radio
        // (toggle, check de compatibilidad, restauracion pendiente): dos taps
        // seguidos intercalarían comandos root en Dispatchers.IO y el radio
        // quedaría en un canal distinto al mostrado. Es static @Volatile como las
        // otras dos banderas para sobrevivir a un recreate() a mitad del retune.
        @Volatile
        private var retuneInProgress = false
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
        if (compatCheckInProgress) {
            // Ya hay una comprobación en vuelo (p. ej. recreate() durante el test):
            // no relanzar el test ni tocar el radio; su finally hará la restauración.
            waitForCompatCheckInProgress()
        } else {
            if (cached != null) {
                Log.d(TAG, "Using cached compatibility result")
                applyCompatResult(cached)
            } else {
                showCompatNotVerified()
            }
            // Si una verificación anterior murió a mitad (la escritura de
            // con_mode puede llevarse por delante la app), aplicar la restauración
            // pendiente también cuando hay un resultado en caché: el finally pudo
            // fallar al encender el WiFi y el marcador seguir vivo.
            applyPendingCompatRestore()
        }
    }

    private fun applyPendingCompatRestore() {
        if (pendingRestoreInProgress || compatCheckInProgress) return
        pendingRestoreInProgress = true
        btnVerifyCompatibility.isEnabled = false
        lifecycleScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    if (compatCheckInProgress) return@withContext false
                    PendingCompatRestore.restoreIfPending(applicationContext)
                }
                if (restored) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.compat_restore_toast),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (!compatCheckInProgress) checkCurrentMode()
            } finally {
                pendingRestoreInProgress = false
                btnVerifyCompatibility.isEnabled = !compatCheckInProgress
            }
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
        layoutDeviceInfo = findViewById(R.id.layoutDeviceInfo)
        
        rowChipset = findViewById(R.id.rowChipset)
        rowDriver = findViewById(R.id.rowDriver)
        rowRoot = findViewById(R.id.rowRoot)
        rowSysfs = findViewById(R.id.rowSysfs)
        rowCapabilities = findViewById(R.id.rowCapabilities)
        rowPixieDustBinaries = findViewById(R.id.rowPixieDustBinaries)
        tvChannelHeader = findViewById(R.id.tvChannelHeader)
        chipGroupChannels = findViewById(R.id.chipGroupChannels)
        tvChannelHint = findViewById(R.id.tvChannelHint)
        setupChannelChips()
    }

    private fun setupChannelChips() {
        // El numero es local-neutro; los 5 GHz se marcan para no confundirlos.
        for (channel in MonitorChannelPolicy.SELECTABLE) {
            val chipId = chipIdForChannel(channel.number) ?: continue
            findViewById<Chip>(chipId).text =
                if (channel.is5GHz) "${channel.number}\u00B75G" else channel.number.toString()
        }
        chipGroupChannels.setOnCheckedStateChangeListener { _, checkedIds ->
            if (updatingChannelChips) return@setOnCheckedStateChangeListener
            // Igual que btnToggle/btnVerify: ningun comando root interleavea con
            // el check de compatibilidad o una restauracion pendiente (el check
            // rota canales y mide captura sobre esta misma interfaz).
            if (compatCheckInProgress || pendingRestoreInProgress || retuneInProgress) {
                renderChannelSelector()
                return@setOnCheckedStateChangeListener
            }
            val number = channelForChipId(checkedIds.firstOrNull()) ?: return@setOnCheckedStateChangeListener
            val target = MonitorChannelPolicy.findChannel(number) ?: return@setOnCheckedStateChangeListener
            if (target.number == currentTunedChannel?.number) return@setOnCheckedStateChangeListener
            applyMonitorChannel(target)
        }
    }

    private fun applyMonitorChannel(target: WifiChannel) {
        lifecycleScope.launch {
            // La bandera se levanta DENTRO del cuerpo: si el scope se cancela
            // entre el click y el primer despacho (destroy/recreate rapidos),
            // el bloque nunca corre y una bandera statica puesta desde el
            // listener quedaría trabada hasta matar el proceso. Dos clicks en
            // el mismo iterador del loop principal no existen (cada tap es su
            // propio evento), así que el listener sigue serializando.
            retuneInProgress = true
            try {
                val outcome = withContext(Dispatchers.IO) {
                    monitorManager.retuneMonitor(target).also {
                        if (it.tunedChannel != null) {
                            MonitorChannelStore.save(applicationContext, target)
                        }
                    }
                }
                if (outcome.tunedChannel != null) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.channel_applied, target.number),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.channel_apply_failed, target.number),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                currentTunedChannel = outcome.tunedChannel
                currentIwAvailable = outcome.iwAvailable
            } finally {
                retuneInProgress = false
                renderChannelSelector()
            }
        }
    }

    private fun renderChannelSelector() {
        val visible = MonitorChannelPolicy.selectorVisible(currentMode)
        tvChannelHeader.visibility = if (visible) View.VISIBLE else View.GONE
        chipGroupChannels.visibility = if (visible) View.VISIBLE else View.GONE
        // El hint avisa solo cuando el dispositivo no puede sintonizar en
        // absoluto (sin `iw`); con `iw` el exit code ya se notifico por toast.
        tvChannelHint.visibility =
            if (visible && !currentIwAvailable) View.VISIBLE else View.GONE
        if (!visible) return
        updatingChannelChips = true
        val checkedChipId = chipIdForChannel(
            currentTunedChannel?.number ?: MonitorChannelStore.load(applicationContext).number
        )
        if (checkedChipId != null) {
            chipGroupChannels.check(checkedChipId)
        }
        updatingChannelChips = false
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
            if (!compatCheckInProgress && !pendingRestoreInProgress && !retuneInProgress) {
                checkDeviceCompatibility()
            }
        }

        btnToggle.setOnClickListener {
            // Ningun comando root interleavea con un retune en curso, con el
            // check de compatibilidad o con una restauracion pendiente (las
            // tres operacciones togglean la misma radio en Dispatchers.IO).
            if (pendingRestoreInProgress || compatCheckInProgress || retuneInProgress) {
                return@setOnClickListener
            }
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
            } catch (e: CancellationException) {
                // Un recreate() cancela esta coroutine. El bloque IO pudo escribir
                // ya el resultado en la caché; borrarla aquí lo perdería y la
                // nueva Activity repetiría el check (que corta el WiFi).
                throw e
            } catch (e: Exception) {
                // Un fallo inesperado no debe tumbar la app: el radio ya se
                // restauró en el finally de checkCompatibility(); aquí solo
                // se refleja el error en la tarjeta. Se invalida también el
                // resultado cacheado: un éxito anterior no puede sobrevivir a
                // una verificación fallida (dejaría el modo monitor abierto).
                logE(TAG, "Unexpected error during compatibility check", e)
                cachedCompatResult = null
                isCompatVerified = false
                isDeviceCompatible = false
                tvCompatTitle.text = getString(R.string.device_compatibility)
                tvCompatStatus.text = getString(R.string.compat_check_unexpected_error)
                layoutDeviceInfo.visibility = View.GONE
                showCompatError(getString(R.string.compat_check_unexpected_error))
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
        tvCompatStatus.text = getString(R.string.progress_phase_percent, phase, percent)
        btnVerifyCompatibility.text = getString(
            R.string.checking_compatibility_progress,
            getString(R.string.checking_compatibility),
            percent
        )
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

    private fun setCompatTitleIcon(iconRes: Int, tintColorRes: Int) {
        val sizePx = (28 * resources.displayMetrics.density).toInt()
        val drawable = ContextCompat.getDrawable(this, iconRes)?.mutate()
        drawable?.setBounds(0, 0, sizePx, sizePx)
        drawable?.setTint(getColor(tintColorRes))
        tvCompatTitle.setCompoundDrawablesRelative(drawable, null, null, null)
    }

    // Presentación compartida de un fallo de compatibilidad: icono, color,
    // bloqueo del toggle y aviso al usuario.
    private fun showCompatError(message: String) {
        setCompatTitleIcon(R.drawable.ic_close_circle, R.color.managed)
        tvCompatStatus.setTextColor(getColor(R.color.managed))
        setToggleEnabled(false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // Estado inicial cuando el usuario aún no ha solicitado la comprobación: la
    // tarjeta muestra "sin verificar" y el resultado detallado queda oculto.
    private fun showCompatNotVerified() {
        isCompatVerified = false
        layoutDeviceInfo.visibility = View.GONE
        setCompatTitleIcon(R.drawable.ic_info, R.color.md_theme_onSurfaceVariant)
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
            btnToggle.text = getString(R.string.not_compatible)
            btnToggle.setBackgroundColor(getColor(R.color.unknown))

            val reason = result.issues.find { it.severity == Severity.CRITICAL }
                ?.let { it.suggestion.ifBlank { it.message } }
                ?: getString(R.string.not_compatible)
            tvInfo.text = reason
            showCompatError(reason)
        }
    }

    private fun updateCompatibilityUI(result: CompatibilityResult) {
        layoutDeviceInfo.visibility = View.VISIBLE
        
        if (result.isCompatible) {
            setCompatTitleIcon(R.drawable.ic_check_circle, R.color.monitor_active)
            tvCompatTitle.text = getString(R.string.device_compatible)
            tvCompatStatus.text = getString(R.string.ready_to_use)
            tvCompatStatus.setTextColor(getColor(R.color.monitor_active))
        } else {
            setCompatTitleIcon(R.drawable.ic_close_circle, R.color.managed)
            tvCompatTitle.text = getString(R.string.device_incompatible)
            tvCompatStatus.text = getString(R.string.not_compatible)
            tvCompatStatus.setTextColor(getColor(R.color.managed))
        }
        
        updateInfoRow(
            rowChipset,
            R.drawable.ic_chipset,
            getString(R.string.label_chipset),
            result.deviceInfo.chipset,
            result.issues.none { it.type == IssueType.CHIPSET }
        )
        
        val driverKnown = result.deviceInfo.wlanDriver.isNotBlank() &&
            !result.deviceInfo.wlanDriver.equals("Unknown", ignoreCase = true)
        updateInfoRow(
            rowDriver,
            R.drawable.ic_driver,
            getString(R.string.label_driver),
            result.deviceInfo.wlanDriver.ifEmpty { getString(R.string.not_found) },
            driverKnown
        )
        
        val rootText = if (result.deviceInfo.rootInfo.isRooted) {
            "${result.deviceInfo.rootInfo.rootName} ${result.deviceInfo.rootInfo.version}"
        } else getString(R.string.no)
        updateInfoRow(rowRoot, R.drawable.ic_root, getString(R.string.label_root), rootText, result.deviceInfo.rootInfo.isRooted)

        updateInfoRow(rowSysfs, R.drawable.ic_sysfs, getString(R.string.label_sysfs), result.deviceInfo.conModePath?.substringAfterLast('/') ?: getString(R.string.not_found),
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
        updateInfoRow(rowCapabilities, R.drawable.ic_capabilities, getString(R.string.label_capabilities), capText, capPassed)

        // Mostrar estado de los binarios Pixie Dust (wpa_supplicant, wpa_cli, pixiewps)
        val pdb = result.deviceInfo.pixieDustBinaries
        val pdbText = NativeBinaries.missingBinariesSummary(this, pdb.missing)
            ?: getString(R.string.pixie_binaries_available)
        updateInfoRow(rowPixieDustBinaries, R.drawable.ic_wifi, getString(R.string.label_pixie_dust_binaries), pdbText, pdb.allAvailable)

        // El botón permanece siempre habilitado y con un solo listener: si la
        // comprobación ya confirmó que faltan binarios, el toque explica cuáles
        // en vez de fallar en silencio o abrir una pantalla inutilizable.
        // El alpha reducido solo comunica «aún no listo»; no deshabilita nada.
        btnPixieDust.alpha = if (pdb.allAvailable) 1f else 0.45f
    }

    private fun updateInfoRow(row: View, iconRes: Int, label: String, value: String, passed: Boolean) {
        // Las filas comparten un único layout con tags: los ids reasignados
        // por <include> solo existen en los wrappers rowChipset…rowPixieDustBinaries.
        row.findViewWithTag<ImageView>(TAG_ROW_ICON)?.setImageResource(iconRes)
        row.findViewWithTag<TextView>(TAG_ROW_LABEL)?.text = label
        row.findViewWithTag<TextView>(TAG_ROW_VALUE)?.text = value
        row.findViewWithTag<ImageView>(TAG_ROW_STATUS)?.visibility = View.VISIBLE

        val checkIcon = if (passed) R.drawable.ic_check_circle else R.drawable.ic_close_circle
        val color = if (passed) R.color.monitor_active else R.color.managed

        row.findViewWithTag<ImageView>(TAG_ROW_STATUS)?.apply {
            setImageResource(checkIcon)
            setColorFilter(getColor(color))
        }
        row.findViewWithTag<ImageView>(TAG_ROW_ICON)?.setColorFilter(getColor(color))

        if (!passed) {
            row.findViewWithTag<TextView>(TAG_ROW_VALUE)?.setTextColor(getColor(color))
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
                monitorManager.enableMonitorMode(MonitorChannelStore.load(applicationContext))
            }

            currentMode = result.type
            updateUI(result)
            setLoading(false)

            if (result.type == MonitorMode.UNKNOWN) {
                Toast.makeText(this@MainActivity, getString(result.statusRes), Toast.LENGTH_LONG).show()
            }
            // Monitor activo pero con la sintonia rechazada: la radio escucha en
            // vacio (0 frames) y las herramientas externas no verian nada.
            if (result.type == MonitorMode.MONITOR && result.tunedChannel == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.channel_tune_warn),
                    Toast.LENGTH_LONG
                ).show()
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

    // Sin verificación previa solo se permite desactivar (recuperación) o
    // reintentar la detección; activar monitor sigue bloqueado aunque updateUI
    // o setLoading terminen con el toggle visualmente habilitado.
    private fun toggleEnabledByState(): Boolean =
        toggleEnabledForState(isCompatVerified, currentMode)

    // Centraliza el estado del boton: ademas de bloquear el click, lo atenua
    // visualmente para que el usuario vea que no esta disponible.
    private fun setToggleEnabled(enabled: Boolean) {
        btnToggle.isEnabled = enabled
        btnToggle.alpha = if (enabled) 1f else 0.45f
    }

    private fun setLoading(loading: Boolean, isActivating: Boolean = true) {
        setToggleEnabled(!loading && toggleEnabledByState())
        if (loading) {
            btnToggle.text = if (isActivating) getString(R.string.activating) else getString(R.string.deactivating)
        }
    }

    private fun updateUI(result: MonitorResult) {
        currentTunedChannel = result.tunedChannel
        currentIwAvailable = result.iwAvailable
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
                setToggleEnabled(toggleEnabledByState())
            }
            MonitorMode.MANAGED -> {
                tvStatus.setTextColor(getColor(R.color.managed))
                btnToggle.text = getString(R.string.enable_monitor)
                btnToggle.setBackgroundColor(getColor(R.color.monitor_active))
                btnToggle.setTextColor(android.graphics.Color.WHITE)
                setToggleEnabled(toggleEnabledByState())
            }
            MonitorMode.UNKNOWN -> {
                tvStatus.setTextColor(getColor(R.color.unknown))
                btnToggle.text = getString(R.string.retry)
                btnToggle.setBackgroundColor(getColor(R.color.unknown))
                btnToggle.setTextColor(android.graphics.Color.WHITE)
                setToggleEnabled(toggleEnabledByState())
            }
        }
        renderChannelSelector()
    }
}
