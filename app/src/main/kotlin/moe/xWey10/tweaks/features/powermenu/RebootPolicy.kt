// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

import moe.xWey10.tweaks.core.RebootTarget

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

/** Pure permission policy; the caller obtains grants from SystemUI, never from the module APK. */
internal enum class MissingPowerPermission { REBOOT, RECOVERY }

internal fun missingRebootPermission(
    target: RebootTarget,
    canReboot: Boolean,
    canRecover: Boolean,
): MissingPowerPermission? = when {
    target == RebootTarget.SYSTEM_UI -> null
    !canReboot -> MissingPowerPermission.REBOOT
    target == RebootTarget.RECOVERY && !canRecover -> MissingPowerPermission.RECOVERY
    else -> null
}
