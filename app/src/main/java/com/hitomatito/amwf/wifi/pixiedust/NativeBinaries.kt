/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import android.os.Build
import android.os.Process
import com.hitomatito.amwf.ShellExecutor
import java.io.File

/**
 * Gestión de los binarios nativos necesarios para Pixie Dust.
 *
 * `wpa_cli` y `pixiewps` se buscan primero en [Context.filesDir] (copiados
 * desde assets/wifi/ por [ensure()]) y, si no están, en las rutas del sistema
 * (/system/bin, /vendor/bin, /vendor/bin/hw, /system/xbin, /odm/bin).
 *
 * `wpa_supplicant` es la excepción: **solo** se acepta el binario empaquetado
 * en [Context.filesDir]. El del sistema está compilado con
 * `CONFIG_ANDROID_LOG`, que trunca los hexdump WPS, y además no debe ser
 * terminado por la limpieza de AmWF.
 *
 * Los binarios requeridos (empaquetados en assets/wifi/ — ver BINARIES_SETUP.md):
 *   - wpa_supplicant  (arm64-v8a, compilado desde wpa_supplicant 2.11 con
 *                      CONFIG_DEBUG_FILE y sin CONFIG_ANDROID_LOG)
 *   - wpa_cli         (arm64-v8a y armeabi-v7a, compilado desde wpa_supplicant 2.11)
 *   - pixiewps        (arm64-v8a y armeabi-v7a, compilado desde el proyecto Wiire)
 *   - libnl-3.so.200 y libnl-genl-3.so.200 (arm64-v8a, libnl 3.2.25)
 *
 * Esta clase es la única fuente de verdad para resolver rutas de binarios:
 * tanto [com.hitomatito.amwf.DeviceCompatibilityChecker] como
 * [PixieDustRunner] la usan, de modo que la vista de compatibilidad y el
 * ataque real coinciden siempre.
 */
object NativeBinaries {

    private const val TAG = "NativeBinaries"
    private const val ASSETS_DIR = "wifi/"
    private const val PREFS_NAME = "native_binaries"
    private const val PREF_COPIED_UPDATE_TIME = "copied_update_time"

    // Nombres de asset por arquitectura. El sufijo vacío se usa para arm64-v8a,
    // "_32" para armeabi-v7a (siguiendo la convención del ecosistema).
    const val WPA_SUPPLICANT = "wpa_supplicant"
    const val WPA_SUPPLICANT_32 = "wpa_supplicant_32"
    const val WPA_CLI = "wpa_cli"
    const val WPA_CLI_32 = "wpa_cli_32"
    const val PIXIEWPS = "pixiewps"
    const val PIXIEWPS_32 = "pixiewps_32"

    /*
     * Librerías compartidas que necesita el wpa_supplicant empaquetado
     * (arm64-v8a). Enlaza con libnl 3.2.25, compilada para Android y
     * empaquetada junto a los ejecutables. El runtime exporta
     * LD_LIBRARY_PATH al binaryDir, por lo que deben vivir en el mismo
     * directorio (ver BINARIES_SETUP.md).
     */
    private val supplicantLibraries: List<String> = listOf(
        "libnl-3.so.200",
        "libnl-genl-3.so.200"
    )

    const val WPA_SUPPLICANT_CONF = "wpa_supplicant.conf"

    // Rutas del sistema donde buscar binarios si no están empaquetados en assets.
    private val systemSearchPaths = listOf(
        "/system/bin",
        "/vendor/bin",
        "/vendor/bin/hw",
        "/system/xbin",
        "/odm/bin"
    )

    private val currentArchAssets: List<String>
        get() {
            val assets = mutableListOf<String>()
            if (archSuffix().isEmpty()) {
                assets += listOf(WPA_SUPPLICANT, WPA_CLI, PIXIEWPS)
                assets += supplicantLibraries
            } else {
                assets += listOf(WPA_SUPPLICANT_32, WPA_CLI_32, PIXIEWPS_32)
            }
            assets += WPA_SUPPLICANT_CONF
            return assets
        }

