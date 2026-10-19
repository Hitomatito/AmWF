package com.hitomatito.amwf.diagnostics

/**
 * Utilidades de formato del informe de diagnostico. Funciones puras para poder
 * testearlas sin Android.
 */
object DiagnosticsFormat {

    private val MAC_REGEX = Regex("\\b([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5})\\b")
    private val SSID_REGEX = Regex("(?m)(ssid\\s+).+$")

    /**
     * Sustituye cada direccion MAC por su prefijo OUI (3 primeros octetos) y
     * oculta el resto. El OUI sigue siendo util para identificar el chipset o
     * fabricante, pero no permite identificar el dispositivo del usuario.
     */
    fun redactMacAddresses(text: String): String =
        MAC_REGEX.replace(text) { match ->
            val octets = match.value.split(":")
            octets.take(3).joinToString(":") + ":xx:xx:xx"
        }

    /**
     * Oculta el nombre de la red WiFi (SSID), que puede identificar la ubicacion
     * del usuario aunque no sea una MAC.
     */
    fun redactSsid(text: String): String =
        SSID_REGEX.replace(text) { match -> "${match.groupValues[1]}<redacted>" }

    /**
     * Aplica todas las redacciones de privacidad al informe.
     *
     * [secrets] son cadenas literales que hay que ocultar si aparecen (p. ej. el
     * SSID actual): el kernel log lo imprime como texto plano ("Connecting to
     * ZERO TWO"), asi que no basta con la regla que ancla la clave "ssid".
     */
    fun redact(text: String, secrets: List<String> = emptyList()): String {
        var out = text
        secrets.asSequence()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .sortedByDescending { it.length }
            .forEach { out = out.replace(it, "<redacted>") }
        return redactSsid(redactMacAddresses(out))
    }

    /**
     * Extrae los SSID visibles en la salida de `iw dev` (una linea `ssid <nombre>`
     * por interfaz conectada). Se usan como [secrets] para el resto del informe.
     */
    fun ssidsFromIwDev(text: String): List<String> =
        text.lineSequence()
            .mapNotNull { line ->
                val match = IW_SSID_REGEX.find(line) ?: return@mapNotNull null
                match.groupValues[1].trim().ifEmpty { null }
            }
            .filterNot { it == "<redacted>" }
            .distinct()
            .toList()

    private val IW_SSID_REGEX = Regex("^\\s*ssid\\s+(.+?)\\s*$")
}
