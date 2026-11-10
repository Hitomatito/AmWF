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
    suspend fun launch(
        iface: String,
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

internal interface PixiewpsGateway {
    fun buildCommand(m3: M3Parser.M3Data, m5m7: M3Parser.M5M7Capture?): String
    suspend fun run(command: String, rawOutput: StringBuilder, log: (String) -> Unit): String?
}

internal interface PskExtractorGateway {
    suspend fun extract(
        bssid: String,
        iface: String,
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
