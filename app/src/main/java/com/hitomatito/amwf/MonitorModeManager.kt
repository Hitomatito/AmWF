/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import com.hitomatito.amwf.ShellExecutor.CON_MODE_WRITE_TIMEOUT_MS
import com.hitomatito.amwf.ShellExecutor.execRoot
import com.hitomatito.amwf.ShellExecutor.logD
import com.hitomatito.amwf.ShellExecutor.logE
import com.hitomatito.amwf.ShellExecutor.logW
import com.hitomatito.amwf.wifi.pixiedust.interfacesFromSysfsListing

class MonitorModeManager {

    // No es lazy: se refresca al inicio de cada operación. Si se fijara una sola
    // vez, un cambio del estado del radio (WiFi apagada, driver recargado) dejaría
    // las operaciones apuntando a una interfaz equivocada.
    private var interfaceName: String = "wlan0"

    companion object {
        private const val TAG = "MonitorMode"
        private const val INTERFACE_DOWN_TIMEOUT = 3000L

        /**
         * Plazo para que la recarga del driver refleje la escritura de
         * con_mode: documentada hasta ~10 s (misma latencia que la escritura
         * en sí, ver el invariante de CON_MODE_WRITE_TIMEOUT_MS).
         */
        private const val MONITOR_STATE_TIMEOUT_MS = 10_000L
        private const val MONITOR_STATE_POLL_MS = 1_000L
    }

    /**
     * Detects the WiFi interface dynamically. Tries wlan0, wlan1, p2p0, etc.,
     * falls back to the sysfs listing (same strategy as the compatibility
     * check) and only then to the "wlan0" placeholder.
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
        // Respaldo de sysfs, como el que ya usa el check de compatibilidad:
        // un dispositivo con la interfaz fuera de la lista de candidatos debe
        // operar sobre su interfaz real, no caer a un "wlan0" inventado (con él
        // `ip link set down` fallaría y el enable abortaría sin motivo). Se
        // reutiliza el parser puro y testeado del lado Pixie.
        val fromSysfs = interfacesFromSysfsListing(
            execRoot("ls /sys/class/net 2>/dev/null").output
        ).firstOrNull()
        if (fromSysfs != null) {
            logD(TAG, "Found WiFi interface (sysfs fallback): $fromSysfs")
            return fromSysfs
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

    fun enableMonitorMode(preferredChannel: WifiChannel): MonitorResult {
        logD(TAG, "=== enableMonitorMode() ===")
        
        if (!isRootAvailable()) {
            return MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_root,
                infoRes = R.string.error_root_required
            )
        }
        interfaceName = findWifiInterface()

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
                    infoRes = R.string.error_wifi_interface_down
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
                    infoRes = R.string.error_con_mode_unavailable
                )
            }

            // Step 3: Write con_mode=4 (monitor mode)
            logD(TAG, "Writing con_mode=4 to $conModePath...")
            val writeResult = execRoot(
                "echo 4 > $conModePath",
                timeoutMs = CON_MODE_WRITE_TIMEOUT_MS
            )
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
                    infoRes = R.string.error_write_mode
                )
            }

            // Step 4: Bring interface UP
            logD(TAG, "Bringing $interfaceName UP...")
            execRoot("ip link set $interfaceName up")
            Thread.sleep(500)

            // Step 5: Sintonizar la radio. En QCACLD el monitor nace SIN canal
            // (con_mode=4 solo habilita la entrega de frames crudos); sin esta
            // sintonía la interfaz "en monitor" no recibe nada.
            val tune = tuneMonitorRadio(interfaceName, preferredChannel)

            // Paso 5b: la recarga del driver tras la escritura de con_mode
            // (documentada: hasta ~10 s) deja a `iw dev info` mostrando
            // "managed" o vacío un rato aunque la escritura haya surtido
            // efecto. Leer el estado medio segundo después produce falsos
            // fallos con con_mode=4 ya escrito y el framework apagado. Todos
            // los pasos previos verifican con reintentos; el estado final
            // también los necesita. Si aun así no es MONITOR, se reporta con
            // el radio tal cual: nunca se enciende el WiFi del framework
            // sobre una radio posiblemente a medio camino del monitor.
            val mode = waitForMonitorMode()

            when (mode) {
                MonitorMode.MONITOR -> MonitorResult(
                    type = MonitorMode.MONITOR,
                    statusRes = R.string.monitor_mode_on,
                    infoRes = infoResFor(mode),
                    infoArg = interfaceName,
                    tunedChannel = tune.tunedChannel,
                    iwAvailable = tune.iwAvailable
                )
                else -> MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    infoRes = infoResFor(mode),
                    infoArg = interfaceName,
                    tunedChannel = tune.tunedChannel,
                    iwAvailable = tune.iwAvailable
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
                infoRes = R.string.error_internal_enable
            )
        }
    }

    fun disableMonitorMode(): MonitorResult {
        logD(TAG, "=== disableMonitorMode() ===")
        
        if (!isRootAvailable()) {
            return MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_root,
                infoRes = R.string.error_root_required
            )
        }
        interfaceName = findWifiInterface()

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
                    infoRes = R.string.error_wifi_interface_down
                )
            }
            logD(TAG, "$interfaceName is DOWN, proceeding...")

            // Step 2: Write con_mode=0 (managed mode)
            val conModePath = resolveConModePath()
            if (conModePath != null) {
                logD(TAG, "Writing con_mode=0 to $conModePath...")
                val writeResult = execRoot(
                    "echo 0 > $conModePath",
                    timeoutMs = CON_MODE_WRITE_TIMEOUT_MS
                )
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

            val mode = getCurrentState()

            when (mode) {
                MonitorMode.MANAGED -> MonitorResult(
                    type = MonitorMode.MANAGED,
                    statusRes = R.string.monitor_mode_normal,
                    infoRes = infoResFor(mode),
                    infoArg = interfaceName
                )
                else -> MonitorResult(
                    type = MonitorMode.UNKNOWN,
                    statusRes = R.string.error_unknown,
                    infoRes = infoResFor(mode),
                    infoArg = interfaceName
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
                infoRes = R.string.error_internal_disable
            )
        }
    }

    /**
     * Restarts the Android WiFi service to reinitialize the driver
     * after changing con_mode. This ensures the WiFi radio is properly
     * activated in managed mode.
     */
    private fun restartWifiService() = restartWifi()