    fun binaryDir(context: Context): String = context.filesDir.absolutePath

    /**
     * Retorna el sufijo de asset vacío para 64-bit o "_32" para 32-bit.
     *
     * Se consulta el ABI del proceso, no solo el del dispositivo: en un
     * terminal de 64 bits la app puede ejecutarse en modo 32 bits y debe usar
     * los assets `_32`.
     */
    fun archSuffix(): String = if (Process.is64Bit()) "" else "_32"

    fun wpaSupplicantAssetName() = if (archSuffix().isEmpty()) WPA_SUPPLICANT else WPA_SUPPLICANT_32
    fun wpaCliAssetName() = if (archSuffix().isEmpty()) WPA_CLI else WPA_CLI_32
    fun pixiewpsAssetName() = if (archSuffix().isEmpty()) PIXIEWPS else PIXIEWPS_32

    fun ctrlDir(): String =
        if (Build.VERSION.SDK_INT >= 28) CTRL_DIR_VENDOR else CTRL_DIR_LEGACY

    /**
     * Ruta del config que se entrega al wpa_supplicant. No puede estar en
     * [Context.filesDir]: el dominio SELinux de `su` no puede leer el
     * almacenamiento privado de la app, así que se copia al directorio de
     * control (escribible desde `su`) justo antes de lanzar el supplicant.
     */
    fun runtimeConfigPath(): String = "${ctrlDir()}/$WPA_SUPPLICANT_CONF"

    private const val CTRL_DIR_VENDOR = "/data/vendor/wifi/amwf"
    private const val CTRL_DIR_LEGACY = "/data/misc/wifi/amwf"

    /**
     * Nombre con el que el binario aparece instalado en el sistema. Los
     * assets de 32-bit llevan sufijo "_32", pero los binarios del sistema
     * no lo llevan (p. ej. "wpa_cli_32" → "wpa_cli").
     */
    fun systemBinaryName(assetName: String): String = assetName.removeSuffix("_32")

    /**
     * Resuelve la ruta completa de un binario, buscando primero en [filesDir]
     * (donde [ensure()] copia los assets) y luego en las rutas del sistema.
     *
     * @return la ruta absoluta al binario si se encuentra, o la ruta esperada
     *         en [filesDir] si no se encuentra (para reportar error claramente).
     */
    fun resolveBinaryPath(context: Context, assetName: String): String {
        // 1) Primero intentar en filesDir (donde se copian los assets).
        val filesDirFile = File(binaryDir(context), assetName)
        if (filesDirFile.isUsableBinary()) {
            return filesDirFile.absolutePath
        }

        // El wpa_supplicant del sistema NUNCA es un sustituto válido: trunca
        // los hexdump WPS y, además, `stopSupplicant()` haría `pkill` sobre la
        // ruta del sistema. Si falta el empaquetado, se devuelve la ruta
        // esperada en filesDir para que el error sea explícito.
        if (assetName == WPA_SUPPLICANT || assetName == WPA_SUPPLICANT_32) {
            return filesDirFile.absolutePath
        }

        // 2) Luego buscar en rutas del sistema con el nombre sin sufijo de arch.
        val systemName = systemBinaryName(assetName)
        for (systemPath in systemSearchPaths) {
            val systemFile = File(systemPath, systemName)
            if (systemFile.isUsableBinary()) {
                return systemFile.absolutePath
            }
        }

        // 3) No se encontró: retornar la ruta esperada en filesDir para el mensaje de error.
        return filesDirFile.absolutePath
    }

    /** true si el binario está en [filesDir] o en alguna ruta del sistema. */
    fun isBinaryAvailable(context: Context, assetName: String): Boolean =
        File(resolveBinaryPath(context, assetName)).isUsableBinary()

    /** Un binario es usable si existe y no está vacío (una copia truncada no sirve). */
    private fun File.isUsableBinary(): Boolean = isFile && length() > 0L

