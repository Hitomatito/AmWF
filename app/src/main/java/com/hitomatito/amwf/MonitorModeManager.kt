package com.hitomatito.amwf

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class MonitorModeManager {

    private val interfaceName = "wlan0"
    private val conModePath = "/sys/module/wlan/parameters/con_mode"

    companion object {
        private const val TAG = "MonitorMode"
        private const val COMMAND_TIMEOUT = 5000L
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
            execCommand("ip link set $interfaceName down")
            
            val writeResult = execCommand("echo 4 > $conModePath")
            if (writeResult.error.contains("denied") || writeResult.error.contains("readonly")) {
                return MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_selinux,
                    info = writeResult.error
                )
            }
            
            execCommand("ip link set $interfaceName up")
            Thread.sleep(500)

            val info = execCommand("iw dev $interfaceName info").output
            val mode = parseMode(info)

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
            execCommand("ip link set $interfaceName down")
            
            execCommand("echo 0 > $conModePath")
            
            execCommand("ip link set $interfaceName up")
            Thread.sleep(500)

            val info = execCommand("iw dev $interfaceName info").output
            val mode = parseMode(info)

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
            val info = execCommand("iw dev $interfaceName info").output
            val mode = parseMode(info)
            
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
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "su -c '$command'"))
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < COMMAND_TIMEOUT) {
                if (process.inputStream.available() > 0) {
                    output.append(outputReader.readText())
                }
                if (process.errorStream.available() > 0) {
                    error.append(errorReader.readText())
                }
                try {
                    val exitCode = process.exitValue()
                    if (outputReader.ready()) output.append(outputReader.readText())
                    if (errorReader.ready()) error.append(errorReader.readText())
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
