// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10
package moe.xWey10.tweaks.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The extension is selected by the audio encoder; user templates never supply it. */
object RecorderNaming {
    const val DEFAULT = "Essential-{datetime}"
    private val forbidden = Regex("[\\/:*?\"<>|\\p{Cntrl}]")
    private val tokens = Regex("\\{(datetime|date|time|epoch)\\}")

    fun normalizeTemplate(raw: String): String = raw.trim().take(80).ifBlank { DEFAULT }

    fun fileBase(template: String, whenMillis: Long = System.currentTimeMillis()): String {
        val date = Date(whenMillis)
        val formatted = mapOf(
            "datetime" to SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(date),
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date),
            "time" to SimpleDateFormat("HH-mm-ss", Locale.US).format(date),
            "epoch" to whenMillis.toString(),
        )
        val result = tokens.replace(normalizeTemplate(template)) { formatted.getValue(it.groupValues[1]) }
            .replace(forbidden, "_").trim().trim('.', ' ').take(110)
        return result.ifBlank { "Essential-${whenMillis}" }
    }
}
