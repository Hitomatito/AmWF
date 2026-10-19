package com.hitomatito.amwf.diagnostics

import android.content.Context
import android.os.Build
import com.hitomatito.amwf.CompatibilityResult
import com.hitomatito.amwf.MonitorModeManager
import com.hitomatito.amwf.ShellExecutor
import com.hitomatito.amwf.resolveConModePath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reune el estado del dispositivo y de la app para el informe de diagnostico.
 *
 * Todo es **pasivo**: no se ejecuta la prueba de capacidades (que corta el WiFi)
 * ni se modifica `con_mode`. Solo se leen propiedades, sysfs e `iw`.
 */
class DiagnosticsCollector {

    /**
     * SSID(s) visibles al generar el informe. El SSID se imprime como texto
     * plano en el kernel log, asi que se guardan aqui para ocultarlos en todo
     * el documento.
     */
    var ssidSecrets: List<String> = emptyList()
        private set

    fun collect(context: Context, compatResult: CompatibilityResult?): String {
        val sb = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

        ssidSecrets = querySsids()

        sb.appendLine("AmWF Diagnostic Report")
        sb.appendLine("Generated: $timestamp")
        sb.appendLine("App version: ${appVersion(context)}")
        sb.appendLine("=".repeat(64))
        sb.appendLine()

        appendDevice(sb)
        appendRoot(sb)
        appendChipset(sb)
        appendWifi(sb)
        appendDriverModules(sb)
        appendKernelLog(sb)
        appendTools(sb)
        appendCompatibility(sb, compatResult)
        appendLog(sb)

        return sb.toString()
    }

