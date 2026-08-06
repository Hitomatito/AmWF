package com.hitomatito.amwf

import com.hitomatito.amwf.ShellExecutor.execRoot
import com.hitomatito.amwf.ShellExecutor.logD
import com.hitomatito.amwf.ShellExecutor.logE
import com.hitomatito.amwf.ShellExecutor.logW

class MonitorModeManager {

    private val interfaceName: String by lazy { findWifiInterface() }

    companion object {
        private const val TAG = "MonitorMode"
        private const val INTERFACE_DOWN_TIMEOUT = 3000L
    }

    /**
     * Detects the WiFi interface dynamically.
     * Tries wlan0, wlan1, p2p0, etc. Returns first found or "wlan0" as fallback.
     */
    private fun findWifiInterface(): String {
        val candidates = listOf("wlan0", "wlan1", "p2p0", "swlan0")
        for (iface in candidates) {
            val result = execRoot("ip link show $iface 2>/dev/null")
            if (result.exitCode == 0 && result.output.isNotEmpty()) {
                logD(TAG, "Found WiFi interface: $iface")
                return iface
            }
        }
        logW(TAG, "No WiFi interface found, using fallback wlan0")
        return "wlan0"
    }

    fun isRootAvailable(): Boolean {
        return try {
            val result = execRoot("id")
            result.output.contains("uid=0") || result.output.contains("root")
        } catch (e: Exception) {
            logE(TAG, "Root check failed: ${e.message}")
            false
        }
    }

    fun enableMonitorMode(): MonitorResult {
        logD(TAG, "=== enableMonitorMode() ===")
        
        if (!isRootAvailable()) {
            return MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_root,
                info = "Root access required"
            )
        }

        return try {
            // Step 1: Disable WiFi service to ensure clean state
            logD(TAG, "Disabling WiFi service...")
            execRoot("svc wifi disable")
            Thread.sleep(1000)

            // Step 2: Bring interface DOWN and verify it actually went down
            logD(TAG, "Bringing $interfaceName DOWN...")
            execRoot("ip link set $interfaceName down")
            
            // Wait and verify interface is actually DOWN
            val downVerified = verifyInterfaceState(desiredState = false)
            if (!downVerified) {
                logE(TAG, "Failed to bring $interfaceName DOWN")
                // Try to restore WiFi on failure
                execRoot("svc wifi enable")
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    info = "No se pudo desactivar la interfaz WiFi"
                )
            }
            logD(TAG, "$interfaceName is DOWN, proceeding...")

