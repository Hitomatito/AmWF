/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import android.annotation.SuppressLint
import android.content.Context
import com.hitomatito.amwf.ShellExecutor.execNoRoot
import com.hitomatito.amwf.ShellExecutor.execRoot
import com.hitomatito.amwf.ShellExecutor.logD
import com.hitomatito.amwf.ShellExecutor.logE
import com.hitomatito.amwf.ShellExecutor.logW
import com.hitomatito.amwf.wifi.pixiedust.NativeBinaries
import java.io.File

/**
 * Modulos WiFi Qualcomm conocidos que exponen con_mode. El sufijo del driver
 * out-of-tree ("qca_cld3_") varia, por eso ademas de nombres exactos se aceptan
 * coincidencias por pista (ver [WIFI_MODULE_HINTS]).
 */
private val KNOWN_CON_MODE_MODULES = listOf(
    "wlan",             // POCO X3, Snapdragon 860 (codename vayu)
    "hdd",              // Xiaomi 11T Pro, Snapdragon 888 (lahaina)
    "kiwi_v2",          // Snapdragon 8 Gen 2 (cape)
    "kiwi",             // Snapdragon 6/7 series
    "wcn",              // Qualcomm WCN6xxx
    "qca_cld3_kiwi_v2"  // Galaxy Z Fold7, Snapdragon 8 Elite (sun)
)

/**
 * Pistas para reconocer el modulo WiFi cuando el nombre no esta en la lista
 * exacta. Cubre variantes del driver out-of-tree como "qca_cld3_kiwi_v2".
 */
private val WIFI_MODULE_HINTS = listOf("cld", "wlan", "hdd", "kiwi", "wcn", "qca")

/**
 * Elige la ruta de con_mode mas probable a partir de las rutas candidatas.
 * Prioriza los nombres de modulo conocidos y, si ninguno coincide, cualquier
 * modulo que parezca WiFi. Funcion pura para poder unit-testearla.
 */
internal fun selectConModePath(candidates: List<String>): String? {
    for (module in KNOWN_CON_MODE_MODULES) {
        val path = "/sys/module/$module/parameters/con_mode"
        if (path in candidates) return path
    }
    return candidates.firstOrNull { candidate ->
        val module = candidate.removePrefix("/sys/module/").substringBefore('/')
        WIFI_MODULE_HINTS.any { module.contains(it) }
    }
}

/**
 * Encuentra la ruta real del archivo con_mode del driver WiFi Qualcomm (QCACLD).
 * El nombre del modulo varia segun el chip/dispositivo: "wlan" (POCO X3, Snapdragon 860),
 * "hdd" (Xiaomi 11T Pro / Snapdragon 888), "qca_cld3_kiwi_v2" (Galaxy Z Fold7,
 * Snapdragon 8 Elite), etc. Sin esto el metodo con_mode falla en HW mas reciente.
 *
 * La deteccion se hace con root: en Android moderno SELinux impide al UID de la
 * app leer /sys/module, por lo que un escaneo directo con File no encuentra la
 * ruta aunque exista. `su` si puede leer sysfs.
 */
fun resolveConModePath(): String? {
    // 1) Via rapida: modulos conocidos visibles para el UID de la app.
    for (module in KNOWN_CON_MODE_MODULES) {
        val path = "/sys/module/$module/parameters/con_mode"
        if (File(path).exists()) return path
    }

    // 2) Descubrimiento con root: su puede listar sysfs aunque la app no pueda.
    val discovered = execRoot("ls -d /sys/module/*/parameters/con_mode 2>/dev/null")
        .output
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("/sys/module/") && it.endsWith("/parameters/con_mode") }
        .toList()
    selectConModePath(discovered)?.let { return it }

    // 3) Ultimo recurso: escaneo directo (funciona con SELinux permisivo).
    File("/sys/module").listFiles().orEmpty().forEach { dir ->
        if (dir.isDirectory) {
            val path = File(dir, "parameters/con_mode")
            if (path.exists()) return path.absolutePath
        }
    }
    return null
}

data class CompatibilityResult(
    val isCompatible: Boolean,
    val issues: List<CompatibilityIssue>,
    val deviceInfo: DeviceInfo
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val chipset: String,
    val kernelArch: String,
    val cpuAbi: String,
    val wlanDriver: String,
    val conModePath: String?,
    val rootInfo: RootInfo,
    val capabilities: CapabilitiesInfo,
    val pixieDustBinaries: PixieDustBinariesInfo
)

data class CapabilitiesInfo(
    val canInject: Boolean?,
    val canCapture: Boolean?,
    val isPassiveOnly: Boolean,
    val tested: Boolean
)

