/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import com.hitomatito.amwf.ShellExecutor
import java.util.concurrent.BlockingQueue

/**
 * Contratos mínimos que necesita [PixieDustRunner] de sus colaboradores.
 * Permiten inyectar fakes en los tests JVM sin `Context` ni procesos reales.
 * Las implementaciones reales son los propios colaboradores y las clases System*.
 */
internal interface SupplicantGateway {
    /**
     * Arranca el supplicant con el config runtime. [freqMHz], si se conoce,
     * limita el escaneo al canal del objetivo (`freq_list` global); con null
     * el supplicant barre toda la banda (comportamiento histórico).
     */
    suspend fun launch(
        iface: String,
        freqMHz: Int?,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ): SupplicantProcesses.Handle?

    fun stop(process: Process?)
    fun stopAndFlush(
        handle: SupplicantProcesses.Handle?,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    )

    fun flushLines(queue: BlockingQueue<String>, rawOutput: StringBuilder, log: (String) -> Unit)
    fun discardStale(queue: BlockingQueue<String>)
    fun recordLine(line: String, rawOutput: StringBuilder, log: (String) -> Unit)
    fun cli(iface: String, args: String): ShellExecutor.Result
    fun sendWpsReg(bssid: String, iface: String, log: (String) -> Unit): ShellExecutor.Result
    fun cancelWps(iface: String, log: (String) -> Unit)
}

/** Comando ya armado junto con la decisión que lo produjo, para loguearla sin reevaluar. */
internal data class PixiewpsInvocation(val command: String, val plan: PixiewpsPlan)

internal interface PixiewpsGateway {
    fun buildCommand(
        bssid: String,
        m3: M3Parser.M3Data,
        m5m7: M3Parser.M5M7Capture?
    ): PixiewpsInvocation

    suspend fun run(
        invocation: PixiewpsInvocation,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ): String?
}

internal interface PskExtractorGateway {
    /** [freqMHz] limita el escaneo del supplicant de la extracción al canal del objetivo. */
    suspend fun extract(
        bssid: String,
        iface: String,
        freqMHz: Int?,
        ssid: String,
        pin: String,
        rawOutput: StringBuilder,
        log: (String) -> Unit
    ): String?
}

internal interface NativeBinariesGateway {
    /** Copia los assets si hace falta y devuelve los binarios críticos ausentes. Puede lanzar. */
    fun prepare(): List<String>
    fun missingBinariesMessage(missing: List<String>): String?
    fun binaryDir(): String
    fun ctrlDir(): String
    fun archSuffix(): String
}

internal interface AuditLogGateway {
    fun persist(
        result: PixieDustResult?,
        target: PixieAuditLog.Target,
        eventLines: List<String>,
        rawLines: List<String>,
        log: (String) -> Unit
    )
}

/**
 * Marcador persistente del "el ataque apagó el WiFi del usuario": el runner lo
 * escribe antes del `svc wifi disable` y lo limpia en `cleanup()` solo si el
 * framework confirmó el encendido. Cubre la muerte del proceso a mitad de
 * auditoría (misma clase de problema que el marcador del check compat).
 */
internal interface AttackRestoreGateway {
    /** Persiste síncronamente que esta auditoría apagó el WiFi del sistema. */
    fun markWifiDisabled()

    /** Limpia el marcador si la restauración se confirmó; si no, se conserva. */
    fun clearWifiDisabledMarker(restored: Boolean)
}

/** Implementación real: delega en [NativeBinaries]. */
internal class SystemNativeBinaries(private val context: Context) : NativeBinariesGateway {
    override fun prepare(): List<String> {
        NativeBinaries.ensure(context)
        return NativeBinaries.missingCriticalBinaries(context)
    }

    override fun missingBinariesMessage(missing: List<String>): String? =
        NativeBinaries.missingBinariesMessage(context, missing)

    override fun binaryDir(): String = NativeBinaries.binaryDir(context)
    override fun ctrlDir(): String = NativeBinaries.ctrlDir()
    override fun archSuffix(): String = NativeBinaries.archSuffix()
}

/** Implementación real: delega en [PixieAuditLog]. */
internal class SystemAuditLog(private val context: Context) : AuditLogGateway {
    override fun persist(
        result: PixieDustResult?,
        target: PixieAuditLog.Target,
        eventLines: List<String>,
        rawLines: List<String>,
        log: (String) -> Unit
    ) {
        PixieAuditLog.persist(
            context = context,
            result = result,
            target = target,
            eventLines = eventLines,
            rawLines = rawLines,
            log = log
        )
    }
}

/** Implementación real: delega en [PixieAttackRestore]. */
internal class SystemAttackRestore(private val context: Context) : AttackRestoreGateway {
    override fun markWifiDisabled() {
        PixieAttackRestore.markWifiDisabled(context)
    }

    override fun clearWifiDisabledMarker(restored: Boolean) {
        if (restored) PixieAttackRestore.clear(context)
    }
}
