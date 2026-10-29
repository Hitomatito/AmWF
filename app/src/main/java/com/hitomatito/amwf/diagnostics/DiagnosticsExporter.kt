/*
 * AmWF - Android WiFi Monitor Mode Controller
 * Copyright (C) 2026 Hitomatito
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.hitomatito.amwf.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escribe el informe en cache y lo comparte mediante el share sheet.
 */
object DiagnosticsExporter {

    fun write(context: Context, content: String, secrets: List<String> = emptyList()): Uri {
        val redacted = DiagnosticsFormat.redact(content, secrets)
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "amwf-diagnostics-$stamp.txt")
        file.writeText(redacted)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun share(context: Context, uri: Uri, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            // clipData propaga el permiso de lectura al share sheet (necesario
            // para la vista previa y algunos receptores).
            clipData = ClipData.newUri(context.contentResolver, subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, subject))
    }
}
