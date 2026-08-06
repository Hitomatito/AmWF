package com.hitomatito.amwf

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class MonitorModeManager {

    private val interfaceName: String by lazy { findWifiInterface() }

    companion object {
        private const val TAG = "MonitorMode"
        private const val COMMAND_TIMEOUT = 10000L  // Increased for slow devices
        private const val INTERFACE_DOWN_TIMEOUT = 3000L
    }

    /**
     * Detects the WiFi interface dynamically.
     * Tries wlan0, wlan1, p2p0, etc. Returns first found or "wlan0" as fallback.
     */
    private fun findWifiInterface(): String {
        val candidates = listOf("wlan0", "wlan1", "p2p0", "swlan0")
        for (iface in candidates) {
            val result = execCommand("ip link show $iface 2>/dev/null")
            if (result.exitCode == 0 && result.output.isNotEmpty()) {
                Log.d(TAG, "Found WiFi interface: $iface")
                return iface
            }
        }
        Log.w(TAG, "No WiFi interface found, using fallback wlan0")
        return "wlan0"
    }

    fun isRootAvailable(): Boolean {
        return try {
            val result = execCommand("id")
            result.output.contains("uid=0") || result.output.contains("root")
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed: ${e.message}")
            false
        }
    }

    fun enableMonitorMode(): MonitorResult {
        Log.d(TAG, "=== enableMonitorMode() ===")
        
        if (!isRootAvailable()) {
            return MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_root,
                info = "Root access required"
            )
        }

        return try {
            // Step 1: Bring interface DOWN and verify it actually went down
            Log.d(TAG, "Bringing $interfaceName DOWN...")
            execCommand("ip link set $interfaceName down")
            
            // Wait and verify interface is actually DOWN
            val downVerified = verifyInterfaceState(desiredState = false)
            if (!downVerified) {
                Log.e(TAG, "Failed to bring $interfaceName DOWN")
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    info = "No se pudo desactivar la interfaz WiFi"
                )
            }
            Log.d(TAG, "$interfaceName is DOWN, proceeding...")

            val conModePath = resolveConModePath()
            if (conModePath == null) {
                // Restore interface before returning error
                execCommand("ip link set $interfaceName up")
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    info = "con_mode no disponible en este driver WiFi"
                )
            }

            // Step 2: Write con_mode=4 (monitor mode)
            Log.d(TAG, "Writing con_mode=4 to $conModePath...")
            val writeResult = execCommand("echo 4 > $conModePath")
            if (writeResult.exitCode != 0 || writeResult.error.contains("denied") || 
                writeResult.error.contains("readonly") || writeResult.error.contains("Permission") ||
                writeResult.error.contains("Read-only file system") ||
                writeResult.error.contains("Operation not permitted") ||
                writeResult.error.contains("Input/output error")) {
                Log.e(TAG, "Failed to write con_mode: ${writeResult.error}")
                // Restore interface before returning error
                execCommand("ip link set $interfaceName up")
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_selinux,
                    info = writeResult.error
                )
            }

            // Step 3: Bring interface UP
            Log.d(TAG, "Bringing $interfaceName UP...")
            execCommand("ip link set $interfaceName up")
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
            Log.e(TAG, "Exception in enableMonitorMode: ${e.message}", e)
            // Try to restore interface on exception
            try { execCommand("ip link set $interfaceName up") } catch (_: Exception) {}
            MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_unknown,
                info = e.stackTraceToString()
            )
        }
    }

    fun disableMonitorMode(): MonitorResult {
        Log.d(TAG, "=== disableMonitorMode() ===")
        
        if (!isRootAvailable()) {
            return MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_root,
                info = "Root access required"
            )
        }

        return try {
            // Step 1: Bring interface DOWN and verify
            Log.d(TAG, "Bringing $interfaceName DOWN...")
            execCommand("ip link set $interfaceName down")
            
            val downVerified = verifyInterfaceState(desiredState = false)
            if (!downVerified) {
                Log.e(TAG, "Failed to bring $interfaceName DOWN")
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    info = "No se pudo desactivar la interfaz WiFi"
                )
            }
            Log.d(TAG, "$interfaceName is DOWN, proceeding...")

            // Step 2: Write con_mode=0 (managed mode)
            val conModePath = resolveConModePath()
            if (conModePath != null) {
                Log.d(TAG, "Writing con_mode=0 to $conModePath...")
                val writeResult = execCommand("echo 0 > $conModePath")
                if (writeResult.exitCode != 0) {
                    Log.w(TAG, "Warning writing con_mode=0: ${writeResult.error}")
                }
            }

            // Step 3: Bring interface UP
            Log.d(TAG, "Bringing $interfaceName UP...")
            execCommand("ip link set $interfaceName up")
            Thread.sleep(500)

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
            Log.e(TAG, "Exception in disableMonitorMode: ${e.message}", e)
            // Try to restore interface on exception
            try { execCommand("ip link set $interfaceName up") } catch (_: Exception) {}
            MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_unknown,
                info = e.stackTraceToString()
            )
        }
    }

    fun getCurrentMode(): MonitorResult {
        Log.d(TAG, "=== getCurrentMode() ===")
        
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
            val result = execCommand("ip link show $interfaceName")
            val isUp = result.output.contains("state UP", ignoreCase = true)
            
            Log.d(TAG, "Interface state check attempt $attempt: isUp=$isUp, desired=$desiredState")
            
            if (isUp == desiredState) {
                return true
            }
        }
        return false
    }

    private fun getCurrentState(): Pair<MonitorMode, String> {
        // 1) Intentar con "iw" (si el binario existe en el dispositivo)
        val iwInfo = execCommand("iw dev $interfaceName info").output
        if (iwInfo.isNotEmpty()) {
            return parseMode(iwInfo) to briefInfo(parseMode(iwInfo))
        }
        // 2) Sin "iw": el tipo de enlace radiotap (802.11 crudo) es la
        //    señal de que con_mode=4 esta activo.
        val linkInfo = execCommand("ip -d link show $interfaceName").output
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

    private data class ExecResult(val exitCode: Int, val output: String, val error: String)

    private fun execCommand(command: String): ExecResult {
        return try {
            // Use su directly (not sh -c "su -c 'cmd'") to prevent shell injection.
            // su internally runs through sh, so pipes/redirects work.
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < COMMAND_TIMEOUT) {
                // Use ready() instead of available() - more reliable for data availability
                if (outputReader.ready()) {
                    val line = outputReader.readLine()
                    if (line != null) output.appendLine(line)
                }
                if (errorReader.ready()) {
                    val line = errorReader.readLine()
                    if (line != null) error.appendLine(line)
                }
                try {
                    val exitCode = process.exitValue()
                    // Process finished: drain remaining data from streams
                    while (outputReader.ready()) {
                        val line = outputReader.readLine()
                        if (line != null) output.appendLine(line)
                    }
                    while (errorReader.ready()) {
                        val line = errorReader.readLine()
                        if (line != null) error.appendLine(line)
                    }
                    return ExecResult(exitCode, output.toString().trim(), error.toString().trim())
                } catch (e: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            
            process.destroyForcibly()
            ExecResult(-1, output.toString().trim(), "Timeout")
        } catch (e: Exception) {
            ExecResult(-1, "", e.message ?: "Unknown error")
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