/**
 * Información sobre los binarios de Pixie Dust que faltan en el dispositivo.
 * La detección la realiza [NativeBinaries] (assets copiados a filesDir y
 * rutas del sistema), de modo que la vista de compatibilidad y el ataque
 * real comparten la misma fuente de verdad.
 */
data class PixieDustBinariesInfo(
    val missing: List<String> = emptyList()
) {
    val allAvailable: Boolean
        get() = missing.isEmpty()
}

data class RootInfo(
    val isRooted: Boolean,
    val rootType: RootType,
    val rootName: String,
    val version: String
)

enum class RootType {
    MAGISK,
    KERNELSU,
    APATCH,
    PHH_SU,
    SUPERSU,
    OTHER,
    NONE
}

data class CompatibilityIssue(
    val type: IssueType,
    val severity: Severity,
    val message: String,
    val suggestion: String
)

enum class IssueType {
    ROOT,
    ARCHITECTURE,
    CHIPSET,
    DRIVER,
    SYSFS,
    SELINUX,
    PIXIE_DUST
}

enum class Severity {
    CRITICAL,
    WARNING,
    INFO
}

class DeviceCompatibilityChecker(private val context: Context) {

    companion object {
        private const val TAG = "CompatChecker"
        
        private val SU_PATHS = listOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/vendor/bin/su"
        )
        
        // Rutas absolutas de binarios de OTROS paquetes/dominios (termux, SuperSU),
        // heredadas de la detección clásica de root. No se pueden derivar de
        // getFilesDir(): son ubicaciones fijas del sistema de ficheros raíz.
        @SuppressLint("SdCardPath")
        private val ROOT_INDICATORS = mapOf(
            RootType.MAGISK to listOf(
                "/data/adb/magisk",
                "/data/adb/magisk/busybox",
                "/sbin/.magisk"
            ),
            RootType.KERNELSU to listOf(
                "/data/adb/ksu",
                "/data/ksu",
                "/system/bin/ksud",
                "/system/xbin/ksud",
                "/data/data/com.termux/files/usr/bin/ksu"
            ),
            RootType.APATCH to listOf(
                "/data/adb/ap",
                "/data/ap",
                "/system/bin/apd"
            ),
            RootType.PHH_SU to listOf(
                "/system/xbin/phh-su",
                "/system/bin/phh-su"
            ),
            RootType.SUPERSU to listOf(
                "/data/su",
                "/system/su.d",
                "/data/data/eu.chainfire.supersu"
            )
        )
        
        private val SNAPDRAGON_CODENAMES = mapOf(
            "bengal" to "Snapdragon 662/665/680 (Bengal)",
            "kona" to "Snapdragon 865/870 (Kona)",
            "lahaina" to "Snapdragon 888/888+ (Lahaina)",
            "taro" to "Snapdragon 8 Gen 1 (Taro)",
            "cape" to "Snapdragon 8 Gen 2 (Cape)",
            "kalama" to "Snapdragon 8 Gen 3 (Kalama)",
            "pineapple" to "Snapdragon 7 Gen 1/2 (Pineapple)",
            "yupik" to "Snapdragon 7 Gen 1 (Yupik)",
            "qsm" to "Snapdragon 6 Gen 1/2 (QSM)",
            "crown" to "Snapdragon 8s Gen 3 (Crow)",
            "waipio" to "Snapdragon 778G/780G (Waipio)",
            "lito" to "Snapdragon 768G (Lito)",
            "atoll" to "Snapdragon 750G (Atoll)",
            "sm6350" to "Snapdragon 695 (SM6350)",
            "sm7225" to "Snapdragon 750G (SM7225)",
            "vayu" to "Snapdragon 860 (Vayu)",
            "alioth" to "Snapdragon 870 (Alioth)",
            "haydn" to "Snapdragon 888 (Haydn)",
            "corona" to "Snapdragon 720G (Corona)",
            "trinket" to "Snapdragon 665/670/675 (Trinket)",
            "holi" to "Snapdragon 730G (Holi)",
            "msmnile" to "Snapdragon 855/865/870 (CPU Part: 0x072)",
            "cpu7pro" to "Snapdragon 870 (CPU7 Pro)",
            "saipan" to "Snapdragon 7c Gen 2 (Saipan)"
        )

