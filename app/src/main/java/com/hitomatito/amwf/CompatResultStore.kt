/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import android.content.Context
import androidx.core.content.edit
import java.util.regex.Pattern

/**
 * Persistencia del último resultado del check de compatibilidad.
 *
 * Antes vivía solo en los statics de `MainActivity`: al morir el proceso
 * (swipe-away, reinicio) el dato desaparecía y verlo de nuevo exigía repetir
 * el chequeo disruptivo. Ahora el resultado se guarda en disco junto a su
 * marca de tiempo y al `lastUpdateTime` de la app:
 *
 *  - Una apertura en frío restaura el resultado y su fecha sin ejecutar nada
 *    (el test sigue siendo exclusivamente manual).
 *  - Tras una actualización de la app el snapshot se descarta: los binarios
 *    empaquetados y las rutas pudieron cambiar y el dato viejo no puede
 *    desbloquear el modo monitor.
 *  - Un fallo inesperado del check limpia el snapshot, igual que invalida la
 *    caché en memoria: un éxito anterior no puede sobrevivir a una
 *    verificación fallida.
 *
 * El códec es puro (línea a línea, con escape) y unit-testeable en JVM; la
 * decodificación es estricta: cualquier línea imprevista anula el snapshot
 * entero, que se trata como ausente.
 */
internal object CompatResultStore {
    private const val PREFS = "compat_result_store"
    private const val KEY_SNAPSHOT = "snapshot"

    data class Snapshot(
        val result: CompatibilityResult,
        val checkedAtMs: Long,
        val appUpdateTimeMs: Long
    )

    fun save(context: Context, snapshot: Snapshot) {
        prefs(context).edit(commit = true) {
            putString(KEY_SNAPSHOT, encodeSnapshot(snapshot))
        }
    }

    fun load(context: Context): Snapshot? {
        val raw = prefs(context).getString(KEY_SNAPSHOT, null) ?: return null
        return decodeSnapshot(raw)
    }

    fun clear(context: Context) {
        prefs(context).edit(commit = true) { remove(KEY_SNAPSHOT) }
    }

