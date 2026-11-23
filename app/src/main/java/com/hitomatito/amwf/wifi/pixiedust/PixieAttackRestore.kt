/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import com.hitomatito.amwf.ShellExecutor.logD
import com.hitomatito.amwf.ShellExecutor.logW
import com.hitomatito.amwf.ensureWifiEnabled

private const val TAG = "PixieRestore"

/**
 * Marcador de "auditoría Pixie Dust interrumpida".
 *
 * El ataque apaga el WiFi del sistema (`svc wifi disable`) y lo devuelve en su
 * `cleanup()`. Si el proceso muere a mitad (swipe-away desde recents,
 * force-stop o un crash), ese cleanup nunca corre y el usuario se queda con el
 * radio apagado sin saber por qué. Este marcador persiste en SharedPreferences
 * para que el siguiente arranque lo encienda de nuevo; mismo patrón que
 * [com.hitomatito.amwf.PendingCompatRestore] para la verificación de
 * compatibilidad.
 *
 * Se limpia solo cuando el framework confirma el encendido ([ensureWifiEnabled]
 * ya reintenta una vez por su cuenta); si la restauración falla, el marcador
 * se conserva para reintentarlo en el siguiente arranque.
 */
internal object PixieAttackRestore {
    private const val PREFS_NAME = "pixie_attack_state"
    private const val KEY_PENDING = "pending_wifi_restore"

    /**
     * Escrituras con `commit = true`: el proceso puede morir justo después de
     * apagar el radio, que es exactamente el escenario que cubre el marcador.
     * Debe llamarse ANTES del `svc wifi disable` — la muerte en esa ventana
     * no debe dejar el WiFi apagado sin marcador (mismo razonamiento que
     * `radio_touched` en el check de compatibilidad).
     */
    fun markWifiDisabled(context: Context) {
        prefs(context).edit(commit = true) {
            clear()
            putBoolean(KEY_PENDING, true)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit(commit = true) { clear() }
    }

    fun isPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PENDING, false)

    /**
     * true mientras una auditoría corre en este proceso, desde la entrada de
     * `runAttack` hasta la persistencia de su log (único punto por el que
     * pasan todas las salidas). [restoreIfPending] no debe encender el radio
     * en ese caso: el marcador pertenece al ataque vivo y su propio cleanup
     * se encarga de restaurar.
     *
     * Vive en memoria a propósito: tras la muerte del proceso desaparece y la
     * restauración pendiente (que solo importa entonces) vuelve a aplicarse.
     */
    @Volatile
    private var attackInFlight = false

    /** Marca que una auditoría corre en este proceso (lo llama el runner). */
    fun markAttackStarted() {
        attackInFlight = true
    }

    /** Marca el fin de la auditoría: el marcador vuelve a poder aplicarse. */
    fun markAttackFinished() {
        attackInFlight = false
    }

    /**
     * Aplica la restauración pendiente: enciende el radio si el framework no
     * confirma que ya está encendido. Devuelve true solo si el radio quedó
     * encendido; si falla, el marcador se conserva para el siguiente arranque.
     * Debe llamarse en un hilo IO (ejecuta `su`).
     */
    @WorkerThread
    fun restoreIfPending(context: Context): Boolean {
        if (!isPending(context)) return false
        if (attackInFlight) {
            // La Activity se recreó (p. ej. cambio día/noche) con la auditoría
            // viva: el marcador es de este ataque, no de uno interrumpido.
            // Encender el radio aquí rompería la captura —el supplicant del
            // sistema robaría la interfaz— y además borraría el seguro del
            // ataque en curso. Su cleanup restaurará el radio al terminar.
            logD(TAG, "An attack is still running in this process; its cleanup owns the restore")
            return false
        }
        logD(TAG, "Applying pending Pixie Dust WiFi restore")
        val restored = ensureWifiEnabled()
        if (restored) {
            clear(context)
        } else {
            logW(TAG, "WiFi restore not confirmed; keeping pending marker for the next launch")
        }
        return restored
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
