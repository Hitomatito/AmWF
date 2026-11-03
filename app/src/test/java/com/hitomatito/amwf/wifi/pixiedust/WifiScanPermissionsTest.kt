/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fija los requisitos de permisos por versión de Android:
 *
 * - Todas las versiones: ubicación precisa (FINE + COARSE) y servicios de
 *   ubicación activos, porque `startScan()`/`getScanResults()` los exigen.
 * - Android 13+: además NEARBY_WIFI_DEVICES para el resto de APIs WiFi.
 */
class WifiScanPermissionsTest {

    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Test
    fun `android 6 requests fine and coarse location`() {
        assertArrayEquals(
            locationPermissions,
            WifiScanPermissions.runtimePermissionsForSdk(23)
        )
        assertFalse(WifiScanPermissions.usesNearbyWifiPermissionForSdk(23))
    }

    @Test
    fun `android 9 requests fine and coarse location and location services`() {
        assertArrayEquals(
            locationPermissions,
            WifiScanPermissions.runtimePermissionsForSdk(28)
        )
        assertFalse(WifiScanPermissions.usesNearbyWifiPermissionForSdk(28))
    }

    @Test
    fun `android 12L requests fine and coarse location and location services`() {
        assertArrayEquals(
            locationPermissions,
            WifiScanPermissions.runtimePermissionsForSdk(32)
        )
        assertFalse(WifiScanPermissions.usesNearbyWifiPermissionForSdk(32))
    }

    @Test
    fun `android 13 adds nearby wifi devices but still needs location`() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            WifiScanPermissions.runtimePermissionsForSdk(33)
        )
        assertArrayEquals(
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            WifiScanPermissions.requiredPermissionsForSdk(33)
        )
        assertTrue(WifiScanPermissions.usesNearbyWifiPermissionForSdk(33))
    }

    @Test
    fun `android 16 keeps nearby wifi devices and location`() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            WifiScanPermissions.runtimePermissionsForSdk(36)
        )
        assertArrayEquals(
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            WifiScanPermissions.requiredPermissionsForSdk(36)
        )
        assertTrue(WifiScanPermissions.usesNearbyWifiPermissionForSdk(36))
    }

    @Test
    fun `coarse is requested with fine but not required`() {
        assertTrue(
            WifiScanPermissions.runtimePermissionsForSdk(28)
                .contains(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            WifiScanPermissions.requiredPermissionsForSdk(28)
        )
    }
}
