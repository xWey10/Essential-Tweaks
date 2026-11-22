// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.core

enum class RebootTarget(val reason: String) {
    RECOVERY("recovery"), BOOTLOADER("bootloader"), SYSTEM_UI("systemui")
}

data class FeatureConfig(
    val recovery: Boolean = false,
    val bootloader: Boolean = false,
    val revision: Long = 0,
    val systemUi: Boolean = false,
    val confirmNormalReboot: Boolean = false,
    val confirmPowerOff: Boolean = false,
) {
    fun enabled(target: RebootTarget): Boolean = when (target) {
        RebootTarget.RECOVERY -> recovery
        RebootTarget.BOOTLOADER -> bootloader
        RebootTarget.SYSTEM_UI -> systemUi
    }
}

object ConfigKeys {
    const val FILE = "power_menu_v1"
    const val SCHEMA = "schema"
    const val VERSION = 1
    const val RECOVERY = "show_recovery"
    const val BOOTLOADER = "show_bootloader"
    const val SYSTEM_UI = "show_restart_system_ui"
    const val CONFIRM_NORMAL_REBOOT = "confirm_normal_reboot"
    const val CONFIRM_POWER_OFF = "confirm_power_off"
    const val REVISION = "revision"
}

object Protocol {
    const val SYSTEM_UI = "com.android.systemui"
    const val NOTHING_LAUNCHER = "com.nothing.launcher"
    const val RESTART_LAUNCHER = "moe.xWey10.tweaks.RESTART_LAUNCHER"
    const val RESTART_DEADLINE = "deadline"
    const val RESTART_WINDOW_MS = 3000L
    const val REFRESH_SCREENSHOTS = "moe.xWey10.tweaks.REFRESH_SCREENSHOTS"
    const val ESSENTIAL_KEY_CAPTURE = "moe.xWey10.tweaks.ESSENTIAL_KEY_CAPTURE"
    const val ESSENTIAL_KEY_ACTION = "capture"
    const val ESSENTIAL_KEY_PACKAGE = "keyPackage"
    const val ESSENTIAL_KEY_CLASS = "keyClass"
    const val QUERY_PERMISSION = "moe.xWey10.tweaks.permission.QUERY_STATUS"
    const val QUERY = "moe.xWey10.tweaks.QUERY_STATUS"
    const val RESPONSE = "moe.xWey10.tweaks.STATUS"
    const val PLATFORM_SENDER_PERMISSION = "android.permission.STATUS_BAR"
}
