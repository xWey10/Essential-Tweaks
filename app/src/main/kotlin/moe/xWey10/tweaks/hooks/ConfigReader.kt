// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.hooks

import moe.xWey10.tweaks.BuildConfig
import moe.xWey10.tweaks.core.ConfigKeys
import moe.xWey10.tweaks.core.FeatureConfig
import de.robv.android.xposed.XSharedPreferences

class ConfigReader {
    private var preferences: XSharedPreferences? = null
    @Volatile var cached: FeatureConfig? = null
        private set
    private var loggedFailure = false

    @Synchronized
    fun read(): FeatureConfig? = try {
        val prefs = preferences ?: XSharedPreferences(BuildConfig.APPLICATION_ID, ConfigKeys.FILE)
            .also { preferences = it }
        check(prefs.file.canRead()) { "LSPosed managed preferences are not readable" }
        prefs.reload()
        check(prefs.getInt(ConfigKeys.SCHEMA, 0) == ConfigKeys.VERSION) { "Settings not published" }
        FeatureConfig(
            prefs.getBoolean(ConfigKeys.RECOVERY, false),
            prefs.getBoolean(ConfigKeys.BOOTLOADER, false),
            prefs.getLong(ConfigKeys.REVISION, -1),
            prefs.getBoolean(ConfigKeys.SYSTEM_UI, false),
        ).also {
            check(it.revision >= 0) { "Invalid settings revision" }
            cached = it
            loggedFailure = false
        }
    } catch (error: Throwable) {
        cached = null
        preferences = null // Re-resolve LSPosed's path after setup or reinstall.
        if (!loggedFailure) HookLog.error("Settings unavailable; all actions disabled", error)
        loggedFailure = true
        null
    }
}
