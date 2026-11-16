/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import com.hitomatito.amwf.ShellExecutor.CON_MODE_WRITE_TIMEOUT_MS
import com.hitomatito.amwf.ShellExecutor.execRoot
import com.hitomatito.amwf.ShellExecutor.logD
import com.hitomatito.amwf.ShellExecutor.logE
import com.hitomatito.amwf.ShellExecutor.logW
import com.hitomatito.amwf.wifi.pixiedust.WifiRestore

private const val TAG = "CompatChecker"

/**
 * Valida que una pareja (ruta, valor) de restauración pendiente proviene del
 * propio descubrimiento del proceso: una ruta [con_mode] de sysfs y un valor
 * numérico de modo. El marcador vive en las SharedPreferences del paquete, pero
 * se valida igualmente antes de interpolarlo en un comando root: el segmento
 * del módulo no puede empezar por punto, así que `..` y rutas ocultas quedan
 * fuera. El valor se acepta tal cual lo reportó el driver (solo dígitos), no
 * como un modo concreto, para poder devolver siempre el original.
 */
internal fun isValidConModeRestoreTarget(path: String?, value: String?): Boolean =
    path != null && value != null &&
        Regex("^/sys/module/[A-Za-z0-9_-][A-Za-z0-9_.-]*/parameters/con_mode$").matches(path) &&
        Regex("^[0-9]{1,2}$").matches(value)

/**
 * Extrae el código de salida que imprime el `echo \$?` que sigue a la
 * escritura de con_mode. El exit code de `su -c` es siempre el del último
 * comando, así que el valor útil es el último número de la salida.
 */
internal fun parseEchoedExitCode(output: String): Int? =
    output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull()

/** Comando de escritura con el código real de la redirección al final. */
internal fun conModeWriteCommand(path: String, value: String): String =
    "echo $value > $path 2>/dev/null; echo \$?"

/**
 * Escritura de con_mode con el código de salida real impreso por `echo $?`.
 * El exit code de `su -c` es siempre el del último comando, así que
 * `result.exitCode` no sirve por sí solo para detectar un fallo del
 * redireccionamiento. La validación de (ruta, valor) es del llamador.
 */
internal fun writeConMode(path: String, value: String): Boolean {
    val result = execRoot(
        conModeWriteCommand(path, value),
        timeoutMs = CON_MODE_WRITE_TIMEOUT_MS
    )
    val echoedCode = parseEchoedExitCode(result.output)
    val ok = result.exitCode == 0 && echoedCode == 0
    if (!ok) {
        logE(
            TAG,
            "con_mode write failed for $path: exit=${result.exitCode}, " +
                "code=$echoedCode, error=${result.error}"
        )
    }
    return ok
}

/**
 * Qué debe hacer el siguiente arranque tras una verificación interrumpida.
 * Es una decisión pura para poder probarla sin Context ni comandos root.
 */
internal data class PendingRestorePlan(
    val path: String?,
    val value: String?,
    val restartRadio: Boolean
) {
    val restoreConMode: Boolean get() = path != null && value != null
    val hasWork: Boolean get() = restoreConMode || restartRadio
}

/** Estado leído de SharedPreferences: viaja junto hasta la decisión de restauración. */
internal data class PendingRestoreState(
    val pending: Boolean,
    val radioTouched: Boolean,
    val path: String?,
    val value: String?
)

internal fun planPendingRestore(state: PendingRestoreState): PendingRestorePlan {
    if (!state.pending) return PendingRestorePlan(path = null, value = null, restartRadio = false)
    val normalizedValue = state.value?.trim()
    val validTarget = isValidConModeRestoreTarget(state.path, normalizedValue)
    return PendingRestorePlan(
        path = state.path?.takeIf { validTarget },
        value = normalizedValue?.takeIf { validTarget },
        restartRadio = state.radioTouched
    )
}

/**
 * Patrón único de reinicio del servicio WiFi que comparten el check y la
 * restauración pendiente, para no duplicar la secuencia ni su espera.
 */
internal fun restartWifi() {
    execRoot("svc wifi disable")
    Thread.sleep(1000)
    execRoot("svc wifi enable")
    Thread.sleep(1000)
}

/**
 * Estado del radio según el framework. `svc wifi enable` puede devolver 0 sin
 * encenderlo, así que se consulta `dumpsys wifi` en lugar de fiarse del comando.
 * null = no se pudo interpretar la salida.
 */
internal fun readWifiEnabledState(): Boolean? = WifiRestore.isEnabled(
    execRoot(
        "dumpsys wifi 2>/dev/null | grep -m1 -i -E 'Wi-?Fi is'",
        timeoutMs = 5000
    ).output
)

