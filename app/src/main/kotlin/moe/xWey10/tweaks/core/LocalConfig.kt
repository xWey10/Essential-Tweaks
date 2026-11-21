// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Private canonical copy survives LSPosed switching the shared-preferences directory. */
class LocalConfig(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "power-menu-v1.json"))
    var current: FeatureConfig = read()
        private set
    var published: Boolean = false
        private set

    init { publish() }

    private fun read(): FeatureConfig = try {
        val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
        FeatureConfig(
            recovery = json.getBoolean(ConfigKeys.RECOVERY),
            bootloader = json.getBoolean(ConfigKeys.BOOTLOADER),
            revision = json.getLong(ConfigKeys.REVISION),
            systemUi = json.optBoolean(ConfigKeys.SYSTEM_UI),
        )
    } catch (_: Exception) {
        FeatureConfig()
    }

    fun update(target: RebootTarget, enabled: Boolean) {
        val next = when (target) {
            RebootTarget.RECOVERY -> current.copy(recovery = enabled)
            RebootTarget.BOOTLOADER -> current.copy(bootloader = enabled)
            RebootTarget.SYSTEM_UI -> current.copy(systemUi = enabled)
        }.copy(revision = current.revision + 1)
        val json = JSONObject().apply {
            put(ConfigKeys.RECOVERY, next.recovery)
            put(ConfigKeys.BOOTLOADER, next.bootloader)
            put(ConfigKeys.SYSTEM_UI, next.systemUi)
            put(ConfigKeys.REVISION, next.revision)
        }
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        current = next
        publish()
    }

    @Suppress("DEPRECATION")
    // Only non-sensitive flags in LSPosed's managed directory. commit's result and visibility
    // before the status query are required; KTX edit/apply does not expose that result.
    @SuppressLint("WorldReadableFiles", "ApplySharedPref", "UseKtx")
    fun publish() {
        published = try {
            // LSPosed API 93+ redirects this file to its managed random directory.
            // Without LSPosed this throws; never chmod the app's private directory.
            context.getSharedPreferences(ConfigKeys.FILE, Context.MODE_WORLD_READABLE)
                .edit()
                .putInt(ConfigKeys.SCHEMA, ConfigKeys.VERSION)
                .putBoolean(ConfigKeys.RECOVERY, current.recovery)
                .putBoolean(ConfigKeys.BOOTLOADER, current.bootloader)
                .putBoolean(ConfigKeys.SYSTEM_UI, current.systemUi)
                .putLong(ConfigKeys.REVISION, current.revision)
                .commit()
        } catch (_: Exception) {
            false
        }
    }
}
