// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.charging

import moe.xWey10.tweaks.core.TweakSettings
import java.util.Locale

internal data class BatteryMetrics(
    val millivolts: Int?,
    val microamps: Long?,
    val deciCelsius: Int?,
    val charging: Boolean,
    val remainingMillis: Long? = null,
) {
    fun format(settings: TweakSettings, locale: Locale): String {
        if (settings.chargingOnly && !charging) return ""
        val volts = millivolts?.takeIf { it > 0 }?.div(1000.0)
        val milliamps = microamps?.takeUnless {
            it == Long.MIN_VALUE || it == Int.MIN_VALUE.toLong()
        }?.div(1000.0)
        return buildList {
            if (settings.voltage && volts != null) add(String.format(locale, "%.2f\u00a0V", volts))
            if (settings.current && milliamps != null) add(String.format(locale, "%+.0f\u00a0mA", milliamps))
            if (settings.power && volts != null && milliamps != null) {
                add(String.format(locale, "%+.2f\u00a0W", volts * milliamps / 1000))
            }
            if (settings.temperature && deciCelsius != null) {
                add(String.format(locale, "%.1f\u00a0°C", deciCelsius / 10.0))
            }
            if (settings.eta && charging && remainingMillis != null && remainingMillis > 0) {
                val minutes = (remainingMillis - 1) / 60_000 + 1
                add(String.format(locale, "≈ %d:%02d → 100%%", minutes / 60, minutes % 60))
            }
        }.joinToString("  ·  ")
    }
}