    /**
     * Ancla del snapshot a la instalación: cambia con cada actualización de la
     * app, que es el único evento que puede invalidar binarios o rutas.
     */
    fun currentAppUpdateTime(context: Context): Long = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Exception) {
        0L
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// ---------------------------------------------------------------------------
// Códec puro (sin Android): las funciones son internal para los tests JVM.
// ---------------------------------------------------------------------------

private const val FORMAT_HEADER = "compat-v1"
private const val NULL_MARK = "~"
private val ESCAPE_PATTERN = Pattern.compile("\\\\(.)")

/** Escapa lo estructural: barra invertida, salto de línea y tubería. */
private fun esc(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace("|", "\\p")

/** Inverse de [esc] en un solo pase: la sustitución secuencial corrompería. */
private fun unesc(value: String): String = StringBuilder(value.length).apply {
    val matcher = ESCAPE_PATTERN.matcher(value)
    var cursor = 0
    while (matcher.find(cursor)) {
        append(value, cursor, matcher.start())
        when (val escaped = matcher.group(1)) {
            "n" -> append('\n')
            "p" -> append('|')
            else -> append(escaped)
        }
        cursor = matcher.end()
    }
    append(value, cursor, value.length)
}.toString()

/** Triestado compacto: 1/0/~ (null). */
private fun encodeTri(value: Boolean?): String = when (value) {
    true -> "1"
    false -> "0"
    null -> NULL_MARK
}

internal fun encodeSnapshot(snapshot: CompatResultStore.Snapshot): String {
    val info = snapshot.result.deviceInfo
    return buildString {
        appendLine(FORMAT_HEADER)
        appendLine("checkedAt=${snapshot.checkedAtMs}")
        appendLine("updatedAt=${snapshot.appUpdateTimeMs}")
        appendLine("compatible=${if (snapshot.result.isCompatible) 1 else 0}")
        appendLine("manufacturer=${esc(info.manufacturer)}")
        appendLine("model=${esc(info.model)}")
        appendLine("chipset=${esc(info.chipset)}")
        appendLine("kernelArch=${esc(info.kernelArch)}")
        appendLine("cpuAbi=${esc(info.cpuAbi)}")
        appendLine("wlanDriver=${esc(info.wlanDriver)}")
        appendLine("conModePath=${info.conModePath?.let(::esc) ?: NULL_MARK}")
        appendLine("rooted=${if (info.rootInfo.isRooted) 1 else 0}")
        appendLine("rootType=${info.rootInfo.rootType.name}")
        appendLine("rootName=${esc(info.rootInfo.rootName)}")
        appendLine("rootVersion=${esc(info.rootInfo.version)}")
        appendLine("capInject=${encodeTri(info.capabilities.canInject)}")
        appendLine("capCapture=${encodeTri(info.capabilities.canCapture)}")
        appendLine("capPassive=${if (info.capabilities.isPassiveOnly) 1 else 0}")
        appendLine("capTested=${if (info.capabilities.tested) 1 else 0}")
        appendLine("capConModeWrite=${encodeTri(info.capabilities.conModeWriteOk)}")
        appendLine("pixieMissing=${info.pixieDustBinaries.missing.joinToString(",")}")
        snapshot.result.issues.forEach { issue ->
            appendLine(
                "issue=${issue.type.name}|${issue.severity.name}|" +
                    "${esc(issue.message)}|${esc(issue.suggestion)}"
            )
        }
    }
}

internal fun decodeSnapshot(raw: String): CompatResultStore.Snapshot? {
    // lineSequence emite una última línea vacía cuando el texto termina en \n:
    // es el terminador del formato, no contenido a validar (una vacía INTERNA
    // sigue siendo inválida y anula el snapshot).
    val lines = raw.lineSequence().toList().dropLastWhile { it.isEmpty() }
    if (lines.isEmpty() || lines.first() != FORMAT_HEADER) return null

    val scalars = mutableMapOf<String, String>()
    val issues = mutableListOf<CompatibilityIssue>()
    for (line in lines.drop(1)) {
        if (line.isEmpty()) return null
        if (line.startsWith("issue=")) {
            val parts = line.removePrefix("issue=").split("|", limit = 4)
            if (parts.size != 4) return null
            val type = runCatching { IssueType.valueOf(parts[0]) }.getOrNull() ?: return null
            val severity = runCatching { Severity.valueOf(parts[1]) }.getOrNull() ?: return null
            issues.add(
                CompatibilityIssue(
                    type = type,
                    severity = severity,
                    message = unesc(parts[2]),
                    suggestion = unesc(parts[3])
                )
            )
            continue
        }
        val key = line.substringBefore("=", "")
        val value = line.substringAfter("=", "")
        if (key.isEmpty() || key !in KNOWN_KEYS) return null
        scalars[key] = value
    }

    // Helpers que LANZAN dentro del try: distinguen «clave ausente» o «valor
    // basura» (IllegalState) del triestado legítimo (~ = null), y el catch
    // trata cualquier malformación como snapshot ausente.
    return try {
        fun str(key: String): String = scalars[key] ?: error("missing $key")
        fun flag(key: String): Boolean = when (str(key)) {
            "1" -> true
            "0" -> false
            else -> error("bad flag $key")
        }
        // Triestado sin ambigüedad: "~" ES un valor válido, distinto de clave
        // ausente (que aquí lanza).
        fun tri(key: String): Boolean? = when (str(key)) {
            "1" -> true
            "0" -> false
            NULL_MARK -> null
            else -> error("bad tri-state $key")
        }
        fun long(key: String): Long = str(key).toLongOrNull() ?: error("bad long $key")

        CompatResultStore.Snapshot(
            checkedAtMs = long("checkedAt"),
            appUpdateTimeMs = long("updatedAt"),
            result = CompatibilityResult(
                isCompatible = flag("compatible"),
                issues = issues,
                deviceInfo = DeviceInfo(
                    manufacturer = unesc(str("manufacturer")),
                    model = unesc(str("model")),
                    chipset = unesc(str("chipset")),
                    kernelArch = unesc(str("kernelArch")),
                    cpuAbi = unesc(str("cpuAbi")),
                    wlanDriver = unesc(str("wlanDriver")),
                    conModePath = str("conModePath").let {
                        if (it == NULL_MARK) null else unesc(it)
                    },
                    rootInfo = RootInfo(
                        isRooted = flag("rooted"),
                        rootType = runCatching { RootType.valueOf(str("rootType")) }
                            .getOrNull() ?: error("bad rootType"),
                        rootName = unesc(str("rootName")),
                        version = unesc(str("rootVersion"))
                    ),
                    capabilities = CapabilitiesInfo(
                        canInject = tri("capInject"),
                        canCapture = tri("capCapture"),
                        isPassiveOnly = flag("capPassive"),
                        tested = flag("capTested"),
                        conModeWriteOk = tri("capConModeWrite")
                    ),
                    pixieDustBinaries = PixieDustBinariesInfo(
                        missing = str("pixieMissing").split(",").filter { it.isNotEmpty() }
                    )
                )
            )
        )
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        // Los valueOf de enums ya se filtran arriba; queda por defensividad.
        null
    }
}

private val KNOWN_KEYS = setOf(
    "checkedAt", "updatedAt", "compatible",
    "manufacturer", "model", "chipset", "kernelArch", "cpuAbi", "wlanDriver",
    "conModePath", "rooted", "rootType", "rootName", "rootVersion",
    "capInject", "capCapture", "capPassive", "capTested", "capConModeWrite",
    "pixieMissing"
)
