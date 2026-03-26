package com.hitomatito.amwf

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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

    private val conModeFile = "/sys/module/wlan/parameters/con_mode"

    companion object {
        private const val TAG = "CompatChecker"
        private const val COMMAND_TIMEOUT = 5000L
        
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
    }

    private fun execCommand(command: String, timeoutMs: Long = COMMAND_TIMEOUT): ExecResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "su -c '$command'"))
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val startTime = System.currentTimeMillis()
            var running = true
            
            while (running && System.currentTimeMillis() - startTime < timeoutMs) {
                if (process.inputStream.available() > 0) {
                    val line = outputReader.readLine()
                    if (line != null) {
                        output.appendLine(line)
                    }
                }
                if (process.errorStream.available() > 0) {
                    val line = errorReader.readLine()
                    if (line != null) {
                        error.appendLine(line)
                    }
                }
                try {
                    val exitCode = process.exitValue()
                    while (outputReader.ready()) output.appendLine(outputReader.readLine())
                    while (errorReader.ready()) error.appendLine(errorReader.readLine())
                    return ExecResult(exitCode, output.toString().trim(), error.toString().trim())
                } catch (e: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            
            process.destroyForcibly()
            ExecResult(-1, output.toString().trim(), "Command timeout")
        } catch (e: Exception) {
            ExecResult(-1, "", e.message ?: "Unknown error")
        }
    }

    private fun execCommandNoRoot(command: String, timeoutMs: Long = COMMAND_TIMEOUT): ExecResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            
            val output = StringBuilder()
            val startTime = System.currentTimeMillis()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (process.inputStream.available() > 0) {
                    output.append(reader.readText())
                    break
                }
                try {
                    process.exitValue()
                    break
                } catch (e: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            
            if (process.isAlive) {
                process.destroyForcibly()
            }
            
            ExecResult(0, output.toString().trim(), "")
        } catch (e: Exception) {
            ExecResult(-1, "", e.message ?: "Unknown error")
        }
    }

    data class ExecResult(val exitCode: Int, val output: String, val error: String)

    fun checkCompatibility(): CompatibilityResult {
        val issues = mutableListOf<CompatibilityIssue>()
        
        Log.d(TAG, "=== Starting compatibility check ===")

        val deviceInfo = gatherDeviceInfo()
        Log.d(TAG, "Device: ${deviceInfo.manufacturer} ${deviceInfo.model}")
        Log.d(TAG, "Chipset: ${deviceInfo.chipset}")
        Log.d(TAG, "Root: ${deviceInfo.rootInfo.rootName} v${deviceInfo.rootInfo.version}")

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
            issues.add(CompatibilityIssue(
                type = IssueType.SYSFS,
                severity = Severity.CRITICAL,
                message = "Archivo con_mode no encontrado",
                suggestion = "El driver WiFi no es compatible con el método de modo monitor"
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
        
        Log.d(TAG, "=== Compatibility check complete ===")
        Log.d(TAG, "Compatible: $isCompatible")

        return CompatibilityResult(
            isCompatible = isCompatible,
            issues = issues,
            deviceInfo = deviceInfo
        )
    }

    private fun testConModeWrite(path: String): Boolean {
        Log.d(TAG, "Testing con_mode write at $path")
        
        val testResult = execCommand("echo 4 > $path 2>&1; echo \$?")
        
        if (testResult.output.contains("1") || testResult.error.contains("denied") || 
            testResult.error.contains("Permission") || testResult.error.contains("readonly")) {
            Log.d(TAG, "Write test failed: ${testResult.error}")
            execCommand("echo 0 > $path 2>&1")
            return false
        }
        
        execCommand("echo 0 > $path 2>&1")
        Log.d(TAG, "Write test passed")
        return true
    }

    private fun detectRoot(): RootInfo {
        Log.d(TAG, "=== Detecting root type ===")
        
        for ((rootType, indicators) in ROOT_INDICATORS) {
            for (path in indicators) {
                if (File(path).exists()) {
                    val version = getRootVersion(rootType)
                    Log.d(TAG, "Found $rootType at $path, version: $version")
                    return RootInfo(
                        isRooted = true,
                        rootType = rootType,
                        rootName = getRootDisplayName(rootType),
                        version = version
                    )
                }
            }
        }
        
        Log.d(TAG, "No known root dirs found, testing su commands...")
        
        val suCommands = listOf(
            "su -c id",
            "id",
            "/system/xbin/su -c id",
            "/system/bin/su -c id",
            "su0 id",
            "ksu id"
        )
        
        for (command in suCommands) {
            val result = execCommandNoRoot(command)
            Log.d(TAG, "Testing '$command': ${result.output}")
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
        
        Log.d(TAG, "=== No root detected ===")
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
                execCommandNoRoot("magisk -v").output.ifEmpty {
                    File("/data/adb/magisk/util_functions.sh").let { 
                        if (it.exists()) "Installed" else "Unknown"
                    }
                }
            }
            RootType.KERNELSU -> {
                execCommandNoRoot("cat /proc/ksu/version 2>/dev/null").output.ifEmpty { "Installed" }
            }
            RootType.APATCH -> {
                execCommandNoRoot("cat /data/adb/ap/version 2>/dev/null").output.ifEmpty { "Installed" }
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
        val kernelArch = execCommandNoRoot("uname -m").output.ifEmpty { "Unknown" }
        val cpuAbi = getSystemProperty("ro.product.cpu.abi")
        val wlanDriver = detectWlanDriver()
        val conMode = if (File(conModeFile).exists()) conModeFile else null
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
        Log.d(TAG, "=== Testing injection/capture capabilities ===")
        
        val conModePath = if (File(conModeFile).exists()) conModeFile else null
        if (conModePath == null) {
            return CapabilitiesInfo(
                canInject = null,
                canCapture = null,
                isPassiveOnly = true,
                tested = false
            )
        }

        val originalMode = execCommand("cat $conModePath 2>/dev/null").output.trim()

        execCommand("echo 4 > $conModePath 2>/dev/null")
        Thread.sleep(500)
        
        val interfaceName = execCommand("ls /sys/class/net/ 2>/dev/null | grep -E '^wlan' | head -1").output.trim()
        
        val canInject = testPacketInjection(interfaceName)
        val canCapture = testCaptureCapability(interfaceName)
        
        execCommand("echo $originalMode > $conModePath 2>/dev/null")
        Thread.sleep(300)

        val isPassiveOnly = !canInject && !canCapture

        Log.d(TAG, "Capabilities - Inject: $canInject, Capture: $canCapture, Passive: $isPassiveOnly")

        return CapabilitiesInfo(
            canInject = canInject,
            canCapture = canCapture,
            isPassiveOnly = isPassiveOnly,
            tested = true
        )
    }

    private fun testPacketInjection(interfaceName: String): Boolean {
        if (interfaceName.isEmpty()) return false
        
        val rawSocketTest = execCommand(
            "ip link set $interfaceName up 2>&1 && " +
            "timeout 1 iw dev $interfaceName set monitor control 2>&1"
        ).output

        if (rawSocketTest.contains("Operation not supported") ||
            rawSocketTest.contains("no such device") ||
            rawSocketTest.contains("Invalid argument")) {
            Log.d(TAG, "Raw injection not supported: $rawSocketTest")
            return false
        }

        execCommand("iw dev $interfaceName set type managed 2>&1")

        val injectCheck = execCommand(
            "cat /sys/class/net/$interfaceName/device/inject 2>/dev/null || " +
            "cat /proc/net/tcp6 2>/dev/null | head -3 || echo 'none'"
        ).output

        if (injectCheck.contains("no such file") || injectCheck == "none") {
            Log.d(TAG, "No injection interface found")
            return false
        }

        val txTest = execCommand(
            "timeout 2 iw dev $interfaceName set txpower fixed 3000 2>&1"
        ).output

        if (!txTest.contains("command failed") && !txTest.contains("not supported")) {
            Log.d(TAG, "Tx power injection supported")
            return true
        }

        val monitorInject = execCommand(
            "iw dev $interfaceName set monitor 4addr 2>&1"
        ).output

        if (!monitorInject.contains("command failed") && 
            !monitorInject.contains("not supported")) {
            Log.d(TAG, "4addr monitor injection supported")
            return true
        }

        Log.d(TAG, "Packet injection not confirmed")
        return false
    }

    private fun testCaptureCapability(interfaceName: String): Boolean {
        if (interfaceName.isEmpty()) return false
        
        val monitorTypeTest = execCommand(
            "iw dev $interfaceName set type monitor 2>&1"
        ).output

        if (monitorTypeTest.contains("command failed") || 
            monitorTypeTest.contains("Operation not supported") ||
            monitorTypeTest.contains("no such device")) {
            Log.d(TAG, "Monitor type not supported: $monitorTypeTest")
            return false
        }

        val activeTest = execCommand(
            "iw dev $interfaceName set type monitor 2>&1 && " +
            "iw dev $interfaceName info 2>&1 | grep -c 'type monitor'"
        ).output

        val setBackResult = execCommand(
            "iw dev $interfaceName set type managed 2>&1"
        ).output

        val monitorCount = activeTest.trim().toIntOrNull() ?: 0
        if (monitorCount > 0) {
            Log.d(TAG, "Capture test passed - can set monitor type")
            return true
        }

        val freqTest = execCommand(
            "timeout 2 iw dev $interfaceName scan trigger 2>&1"
        ).output
        
        if (freqTest.contains("MLME") || freqTest.contains("command failed")) {
            Log.d(TAG, "Scan trigger failed: $freqTest")
            return false
        }

        val channelTest = execCommand(
            "timeout 2 iw dev $interfaceName scan 2>&1 | head -20"
        ).output

        if (channelTest.isNotEmpty() && 
            !channelTest.contains("command failed") &&
            !channelTest.contains("no such device")) {
            Log.d(TAG, "Active scan works - capture capability confirmed")
            return true
        }

        Log.d(TAG, "Capture test: passive scan only")
        return false
    }

    private fun getSystemProperty(prop: String): String {
        return execCommandNoRoot("getprop $prop").output.ifEmpty {
            execCommand("getprop $prop").output
        }
    }

    private fun detectChipset(): String {
        Log.d(TAG, "=== Detecting chipset with root access ===")
        
        val socLine = getSystemProperty("ro.hardware")
        Log.d(TAG, "ro.hardware: $socLine")
        
        val socLower = socLine.lowercase()
        
        for ((codename, chipName) in SNAPDRAGON_CODENAMES) {
            if (socLower.contains(codename.lowercase())) {
                Log.d(TAG, "Found Snapdragon codename: $codename -> $chipName")
                return chipName
            }
        }
        
        val cpuInfoHardware = execCommand("cat /proc/cpuinfo 2>/dev/null").output
        Log.d(TAG, "Full cpuinfo:\n$cpuInfoHardware")
        
        val hardwareLine = cpuInfoHardware.lines().find { it.contains("Hardware", ignoreCase = true) || it.contains("hardware", ignoreCase = true) }
        val cpuInfoModel = cpuInfoHardware.lines().find { it.contains("model name", ignoreCase = true) }
        val cpuPartLine = cpuInfoHardware.lines().find { it.contains("CPU part", ignoreCase = true) }
        
        Log.d(TAG, "cpuinfo Hardware: $hardwareLine")
        Log.d(TAG, "cpuinfo model: $cpuInfoModel")
        Log.d(TAG, "cpuinfo CPU part: $cpuPartLine")
        
        val socModel = getSystemProperty("ro.product.board")
            .ifEmpty { getSystemProperty("ro.board.platform") }
        Log.d(TAG, "ro.product.board: $socModel")
        
        val cpuInfoLower = (cpuInfoHardware + (cpuInfoModel ?: "")).lowercase()
        
        for ((codename, chipName) in SNAPDRAGON_CODENAMES) {
            if (cpuInfoLower.contains(codename.lowercase()) || socLower.contains(codename.lowercase())) {
                Log.d(TAG, "Found in cpuinfo: $codename -> $chipName")
                return chipName
            }
        }
        
        val cpuPartHex = cpuPartLine?.substringAfter(":")?.trim()?.uppercase() ?: ""
        if (cpuPartHex.isNotEmpty()) {
            val snapdragonCpuPart = SNAPDRAGON_CPU_PARTS[cpuPartHex]
            if (snapdragonCpuPart != null) {
                Log.d(TAG, "Found by CPU part: $cpuPartHex -> $snapdragonCpuPart")
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
        val drivers = execCommand("ls -la /sys/class/net/wlan*/device/driver 2>/dev/null").output
        val modules = execCommand("ls /sys/module/ 2>/dev/null | grep -i wlan").output
        
        return when {
            drivers.contains("bcmdhd") -> "Broadcom BCMDHD"
            drivers.contains("brcmfmac") || drivers.contains("brcm") -> "Broadcom BRCM"
            drivers.contains("wlan") -> "Qualcomm WCNSS"
            modules.contains("wlan") -> "WiFi Module"
            else -> "Unknown"
        }
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
            val hasConMode = File(conModeFile).exists()
            rootInfo.isRooted && hasConMode
        } catch (e: Exception) {
            false
        }
    }
}
