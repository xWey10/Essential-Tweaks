// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.charging

import moe.xWey10.tweaks.core.TweakSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class BatteryMetricsTest {
    private val all = TweakSettings(chargingOnly = false)

    @Test fun positiveCurrentAndPowerAreAtTheBattery() {
        val battery = BatteryMetrics(4250, 1234000, 317, true)
        assertEquals("4.25\u00a0V  ·  +1234\u00a0mA  ·  +5.24\u00a0W  ·  31.7\u00a0°C",
            battery.format(all, Locale.US))
    }

    @Test fun independentPowerTogglePreservesDischargeSign() {
        val battery = BatteryMetrics(4000, -800000, -30, false)
        assertEquals("-3,20\u00a0W",
            battery.format(all.copy(voltage = false, current = false, temperature = false), Locale.GERMANY))
    }

    @Test fun missingInputsSuppressOnlyDependentReadings() {
        for (unsupported in listOf(null, Long.MIN_VALUE, Int.MIN_VALUE.toLong())) {
            val battery = BatteryMetrics(4000, unsupported, 0, true)
            assertEquals("4.00\u00a0V  ·  0.0\u00a0°C", battery.format(all, Locale.US))
        }
    }

    @Test fun zeroCurrentRemainsARealMeasurement() {
        val battery = BatteryMetrics(4000, 0, null, true)
        assertEquals("+0\u00a0mA", battery.format(all.copy(voltage = false, power = false), Locale.US))
    }

    @Test fun chargingOnlyHidesDischargeWhilePluggedStatusIsNotCharging() {
        assertEquals("", BatteryMetrics(4000, -180000, 280, false)
            .format(all.copy(chargingOnly = true), Locale.US))
    }
}