    fun getCurrentMode(): MonitorResult {
        logD(TAG, "=== getCurrentMode() ===")
        
        if (!isRootAvailable()) {
            return MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_root,
                infoRes = R.string.error_root_check_failed
            )
        }
        interfaceName = findWifiInterface()

        return try {
            val mode = getCurrentState()
            val iwInfoText = execRoot(MonitorChannelPolicy.infoCommand(interfaceName)).output
            val tuned = if (mode == MonitorMode.MONITOR) {
                MonitorChannelPolicy.parseTunedChannel(iwInfoText)
            } else {
                null
            }

            val statusRes = when (mode) {
                MonitorMode.MONITOR -> R.string.monitor_mode_on
                MonitorMode.MANAGED -> R.string.monitor_mode_normal
                MonitorMode.UNKNOWN -> R.string.status_unknown
            }

            // "iw dev info" puede devolver vacio transitoriamente (recarga del
            // driver) aun con iw instalado: la presencia se comprueba con
            // command -v, no con la salida del comando anterior.
            val iwAvailable = mode != MonitorMode.MONITOR || hasIwTool()

            MonitorResult(
                type = mode,
                statusRes = statusRes,
                infoRes = infoResFor(mode),
                infoArg = interfaceName,
                tunedChannel = tuned,
                iwAvailable = iwAvailable
            )
        } catch (e: Exception) {
            logE(TAG, "Exception in getCurrentMode: ${e.message}", e)
            MonitorResult(
                type = MonitorMode.UNKNOWN,
                statusRes = R.string.error_unknown,
                infoRes = R.string.error_internal_status
            )
        }
    }

    /**
     * Re-sintoniza el canal del monitor sin tocar con_mode (selector de la UI).
     * Debe llamarse en un hilo IO.
     */
    internal fun retuneMonitor(target: WifiChannel): TuneOutcome {
        logD(TAG, "=== retuneMonitor(ch ${target.number}) ===")
        interfaceName = findWifiInterface()
        return tuneMonitorRadio(interfaceName, target)
    }

    /**
     * Aplica `iw dev <iface> set type monitor` (si hace falta) y el canal
     * [target]. Muchos drivers QCACLD no reflejan el canal en `iw dev info`
     * durante el monitor, asi que la verificacion primaria es el codigo de
     * salida de `iw set channel`; si el driver si lo reporta, debe coincidir.
     */
    private fun tuneMonitorRadio(iface: String, target: WifiChannel): TuneOutcome {
        val iwInfo = execRoot(MonitorChannelPolicy.infoCommand(iface)).output
        if (iwInfo.isBlank() && !hasIwTool()) {
            logW(TAG, "iw not available; monitor radio stays untuned")
            return TuneOutcome(iwAvailable = false)
        }
        if (!MonitorChannelPolicy.reportsMonitorType(iwInfo)) {
            logD(TAG, "Interface not in monitor type; applying set type monitor")
            execRoot(MonitorChannelPolicy.setMonitorTypeCommand(iface))
            Thread.sleep(300)
        }
        logD(TAG, "Tuning $iface to channel ${target.number} (${target.frequencyMHz} MHz)")
        val setResult = execRoot(MonitorChannelPolicy.setChannelCommand(iface, target))
        val reported = MonitorChannelPolicy.parseTunedChannel(
            execRoot(MonitorChannelPolicy.infoCommand(iface)).output
        )
        return if (MonitorChannelPolicy.isTuneAccepted(setResult.exitCode, reported, target)) {
            TuneOutcome(iwAvailable = true, tunedChannel = reported ?: target)
        } else {
            logW(TAG, "Tune rejected: exit=${setResult.exitCode} iw reports ${reported?.number ?: "nothing"}")
            TuneOutcome(iwAvailable = true)
        }
    }

    private fun hasIwTool(): Boolean =
        execRoot("command -v iw 2>/dev/null").output.isNotBlank()

    /**
     * Espera a que el estado del radio refleje la escritura de con_mode.
     *
     * La recarga del driver QCACLD (documentada: hasta ~10 s) deja lecturas
     * transitorias ("type managed", o `iw`/`ip -d link` vacíos durante la
     * recarga) aunque la escritura haya surtido efecto: sin esta espera, un
     * driver lento reporta un fallo que no lo es. Devuelve MONITOR en cuanto
     * se confirma; si vence el plazo, la última lectura (el fallo se reporta
     * con el radio tal cual — restaurar aquí sería encender el WiFi del
     * framework sobre una radio a medio camino del monitor).
     */
    private fun waitForMonitorMode(): MonitorMode {
        val deadline = System.currentTimeMillis() + MONITOR_STATE_TIMEOUT_MS
        var mode = getCurrentState()
        while (mode != MonitorMode.MONITOR && System.currentTimeMillis() < deadline) {
            Thread.sleep(MONITOR_STATE_POLL_MS)
            mode = getCurrentState()
        }
        return mode
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

    private fun getCurrentState(): MonitorMode {
        // 1) Intentar con "iw" (si el binario existe en el dispositivo)
        val iwInfo = execRoot("iw dev $interfaceName info").output
        if (iwInfo.isNotEmpty()) {
            return parseMode(iwInfo)
        }
        // 2) Sin "iw": el tipo de enlace radiotap (802.11 crudo) es la
        //    señal de que con_mode=4 esta activo.
        val linkInfo = execRoot("ip -d link show $interfaceName").output
        return when {
            linkInfo.contains("ieee802.11/radiotap", ignoreCase = true) -> MonitorMode.MONITOR
            linkInfo.contains("link/ether", ignoreCase = true) -> MonitorMode.MANAGED
            else -> MonitorMode.UNKNOWN
        }
    }

    // Resumen corto localizado para la tarjeta de informacion
    // (evita el volcado bruto de ip/iw).
    private fun infoResFor(mode: MonitorMode): Int = when (mode) {
        MonitorMode.MONITOR -> R.string.info_mode_monitor
        MonitorMode.MANAGED -> R.string.info_mode_managed
        MonitorMode.UNKNOWN -> R.string.info_mode_unknown
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
    val info: String = "",
    // Recurso localizado para la linea de informacion. Si es 0 se usa `info`.
    val infoRes: Int = 0,
    // Argumento de formato (%1$s) para infoRes (normalmente el nombre de la interfaz).
    val infoArg: String = "",
    // Canal del monitor que la app pudo aplicar (o null si no hay `iw` o el
    // driver rechazo la sintonia). Cuando `iw dev info` no refleja el canal,
    // es el canal intentado, no una lectura verificada.
    val tunedChannel: WifiChannel? = null,
    // false => el dispositivo no tiene `iw`: la radio no puede sintonizarse.
    val iwAvailable: Boolean = true
)

/** Resultado de intentar sintonizar el monitor. */
internal data class TuneOutcome(
    val iwAvailable: Boolean,
    val tunedChannel: WifiChannel? = null
)

enum class MonitorMode {
    MONITOR, MANAGED, UNKNOWN
}
