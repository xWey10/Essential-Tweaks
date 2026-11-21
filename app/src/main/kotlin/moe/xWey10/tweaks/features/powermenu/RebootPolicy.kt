// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

internal enum class Denial { SETTINGS, DISABLED, LOCKED, UNAVAILABLE }

internal data class GateState(
    val readable: Boolean,
    val enabled: Boolean,
    val deviceLocked: Boolean,
    val keyguardShowing: Boolean,
    val interactive: Boolean,
    val userUnlocked: Boolean,
    val stockVerificationRequired: Boolean,
    val currentUserId: Int,
    val provisioned: Boolean,
    val userSetupComplete: Boolean,
    val ageTest: Boolean,
    val restricted: Boolean,
    val monkey: Boolean,
)

internal fun denial(state: GateState): Denial? = with(state) {
    when {
        !readable -> Denial.SETTINGS
        !enabled -> Denial.DISABLED
        deviceLocked || keyguardShowing || !interactive || !userUnlocked || stockVerificationRequired -> Denial.LOCKED
        currentUserId != 0 || !provisioned || !userSetupComplete || ageTest || restricted || monkey -> Denial.UNAVAILABLE
        else -> null
    }
}
