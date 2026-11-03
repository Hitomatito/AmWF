/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.wifi.pixiedust

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hitomatito.amwf.R
import com.hitomatito.amwf.databinding.ItemPixieNetworkBinding

/**
 * Lista de redes WiFi detectadas.
 *
 * Cada fila muestra el SSID, el BSSID, el canal, la banda, el tipo de seguridad,
 * la calidad de señal y el estado WPS. Si el mismo SSID también emite en otra
 * banda, se indica con una etiqueta para que el usuario distinga redes dual-band.
 * Las redes no auditables se atenúan y siguen siendo pulsables para explicar al
 * usuario por qué no se pueden elegir.
 */
class PixieNetworkAdapter(
    private val onNetworkClick: (PixieDustNetwork) -> Unit
) : ListAdapter<PixieNetworkAdapter.Item, PixieNetworkAdapter.NetworkViewHolder>(DiffCallback) {

    data class Item(
        val network: PixieDustNetwork,
        val selected: Boolean,
        val otherBands: Set<WifiBand> = emptySet()
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val binding = ItemPixieNetworkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NetworkViewHolder(binding, onNetworkClick)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NetworkViewHolder(
        private val binding: ItemPixieNetworkBinding,
        private val onNetworkClick: (PixieDustNetwork) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Item) {
            val context = binding.root.context
            val network = item.network
            val auditable = network.isAuditable()
            val density = context.resources.displayMetrics.density
            val band = network.band()
            val security = network.security()
            val bandLabel = band.label(context)
            val securityLabel = security.label(context)

            binding.tvSsid.text = network.displayName
            val channel = network.channel()
            binding.tvMeta.text = if (channel != null) {
                context.getString(
                    R.string.pixie_network_channel_bssid,
                    channel,
                    network.bssid
                )
            } else {
                context.getString(R.string.pixie_network_bssid, network.bssid)
            }
            binding.tvSignal.text = context.getString(
                R.string.pixie_signal_dbm,
                network.level
            )
            binding.tvSignal.setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        network.level >= -60 -> R.color.monitor_active
                        network.level >= -75 -> R.color.md_theme_onSurface
                        else -> R.color.managed
                    }
                )
            )

            binding.tvBand.text = bandLabel
            binding.tvBand.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.md_theme_primaryContainer)
            )
            binding.tvBand.setTextColor(
                ContextCompat.getColor(context, R.color.md_theme_onPrimaryContainer)
            )
            binding.tvBand.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO

            binding.tvSecurity.text = securityLabel
            binding.tvSecurity.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.md_theme_surfaceVariant)
            )
            binding.tvSecurity.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (security == WifiSecurity.OPEN || security == WifiSecurity.WEP) {
                        R.color.managed
                    } else {
                        R.color.md_theme_onSurfaceVariant
                    }
                )
            )
            binding.tvSecurity.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO

            val wpsStatus = when {
                network.wpsLocked -> context.getString(R.string.pixie_wps_locked)
                network.wpsCapable -> context.getString(R.string.pixie_wps_available)
                else -> context.getString(R.string.pixie_wps_not_supported)
            }
            binding.tvWpsChip.text = when {
                network.wpsLocked -> context.getString(R.string.pixie_wps_locked_chip)
                network.wpsCapable -> context.getString(R.string.pixie_wps_chip)
                else -> context.getString(R.string.pixie_wps_not_supported)
            }

            val chipBackground = when {
                network.wpsLocked -> R.color.md_theme_surfaceVariant
                network.wpsCapable -> R.color.md_theme_primaryContainer
                else -> R.color.md_theme_surfaceVariant
            }
            val chipText = when {
                network.wpsLocked -> R.color.managed
                network.wpsCapable -> R.color.md_theme_onPrimaryContainer
                else -> R.color.md_theme_onSurfaceVariant
            }
            binding.tvWpsChip.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, chipBackground)
            )
            binding.tvWpsChip.setTextColor(ContextCompat.getColor(context, chipText))
            binding.tvWpsChip.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO

            val otherBands = item.otherBands
            if (otherBands.isEmpty()) {
                binding.tvAlsoOnBand.visibility = View.GONE
            } else {
                val bands = otherBands.sortedBy { it.ordinal }
                    .joinToString(" + ") { it.label(context) }
                binding.tvAlsoOnBand.text =
                    context.getString(R.string.pixie_also_on_band, bands)
                binding.tvAlsoOnBand.visibility = View.VISIBLE
                binding.tvAlsoOnBand.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.md_theme_surfaceVariant)
                )
                binding.tvAlsoOnBand.setTextColor(
                    ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant)
                )
                binding.tvAlsoOnBand.importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            binding.rbSelected.isChecked = item.selected
            binding.cardNetwork.strokeColor = ContextCompat.getColor(
                context,
                if (item.selected) R.color.md_theme_primary else R.color.card_stroke
            )
            binding.cardNetwork.strokeWidth =
                ((if (item.selected) 2 else 1) * density).toInt()
            binding.cardNetwork.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (item.selected) R.color.md_theme_surfaceVariant else R.color.card_background
                )
            )
            binding.root.alpha = if (auditable) 1f else 0.7f

            val description = context.getString(
                R.string.pixie_network_row_description,
                network.displayName,
                network.bssid,
                bandLabel,
                securityLabel,
                wpsStatus
            )
            binding.root.contentDescription = if (item.selected) {
                context.getString(R.string.pixie_network_row_selected_description, description)
            } else {
                description
            }

            binding.root.setOnClickListener { onNetworkClick(network) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean =
            oldItem.network.bssid == newItem.network.bssid

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
            oldItem == newItem
    }
}