    /**
     * Retorna los binarios críticos de la arquitectura actual que no están
     * listos para ejecutar Pixie Dust. Incluye el fichero de configuración del
     * supplicant y, cuando se usa el supplicant empaquetado, las librerías que
     * necesita en runtime.
     *
     * El `wpa_supplicant` del sistema no se acepta como sustituto: está
     * compilado con `CONFIG_ANDROID_LOG`, que trunca los hexdump WPS a 32
     * bytes y hace imposible reconstruir el M3. Solo vale el binario
     * empaquetado (arm64-v8a).
     */
    fun missingCriticalBinaries(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!usesBundledSupplicant(context)) {
            missing.add(wpaSupplicantAssetName())
        }
        missing.addAll(
            listOf(wpaCliAssetName(), pixiewpsAssetName())
                .filter { assetName -> !isBinaryAvailable(context, assetName) }
        )
        if (!isBinaryAvailable(context, WPA_SUPPLICANT_CONF)) {
            missing.add(WPA_SUPPLICANT_CONF)
        }
        if (usesBundledSupplicant(context)) {
            missing.addAll(missingSupplicantLibraries(context))
        }
        return missing
    }

    /**
     * true si el wpa_supplicant empaquetado (arm64) está en [filesDir]; en ese
     * caso el runtime necesita las [supplicantLibraries] en el mismo directorio.
     */
    fun usesBundledSupplicant(context: Context): Boolean =
        File(binaryDir(context), wpaSupplicantAssetName()).isUsableBinary()

    /**
     * Retorna las librerías del supplicant que faltan o están vacías en [filesDir].
     * Solo son relevantes cuando [usesBundledSupplicant] es true.
     */
    fun missingSupplicantLibraries(context: Context): List<String> =
        supplicantLibraries.filter { library ->
            !File(binaryDir(context), library).isUsableBinary()
        }

    /**
     * Copia best-effort los assets conocidos a [filesDir] y ajusta permisos.
     * Los assets de otras arquitecturas pueden faltar sin problema. No lanza
     * excepciones: los fallos se detectan después con [missingCriticalBinaries].
     *
     * La copia se repite tras cada actualización de la app (marcador basado en
     * `lastUpdateTime`), para refrescar binarios y permisos aunque ya existan.
     */
    fun ensure(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdate = packageUpdateTime(context)
        val needsRefresh = prefs.getLong(PREF_COPIED_UPDATE_TIME, -1L) != lastUpdate

        for (asset in currentArchAssets) {
            val target = File(binaryDir(context), asset)
            if (needsRefresh || !target.exists() || target.length() == 0L) {
                copyAsset(context, asset, target)
            }
            if (target.exists()) {
                applyAssetPermissions(asset, target)
            }
        }
        prefs.edit().putLong(PREF_COPIED_UPDATE_TIME, lastUpdate).apply()
    }

    private fun packageUpdateTime(context: Context): Long = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Exception) {
        0L
    }

    /**
     * Ajusta permisos del asset copiado. Los ejecutables deben poder correr
     * desde `su`; los .so y .conf solo necesitan lectura. No se conceden
     * permisos de escritura (el proceso propietario ya puede escribirlos).
     */
    private fun applyAssetPermissions(asset: String, target: File) {
        target.setReadable(true, false)
        val executable = !asset.endsWith(".conf") && !asset.contains(".so")
        target.setExecutable(executable)
    }

    /**
     * Copia un asset a [target] mediante un fichero temporal para no dejar
     * binarios truncados si la copia se interrumpe. Best-effort: ignora los
     * assets ausentes (p. ej. variantes de otra arquitectura).
     */
    private fun copyAsset(context: Context, asset: String, target: File) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            context.assets.open("$ASSETS_DIR$asset").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            ShellExecutor.logW(TAG, "No se pudo copiar el asset $asset: ${e.message}")
        }
    }
}