        private val SNAPDRAGON_CPU_PARTS = mapOf(
            "0x001" to "Snapdragon S1",
            "0x002" to "Snapdragon S2",
            "0x003" to "Snapdragon S3",
            "0x004" to "Snapdragon S4",
            "0x006" to "Snapdragon 400/410/412",
            "0x00C" to "Snapdragon 410/412",
            "0x011" to "Snapdragon 800 (APQ8064)",
            "0x012" to "Snapdragon 800",
            "0x013" to "Snapdragon 800",
            "0x014" to "Snapdragon 800 (MSM8x30)",
            "0x018" to "Snapdragon 615/616",
            "0x019" to "Snapdragon 610",
            "0x020" to "Snapdragon 410",
            "0x023" to "Snapdragon 617",
            "0x027" to "Snapdragon 415",
            "0x030" to "Snapdragon 620/618",
            "0x031" to "Snapdragon 616",
            "0x032" to "Snapdragon 615",
            "0x033" to "Snapdragon 612",
            "0x035" to "Snapdragon 439",
            "0x037" to "Snapdragon 429",
            "0x038" to "Snapdragon 632",
            "0x040" to "Snapdragon 660",
            "0x041" to "Snapdragon 636",
            "0x044" to "Snapdragon 670",
            "0x046" to "Snapdragon 675",
            "0x050" to "Snapdragon 710",
            "0x051" to "Snapdragon 712",
            "0x055" to "Snapdragon 665",
            "0x056" to "Snapdragon 730/730G",
            "0x060" to "Snapdragon 765/765G",
            "0x061" to "Snapdragon 768G",
            "0x062" to "Snapdragon 750G",
            "0x063" to "Snapdragon 690 5G",
            "0x066" to "Snapdragon 778G",
            "0x068" to "Snapdragon 780G",
            "0x070" to "Snapdragon 855/855+",
            "0x071" to "Snapdragon 855/855+",
            "0x072" to "Snapdragon 865/865+",
            "0x073" to "Snapdragon 870",
            "0x080" to "Snapdragon 888/888+",
            "0x081" to "Snapdragon 8 Gen 1",
            "0x083" to "Snapdragon 8+ Gen 1",
            "0x084" to "Snapdragon 8 Gen 2",
            "0x085" to "Snapdragon 8+ Gen 2",
            "0x086" to "Snapdragon 8 Gen 3",
            "0x090" to "Snapdragon 7c/7c+",
            "0x092" to "Snapdragon 7 Gen 1",
            "0x094" to "Snapdragon 7+ Gen 2",
            "0x0A0" to "Snapdragon 6 Gen 1",
            "0x0A1" to "Snapdragon 6 Gen 2",
            "0x0B0" to "Snapdragon 4 Gen 1",
            "0x0B1" to "Snapdragon 4 Gen 2"
        )

        /**
         * Busca el nombre comercial de un Snapdragon por su "CPU part" de
         * /proc/cpuinfo. Comparación insensible a mayúsculas para que el prefijo
         * funcione tanto como "0x..." (estándar) como "0X..." (tras un uppercase).
         */
        internal fun resolveSnapdragonByCpuPart(cpuPart: String): String? =
            SNAPDRAGON_CPU_PARTS.entries
                .firstOrNull { it.key.uppercase() == cpuPart.trim().uppercase() }
                ?.value

        /**
         * Extrae el número de frames "N packets captured" del resumen de tcpdump.
         * Función pura (no requiere instancia) para poder unit-testearla.
         */
        internal fun countCapturedPackets(output: String): Int =
            Regex("(\\d+)\\s+packets? captured").find(output)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Ejecuta la comprobación completa. [onProgress] recibe (porcentaje, fase)
     * desde el hilo IO; la UI debe volcarlo a su hilo principal. Los porcentajes
     * son hitos reales del flujo, no una animación simulada.
     */
    fun checkCompatibility(
        onProgress: (percent: Int, phase: String) -> Unit = { _, _ -> }
    ): CompatibilityResult {
        val issues = mutableListOf<CompatibilityIssue>()
        val phase = { percent: Int, res: Int -> onProgress(percent, context.getString(res)) }

        logD(TAG, "=== Starting compatibility check ===")

        try {
            return runCompatibilityChecks(phase, issues)
        } finally {
            // Toda salida (éxito, fallo o excepción) debe restaurar el radio:
            // el check lo apaga antes de escribir con_mode.
            restoreWifiAfterCheck(phase)
        }
    }

