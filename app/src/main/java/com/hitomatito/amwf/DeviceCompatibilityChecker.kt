package com.hitomatito.amwf

import com.hitomatito.amwf.ShellExecutor.execNoRoot
import com.hitomatito.amwf.ShellExecutor.execRoot
import com.hitomatito.amwf.ShellExecutor.logD
import com.hitomatito.amwf.ShellExecutor.logE
import com.hitomatito.amwf.ShellExecutor.logW
import java.io.File

/**
 * Encuentra la ruta real del archivo con_mode del driver WiFi Qualcomm (QCACLD).
 * El nombre del módulo varia segun el chip/dispositivo: "wlan" (POCO X3, Snapdragon 860),
 * "hdd" (Xiaomi 11T Pro / Snapdragon 888), "kiwi_v2" (Snapdragon 8 Gen 2), etc.
 * Sin esto el metodo con_mode falla en HW mas reciente.
 */
fun resolveConModePath(): String? {
    val knownModules = listOf("wlan", "hdd", "kiwi_v2", "kiwi", "wcn")
    for (module in knownModules) {
        val path = "/sys/module/$module/parameters/con_mode"
        if (File(path).exists()) return path
    }
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
    val capabilities: CapabilitiesInfo
)

data class CapabilitiesInfo(
    val canInject: Boolean?,
    val canCapture: Boolean?,
    val isPassiveOnly: Boolean,
    val tested: Boolean
)

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
    SELINUX
}

enum class Severity {
    CRITICAL,
    WARNING,
    INFO
}

class DeviceCompatibilityChecker {