            val conModePath = resolveConModePath()
            if (conModePath == null) {
                // Restore interface and WiFi before returning error
                execRoot("ip link set $interfaceName up")
                execRoot("svc wifi enable")
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    info = "con_mode no disponible en este driver WiFi"
                )
            }

            // Step 3: Write con_mode=4 (monitor mode)
            logD(TAG, "Writing con_mode=4 to $conModePath...")
            val writeResult = execRoot("echo 4 > $conModePath")
            if (writeResult.exitCode != 0 || writeResult.error.contains("denied") || 
                writeResult.error.contains("readonly") || writeResult.error.contains("Permission") ||
                writeResult.error.contains("Read-only file system") ||
                writeResult.error.contains("Operation not permitted") ||
                writeResult.error.contains("Input/output error")) {
                logE(TAG, "Failed to write con_mode: ${writeResult.error}")
                // Restore interface and WiFi before returning error
                execRoot("ip link set $interfaceName up")
                execRoot("svc wifi enable")
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_selinux,
                    info = writeResult.error
                )
            }

            // Step 4: Bring interface UP
            logD(TAG, "Bringing $interfaceName UP...")
            execRoot("ip link set $interfaceName up")
            Thread.sleep(500)

            val (mode, info) = getCurrentState()

            when (mode) {
                MonitorMode.MONITOR -> MonitorResult(
                    type = MonitorMode.MONITOR,
                    statusRes = R.string.monitor_mode_on,
                    info = info
                )
                else -> MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    info = info
                )
            }
        } catch (e: Exception) {
            logE(TAG, "Exception in enableMonitorMode: ${e.message}", e)
            // Try to restore interface and WiFi on exception
            try { 
                execRoot("ip link set $interfaceName up")
                execRoot("svc wifi enable")
            } catch (_: Exception) {}
            MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_unknown,
                info = e.stackTraceToString()
            )
        }
    }

    fun disableMonitorMode(): MonitorResult {
        logD(TAG, "=== disableMonitorMode() ===")
        
        if (!isRootAvailable()) {
            return MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_root,
                info = "Root access required"
            )
        }

        return try {
            // Step 1: Bring interface DOWN and verify
            logD(TAG, "Bringing $interfaceName DOWN...")
            execRoot("ip link set $interfaceName down")
            
            val downVerified = verifyInterfaceState(desiredState = false)
            if (!downVerified) {
                logE(TAG, "Failed to bring $interfaceName DOWN")
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    info = "No se pudo desactivar la interfaz WiFi"
                )
            }
            logD(TAG, "$interfaceName is DOWN, proceeding...")

            // Step 2: Write con_mode=0 (managed mode)
            val conModePath = resolveConModePath()
            if (conModePath != null) {
                logD(TAG, "Writing con_mode=0 to $conModePath...")
                val writeResult = execRoot("echo 0 > $conModePath")
                if (writeResult.exitCode != 0) {
                    logW(TAG, "Warning writing con_mode=0: ${writeResult.error}")
                }
            }

            // Step 3: Bring interface UP
            logD(TAG, "Bringing $interfaceName UP...")
            execRoot("ip link set $interfaceName up")
            Thread.sleep(500)

            // Step 4: Restart WiFi service to fully restore managed mode
            // After changing con_mode, Android's WiFi framework needs to reinitialize
            logD(TAG, "Restarting WiFi service...")
            restartWifiService()
            Thread.sleep(2000)

            val (mode, info) = getCurrentState()

            when (mode) {
                MonitorMode.MANAGED -> MonitorResult(
                    type = MonitorMode.MANAGED,
                    statusRes = R.string.monitor_mode_normal,
                    info = info
                )
                else -> MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    info = info
                )
            }
        } catch (e: Exception) {
            logE(TAG, "Exception in disableMonitorMode: ${e.message}", e)
            // Try to restore interface and WiFi on exception
            try { 
                execRoot("ip link set $interfaceName up")
                execRoot("svc wifi enable")
            } catch (_: Exception) {}
            MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_unknown,
                info = e.stackTraceToString()
            )
        }
    }

    /**
     * Restarts the Android WiFi service to reinitialize the driver
     * after changing con_mode. This ensures the WiFi radio is properly
     * activated in managed mode.
     */
    private fun restartWifiService() {
        // Disable WiFi
        execRoot("svc wifi disable")
        Thread.sleep(1000)
        // Re-enable WiFi
        execRoot("svc wifi enable")
        Thread.sleep(1000)
    }

    fun getCurrentMode(): MonitorResult {
        logD(TAG, "=== getCurrentMode() ===")
        
        if (!isRootAvailable()) {
            return MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_root,
                info = "Root check failed"
            )
        }

        return try {
            val (mode, info) = getCurrentState()

            val statusRes = when (mode) {
                MonitorMode.MONITOR -> R.string.monitor_mode_on
                MonitorMode.MANAGED -> R.string.monitor_mode_normal
                MonitorMode.UNKNOWN -> R.string.status_unknown
            }

            MonitorResult(type = mode, statusRes = statusRes, info = info)
        } catch (e: Exception) {
            MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_unknown,
                info = e.stackTraceToString()
            )
        }
    }

    /**
     * Verifies that the interface reached the desired state (UP or DOWN).
     * Retries multiple times with sleep to account for driver latency.
     */
    private fun verifyInterfaceState(desiredState: Boolean): Boolean {
        val stateStr = if (desiredState) "UP" else "DOWN"
        val maxRetries = 5
        val retryDelay = INTERFACE_DOWN_TIMEOUT / maxRetries

        for (attempt in 1..maxRetries) {
            Thread.sleep(retryDelay)
            val result = execRoot("ip link show $interfaceName")
            val isUp = result.output.contains("state UP", ignoreCase = true)
            
            logD(TAG, "Interface state check attempt $attempt: isUp=$isUp, desired=$desiredState")
            
            if (isUp == desiredState) {
                return true
            }
        }
        return false
    }

    private fun getCurrentState(): Pair<MonitorMode, String> {
        // 1) Intentar con "iw" (si el binario existe en el dispositivo)
        val iwInfo = execRoot("iw dev $interfaceName info").output
        if (iwInfo.isNotEmpty()) {
            return parseMode(iwInfo) to briefInfo(parseMode(iwInfo))
        }
        // 2) Sin "iw": el tipo de enlace radiotap (802.11 crudo) es la
        //    señal de que con_mode=4 esta activo.
        val linkInfo = execRoot("ip -d link show $interfaceName").output
        val mode = when {
            linkInfo.contains("ieee802.11/radiotap", ignoreCase = true) -> MonitorMode.MONITOR
            linkInfo.contains("link/ether", ignoreCase = true) -> MonitorMode.MANAGED
            else -> MonitorMode.UNKNOWN
        }
        return mode to briefInfo(mode)
    }

    // Resumen corto para la tarjeta de informacion (evita el volcado bruto de ip/iw)
    private fun briefInfo(mode: MonitorMode): String = when (mode) {
        MonitorMode.MONITOR -> "$interfaceName en modo monitor (802.11 radiotap)"
        MonitorMode.MANAGED -> "$interfaceName en modo gestionado (managed)"
        MonitorMode.UNKNOWN -> "No se pudo determinar el estado de $interfaceName"
    }

    private fun parseMode(output: String): MonitorMode {
        return when {
            output.contains("type monitor", ignoreCase = true) -> MonitorMode.MONITOR
            output.contains("type managed", ignoreCase = true) -> MonitorMode.MANAGED
            else -> MonitorMode.UNKNOWN
        }
    }
}

data class MonitorResult(
    val type: MonitorMode,
    val statusRes: Int,
    val info: String = ""
)

enum class MonitorMode {
    MONITOR, MANAGED, UNKNOWN
}