    private fun runCompatibilityChecks(
        phase: (Int, Int) -> Unit,
        issues: MutableList<CompatibilityIssue>
    ): CompatibilityResult {
        // Apaga el radio WiFi ANTES de los tests de escritura a con_mode.
        // Con el cliente conectado, "echo 4 > con_mode" falla con
        // "Operation not permitted", lo que marcaría un dispositivo
        // realmente compatible como no compatible. El radio se restaura
        // una única vez al final de checkCompatibility().
        phase(5, R.string.phase_wifi_disable)
        execRoot("svc wifi disable")
        Thread.sleep(1000)

        phase(15, R.string.phase_detect)
        val deviceInfo = gatherDeviceInfo(phase)
        logD(TAG, "Device: ${deviceInfo.manufacturer} ${deviceInfo.model}")
        logD(TAG, "Chipset: ${deviceInfo.chipset}")
        logD(TAG, "Root: ${deviceInfo.rootInfo.rootName} v${deviceInfo.rootInfo.version}")

        if (!deviceInfo.rootInfo.isRooted) {
            issues.add(CompatibilityIssue(
                type = IssueType.ROOT,
                severity = Severity.CRITICAL,
                message = context.getString(R.string.issue_root_message),
                suggestion = context.getString(R.string.issue_root_suggestion)
            ))
        }

        if (!isSnapdragon(deviceInfo.chipset)) {
            issues.add(CompatibilityIssue(
                type = IssueType.CHIPSET,
                severity = Severity.CRITICAL,
                message = context.getString(R.string.issue_chipset_message, deviceInfo.chipset),
                suggestion = context.getString(R.string.issue_chipset_suggestion)
            ))
        }

        if (deviceInfo.conModePath == null) {
            val legacyDriver = File("/sys/module/wlan").exists()
            issues.add(CompatibilityIssue(
                type = IssueType.SYSFS,
                severity = Severity.CRITICAL,
                message = context.getString(R.string.issue_conmode_missing_message),
                suggestion = if (legacyDriver) {
                    context.getString(R.string.issue_conmode_missing_suggestion_legacy)
                } else {
                    context.getString(R.string.issue_conmode_missing_suggestion_cnss)
                }
            ))
        } else {
            phase(88, R.string.phase_conmode_write)
            val testResult = testConModeWrite(deviceInfo.conModePath)
            if (!testResult) {
                issues.add(CompatibilityIssue(
                    type = IssueType.SYSFS,
                    severity = Severity.CRITICAL,
                    message = context.getString(R.string.issue_conmode_write_message),
                    suggestion = context.getString(R.string.issue_conmode_write_suggestion)
                ))
            }
        }

        // Verificar binarios de Pixie Dust (wpa_supplicant, wpa_cli, pixiewps)
        val missingPixieBinaries = deviceInfo.pixieDustBinaries.missing
        if (missingPixieBinaries.isNotEmpty()) {
            issues.add(CompatibilityIssue(
                type = IssueType.PIXIE_DUST,
                severity = Severity.WARNING,
                message = NativeBinaries.missingBinariesSummary(
                    context,
                    missingPixieBinaries
                ).orEmpty(),
                suggestion = NativeBinaries.missingBinariesMessage(
                    context,
                    missingPixieBinaries
                ).orEmpty()
            ))
        }

        val isCompatible = issues.none { it.severity == Severity.CRITICAL }
        
        logD(TAG, "=== Compatibility check complete ===")
        logD(TAG, "Compatible: $isCompatible")

        return CompatibilityResult(
            isCompatible = isCompatible,
            issues = issues,
            deviceInfo = deviceInfo
        )
    }

    /**
     * Restart WiFi service ONCE after ALL con_mode tests complete.
     * Both testCapabilities() and testConModeWrite() modify con_mode,
     * which disrupts WiFi. A single restart here ensures WiFi is
     * properly restored after all tests finish, including exceptional exits.
     */
    private fun restoreWifiAfterCheck(phase: (Int, Int) -> Unit) {
        try {
            phase(95, R.string.phase_restore)
        } catch (_: Exception) {
            // El progreso es best-effort; nunca debe impedir la restauración.
        }
        logD(TAG, "Restarting WiFi service to restore managed mode after all tests")
        try {
            execRoot("svc wifi disable")
            Thread.sleep(1000)
            execRoot("svc wifi enable")
            Thread.sleep(1000)
        } catch (e: Exception) {
            logE(TAG, "Failed to restart WiFi service: ${e.message}", e)
        }
    }

