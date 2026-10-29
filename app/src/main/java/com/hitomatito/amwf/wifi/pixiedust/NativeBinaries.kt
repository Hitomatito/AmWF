/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Gestión de los binarios nativos necesarios para Pixie Dust.
 *
 * Los binarios se buscan en dos ubicaciones, en orden de preferencia:
 *   1. [Context.filesDir] — copiados desde [assets/wifi/] por [ensure()]
 *   2. Rutas del sistema (/system/bin, /vendor/bin, /system/xbin, /odm/bin)
 *
 * Los binarios requeridos (debes empaquetarlos en assets/wifi/ o tenerlos
 * instalados en el sistema — ver BINARIES_SETUP.md):
 *   - wpa_supplicant  (arm64-v8a y armeabi-v7a)
 *   - wpa_cli         (arm64-v8a y armeabi-v7a)
 *   - pixiewps        (arm64-v8a y armeabi-v7a)
 *   - libnl-3.so, libnl-genl-3.so, libnl-route-3.so (arm64-v8a y armeabi-v7a)
 *
 * @property context Contexto de la aplicación.
 */
object NativeBinaries {

    private const val ASSETS_DIR = "wifi/"

    // Nombres de asset por arquitectura. El sufijo vacío se usa para arm64-v8a,
    // "_32" para armeabi-v7a (siguiendo la convención del ecosistema).
    const val WPA_SUPPLICANT = "wpa_supplicant"
    const val WPA_SUPPLICANT_32 = "wpa_supplicant_32"
    const val WPA_CLI = "wpa_cli"
    const val WPA_CLI_32 = "wpa_cli_32"
    const val PIXIEWPS = "pixiewps"
    const val PIXIEWPS_32 = "pixiewps_32"

    const val LIB_NL_3 = "libnl-3.so"
    const val LIB_NL_GENL_3 = "libnl-genl-3.so"
    const val LIB_NL_ROUTE_3 = "libnl-route-3.so"
    const val LIB_NL_3_32 = "libnl-3.so-32"
    const val LIB_NL_GENL_3_32 = "libnl-genl-3.so-32"
    const val LIB_NL_ROUTE_3_32 = "libnl-route-3.so-32"

    const val WPA_SUPPLICANT_CONF = "wpa_supplicant.conf"

    // Rutas del sistema donde buscar binarios si no están empaquetados en assets.
    private val SYSTEM_SEARCH_PATHS = listOf(
        "/system/bin",
        "/vendor/bin",
        "/system/xbin",
        "/odm/bin"
    )

    // Binarios críticos mínimos para ejecutar Pixie Dust.
    private val criticalBinaries = listOf(
        WPA_SUPPLICANT, WPA_SUPPLICANT_32,
        WPA_CLI, WPA_CLI_32,
        PIXIEWPS, PIXIEWPS_32
    )

    private val allAssets = listOf(
        WPA_SUPPLICANT, WPA_SUPPLICANT_32,
        WPA_CLI, WPA_CLI_32,
        PIXIEWPS, PIXIEWPS_32,
        LIB_NL_3, LIB_NL_GENL_3, LIB_NL_ROUTE_3,
        LIB_NL_3_32, LIB_NL_GENL_3_32, LIB_NL_ROUTE_3_32,
        WPA_SUPPLICANT_CONF
    )

    private val libraryAssets = listOf(
        LIB_NL_3, LIB_NL_GENL_3, LIB_NL_ROUTE_3,
        LIB_NL_3_32, LIB_NL_GENL_3_32, LIB_NL_ROUTE_3_32
    )

    fun binaryDir(context: Context): String = context.filesDir.absolutePath

    /**
     * Retorna el sufijo de asset vacío para 64-bit o "_32" para 32-bit.
     */
    fun archSuffix(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Build.VERSION.SDK_INT >= 24) "" else "_32"

    fun wpaSupplicantAssetName() = if (archSuffix().isEmpty()) WPA_SUPPLICANT else WPA_SUPPLICANT_32
    fun wpaCliAssetName() = if (archSuffix().isEmpty()) WPA_CLI else WPA_CLI_32
    fun pixiewpsAssetName() = if (archSuffix().isEmpty()) PIXIEWPS else PIXIEWPS_32

    fun ctrlDir(): String =
        if (Build.VERSION.SDK_INT >= 28) CTRL_DIR_VENDOR else CTRL_DIR_LEGACY

    private const val CTRL_DIR_VENDOR = "/data/vendor/wifi/wpa/amwf"
    private const val CTRL_DIR_LEGACY = "/data/misc/wifi/amwf"

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
        if (filesDirFile.exists()) {
            return filesDirFile.absolutePath
        }

        // 2) Luego buscar en rutas del sistema.
        for (systemPath in SYSTEM_SEARCH_PATHS) {
            val systemFile = File("$systemPath/$assetName")
            if (systemFile.exists()) {
                return systemFile.absolutePath
            }
        }

        // 3) No se encontró: retornar la ruta esperada en filesDir para el mensaje de error.
        return filesDirFile.absolutePath
    }

    /**
     * Verifica que los binarios críticos (wpa_supplicant, wpa_cli, pixiewps)
     * estén disponibles ya sea en [filesDir] o en rutas del sistema.
     */
    fun areCriticalBinariesAvailable(context: Context): Boolean =
        criticalBinaries.all { assetName ->
            resolveBinaryPath(context, assetName).let { path ->
                File(path).exists()
            }
        }

    /**
     * Retorna la lista de binarios críticos que no se encontraron
     * ni en [filesDir] ni en las rutas del sistema.
     */
    fun missingCriticalBinaries(context: Context): List<String> =
        criticalBinaries.filter { assetName ->
            !File(resolveBinaryPath(context, assetName)).exists()
        }

    /**
     * Copia todos los binarios de assets a filesDir y les asigna permisos de ejecución.
     * Retorna true si todos los binarios críticos estuvieron presentes y se copiaron.
     */
    fun ensure(context: Context): Boolean {
        var ok = true
        for (asset in allAssets) {
            val target = File(binaryDir(context), asset)
            if (!copyAsset(context, asset, target)) {
                ok = false
                continue
            }
            // Los .conf no necesitan permiso de ejecución.
            if (!asset.endsWith(".conf")) {
                target.setReadable(true, false)
                target.setWritable(true, false)
                target.setExecutable(true, false)
            }
        }

        // Symlinks de librerías para la variante 32-bit (para que los binarios _32
        // encuentren libnl-3.so etc. bajo el nombre sin sufijo).
        if (archSuffix().isNotEmpty()) {
            val dir = binaryDir(context)
            for ((source, link) in listOf(
                LIB_NL_3_32 to LIB_NL_3,
                LIB_NL_GENL_3_32 to LIB_NL_GENL_3,
                LIB_NL_ROUTE_3_32 to LIB_NL_ROUTE_3
            )) {
                val linkFile = File(dir, link)
                if (!linkFile.exists()) {
                    try {
                        linkFile.createNewFile()
                    } catch (_: Exception) {}
                }
            }
        }
        return ok
    }

    fun allBinariesPresent(context: Context): Boolean =
        allAssets.all { asset ->
            val f = File(binaryDir(context), asset)
            f.exists() && f.length() > 0
        }

    private fun copyAsset(context: Context, asset: String, target: File): Boolean {
        return try {
            context.assets.open("$ASSETS_DIR$asset").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            // Es normal que falte algún asset si el desarrollador aún no los ha
            // empaquetado (ver BINARIES_SETUP.md). No fallamos duro aquí.
            false
        }
    }
}
