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

    /**
     * Estado final de la sesión, derivado una sola vez del [PixieDustUiState].
     * Un enum basta: no lleva datos y así cada `when` es exhaustivo sin ramas
     * muertas.
     */
    private enum class SessionOutcome { RUNNING, SUCCESS, CANCELLED, FAILURE }

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
        val startedAt = state.session.startedAt
        if (startedAt == null) {
            binding.cardSession.root.visibility = View.GONE
            stopChronometer()
            return
        }
        binding.cardSession.root.visibility = View.VISIBLE

        val outcome = when {
            state.session.isAttacking -> SessionOutcome.RUNNING
            state.session.cancelled -> SessionOutcome.CANCELLED
            state.session.result is PixieDustResult.Success -> SessionOutcome.SUCCESS
            else -> SessionOutcome.FAILURE
        }

        bindTarget(state)
        bindStatus(outcome)
        bindProgress(state, outcome)
        bindChronometer(
            startedAt,
            state.session.finishedAt,
            outcome == SessionOutcome.RUNNING
        )
        bindPhases(state, outcome)
        bindResult(state, outcome)
        bindConsole(state)
    }

    private fun bindTarget(state: PixieDustUiState) {
        val target = state.scan.selectedNetwork
        binding.cardSession.tvSessionTarget.text = target?.displayName
            ?: context.getString(R.string.pixie_target_none_title)
        binding.cardSession.tvSessionBssid.text = if (target != null) {
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
            SessionOutcome.RUNNING -> {
                labelRes = R.string.pixie_session_status_running
                backgroundRes = R.color.md_theme_primaryContainer
                foregroundRes = R.color.md_theme_onPrimaryContainer
            }
            SessionOutcome.SUCCESS -> {
                labelRes = R.string.pixie_session_status_success
                backgroundRes = R.color.monitor_active
                foregroundRes = R.color.md_theme_onPrimary
            }
            SessionOutcome.CANCELLED -> {
                labelRes = R.string.pixie_session_status_cancelled
                backgroundRes = R.color.md_theme_surfaceVariant
                foregroundRes = R.color.md_theme_onSurfaceVariant
            }
            SessionOutcome.FAILURE -> {
                labelRes = R.string.pixie_session_status_failure
                backgroundRes = R.color.error_container
                foregroundRes = R.color.on_error_container
            }
        }
        binding.cardSession.tvSessionStatus.setText(labelRes)
        binding.cardSession.tvSessionStatus.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, backgroundRes)
        )
        binding.cardSession.tvSessionStatus.setTextColor(ContextCompat.getColor(context, foregroundRes))
    }

    /** Progreso, color y textos del indicador para un resultado. */
    private data class ProgressVisual(
        val progress: Int,
        val colorRes: Int,
        val titleRes: Int,
        val detailRes: Int
    )

    /**
     * Un único `when` por resultado. Antes había tres switches con la misma
     * estructura (progreso, color y par título/detalle), fáciles de
     * desincronizar al añadir un estado.
     */
    private fun progressVisual(
        state: PixieDustUiState,
        outcome: SessionOutcome
    ): ProgressVisual = when (outcome) {
        SessionOutcome.SUCCESS -> ProgressVisual(
            progress = 100,
            colorRes = R.color.monitor_active,
            titleRes = R.string.pixie_stage_complete,
            detailRes = R.string.pixie_stage_complete_detail
        )
        SessionOutcome.FAILURE -> ProgressVisual(
            progress = 100,
            colorRes = R.color.managed,
            titleRes = R.string.pixie_stage_failed,
            detailRes = R.string.pixie_stage_failed_detail
        )
        SessionOutcome.CANCELLED -> ProgressVisual(
            progress = state.session.stage?.progress ?: 0,
            colorRes = R.color.warning,
            titleRes = R.string.pixie_stage_cancelled,
            detailRes = R.string.pixie_stage_cancelled_detail
        )
        SessionOutcome.RUNNING -> {
            val stage = state.session.stage
            val (titleRes, detailRes) = stageResources(stage)
            ProgressVisual(
                progress = stage?.progress ?: PixieDustStage.PREPARING.progress,
                colorRes = R.color.md_theme_primary,
                titleRes = titleRes,
                detailRes = detailRes
            )
        }
    }

    private fun bindProgress(state: PixieDustUiState, outcome: SessionOutcome) {
        val visual = progressVisual(state, outcome)
        binding.cardSession.progressSession.setProgressCompat(visual.progress, true)
        binding.cardSession.progressSession.setIndicatorColor(
            ContextCompat.getColor(context, visual.colorRes)
        )
        binding.cardSession.tvSessionPercent.text = context.getString(R.string.pixie_percent, visual.progress)
        binding.cardSession.tvSessionStage.setText(visual.titleRes)
        binding.cardSession.tvSessionStageDetail.setText(visual.detailRes)
        binding.cardSession.progressSession.contentDescription = context.getString(
            R.string.pixie_session_progress_description,
            visual.progress,
            context.getString(visual.titleRes)
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
                binding.cardSession.chronSession.base = SystemClock.elapsedRealtime() - sinceStart
                binding.cardSession.chronSession.start()
                chronometerRunning = true
            }
        } else {
            stopChronometer()
            // Con la sesión terminada el cronómetro debe mostrar el total, no
            // reiniciarse a 00:00 al volver a pintar o recrear la pantalla.
            val elapsed = (finishedAt ?: System.currentTimeMillis()) - startedAt
            binding.cardSession.chronSession.base =
                SystemClock.elapsedRealtime() - elapsed.coerceAtLeast(0L)
        }
    }

    private fun stopChronometer() {
        if (!chronometerRunning) return
        binding.cardSession.chronSession.stop()
        chronometerRunning = false
    }

    private fun bindPhases(state: PixieDustUiState, outcome: SessionOutcome) {
        val current = if (outcome == SessionOutcome.SUCCESS) {
            PixieDustPhase.CREDENTIALS
        } else {
            state.session.stage?.phase ?: PixieDustPhase.PREPARE
        }
        val bars = listOf(
            binding.cardSession.phase1Bar,
            binding.cardSession.phase2Bar,
            binding.cardSession.phase3Bar,
            binding.cardSession.phase4Bar,
            binding.cardSession.phase5Bar
        )
        val labels = listOf(
            binding.cardSession.phase1Label,
            binding.cardSession.phase2Label,
            binding.cardSession.phase3Label,
            binding.cardSession.phase4Label,
            binding.cardSession.phase5Label
        )
        val doneColor = ContextCompat.getColor(context, R.color.md_theme_primaryContainer)
        val activeColor = ContextCompat.getColor(context, R.color.md_theme_primary)
        val failedColor = ContextCompat.getColor(context, R.color.managed)
        val stoppedColor = ContextCompat.getColor(context, R.color.warning)
        val pendingColor = ContextCompat.getColor(context, R.color.md_theme_outlineVariant)
        val reached = current.ordinal
        val currentColor = when (outcome) {
            SessionOutcome.RUNNING -> activeColor
            SessionOutcome.SUCCESS -> doneColor
            SessionOutcome.CANCELLED -> stoppedColor
            SessionOutcome.FAILURE -> failedColor
        }

        bars.forEachIndexed { index, bar ->
            bar.setBackgroundColor(
                when {
                    index < reached -> doneColor
                    index == reached -> currentColor
                    else -> pendingColor
                }
            )
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

    /** Colores e icono del banner de resultado. */
    private data class ResultVisual(
        val bannerTint: Int,
        val bannerText: Int,
        val subtitleText: Int,
        val iconRes: Int,
        val iconTint: Int
    )

    private fun resultVisual(outcome: SessionOutcome): ResultVisual = when (outcome) {
        SessionOutcome.SUCCESS -> ResultVisual(
            bannerTint = R.color.md_theme_primaryContainer,
            bannerText = R.color.md_theme_onPrimaryContainer,
            subtitleText = R.color.md_theme_onPrimaryContainer,
            iconRes = R.drawable.ic_check_circle,
            iconTint = R.color.monitor_active
        )
        SessionOutcome.CANCELLED -> ResultVisual(
            bannerTint = R.color.md_theme_surfaceVariant,
            bannerText = R.color.md_theme_onSurface,
            subtitleText = R.color.md_theme_onSurface,
            iconRes = R.drawable.ic_close_circle,
            iconTint = R.color.warning
        )
        SessionOutcome.RUNNING, SessionOutcome.FAILURE -> ResultVisual(
            bannerTint = R.color.error_container,
            bannerText = R.color.on_error_container,
            subtitleText = R.color.on_error_container,
            iconRes = R.drawable.ic_warning,
            iconTint = R.color.managed
        )
    }

    private fun bindResult(state: PixieDustUiState, outcome: SessionOutcome) {
        val result = state.session.result
        val show = outcome != SessionOutcome.RUNNING
        binding.cardSession.layoutSessionResult.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val visual = resultVisual(outcome)
        binding.cardSession.layoutResultBanner.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, visual.bannerTint)
        )
        binding.cardSession.ivResultIcon.setImageResource(visual.iconRes)
        binding.cardSession.ivResultIcon.setColorFilter(ContextCompat.getColor(context, visual.iconTint))
        binding.cardSession.tvResultStatus.setTextColor(ContextCompat.getColor(context, visual.bannerText))
        binding.cardSession.tvResultSubtitle.setTextColor(ContextCompat.getColor(context, visual.subtitleText))

        when (outcome) {
            SessionOutcome.SUCCESS -> bindSuccessResult(result as PixieDustResult.Success)
            SessionOutcome.CANCELLED -> bindStoppedResult()
            SessionOutcome.RUNNING, SessionOutcome.FAILURE ->
                bindFailureResult(result as? PixieDustResult.Failure)
        }
    }

    private fun bindSuccessResult(result: PixieDustResult.Success) {
        binding.cardSession.tvResultStatus.setText(R.string.pixie_success_title)
        binding.cardSession.tvResultSubtitle.setText(
            if (result.wpaPsk.isNullOrBlank()) R.string.pixie_success_subtitle_pin_only
            else R.string.pixie_success_subtitle
        )
        binding.cardSession.layoutResultCredentials.visibility = View.VISIBLE
        binding.cardSession.tvResultReason.visibility = View.GONE

        binding.cardSession.tvResultPin.text = result.wpsPin ?: context.getString(R.string.pixie_not_available)
        binding.cardSession.btnCopyPin.isEnabled = !result.wpsPin.isNullOrBlank()
        binding.cardSession.btnCopyPin.alpha = if (binding.cardSession.btnCopyPin.isEnabled) 1f else 0.4f

        val hasPsk = !result.wpaPsk.isNullOrBlank()
        binding.cardSession.rowResultPsk.visibility = if (hasPsk) View.VISIBLE else View.GONE
        binding.cardSession.tvResultPsk.text = result.wpaPsk ?: context.getString(R.string.pixie_not_available)

        binding.cardSession.btnCopyResult.visibility = View.VISIBLE
        binding.cardSession.btnCopyResult.isEnabled = true
    }

    private fun bindFailureResult(result: PixieDustResult.Failure?) {
        binding.cardSession.tvResultStatus.setText(R.string.pixie_failure_title)
        binding.cardSession.tvResultSubtitle.setText(R.string.pixie_failure_subtitle)
        binding.cardSession.layoutResultCredentials.visibility = View.GONE
        binding.cardSession.tvResultReason.text = result?.reason.orEmpty()
        binding.cardSession.tvResultReason.visibility = if (result == null) View.GONE else View.VISIBLE
        binding.cardSession.btnCopyResult.visibility = View.GONE
    }

    private fun bindStoppedResult() {
        binding.cardSession.tvResultStatus.setText(R.string.pixie_result_stopped_title)
        binding.cardSession.tvResultSubtitle.setText(R.string.pixie_result_stopped_subtitle)
        binding.cardSession.layoutResultCredentials.visibility = View.GONE
        binding.cardSession.tvResultReason.visibility = View.GONE
        binding.cardSession.btnCopyResult.visibility = View.GONE
    }

    /**
     * El registro es el protagonista de la sesión: se pinta siempre, con
     * timestamps tenues y el prefijo de nivel coloreado para que se pueda
     * seguir de un vistazo.
     */
    private fun bindConsole(state: PixieDustUiState) {
        val lines = state.consoleLines
        val signature = lines.hashCode()
        binding.cardSession.tvConsoleCount.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.cardSession.btnCopyLog.isEnabled = lines.isNotEmpty()
        binding.cardSession.btnCopyLog.alpha = if (lines.isNotEmpty()) 1f else 0.4f

        if (signature != lastConsoleSignature) {
            binding.cardSession.tvConsoleCount.text = context.resources.getQuantityString(
                R.plurals.pixie_console_lines,
                lines.size,
                lines.size
            )
            binding.cardSession.tvConsole.setTextColor(
                ContextCompat.getColor(context, R.color.md_theme_onSurface)
            )
            binding.cardSession.tvConsole.text = if (lines.isEmpty()) {
                context.getString(R.string.pixie_console_empty)
            } else {
                formatLog(lines)
            }
            lastConsoleSignature = signature

            // Solo seguimos el final si el usuario no está leyendo más arriba.
            // Se usa scrollTo (no fullScroll) para que el gesto no arrastre
            // también la página exterior.
            if (lines.isNotEmpty() && consoleExpanded && consoleAtBottom) {
                binding.cardSession.svConsole.post {
                    val content = binding.cardSession.svConsole.getChildAt(0) ?: return@post
                    binding.cardSession.svConsole.scrollTo(
                        0,
                        (content.height - binding.cardSession.svConsole.height).coerceAtLeast(0)
                    )
                }
            }
        }

        binding.cardSession.svConsole.visibility = if (consoleExpanded) View.VISIBLE else View.GONE
        binding.cardSession.btnToggleConsole.contentDescription = context.getString(
            if (consoleExpanded) R.string.pixie_console_hide
            else R.string.pixie_console_show
        )
        binding.cardSession.btnToggleConsole.rotation = if (consoleExpanded) 180f else 0f
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