    private fun testConModeWrite(path: String): Boolean {
        logD(TAG, "Testing con_mode write at $path")

        // Save original value before testing
        val originalValue = execRoot("cat $path 2>/dev/null").output.trim()
        logD(TAG, "Original con_mode value: $originalValue")

        try {
            // El código de salida real lo imprime el último `echo`: el exit code
            // de `su -c` es el de ese echo (siempre 0), así que no sirve para
            // detectar el fallo de escritura. stderr va a /dev/null para no
            // volcar el mensaje de error dentro del propio con_mode.
            val testResult = execRoot("echo 4 > $path 2>/dev/null; echo \$?")
            val echoedCode = testResult.output.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.toIntOrNull() != null }
                ?.toIntOrNull()

            val timedOut = testResult.exitCode != 0
            val writeFailed = echoedCode == null || echoedCode != 0
            val permissionError = listOf(
                "denied", "Permission", "readonly", "Read-only",
                "Operation not permitted", "Input/output error"
            ).any { testResult.error.contains(it, ignoreCase = true) }

            if (timedOut || writeFailed || permissionError) {
                logD(
                    TAG,
                    "Write test failed: exit=${testResult.exitCode}, code=$echoedCode, " +
                        "error=${testResult.error}"
                )
                return false
            }

            logD(TAG, "Write test passed")
            return true
        } catch (e: Exception) {
            logE(TAG, "Exception during write test: ${e.message}", e)
            return false
        } finally {
            // CRITICAL: Always restore original value. Si la lectura falló, no
            // se escribe nada: `echo > con_mode` dejaría un valor inválido.
            if (originalValue.isNotEmpty()) {
                logD(TAG, "Restoring con_mode to: $originalValue")
                try {
                    execRoot("echo $originalValue > $path 2>/dev/null")
                } catch (e: Exception) {
                    logE(TAG, "Failed to restore con_mode: ${e.message}", e)
                }
            } else {
                logW(TAG, "No original con_mode value; skipping restore")
            }
        }
    }

    private fun detectRoot(): RootInfo {
        logD(TAG, "=== Detecting root type ===")
        
        for ((rootType, indicators) in ROOT_INDICATORS) {
            for (path in indicators) {
                if (File(path).exists()) {
                    val version = getRootVersion(rootType)
                    logD(TAG, "Found $rootType at $path, version: $version")
                    return RootInfo(
                        isRooted = true,
                        rootType = rootType,
                        rootName = getRootDisplayName(rootType),
                        version = version
                    )
                }
            }
        }
        
        logD(TAG, "No known root dirs found, testing su commands...")
        
        val suCommands = listOf(
            "su -c id",
            "id",
            "/system/xbin/su -c id",
            "/system/bin/su -c id",
            "su0 id",
            "ksu id"
        )
        
        for (command in suCommands) {
            val result = execNoRoot(command)
            logD(TAG, "Testing '$command': ${result.output}")
            if (result.output.contains("uid=0") || result.output.contains("root")) {
                val rootType = when {
                    command.contains("ksu") -> RootType.KERNELSU
                    command.contains("magisk") -> RootType.MAGISK
                    else -> RootType.OTHER
                }
                val version = getRootVersion(rootType)
                return RootInfo(
                    isRooted = true,
                    rootType = rootType,
                    rootName = getRootDisplayName(rootType),
                    version = version
                )
            }
        }
        
        logD(TAG, "=== No root detected ===")
        return RootInfo(
            isRooted = false,
            rootType = RootType.NONE,
            rootName = "No Root",
            version = ""
        )
    }

    private fun getRootVersion(rootType: RootType): String {
        return when (rootType) {
            RootType.MAGISK -> {
                execNoRoot("magisk -v").output.ifEmpty {
                    File("/data/adb/magisk/util_functions.sh").let { 
                        if (it.exists()) "Installed" else "Unknown"
                    }
                }
            }
            RootType.KERNELSU -> {
                execNoRoot("cat /proc/ksu/version 2>/dev/null").output.ifEmpty { "Installed" }
            }
            RootType.APATCH -> {
                execNoRoot("cat /data/adb/ap/version 2>/dev/null").output.ifEmpty { "Installed" }
            }
            else -> "Installed"
        }
    }

    private fun getRootDisplayName(rootType: RootType): String {
        return when (rootType) {
            RootType.MAGISK -> "Magisk"
            RootType.KERNELSU -> "KernelSU"
            RootType.APATCH -> "APatch"
            RootType.PHH_SU -> "phh-su"
            RootType.SUPERSU -> "SuperSU"
            RootType.OTHER -> "Root"
            RootType.NONE -> "No Root"
        }
    }

    private fun gatherDeviceInfo(phase: (Int, Int) -> Unit): DeviceInfo {
        val manufacturer = getSystemProperty("ro.product.manufacturer")
        val model = getSystemProperty("ro.product.model")
        val chipset = detectChipset()
        val kernelArch = execNoRoot("uname -m").output.ifEmpty { "Unknown" }
        val cpuAbi = getSystemProperty("ro.product.cpu.abi")
        val wlanDriver = detectWlanDriver()
        phase(25, R.string.phase_conmode)
        val conMode = resolveConModePath()
        phase(35, R.string.phase_root)
        val rootInfo = detectRoot()
        phase(50, R.string.phase_capture)
        val capabilities = testCapabilities()
        phase(80, R.string.phase_binaries)
        val pixieDustBinaries = detectPixieDustBinaries()

        return DeviceInfo(
            manufacturer = manufacturer.ifEmpty { "Unknown" },
            model = model.ifEmpty { "Unknown" },
            chipset = chipset,
            kernelArch = kernelArch,
            cpuAbi = cpuAbi.ifEmpty { "Unknown" },
            wlanDriver = wlanDriver,
            conModePath = conMode,
            rootInfo = rootInfo,
            capabilities = capabilities,
            pixieDustBinaries = pixieDustBinaries
        )
    }

    /**
     * Detecta los binarios de Pixie Dust delegando en [NativeBinaries], la
     * misma fuente de verdad que usa [com.hitomatito.amwf.wifi.pixiedust.PixieDustRunner]
     * al ejecutar el ataque. Primero copia los assets empaquetados a filesDir
     * (best effort) y después resuelve cada binario en filesDir o en el sistema.
     */
    private fun detectPixieDustBinaries(): PixieDustBinariesInfo {
        NativeBinaries.ensure(context)
        return PixieDustBinariesInfo(missing = NativeBinaries.missingCriticalBinaries(context))
    }

    /**
     * Comprueba si una herramienta está invocable. Usa `command -v` (builtin
     * POSIX) vía el shell raíz — el PATH raíz incluye rutas que el PATH del
     * shell de la app puede no cubrir — y, como respaldo, busca en las rutas
     * típicas de binarios en Android.
     */
    private fun toolAvailable(tool: String): Boolean {
        if (execRoot("command -v $tool 2>/dev/null").output.isNotBlank()) return true
        return listOf(
            "/system/bin/$tool", "/system/xbin/$tool",
            "/vendor/bin/$tool", "/sbin/$tool"
        ).any { File(it).exists() }
    }

    /**
     * Detecta "la herramienta no existe" en la salida de una orden. Todos los tests
     * reales deben fallar (no pasar en falso) cuando el binario requerido no está
     * instalado: "command not found" no contiene cadenas tipo "command failed" o
     * "Operation not supported", por lo que los tests heurísticos previos lo
     * aceptaban como "correcto" — el origen del falso "Monitor completo".
     */
    private fun outputHasToolError(out: String): Boolean =
        out.contains("command not found") || out.contains("not found") ||
        out.contains("No such file") || out.contains("inaccessible") ||
        out.contains("can't open") || out.contains("No such device")

    /**
     * Prueba REAL de captura: con con_mode=4 y la interfaz arriba, comprueba que
     * de verdad llegan frames 802.11 al sistema.
     * - Con tcpdump: captura frames reales (-c 2) y cuenta el resumen "packets captured".
     * - Sin tcpdump: usa el contador rx_packets del kernel como prueba de recepción
     *   de frames (menos informativo que tcpdump pero mide recepción real, no un flag).
     */
    private fun testRealCapture(interfaceName: String): Boolean {
        // El check arranca con el radio apagado (svc wifi disable) para poder escribir
        // con_mode sin cliente asociado. Para medir captura REAL hay que encender el
        // radio; se apaga de nuevo al salir para no alterar el resto del check
        // (testConModeWrite espera el radio apagado).
        execRoot("svc wifi enable")
        Thread.sleep(1000)

        try {
            // Sube la interfaz; si hay `iw`, la pasa a type monitor (radiotap). Si `iw`
            // no existe, con con_mode=4 el driver igualmente entrega frames 802.11.
            execRoot("ip link set $interfaceName up 2>&1")
            if (toolAvailable("iw")) {
                execRoot("iw dev $interfaceName set type monitor 2>&1")
            }
            Thread.sleep(500)

            if (toolAvailable("tcpdump")) {
                val out = execRoot("timeout 4 tcpdump -i $interfaceName -c 2 -n 2>&1")
                logD(TAG, "tcpdump capture: exit=${out.exitCode} out=${out.output.take(200)}")
                if (outputHasToolError(out.output)) return false
                val captured = countCapturedPackets(out.output)
                logD(TAG, if (captured > 0) "Real capture OK: $captured frames"
                           else "Real capture: 0 frames")
                return captured > 0
            }

            // Fallback sin tcpdump: lectura del contador de RX del kernel.
            val before = execRoot(
                "cat /sys/class/net/$interfaceName/statistics/rx_packets 2>/dev/null"
            ).output.trim().toLongOrNull() ?: -1L
            Thread.sleep(3000)
            val after = execRoot(
                "cat /sys/class/net/$interfaceName/statistics/rx_packets 2>/dev/null"
            ).output.trim().toLongOrNull() ?: -1L
            logD(TAG, "rx_packets delta: $before -> $after")
            return before >= 0 && after > before
        } finally {
            execRoot("svc wifi disable")
        }
    }

    /**
     * Prueba REAL de inyección de frames. Sin una herramienta de inyección
     * (aireplay-ng) no es posible enviar un frame 802.11 arbitrario: `iw` es de
     * solo-consumo nl80211 y la inyección requiere AF_PACKET/aireplay.
     * Si no existe la herramienta, canInject queda sin verificar (null), nunca
     * en falso.
     */
    private fun testRealInjection(interfaceName: String): Boolean? {
        val tool = when {
            toolAvailable("aireplay-ng") -> "aireplay-ng"
            else -> {
                logD(TAG, "No real injection tool (aireplay-ng) found; injection unverified")
                return null
            }
        }
        // aireplay-ng --test inyecta probe requests y espera ACK ("Injection is working!")
        val out = execRoot("timeout 6 $tool --test $interfaceName 2>&1")
        logD(TAG, "injection test: ${out.output.take(300)}")
        if (outputHasToolError(out.output)) return false
        return out.output.contains("Injection is working!", ignoreCase = true)
    }

    private fun testCapabilities(): CapabilitiesInfo {
        logD(TAG, "=== Testing injection/capture capabilities (real tests) ===")

        val conModePath = resolveConModePath()
        if (conModePath == null) {
            return CapabilitiesInfo(null, null, isPassiveOnly = true, tested = false)
        }

        val hasTcpdump = toolAvailable("tcpdump")
        val hasInjectionTool = toolAvailable("aireplay-ng")
        logD(TAG, "Tools - tcpdump: $hasTcpdump, aireplay-ng: $hasInjectionTool")

        // Save original mode BEFORE making any changes
        val originalMode = execRoot("cat $conModePath 2>/dev/null").output.trim()
        logD(TAG, "Original con_mode value: $originalMode")

        try {
            // Set to monitor mode (4)
            execRoot("echo 4 > $conModePath 2>/dev/null")
            Thread.sleep(500)

            val interfaceName = findWifiInterface()
            if (interfaceName.isEmpty()) {
                logW(TAG, "No WiFi interface found for capability test")
                return CapabilitiesInfo(null, null, isPassiveOnly = true, tested = false)
            }

            val canCapture = testRealCapture(interfaceName)
            val canInject = testRealInjection(interfaceName)

            val tested = true // al menos la captura siempre se intenta con datos reales
            val isPassiveOnly = canCapture != true && canInject != true

            logD(TAG, "Capabilities - Inject: $canInject, Capture: $canCapture, " +
                "Passive: $isPassiveOnly")

            return CapabilitiesInfo(canInject, canCapture, isPassiveOnly, tested)
        } catch (e: Exception) {
            logE(TAG, "Exception during capability test: ${e.message}", e)
            return CapabilitiesInfo(null, null, isPassiveOnly = true, tested = false)
        } finally {
            // CRITICAL: Always restore original mode, even if tests fail
            logD(TAG, "Restoring con_mode to original value: $originalMode")
            try {
                execRoot("echo $originalMode > $conModePath 2>/dev/null")
                Thread.sleep(300)
            } catch (e: Exception) {
                logE(TAG, "CRITICAL: Failed to restore con_mode: ${e.message}", e)
            }
            // Note: WiFi service is restarted ONCE at the end of checkCompatibility(),
            // after ALL tests complete, to avoid redundant restarts.
        }
    }

    private fun getSystemProperty(prop: String): String {
        return execNoRoot("getprop $prop").output.ifEmpty {
            execRoot("getprop $prop").output
        }
    }

    private fun detectChipset(): String {
        logD(TAG, "=== Detecting chipset with root access ===")
        
        val socLine = getSystemProperty("ro.hardware")
        logD(TAG, "ro.hardware: $socLine")
        
        val socLower = socLine.lowercase()
        
        for ((codename, chipName) in SNAPDRAGON_CODENAMES) {
            if (socLower.contains(codename.lowercase())) {
                logD(TAG, "Found Snapdragon codename: $codename -> $chipName")
                return chipName
            }
        }
        
        val cpuInfoHardware = execRoot("cat /proc/cpuinfo 2>/dev/null").output
        // Solo lineas unicas: /proc/cpuinfo repite el mismo bloque por cada
        // nucleo y llenaba el buffer del informe con ruido, ocultando el resto.
        val compactCpuInfo = cpuInfoHardware.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
        logD(TAG, "cpuinfo (unique lines):\n$compactCpuInfo")
        
        val hardwareLine = cpuInfoHardware.lines().find { it.contains("Hardware", ignoreCase = true) || it.contains("hardware", ignoreCase = true) }
        val cpuInfoModel = cpuInfoHardware.lines().find { it.contains("model name", ignoreCase = true) }
        val cpuPartLine = cpuInfoHardware.lines().find { it.contains("CPU part", ignoreCase = true) }
        
        logD(TAG, "cpuinfo Hardware: $hardwareLine")
        logD(TAG, "cpuinfo model: $cpuInfoModel")
        logD(TAG, "cpuinfo CPU part: $cpuPartLine")
        
        val socModel = getSystemProperty("ro.product.board")
            .ifEmpty { getSystemProperty("ro.board.platform") }
        logD(TAG, "ro.product.board: $socModel")
        
        val cpuInfoLower = (cpuInfoHardware + (cpuInfoModel ?: "")).lowercase()
        
        for ((codename, chipName) in SNAPDRAGON_CODENAMES) {
            if (cpuInfoLower.contains(codename.lowercase()) || socLower.contains(codename.lowercase())) {
                logD(TAG, "Found in cpuinfo: $codename -> $chipName")
                return chipName
            }
        }
        
        val cpuPartHex = cpuPartLine?.substringAfter(":")?.trim() ?: ""
        if (cpuPartHex.isNotEmpty()) {
            val snapdragonCpuPart = resolveSnapdragonByCpuPart(cpuPartHex)
            if (snapdragonCpuPart != null) {
                logD(TAG, "Found by CPU part: $cpuPartHex -> $snapdragonCpuPart")
                return snapdragonCpuPart
            }
        }
        
        return when {
            socLower.contains("exynos") || cpuInfoLower.contains("exynos") -> "Exynos (Samsung)"
            socLower.contains("mt") || socLower.contains("meditek") || cpuInfoLower.contains("mt") -> "MediaTek"
            socLower.contains("kirin") || cpuInfoLower.contains("kirin") -> "Kirin (Huawei)"
            socLower.contains("qcom") || cpuInfoLower.contains("qcom") || 
            socLower.contains("qualcomm") || cpuInfoLower.contains("qualcomm") ||
            cpuInfoHardware.contains("Snapdragon", ignoreCase = true) -> {
                val modelName = socModel.ifEmpty { socLine }
                if (modelName.isNotEmpty() && modelName.length > 1) "Snapdragon ($modelName)" else "Snapdragon (QCOM)"
            }
            !cpuInfoModel.isNullOrEmpty() -> {
                val model = cpuInfoModel.substringAfter(":").trim()
                if (model.isNotEmpty()) model else cpuInfoHardware.substringAfter(":").trim().ifEmpty { socLine }
            }
            socModel.isNotEmpty() -> socModel
            socLine.isNotEmpty() -> socLine
            else -> "Unknown"
        }
    }

    private fun detectWlanDriver(): String {
        val driverPath = execRoot("readlink /sys/class/net/wlan0/device/driver 2>/dev/null").output.lowercase()
        val modules = execRoot("ls /sys/module/ 2>/dev/null | grep -i wlan").output
        
        return when {
            driverPath.contains("bcmdhd") -> "Broadcom BCMDHD"
            driverPath.contains("brcmfmac") || driverPath.contains("/brcm") -> "Broadcom BRCM"
            driverPath.contains("cnss") -> "Qualcomm cnss_pci (WCN6)"
            modules.contains("wlan") -> "Qualcomm QCACLD (wlan)"
            driverPath.contains("wlan") -> "Qualcomm WCNSS"
            else -> "Unknown"
        }
    }

    /**
     * Detects the WiFi interface dynamically.
     * Tries common interface names and returns first found, or empty string if none.
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
        // Fallback: try to find any wlan interface
        val anyWlan = execRoot("ls /sys/class/net/ 2>/dev/null | grep -E '^wlan' | head -1").output.trim()
        if (anyWlan.isNotEmpty()) {
            logD(TAG, "Found WiFi interface (fallback): $anyWlan")
            return anyWlan
        }
        logW(TAG, "No WiFi interface found")
        return ""
    }

    private fun isSnapdragon(chipset: String): Boolean {
        val lower = chipset.lowercase()
        return lower.contains("snapdragon") ||
               lower.contains("qcom") ||
               lower.contains("qualcomm") ||
               chipset.contains("骁龙", ignoreCase = true)
    }

    fun getQuickCheck(): Boolean {
        return try {
            val rootInfo = detectRoot()
            val hasConMode = resolveConModePath() != null
            rootInfo.isRooted && hasConMode
        } catch (e: Exception) {
            false
        }
    }
}