    companion object {
        private const val TAG = "CompatChecker"
        
        private val SU_PATHS = listOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/vendor/bin/su"
        )
        
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
    }

    fun checkCompatibility(): CompatibilityResult {
        val issues = mutableListOf<CompatibilityIssue>()
        
        logD(TAG, "=== Starting compatibility check ===")

        // Apaga el radio WiFi ANTES de los tests de escritura a con_mode.
        // Con el cliente conectado, "echo 4 > con_mode" falla con
        // "Operation not permitted", lo que marcaría un dispositivo
        // realmente compatible como no compatible. El radio se restaura
        // una única vez al final de checkCompatibility().
        execRoot("svc wifi disable")
        Thread.sleep(1000)

        val deviceInfo = gatherDeviceInfo()
        logD(TAG, "Device: ${deviceInfo.manufacturer} ${deviceInfo.model}")
        logD(TAG, "Chipset: ${deviceInfo.chipset}")
        logD(TAG, "Root: ${deviceInfo.rootInfo.rootName} v${deviceInfo.rootInfo.version}")

        if (!deviceInfo.rootInfo.isRooted) {
            issues.add(CompatibilityIssue(
                type = IssueType.ROOT,
                severity = Severity.CRITICAL,
                message = "Root no disponible",
                suggestion = "Esta app requiere root. Usa Magisk, KernelSU, APatch, SuperSU o phh-su"
            ))
        }

        if (!isSnapdragon(deviceInfo.chipset)) {
            issues.add(CompatibilityIssue(
                type = IssueType.CHIPSET,
                severity = Severity.CRITICAL,
                message = "Chipset no compatible: ${deviceInfo.chipset}",
                suggestion = "Este método solo funciona en dispositivos con chipset Qualcomm Snapdragon"
            ))
        }

        if (deviceInfo.conModePath == null) {
            val legacyDriver = File("/sys/module/wlan").exists()
            issues.add(CompatibilityIssue(
                type = IssueType.SYSFS,
                severity = Severity.CRITICAL,
                message = "Archivo con_mode no encontrado",
                suggestion = if (legacyDriver) {
                    "El driver WiFi no expone con_mode en este dispositivo (revisa permisos o SELinux)"
                } else {
                    "Este dispositivo usa un driver Qualcomm de nueva generación (cnss_pci/WCN) " +
                    "que no expone con_mode. El método de modo monitor de esta app no está soportado en este hardware."
                }
            ))
        } else {
            val testResult = testConModeWrite(deviceInfo.conModePath)
            if (!testResult) {
                issues.add(CompatibilityIssue(
                    type = IssueType.SYSFS,
                    severity = Severity.CRITICAL,
                    message = "No se puede escribir en con_mode",
                    suggestion = "Las restricciones SELinux o de permisos impiden modificar el modo WiFi"
                ))
            }
        }

        val isCompatible = issues.none { it.severity == Severity.CRITICAL }
        
        logD(TAG, "=== Compatibility check complete ===")
        logD(TAG, "Compatible: $isCompatible")

        // Restart WiFi service ONCE after ALL con_mode tests complete.
        // Both testCapabilities() and testConModeWrite() modify con_mode,
        // which disrupts WiFi. A single restart here ensures WiFi is
        // properly restored after all tests finish.
        logD(TAG, "Restarting WiFi service to restore managed mode after all tests")
        try {
            execRoot("svc wifi disable")
            Thread.sleep(1000)
            execRoot("svc wifi enable")
            Thread.sleep(1000)
        } catch (e: Exception) {
            logE(TAG, "Failed to restart WiFi service: ${e.message}", e)
        }

        return CompatibilityResult(
            isCompatible = isCompatible,
            issues = issues,
            deviceInfo = deviceInfo
        )
    }

    private fun testConModeWrite(path: String): Boolean {
        logD(TAG, "Testing con_mode write at $path")
        
        // Save original value before testing
        val originalValue = execRoot("cat $path 2>/dev/null").output.trim()
        logD(TAG, "Original con_mode value: $originalValue")
        
        try {
            val testResult = execRoot("echo 4 > $path 2>&1; echo \$?")
            
            if (testResult.output.contains("1") || testResult.error.contains("denied") || 
                testResult.error.contains("Permission") || testResult.error.contains("readonly")) {
                logD(TAG, "Write test failed: ${testResult.error}")
                return false
            }
            
            logD(TAG, "Write test passed")
            return true
        } catch (e: Exception) {
            logE(TAG, "Exception during write test: ${e.message}", e)
            return false
        } finally {
            // CRITICAL: Always restore original value
            logD(TAG, "Restoring con_mode to: $originalValue")
            try {
                execRoot("echo $originalValue > $path 2>&1")
            } catch (e: Exception) {
                logE(TAG, "Failed to restore con_mode: ${e.message}", e)
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

    private fun gatherDeviceInfo(): DeviceInfo {
        val manufacturer = getSystemProperty("ro.product.manufacturer")
        val model = getSystemProperty("ro.product.model")
        val chipset = detectChipset()
        val kernelArch = execNoRoot("uname -m").output.ifEmpty { "Unknown" }
        val cpuAbi = getSystemProperty("ro.product.cpu.abi")
        val wlanDriver = detectWlanDriver()
        val conMode = resolveConModePath()
        val rootInfo = detectRoot()
        val capabilities = testCapabilities()

        return DeviceInfo(
            manufacturer = manufacturer.ifEmpty { "Unknown" },
            model = model.ifEmpty { "Unknown" },
            chipset = chipset,
            kernelArch = kernelArch,
            cpuAbi = cpuAbi.ifEmpty { "Unknown" },
            wlanDriver = wlanDriver,
            conModePath = conMode,
            rootInfo = rootInfo,
            capabilities = capabilities
        )
    }

    private fun testCapabilities(): CapabilitiesInfo {
        logD(TAG, "=== Testing injection/capture capabilities ===")
        
        val conModePath = resolveConModePath()
        if (conModePath == null) {
            return CapabilitiesInfo(
                canInject = null,
                canCapture = null,
                isPassiveOnly = true,
                tested = false
            )
        }

        // Save original mode BEFORE making any changes
        val originalMode = execRoot("cat $conModePath 2>/dev/null").output.trim()
        logD(TAG, "Original con_mode value: $originalMode")

        try {
            // Set to monitor mode (4)
            execRoot("echo 4 > $conModePath 2>/dev/null")
            Thread.sleep(500)
            
            // Detect WiFi interface dynamically (try common names)
            val interfaceName = findWifiInterface()
            if (interfaceName.isEmpty()) {
                logW(TAG, "No WiFi interface found for capability test")
                return CapabilitiesInfo(
                    canInject = null,
                    canCapture = null,
                    isPassiveOnly = true,
                    tested = false
                )
            }
            
            val canInject = testPacketInjection(interfaceName)
            val canCapture = testCaptureCapability(interfaceName)
            
            val isPassiveOnly = !canInject && !canCapture

            logD(TAG, "Capabilities - Inject: $canInject, Capture: $canCapture, Passive: $isPassiveOnly")

            return CapabilitiesInfo(
                canInject = canInject,
                canCapture = canCapture,
                isPassiveOnly = isPassiveOnly,
                tested = true
            )
        } catch (e: Exception) {
            logE(TAG, "Exception during capability test: ${e.message}", e)
            return CapabilitiesInfo(
                canInject = null,
                canCapture = null,
                isPassiveOnly = true,
                tested = false
            )
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

    private fun testPacketInjection(interfaceName: String): Boolean {
        if (interfaceName.isEmpty()) return false
        
        val rawSocketTest = execRoot(
            "ip link set $interfaceName up 2>&1 && " +
            "timeout 1 iw dev $interfaceName set monitor control 2>&1"
        ).output

        if (rawSocketTest.contains("Operation not supported") ||
            rawSocketTest.contains("no such device") ||
            rawSocketTest.contains("Invalid argument")) {
            logD(TAG, "Raw injection not supported: $rawSocketTest")
            return false
        }

        execRoot("iw dev $interfaceName set type managed 2>&1")

        val txTest = execRoot(
            "timeout 2 iw dev $interfaceName set txpower fixed 3000 2>&1"
        ).output

        if (!txTest.contains("command failed") && !txTest.contains("not supported")) {
            logD(TAG, "Tx power injection supported")
            return true
        }

        val monitorInject = execRoot(
            "iw dev $interfaceName set monitor 4addr 2>&1"
        ).output

        if (!monitorInject.contains("command failed") && 
            !monitorInject.contains("not supported")) {
            logD(TAG, "4addr monitor injection supported")
            return true
        }

        logD(TAG, "Packet injection not confirmed")
        return false
    }

    private fun testCaptureCapability(interfaceName: String): Boolean {
        if (interfaceName.isEmpty()) return false
        
        val monitorTypeTest = execRoot(
            "iw dev $interfaceName set type monitor 2>&1"
        ).output

        if (monitorTypeTest.contains("command failed") || 
            monitorTypeTest.contains("Operation not supported") ||
            monitorTypeTest.contains("no such device")) {
            logD(TAG, "Monitor type not supported: $monitorTypeTest")
            return false
        }

        val activeTest = execRoot(
            "iw dev $interfaceName set type monitor 2>&1 && " +
            "iw dev $interfaceName info 2>&1 | grep -c 'type monitor'"
        ).output

        val setBackResult = execRoot(
            "iw dev $interfaceName set type managed 2>&1"
        ).output

        val monitorCount = activeTest.trim().toIntOrNull() ?: 0
        if (monitorCount > 0) {
            logD(TAG, "Capture test passed - can set monitor type")
            return true
        }

        val freqTest = execRoot(
            "timeout 2 iw dev $interfaceName scan trigger 2>&1"
        ).output
        
        if (freqTest.contains("MLME") || freqTest.contains("command failed")) {
            logD(TAG, "Scan trigger failed: $freqTest")
            return false
        }

        val channelTest = execRoot(
            "timeout 2 iw dev $interfaceName scan 2>&1 | head -20"
        ).output

        if (channelTest.isNotEmpty() && 
            !channelTest.contains("command failed") &&
            !channelTest.contains("no such device")) {
            logD(TAG, "Active scan works - capture capability confirmed")
            return true
        }

        logD(TAG, "Capture test: passive scan only")
        return false
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
        logD(TAG, "Full cpuinfo:\n$cpuInfoHardware")
        
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
