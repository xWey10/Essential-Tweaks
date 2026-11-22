// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

import android.content.Context
import android.content.res.Resources
import moe.xWey10.tweaks.R
import moe.xWey10.tweaks.hooks.HookLog

/** Load only resources from the module APK; never load module code into a new Context. */
internal class ModuleText(private val context: Context, private val modulePath: String) {
    private var resources: Resources? = null

    @Suppress("DEPRECATION")
    fun get(id: Int, vararg args: Any): String {
        try {
            val res = resources ?: run {
                val info = context.packageManager.getPackageArchiveInfo(modulePath, 0)?.applicationInfo
                    ?: error("Module APK unavailable")
                info.sourceDir = modulePath
                info.publicSourceDir = modulePath
                context.packageManager.getResourcesForApplication(info).also { resources = it }
            }
            return res.getString(id, *args)
        } catch (error: Throwable) {
            HookLog.error("Could not load localized module text", error)
            val fallback = when (id) {
                R.string.recovery -> "Recovery"
                R.string.bootloader -> "Bootloader"
                R.string.reboot_to -> "Reboot to %s?"
                R.string.reboot -> "Reboot"
                R.string.restart -> "Restart"
                R.string.restart_system_ui -> "Restart System UI"
                R.string.confirm_system_ui -> "The status bar and lock screen may disappear briefly while System UI restarts."
                R.string.cancel -> "Cancel"
                R.string.confirm_recovery -> "The device will restart into recovery. This action does not erase data."
                R.string.confirm_bootloader -> "The device will restart into bootloader (fastboot), not fastbootd."
                R.string.unlock_required -> "Unlock the device and open the power menu again."
                R.string.settings_unavailable -> "Open Essential Tweaks to publish settings, then try again."
                R.string.request_expired -> "Confirmation expired. Open the power menu and try again."
                R.string.missing_reboot_permission -> "SystemUI has no REBOOT permission. Reboot cancelled; no root request will be made."
                R.string.missing_recovery_permission -> "SystemUI has no RECOVERY permission. Recovery reboot unavailable without system permissions."
                R.string.reboot_permission_denied -> "SystemUI refused the reboot request. See the LSPosed log; no root request was made."
                R.string.reboot_failed -> "Could not reboot via SystemUI. See the EssentialTweaks LSPosed log."
                R.string.reboot_timeout -> "No reboot response received. Check the device before trying again."
                else -> "Action unavailable. Check the EssentialTweaks LSPosed log."
            }
            return if (args.isEmpty()) fallback else String.format(fallback, *args)
        }
    }
}
