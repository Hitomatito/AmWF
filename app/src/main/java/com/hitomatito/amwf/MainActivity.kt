package com.hitomatito.amwf

import android.content.Context
import android.os.Bundle
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
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private lateinit var statusIndicator: View
    private lateinit var ivCompatIcon: ImageView
    private lateinit var layoutDeviceInfo: LinearLayout
    
    private lateinit var rowChipset: View
    private lateinit var rowDriver: View
    private lateinit var rowRoot: View
    private lateinit var rowSysfs: View
    private lateinit var rowCapabilities: View

    private val monitorManager by lazy { MonitorModeManager() }
    private val compatibilityChecker by lazy { DeviceCompatibilityChecker() }
    
    private var currentMode = MonitorMode.UNKNOWN
    private var isDeviceCompatible = false

    companion object {
        private const val TAG = "AmWF"
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
        checkDeviceCompatibility()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)
        tvCompatStatus = findViewById(R.id.tvCompatStatus)
        tvCompatTitle = findViewById(R.id.tvCompatTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnToggle = findViewById(R.id.btnToggle)
        btnLanguage = findViewById(R.id.btnLanguage)
        statusIndicator = findViewById(R.id.statusIndicator)
        ivCompatIcon = findViewById(R.id.ivCompatIcon)
        layoutDeviceInfo = findViewById(R.id.layoutDeviceInfo)
        
        rowChipset = findViewById(R.id.rowChipset)
        rowDriver = findViewById(R.id.rowDriver)
        rowRoot = findViewById(R.id.rowRoot)
        rowSysfs = findViewById(R.id.rowSysfs)
        rowCapabilities = findViewById(R.id.rowCapabilities)
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

        btnToggle.setOnClickListener {
            when (currentMode) {
                MonitorMode.MANAGED -> enableMonitorMode()
                MonitorMode.MONITOR -> disableMonitorMode()
                MonitorMode.UNKNOWN -> checkCurrentMode()
                else -> {}
            }
        }
    }

    private fun updateLanguageButton() {
        val currentLang = LocaleHelper.getCurrentLocale(this)
        btnLanguage.text = currentLang.uppercase()
    }

    private fun checkDeviceCompatibility() {
        tvCompatStatus.text = getString(R.string.checking_compatibility)
        
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                compatibilityChecker.checkCompatibility()
            }
            
            isDeviceCompatible = result.isCompatible
            updateCompatibilityUI(result)
            
            if (isDeviceCompatible) {
                checkCurrentMode()
            } else {
                tvStatus.text = getString(R.string.device_incompatible)
                btnToggle.isEnabled = false
                btnToggle.text = getString(R.string.not_compatible)
                btnToggle.setBackgroundColor(getColor(R.color.unknown))
                
                result.issues.find { it.severity == Severity.CRITICAL }?.let {
                    Toast.makeText(this@MainActivity, getString(R.string.root_missing), Toast.LENGTH_LONG).show()
                }
            }
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
            !capInfo.tested -> getString(R.string.capability_unknown)
            capInfo.canInject == true && capInfo.canCapture == true -> getString(R.string.capability_full)
            capInfo.canInject == true -> getString(R.string.capability_injection)
            capInfo.canCapture == true -> getString(R.string.capability_capture)
            else -> getString(R.string.capability_passive)
        }
        val capPassed = capInfo.canInject == true && capInfo.canCapture == true
        updateInfoRow(rowCapabilities, getString(R.string.label_capabilities), capText, capPassed)
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
        CoroutineScope(Dispatchers.Main).launch {
            tvStatus.text = getString(R.string.checking)

            val result = withContext(Dispatchers.IO) {
                monitorManager.getCurrentMode()
            }

            currentMode = result.type
            updateUI(result)
        }
    }

    private fun enableMonitorMode() {
        if (!isDeviceCompatible) {
            Toast.makeText(this, getString(R.string.not_compatible), Toast.LENGTH_SHORT).show()
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
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
        CoroutineScope(Dispatchers.Main).launch {
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

    private fun setLoading(loading: Boolean, isActivating: Boolean = true) {
        btnToggle.isEnabled = !loading
        if (loading) {
            btnToggle.text = if (isActivating) getString(R.string.activating) else getString(R.string.deactivating)
        }
    }

    private fun updateUI(result: MonitorResult) {
        tvStatus.text = getString(result.statusRes)
        tvInfo.text = result.info

        when (currentMode) {
            MonitorMode.MONITOR -> {
                tvStatus.setTextColor(getColor(R.color.monitor_active))
                btnToggle.text = getString(R.string.disable_monitor)
                btnToggle.setBackgroundColor(getColor(R.color.managed))
                btnToggle.setTextColor(android.graphics.Color.WHITE)
                btnToggle.isEnabled = true
            }
            MonitorMode.MANAGED -> {
                tvStatus.setTextColor(getColor(R.color.managed))
                btnToggle.text = getString(R.string.enable_monitor)
                btnToggle.setBackgroundColor(getColor(R.color.monitor_active))
                btnToggle.setTextColor(android.graphics.Color.WHITE)
                btnToggle.isEnabled = true
            }
            MonitorMode.UNKNOWN -> {
                tvStatus.setTextColor(getColor(R.color.unknown))
                btnToggle.text = getString(R.string.retry)
                btnToggle.setBackgroundColor(getColor(R.color.unknown))
                btnToggle.setTextColor(android.graphics.Color.WHITE)
                btnToggle.isEnabled = true
            }
            else -> {}
        }
    }
}
