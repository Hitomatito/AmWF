/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Requisitos de permisos para escanear redes WiFi.
 *
 * `WifiManager.startScan()` y `getScanResults()` exigen ubicación precisa en
 * todas las versiones, incluso en Android 13+ con `NEARBY_WIFI_DEVICES`
 * concedido: el framework sigue llamando a `enforceCanAccessScanResults()`,
 * que comprueba `ACCESS_FINE_LOCATION` y los servicios de ubicación. Por eso:
 *
 * - Android 12L y anteriores: [Manifest.permission.ACCESS_FINE_LOCATION] y
 *   [Manifest.permission.ACCESS_COARSE_LOCATION] (Android 12 exige pedirlos
 *   juntos) y servicios de ubicación activos.
 * - Android 13+: además de ubicación,
 *   [Manifest.permission.NEARBY_WIFI_DEVICES], que el resto de APIs WiFi
 *   modernas requieren.
 *
 * La lógica dependiente de la versión está separada en funciones puras para
 * poder probarla en la JVM sin depender del SDK del dispositivo.
 */
object WifiScanPermissions {

    private const val API_NEARBY_WIFI = 33 // Android 13 (Build.VERSION_CODES.TIRAMISU)

    /**
     * Permisos que se pasan al diálogo del sistema. Incluye COARSE porque
     * Android 12 ignora una solicitud que pida solo FINE; ambos deben ir
     * juntos. COARSE no se exige después: al elegir «precisa» el sistema
     * concede FINE y deja COARSE sin conceder en algunos dispositivos.
     */
    fun runtimePermissions(): Array<String> = runtimePermissionsForSdk(Build.VERSION.SDK_INT)

    internal fun runtimePermissionsForSdk(sdk: Int): Array<String> =
        if (sdk >= API_NEARBY_WIFI) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    /**
     * Permisos que realmente deben estar concedidos. Para escanear WiFi basta
     * FINE (precisa); COARSE solo se usa para que el diálogo de Android 12
     * muestre la opción de precisión.
     */
    fun requiredPermissions(): Array<String> = requiredPermissionsForSdk(Build.VERSION.SDK_INT)

    internal fun requiredPermissionsForSdk(sdk: Int): Array<String> =
        if (sdk >= API_NEARBY_WIFI) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** true si la versión de Android usa además NEARBY_WIFI_DEVICES. */
    internal fun usesNearbyWifiPermissionForSdk(sdk: Int): Boolean = sdk >= API_NEARBY_WIFI

    fun deniedPermissions(context: Context): List<String> =
        requiredPermissions().filter { !isGranted(context, it) }

    fun hasRuntimePermissions(context: Context): Boolean =
        deniedPermissions(context).isEmpty()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * `WifiManager.startScan()` y `getScanResults()` fallan si los servicios
     * de ubicación están desactivados, también en Android 13+ (el framework
     * comprueba `isLocationModeEnabled()` antes de devolver resultados). Por
     * eso se exigen en todas las versiones soportadas.
     */
    fun isLocationServicesEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /**
     * true cuando el sistema ya no mostrará el diálogo de solicitud y el
     * usuario debe conceder el permiso desde Ajustes. Solo debe consultarse
     * después de un intento de solicitud fallido: antes del primer intento
     * `shouldShowRequestPermissionRationale` también devuelve false.
     */
    fun isPermanentlyDenied(activity: Activity): Boolean {
        val denied = deniedPermissions(activity)
        if (denied.isEmpty()) return false
        // Basta con que uno de los permisos esté bloqueado: reintentar no
        // mostrará su diálogo, así que hay que llevar al usuario a Ajustes.
        return denied.any { permission ->
            !activity.shouldShowRequestPermissionRationale(permission)
        }
    }

    fun appSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun locationSettingsIntent(): Intent =
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