/** Solo se da por restaurado si el framework dice explícitamente que está encendido. */
internal fun isWifiEnabledAfterRestore(state: Boolean?): Boolean = state == true

/** Enciende el radio si el framework no confirma que está encendido y reintenta una vez. */
internal fun ensureWifiEnabled(): Boolean {
    if (isWifiEnabledAfterRestore(readWifiEnabledState())) return true
    logW(TAG, "WiFi not confirmed enabled; retrying enable")
    execRoot("svc wifi enable")
    Thread.sleep(1000)
    val enabled = isWifiEnabledAfterRestore(readWifiEnabledState())
    if (!enabled) logE(TAG, "WiFi enable not confirmed after retry; keeping pending marker")
    return enabled
}

/**
 * Reinicio completo y confirmación por framework. Un único punto para el check
 * y la restauración pendiente: `svc wifi enable` puede devolver 0 sin encender
 * el radio, así que la confirmación es parte de la operación, no opcional.
 */
internal fun restartAndEnsureWifiEnabled(): Boolean = try {
    restartWifi()
    ensureWifiEnabled()
} catch (e: Exception) {
    logE(TAG, "Failed to restart WiFi service: ${e.message}", e)
    false
}

/**
 * Marca de "verificación interrumpida". El check apaga la WiFi y luego escribe
 * con_mode; en algunos dispositivos la escritura conmuta el driver y la
 * aplicación puede morir antes de que el `finally` de [DeviceCompatibilityChecker.checkCompatibility]
 * restaure el radio. El marcador persiste en SharedPreferences para que el
 * siguiente arranque de la app aplique la restauración pendiente.
 */
internal object PendingCompatRestore {
    private const val PREFS_NAME = "compat_check_state"
    private const val KEY_PENDING = "pending_restore"
    private const val KEY_RADIO_TOUCHED = "radio_touched"
    private const val KEY_CON_MODE_PATH = "con_mode_path"
    private const val KEY_CON_MODE_VALUE = "con_mode_value"

    /**
     * Las escrituras usan `commit = true`: el proceso puede morir justo después
     * de guardar el marcador, que es precisamente el escenario que cubre. Con
     * `apply()` el dato podría no haber llegado a disco.
     */
    fun markPending(context: Context) {
        prefs(context).edit(commit = true) {
            clear()
            putBoolean(KEY_PENDING, true)
        }
    }

    /** Se llama justo antes de apagar el radio: una muerte en la ventana entre
     * el disable y el guardado no debe dejar el WiFi apagado sin marcador. */
    fun markRadioTouched(context: Context) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_RADIO_TOUCHED, true)
        }
    }

    fun recordConMode(context: Context, path: String, originalValue: String) {
        val value = originalValue.trim()
        if (!isValidConModeRestoreTarget(path, value)) return
        prefs(context).edit(commit = true) {
            putString(KEY_CON_MODE_PATH, path)
            putString(KEY_CON_MODE_VALUE, value)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit(commit = true) { clear() }
    }

    /**
     * Aplica la restauración pendiente: con_mode al valor original y radio
     * reiniciado si el check llegó a apagarlo. Devuelve true solo si todo el
     * trabajo pendiente se aplicó correctamente; si falla, el marcador se
     * conserva para reintentarlo en el siguiente arranque. Debe llamarse en un
     * hilo IO.
     */
    @WorkerThread
    fun restoreIfPending(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_PENDING, false)) return false
        val plan = planPendingRestore(
            PendingRestoreState(
                pending = true,
                radioTouched = prefs.getBoolean(KEY_RADIO_TOUCHED, false),
                path = prefs.getString(KEY_CON_MODE_PATH, null),
                value = prefs.getString(KEY_CON_MODE_VALUE, null)
            )
        )
        if (!plan.hasWork) {
            logD(TAG, "Pending compat-check restore had nothing to apply")
            clear(context)
            return false
        }
        logD(
            TAG,
            "Applying pending compat-check restore " +
                "(conMode=${plan.restoreConMode}, radio=${plan.restartRadio})"
        )
        var restored = true
        if (plan.restoreConMode) {
            val path = plan.path
            val value = plan.value
            if (path == null || value == null || !writeConMode(path, value)) {
                restored = false
            }
        }
        if (plan.restartRadio && !restartAndEnsureWifiEnabled()) {
            restored = false
        }
        if (restored) {
            clear(context)
        } else {
            logW(TAG, "Keeping pending restore marker for the next launch")
        }
        return restored
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
