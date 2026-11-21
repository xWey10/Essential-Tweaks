// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.essentialkey

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class EssentialKeyResult {
    FREED, ALREADY_FREE, RESTORED, ALREADY_RESTORED,
    NOT_INSTALLED, WRONG_USER, ROOT_REQUIRED, TIMEOUT, PARTIAL, FAILED,
}

internal val EssentialKeyResult.restoresStock: Boolean
    get() = this == EssentialKeyResult.RESTORED || this == EssentialKeyResult.ALREADY_RESTORED

internal class EssentialKeyAction(private val context: Context) {
    fun run(): EssentialKeyResult = setPackagesEnabled(enabled = false)

    fun restore(): EssentialKeyResult = setPackagesEnabled(enabled = true)

    private fun setPackagesEnabled(enabled: Boolean): EssentialKeyResult {
        if (context.getSystemService(UserManager::class.java)?.isSystemUser != true) {
            return EssentialKeyResult.WRONG_USER
        }
        val initial = installedPackages()
        if (initial.isEmpty() || (enabled && initial.size != packages.size)) {
            // Do not discard custom actions if a complete stock setup cannot be restored.
            return EssentialKeyResult.NOT_INSTALLED
        }
        val pending = initial.filterValues { it != enabled }.keys
        if (pending.isEmpty()) return if (enabled) {
            EssentialKeyResult.ALREADY_RESTORED
        } else {
            EssentialKeyResult.ALREADY_FREE
        }

        val root = changePackages(pending, enabled)
        val after = installedPackages()
        // Only report success when EVERY previously installed component has the requested state.
        if (initial.keys.all { after[it] == enabled }) return if (enabled) {
            EssentialKeyResult.RESTORED
        } else {
            EssentialKeyResult.FREED
        }
        return when (root) {
            RootAccess.MISSING -> EssentialKeyResult.ROOT_REQUIRED
            RootAccess.TIMED_OUT -> EssentialKeyResult.TIMEOUT
            RootAccess.GRANTED -> if (pending.any { after[it] == enabled }) {
                EssentialKeyResult.PARTIAL
            } else {
                EssentialKeyResult.FAILED
            }
        }
    }

    private fun installedPackages(): Map<String, Boolean> = packages.mapNotNull { name ->
        val manager = context.packageManager
        val info = try {
            manager.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(
                PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
        } catch (_: PackageManager.NameNotFoundException) {
            return@mapNotNull null
        }
        val disabled = when (manager.getApplicationEnabledSetting(name)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> !info.enabled
            else -> false
        }
        name to !disabled
    }.toMap()

    private fun changePackages(targets: Set<String>, enable: Boolean): RootAccess {
        val script = buildString {
            appendLine("if [ \"\$(/system/bin/id -u)\" != 0 ]; then exit 1; fi")
            appendLine("echo $ROOT_MARKER")
            val command = if (enable) "enable" else "disable-user"
            for (name in packages) {
                if (name in targets) appendLine("/system/bin/pm $command --user 0 $name")
            }
        }
        // Redirect to a private cache file so root-manager output cannot fill a pipe.
        val output = File.createTempFile("essential-key-", ".log", context.cacheDir)
        try {
            val process = try {
                ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true).redirectOutput(output).start()
            } catch (_: IOException) {
                return RootAccess.MISSING
            }
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) return RootAccess.TIMED_OUT
                val root = output.bufferedReader().useLines { lines -> lines.take(32).any { it == ROOT_MARKER } }
                return if (root) RootAccess.GRANTED else RootAccess.MISSING
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.outputStream.close()
            }
        } finally {
            output.delete()
        }
    }

    private enum class RootAccess { MISSING, TIMED_OUT, GRANTED }

    companion object {
        private const val ROOT_MARKER = "ET_ESSENTIAL_KEY_ROOT"
        private val packages = listOf("com.nothing.ntessentialrecorder", "com.nothing.ntessentialspace")
        val manualCommandLines: List<String> = packages.map { "sudo pm disable-user --user 0 $it" }
        val manualPasteCommand: String = manualCommandLines.joinToString("; ")
        val manualRestoreCommandLines: List<String> = packages.map { "sudo pm enable --user 0 $it" }
        val manualRestorePasteCommand: String = manualRestoreCommandLines.joinToString("; ")
    }
}
