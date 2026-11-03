/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

/**
 * Filtros de la hoja de selección de red.
 *
 * Vive fuera de `PixieDustActivity` para que el filtrado y la búsqueda sean
 * funciones puras y se puedan probar en JVM. La correspondencia con los chips
 * (`R.id.chip*`) es responsabilidad de la pantalla.
 */
enum class NetworkFilter {
    ALL,
    AUDITABLE,
    BAND_24,
    BAND_5,
    DUAL_BAND
}

/**
 * Bandas detectadas por SSID visible. Se usa para marcar en el listado las
 * redes que también existen en otra banda y para el filtro [NetworkFilter.DUAL_BAND].
 *
 * Las redes ocultas (SSID vacío) se excluyen: compartirían la etiqueta «red
 * oculta» y se agruparían entre sí como si fueran la misma red en dos bandas.
 */
fun bandsBySsid(networks: List<PixieDustNetwork>): Map<String, Set<WifiBand>> =
    networks.filter { it.ssid.isNotEmpty() }
        .groupBy { it.displayName }
        .mapValues { (_, group) -> group.map { it.band() }.toSet() }

/** Número de SSID visibles con presencia en más de una banda. */
fun countDualBandNetworks(bandsBySsid: Map<String, Set<WifiBand>>): Int =
    bandsBySsid.count { it.value.size > 1 }

/**
 * Aplica el chip activo y la búsqueda de texto a la lista de redes.
 *
 * La búsqueda acepta coincidencias parciales por SSID o BSSID, con o sin
 * separadores: "0011" encuentra 00:11:22:33:44:55. Las consultas de menos de
 * tres caracteres sin separadores solo buscan en el SSID para no devolver
 * coincidencias demasiado amplias.
 */
fun filterNetworks(
    networks: List<PixieDustNetwork>,
    filter: NetworkFilter,
    query: String,
    bands: Map<String, Set<WifiBand>>
): List<PixieDustNetwork> {
    val byFilter = when (filter) {
        NetworkFilter.ALL -> networks
        NetworkFilter.AUDITABLE -> networks.filter { it.isAuditable() }
        NetworkFilter.BAND_24 -> networks.filter { it.band() == WifiBand.BAND_24_GHZ }
        NetworkFilter.BAND_5 -> networks.filter { it.band() == WifiBand.BAND_5_GHZ }
        NetworkFilter.DUAL_BAND -> networks.filter {
            (bands[it.displayName]?.size ?: 0) > 1
        }
    }
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return byFilter
    return byFilter.filter { it.matchesQuery(trimmed.lowercase()) }
}

/** [query] ya viene en minúsculas y sin espacios en los extremos. */
private fun PixieDustNetwork.matchesQuery(query: String): Boolean {
    if (displayName.lowercase().contains(query)) return true
    val compactBssid = query.replace(":", "").replace(" ", "")
    // El límite se aplica antes de cualquier comparación con el BSSID, tanto
    // con separadores como sin ellos; si no, "aa" seguía coincidiendo aquí.
    if (compactBssid.length < 3) return false
    return bssid.lowercase().contains(query) ||
        bssid.replace(":", "").lowercase().contains(compactBssid)
}