    private fun appendDevice(sb: StringBuilder) {
        heading(sb, "DEVICE")
        prop(sb, "Build.MANUFACTURER", Build.MANUFACTURER)
        prop(sb, "Build.MODEL", Build.MODEL)
        prop(sb, "Build.DEVICE", Build.DEVICE)
        prop(sb, "Build.PRODUCT", Build.PRODUCT)
        prop(sb, "Build.HARDWARE", Build.HARDWARE)
        prop(sb, "Build.BOARD", Build.BOARD)
        prop(sb, "Build.FINGERPRINT", Build.FINGERPRINT)
        prop(sb, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        prop(sb, "ABIs", Build.SUPPORTED_ABIS.joinToString(", "))
        command(sb, "uname -a", "uname -a", root = false)
        sb.appendLine()
    }

    private fun appendRoot(sb: StringBuilder) {
        heading(sb, "ROOT & SELINUX")
        command(sb, "su -c id", "id")
        command(sb, "getenforce", "getenforce")
        sb.appendLine()
    }

    private fun appendChipset(sb: StringBuilder) {
        heading(sb, "CHIPSET")
        command(sb, "getprop ro.hardware", "getprop ro.hardware", root = false)
        command(sb, "getprop ro.product.board", "getprop ro.product.board", root = false)
        command(sb, "getprop ro.board.platform", "getprop ro.board.platform", root = false)
        command(sb, "CPU part", "grep -i 'CPU part' /proc/cpuinfo | sort -u", root = false)
        sb.appendLine()
    }

    private fun appendWifi(sb: StringBuilder) {
        val iface = findInterface()
        heading(sb, "WIFI")

        val currentMode = MonitorModeManager().getCurrentMode().type.name
        prop(sb, "Current mode (app)", currentMode)
        prop(sb, "Interface", iface)

        val conModePath = resolveConModePath()
        prop(sb, "con_mode path", conModePath ?: "not found")
        if (conModePath != null) {
            command(sb, "con_mode value", "cat $conModePath 2>/dev/null")
        }

        command(sb, "ip -o link show", "ip -o link show")
        command(sb, "ip -d link show $iface", "ip -d link show $iface")
        command(sb, "iw dev", "iw dev 2>/dev/null")
        command(sb, "iw dev $iface info", "iw dev $iface info 2>/dev/null")
        command(
            sb,
            "iw list (monitor-related lines)",
            "iw list 2>/dev/null | grep -iE 'Supported interface modes|monitor|managed' | head -40"
        )
        sb.appendLine()
    }

    private fun appendDriverModules(sb: StringBuilder) {
        heading(sb, "WIFI DRIVER MODULES")
        command(
            sb,
            "wifi-related modules in /sys/module",
            "ls /sys/module 2>/dev/null | grep -iE 'wlan|hdd|kiwi|wcn|cld|qca|cnss'"
        )
        sb.appendLine()
    }

    private fun appendKernelLog(sb: StringBuilder) {
        heading(sb, "KERNEL LOG (dmesg, wifi-related, last 80)")
        command(
            sb,
            "dmesg | grep -iE 'wlan|cnss|icnss|wifi|qca|cld|hdd|firmware' | tail -n 80",
            "dmesg 2>/dev/null | grep -iE 'wlan|cnss|icnss|wifi|qca|cld|hdd|firmware' | tail -n 80"
        )
        sb.appendLine()
    }

    private fun appendTools(sb: StringBuilder) {
        heading(sb, "TOOLS")
        command(
            sb,
            "availability",
            "for t in iw tcpdump aireplay-ng airmon-ng mdk4 nexutil; do " +
                "printf '%-14s ' \"\$t\"; command -v \"\$t\" || echo 'not found'; done"
        )
        sb.appendLine()
    }

    private fun appendCompatibility(sb: StringBuilder, result: CompatibilityResult?) {
        heading(sb, "COMPATIBILITY (last run)")
        if (result == null) {
            sb.appendLine("Not available yet.")
            sb.appendLine()
            return
        }
        val info = result.deviceInfo
        prop(sb, "isCompatible", result.isCompatible.toString())
        prop(sb, "chipset", info.chipset)
        prop(sb, "wlanDriver", info.wlanDriver)
        prop(sb, "conModePath", info.conModePath ?: "not found")
        prop(sb, "kernelArch", info.kernelArch)
        prop(sb, "cpuAbi", info.cpuAbi)
        prop(
            sb,
            "root",
            if (info.rootInfo.isRooted) "${info.rootInfo.rootName} ${info.rootInfo.version}".trim()
            else "none"
        )
        val caps = info.capabilities
        prop(sb, "capabilities.tested", caps.tested.toString())
        prop(sb, "capabilities.canInject", caps.canInject?.toString() ?: "unverified")
        prop(sb, "capabilities.canCapture", caps.canCapture?.toString() ?: "unverified")
        prop(sb, "capabilities.isPassiveOnly", caps.isPassiveOnly.toString())
        if (result.issues.isEmpty()) {
            sb.appendLine("issues: none")
        } else {
            sb.appendLine("issues:")
            result.issues.forEach { issue ->
                sb.appendLine("  - [${issue.severity}] ${issue.type}: ${issue.message}")
                if (issue.suggestion.isNotBlank()) {
                    sb.appendLine("      suggestion: ${issue.suggestion}")
                }
            }
        }
        sb.appendLine()
    }

    private fun appendLog(sb: StringBuilder) {
        heading(sb, "APP LOG (in-memory, last ${AppLogger.snapshot().size} lines)")
        val lines = AppLogger.snapshot()
        if (lines.isEmpty()) {
            sb.appendLine("(empty)")
        } else {
            lines.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
    }

    private fun appVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun querySsids(): List<String> {
        val output = ShellExecutor.execRoot("iw dev 2>/dev/null").output
        return DiagnosticsFormat.ssidsFromIwDev(output)
    }

    private fun findInterface(): String {
        for (iface in listOf("wlan0", "wlan1", "p2p0", "swlan0")) {
            val result = ShellExecutor.execRoot("ip link show $iface 2>/dev/null")
            if (result.exitCode == 0 && result.output.isNotEmpty()) return iface
        }
        return "wlan0"
    }

    private fun heading(sb: StringBuilder, title: String) {
        sb.appendLine("[ $title ]")
    }

    private fun prop(sb: StringBuilder, key: String, value: String) {
        sb.appendLine("$key: $value")
    }

    private fun command(sb: StringBuilder, label: String, command: String, root: Boolean = true) {
        val result = if (root) ShellExecutor.execRoot(command) else ShellExecutor.execNoRoot(command)
        val output = result.output.ifBlank { "(no output)" }
        val trimmed = if (output.length > MAX_COMMAND_OUTPUT) {
            output.take(MAX_COMMAND_OUTPUT) + "\n… (truncated)"
        } else {
            output
        }
        sb.appendLine("$ $label")
        sb.appendLine(trimmed)
    }

    private companion object {
        private const val MAX_COMMAND_OUTPUT = 4000
    }
}
