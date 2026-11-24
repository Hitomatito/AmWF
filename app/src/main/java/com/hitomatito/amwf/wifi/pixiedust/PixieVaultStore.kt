/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import androidx.core.content.edit

/**
 * Bóveda de credenciales recuperadas: cada auditoría que termina con clave
 * WPA se persiste aquí para que el usuario pueda verla de nuevo, copiarla y
 * compartirla como QR sin repetir la auditoría.
 *
 * La clave primaria es el BSSID (un mismo AP auditado dos veces actualiza su
 * entrada en lugar de duplicarla). Las credenciales viven en las prefs
 * PRIVADAS de la app (MODE_PRIVATE), nunca en almacenamiento compartido, y
 * este archivo no las loguea: el registro de sesión ya las muestra y los
 * logs de auditoría ya las persisten en /sdcard con su aviso.
 *
 * El códec es puro (línea a línea, con escape) y unit-testeable en JVM; la
 * decodificación es estricta: una entrada malformada se trata como ausente y
 * no tumba el resto de la bóveda.
 */
/**
 * Una clave WPA recuperada por una auditoría exitosa. Modelo público: viaja
 * en el estado UI; la implementación que la persiste es [PixieVaultStore].
 */
data class PixieVaultEntry(
    val bssid: String,
    val ssid: String,
    val wpaPsk: String,
    val wpsPin: String?,
    val obtainedAtMs: Long
)

internal object PixieVaultStore {
    private const val PREFS = "pixie_vault_store"
    private const val KEY_PREFIX = "net_"

    fun save(context: Context, entry: PixieVaultEntry) {
        if (!isValidBssid(entry.bssid)) return
        prefs(context).edit(commit = true) {
            putString(KEY_PREFIX + entry.bssid.lowercase(), encodeEntry(entry))
        }
    }

    fun delete(context: Context, bssid: String) {
        if (!isValidBssid(bssid)) return
        prefs(context).edit(commit = true) { remove(KEY_PREFIX + bssid.lowercase()) }
    }

    /** Todas las entradas válidas, la más reciente primero. */
    fun loadAll(context: Context): List<PixieVaultEntry> =
        prefs(context).all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, raw) ->
                val bssid = key.removePrefix(KEY_PREFIX)
                if (!isValidBssid(bssid)) return@mapNotNull null
                decodeEntry(raw as? String ?: return@mapNotNull null)
                    ?.copy(bssid = bssid)
            }
            .sortedByDescending { it.obtainedAtMs }

    /** Un BSSID no validado jamás llega a ser clave de prefs o de comando. */
    fun isValidBssid(bssid: String): Boolean =
        Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$").matches(bssid)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// ---------------------------------------------------------------------------
// Códec puro (sin Android): las funciones son internal para los tests JVM.
// Mismo formato que CompatResultStore: la primera «=» separa clave y valor,
// el escape es de un solo pase y la decodificación es estricta.
// ---------------------------------------------------------------------------

private const val ENTRY_HEADER = "vault-v1"

/** Escapa lo estructural: barra invertida, salto de línea y tubería. */
private fun esc(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace("|", "\\p")

private fun unesc(value: String): String {
    val out = StringBuilder(value.length)
    var cursor = 0
    while (cursor < value.length) {
        val ch = value[cursor]
        if (ch == '\\' && cursor + 1 < value.length) {
            when (value[cursor + 1]) {
                'n' -> out.append('\n')
                'p' -> out.append('|')
                else -> out.append(value[cursor + 1])
            }
            cursor += 2
        } else {
            out.append(ch)
            cursor += 1
        }
    }
    return out.toString()
}

internal fun encodeEntry(entry: PixieVaultEntry): String = buildString {
    appendLine(ENTRY_HEADER)
    appendLine("ssid=${esc(entry.ssid)}")
    appendLine("psk=${esc(entry.wpaPsk)}")
    appendLine("pin=${entry.wpsPin?.let(::esc) ?: "~"}")
    appendLine("at=${entry.obtainedAtMs}")
}

internal fun decodeEntry(raw: String): PixieVaultEntry? {
    // lineSequence emite una última línea vacía cuando el texto acaba en \n;
    // además el XmlSerializer de SharedPreferences indenta esa línea. Es el
    // terminador del formato, no contenido: una línea en blanca INTERNA sigue
    // siendo inválida y anula la entrada.
    val lines = raw.lineSequence().toList().dropLastWhile { it.isBlank() }
    if (lines.size != 5 || lines.first() != ENTRY_HEADER) return null

    val scalars = mutableMapOf<String, String>()
    for (line in lines.drop(1)) {
        val key = line.substringBefore("=", "")
        val value = line.substringAfter("=", "")
        if (key.isEmpty() || key !in setOf("ssid", "psk", "pin", "at")) return null
        scalars[key] = value
    }

    return try {
        val ssid = unesc(scalars.getValue("ssid"))
        val psk = unesc(scalars.getValue("psk"))
        val pin = scalars.getValue("pin").let { if (it == "~") null else unesc(it) }
        val at = scalars.getValue("at").toLongOrNull() ?: error("bad at")
        // Una entrada sin SSID ni clave no tiene sentido en la bóveda.
        if (ssid.isBlank() || psk.isBlank()) return null
        PixieVaultEntry(
            bssid = "", // lo aporta la clave de prefs; no se serializa
            ssid = ssid,
            wpaPsk = psk,
            wpsPin = pin,
            obtainedAtMs = at
        )
    } catch (_: IllegalStateException) {
        null
    }
}
