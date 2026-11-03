/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.core.content.ContextCompat
import com.hitomatito.amwf.R
import com.hitomatito.amwf.databinding.ActivityPixieDustBinding

/**
 * Pinta la tarjeta de sesión de la auditoría: estado global, porcentaje por
 * etapas, cronómetro, fases macro, resultado final y registro en vivo.
 *
 * Vive fuera de [PixieDustActivity] para que la pantalla no acumule a la vez la
 * gestión de permisos, el selector de redes y el renderizado del resultado.
 * Mantiene únicamente el estado de presentación (consola y cronómetro); el
 * estado de negocio sigue viviendo en [PixieDustUiState].
 */
class PixieSessionRenderer(
    private val context: Context,
    private val binding: ActivityPixieDustBinding
) {

    /** Estado final de la sesión, derivado una sola vez del [PixieDustUiState]. */
    private sealed interface SessionOutcome {
        data object Running : SessionOutcome
        data object Success : SessionOutcome
        data object Cancelled : SessionOutcome
        data object Failure : SessionOutcome
    }

    /** Visibilidad del registro; la controla el botón de plegar/desplegar. */
    var consoleExpanded = true

    /** El registro sigue el final solo si el usuario no lo ha desplazado. */
    var consoleAtBottom = true

    private var lastConsoleSignature = 0
    private var chronometerRunning = false

    /**
     * Actualiza toda la tarjeta. Si aún no hay sesión, la oculta.
     *
     * El porcentaje es una estimación por hitos ([PixieDustStage.progress]) y se
     * acompaña siempre de la etapa actual para que el valor no se lea como un
     * progreso continuo.
     */
    fun update(state: PixieDustUiState) {
        val startedAt = state.attackStartedAt
        if (startedAt == null) {
            binding.cardSession.visibility = View.GONE
            stopChronometer()
            return
        }
        binding.cardSession.visibility = View.VISIBLE

        val outcome = when {
            state.isAttacking -> SessionOutcome.Running
            state.attackCancelled -> SessionOutcome.Cancelled
            state.result is PixieDustResult.Success -> SessionOutcome.Success
            else -> SessionOutcome.Failure
        }

        bindTarget(state)
        bindStatus(outcome)
        bindProgress(state, outcome)
        bindChronometer(
            startedAt,
            state.attackFinishedAt,
            outcome == SessionOutcome.Running
        )
        bindPhases(state, outcome)
        bindResult(state, outcome)
        bindConsole(state)
    }

    private fun bindTarget(state: PixieDustUiState) {
        val target = state.selectedNetwork
        binding.tvSessionTarget.text = target?.displayName
            ?: context.getString(R.string.pixie_target_none_title)
        binding.tvSessionBssid.text = if (target != null) {
            context.getString(
                R.string.pixie_session_target_meta,
                target.bssid,
                target.band().label(context)
            )
        } else {
            context.getString(R.string.pixie_target_none_message)
        }
    }

    private fun bindStatus(outcome: SessionOutcome) {
        val labelRes: Int
        val backgroundRes: Int
        val foregroundRes: Int
        when (outcome) {
            SessionOutcome.Running -> {
                labelRes = R.string.pixie_session_status_running
                backgroundRes = R.color.md_theme_primaryContainer
                foregroundRes = R.color.md_theme_onPrimaryContainer
            }
            SessionOutcome.Success -> {
                labelRes = R.string.pixie_session_status_success
                backgroundRes = R.color.monitor_active
                foregroundRes = R.color.md_theme_onPrimary
            }
            SessionOutcome.Cancelled -> {
                labelRes = R.string.pixie_session_status_cancelled
                backgroundRes = R.color.md_theme_surfaceVariant
                foregroundRes = R.color.md_theme_onSurfaceVariant
            }
            SessionOutcome.Failure -> {
                labelRes = R.string.pixie_session_status_failure
                backgroundRes = R.color.error_container
                foregroundRes = R.color.on_error_container
            }
        }
        binding.tvSessionStatus.setText(labelRes)
        binding.tvSessionStatus.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, backgroundRes)
        )
        binding.tvSessionStatus.setTextColor(ContextCompat.getColor(context, foregroundRes))
    }

    private fun bindProgress(state: PixieDustUiState, outcome: SessionOutcome) {
        val stage = state.attackStage
        val progress = when (outcome) {
            SessionOutcome.Success, SessionOutcome.Failure -> 100
            SessionOutcome.Cancelled -> stage?.progress ?: 0
            SessionOutcome.Running -> stage?.progress ?: PixieDustStage.PREPARING.progress
        }
        binding.progressSession.setProgressCompat(progress, true)
        binding.progressSession.setIndicatorColor(
            ContextCompat.getColor(
                context,
                when (outcome) {
                    SessionOutcome.Success -> R.color.monitor_active
                    SessionOutcome.Failure -> R.color.managed
                    SessionOutcome.Cancelled -> R.color.warning
                    SessionOutcome.Running -> R.color.md_theme_primary
                }
            )
        )
        binding.tvSessionPercent.text = context.getString(R.string.pixie_percent, progress)

        val (titleRes, detailRes) = when (outcome) {
            SessionOutcome.Success ->
                R.string.pixie_stage_complete to R.string.pixie_stage_complete_detail
            SessionOutcome.Cancelled ->
                R.string.pixie_stage_cancelled to R.string.pixie_stage_cancelled_detail
            SessionOutcome.Failure ->
                R.string.pixie_stage_failed to R.string.pixie_stage_failed_detail
            SessionOutcome.Running -> stageResources(stage)
        }
        binding.tvSessionStage.setText(titleRes)
        binding.tvSessionStageDetail.setText(detailRes)
        binding.progressSession.contentDescription = context.getString(
            R.string.pixie_session_progress_description,
            progress,
            context.getString(titleRes)
        )
    }

    /** Par título/detalle de cada hito, en un único `when` sobre la etapa. */
    private fun stageResources(stage: PixieDustStage?): Pair<Int, Int> = when (stage) {
        PixieDustStage.DISABLING_WIFI ->
            R.string.pixie_stage_interface to R.string.pixie_stage_interface_detail
        PixieDustStage.STARTING_SUPPLICANT ->
            R.string.pixie_stage_supplicant to R.string.pixie_stage_supplicant_detail
        PixieDustStage.REGISTERING_WPS ->
            R.string.pixie_stage_registering to R.string.pixie_stage_registering_detail
        PixieDustStage.CAPTURING_M3 ->
            R.string.pixie_stage_capturing to R.string.pixie_stage_capturing_detail
        PixieDustStage.ANALYZING ->
            R.string.pixie_stage_analyzing to R.string.pixie_stage_analyzing_detail
        PixieDustStage.EXTRACTING_PSK ->
            R.string.pixie_stage_psk to R.string.pixie_stage_psk_detail
        PixieDustStage.RESTORING ->
            R.string.pixie_stage_restoring to R.string.pixie_stage_restoring_detail
        PixieDustStage.COMPLETED ->
            R.string.pixie_stage_complete to R.string.pixie_stage_complete_detail
        PixieDustStage.PREPARING, null ->
            R.string.pixie_stage_preparing to R.string.pixie_stage_preparing_detail
    }

    private fun bindChronometer(startedAt: Long, finishedAt: Long?, running: Boolean) {
        if (running) {
            if (!chronometerRunning) {
                // Chronometer usa la base de elapsedRealtime, no la hora de
                // pared: convertimos el inicio guardado (wall clock) a esa base.
                val sinceStart = System.currentTimeMillis() - startedAt
                binding.chronSession.base = SystemClock.elapsedRealtime() - sinceStart
                binding.chronSession.start()
                chronometerRunning = true
            }
        } else {
            stopChronometer()
            // Con la sesión terminada el cronómetro debe mostrar el total, no
            // reiniciarse a 00:00 al volver a pintar o recrear la pantalla.
            val elapsed = (finishedAt ?: System.currentTimeMillis()) - startedAt
            binding.chronSession.base =
                SystemClock.elapsedRealtime() - elapsed.coerceAtLeast(0L)
        }
    }

    private fun stopChronometer() {
        if (!chronometerRunning) return
        binding.chronSession.stop()
        chronometerRunning = false
    }

    private fun bindPhases(state: PixieDustUiState, outcome: SessionOutcome) {
        val current = if (outcome == SessionOutcome.Success) {
            PixieDustPhase.CREDENTIALS
        } else {
            state.attackStage?.phase ?: PixieDustPhase.PREPARE
        }
        val bars = listOf(
            binding.phase1Bar,
            binding.phase2Bar,
            binding.phase3Bar,
            binding.phase4Bar,
            binding.phase5Bar
        )
        val labels = listOf(
            binding.phase1Label,
            binding.phase2Label,
            binding.phase3Label,
            binding.phase4Label,
            binding.phase5Label
        )
        val doneColor = ContextCompat.getColor(context, R.color.md_theme_primaryContainer)
        val activeColor = ContextCompat.getColor(context, R.color.md_theme_primary)
        val failedColor = ContextCompat.getColor(context, R.color.managed)
        val stoppedColor = ContextCompat.getColor(context, R.color.warning)
        val pendingColor = ContextCompat.getColor(context, R.color.md_theme_outlineVariant)
        val reached = current.ordinal

        bars.forEachIndexed { index, bar ->
            when {
                index < reached -> bar.setBackgroundColor(doneColor)
                index == reached && outcome == SessionOutcome.Failure ->
                    bar.setBackgroundColor(failedColor)
                index == reached && outcome == SessionOutcome.Cancelled ->
                    bar.setBackgroundColor(stoppedColor)
                index == reached && outcome == SessionOutcome.Running ->
                    bar.setBackgroundColor(activeColor)
                index == reached && outcome == SessionOutcome.Success ->
                    bar.setBackgroundColor(doneColor)
                else -> bar.setBackgroundColor(pendingColor)
            }
        }
        labels.forEachIndexed { index, label ->
            label.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (index <= reached) R.color.md_theme_onSurface
                    else R.color.md_theme_onSurfaceVariant
                )
            )
        }
    }

    private fun bindResult(state: PixieDustUiState, outcome: SessionOutcome) {
        val result = state.result
        val show = outcome != SessionOutcome.Running
        binding.layoutSessionResult.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val bannerTint: Int
        val bannerText: Int
        val iconRes: Int
        val iconTint: Int
        when (outcome) {
            SessionOutcome.Success -> {
                bannerTint = R.color.md_theme_primaryContainer
                bannerText = R.color.md_theme_onPrimaryContainer
                iconRes = R.drawable.ic_check_circle
                iconTint = R.color.monitor_active
            }
            SessionOutcome.Cancelled -> {
                bannerTint = R.color.md_theme_surfaceVariant
                bannerText = R.color.md_theme_onSurface
                iconRes = R.drawable.ic_close_circle
                iconTint = R.color.warning
            }
            SessionOutcome.Running, SessionOutcome.Failure -> {
                bannerTint = R.color.error_container
                bannerText = R.color.on_error_container
                iconRes = R.drawable.ic_warning
                iconTint = R.color.managed
            }
        }
        binding.layoutResultBanner.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, bannerTint)
        )
        binding.ivResultIcon.setImageResource(iconRes)
        binding.ivResultIcon.setColorFilter(ContextCompat.getColor(context, iconTint))
        binding.tvResultStatus.setTextColor(ContextCompat.getColor(context, bannerText))
        binding.tvResultSubtitle.setTextColor(
            ContextCompat.getColor(
                context,
                if (outcome == SessionOutcome.Success) {
                    R.color.md_theme_onPrimaryContainer
                } else {
                    bannerText
                }
            )
        )

        when (outcome) {
            SessionOutcome.Success -> bindSuccessResult(result as PixieDustResult.Success)
            SessionOutcome.Cancelled -> bindStoppedResult()
            SessionOutcome.Running, SessionOutcome.Failure ->
                bindFailureResult(result as? PixieDustResult.Failure)
        }
    }

    private fun bindSuccessResult(result: PixieDustResult.Success) {
        binding.tvResultStatus.setText(R.string.pixie_success_title)
        binding.tvResultSubtitle.setText(
            if (result.wpaPsk.isNullOrBlank()) R.string.pixie_success_subtitle_pin_only
            else R.string.pixie_success_subtitle
        )
        binding.layoutResultCredentials.visibility = View.VISIBLE
        binding.tvResultReason.visibility = View.GONE

        binding.tvResultPin.text = result.wpsPin ?: context.getString(R.string.pixie_not_available)
        binding.btnCopyPin.isEnabled = !result.wpsPin.isNullOrBlank()
        binding.btnCopyPin.alpha = if (binding.btnCopyPin.isEnabled) 1f else 0.4f

        val hasPsk = !result.wpaPsk.isNullOrBlank()
        binding.rowResultPsk.visibility = if (hasPsk) View.VISIBLE else View.GONE
        binding.tvResultPsk.text = result.wpaPsk ?: context.getString(R.string.pixie_not_available)

        binding.btnCopyResult.visibility = View.VISIBLE
        binding.btnCopyResult.isEnabled = true
    }

    private fun bindFailureResult(result: PixieDustResult.Failure?) {
        binding.tvResultStatus.setText(R.string.pixie_failure_title)
        binding.tvResultSubtitle.setText(R.string.pixie_failure_subtitle)
        binding.layoutResultCredentials.visibility = View.GONE
        binding.tvResultReason.text = result?.reason.orEmpty()
        binding.tvResultReason.visibility = if (result == null) View.GONE else View.VISIBLE
        binding.btnCopyResult.visibility = View.GONE
    }

    private fun bindStoppedResult() {
        binding.tvResultStatus.setText(R.string.pixie_result_stopped_title)
        binding.tvResultSubtitle.setText(R.string.pixie_result_stopped_subtitle)
        binding.layoutResultCredentials.visibility = View.GONE
        binding.tvResultReason.visibility = View.GONE
        binding.btnCopyResult.visibility = View.GONE
    }

    /**
     * El registro es el protagonista de la sesión: se pinta siempre, con
     * timestamps tenues y el prefijo de nivel coloreado para que se pueda
     * seguir de un vistazo.
     */
    private fun bindConsole(state: PixieDustUiState) {
        val lines = state.consoleLines
        val signature = lines.hashCode()
        binding.tvConsoleCount.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.btnCopyLog.isEnabled = lines.isNotEmpty()
        binding.btnCopyLog.alpha = if (lines.isNotEmpty()) 1f else 0.4f

        if (signature != lastConsoleSignature) {
            binding.tvConsoleCount.text = context.getString(R.string.pixie_console_lines, lines.size)
            binding.tvConsole.setTextColor(
                ContextCompat.getColor(context, R.color.md_theme_onSurface)
            )
            binding.tvConsole.text = if (lines.isEmpty()) {
                context.getString(R.string.pixie_console_empty)
            } else {
                formatLog(lines)
            }
            lastConsoleSignature = signature

            // Solo seguimos el final si el usuario no está leyendo más arriba.
            // Se usa scrollTo (no fullScroll) para que el gesto no arrastre
            // también la página exterior.
            if (lines.isNotEmpty() && consoleExpanded && consoleAtBottom) {
                binding.svConsole.post {
                    val content = binding.svConsole.getChildAt(0) ?: return@post
                    binding.svConsole.scrollTo(
                        0,
                        (content.height - binding.svConsole.height).coerceAtLeast(0)
                    )
                }
            }
        }

        binding.svConsole.visibility = if (consoleExpanded) View.VISIBLE else View.GONE
        binding.btnToggleConsole.contentDescription = context.getString(
            if (consoleExpanded) R.string.pixie_console_hide
            else R.string.pixie_console_show
        )
        binding.btnToggleConsole.rotation = if (consoleExpanded) 180f else 0f
    }

    /** Colorea timestamp y prefijo sin tocar el texto seleccionable. */
    private fun formatLog(lines: List<String>): Spannable {
        val builder = SpannableStringBuilder()
        val timestampColor = ContextCompat.getColor(context, R.color.md_theme_outline)
        lines.forEachIndexed { index, line ->
            if (index > 0) builder.append('\n')
            val lineStart = builder.length
            builder.append(line)

            if (line.length >= 8 && line[2] == ':' && line[5] == ':') {
                builder.setSpan(
                    ForegroundColorSpan(timestampColor),
                    lineStart,
                    lineStart + 8,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val prefixStart = line.indexOf('[')
            if (prefixStart >= 0 && prefixStart + 2 < line.length) {
                val colorRes = when (line.substring(prefixStart, prefixStart + 3)) {
                    "[+]" -> R.color.monitor_active
                    "[-]" -> R.color.managed
                    "[!]" -> R.color.warning
                    "[*]" -> R.color.md_theme_primary
                    else -> null
                }
                if (colorRes != null) {
                    builder.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, colorRes)),
                        lineStart + prefixStart,
                        lineStart + prefixStart + 3,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        return builder
    }
}
