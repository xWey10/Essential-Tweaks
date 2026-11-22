// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File

class LocalTweaks(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "tweaks-v1.json"))
    var current = synchronized(privateFileLock) {
        runCatching { TweakSettings.decode(String(file.readFully(), Charsets.UTF_8)) }
            .getOrDefault(TweakSettings())
    }
        private set

    fun update(next: TweakSettings): Boolean {
        synchronized(privateFileLock) {
            val output = file.startWrite()
            try {
                output.write(next.encode().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
        current = next
        return publish()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WorldReadableFiles", "ApplySharedPref", "UseKtx")
    fun publish(): Boolean = runCatching {
        val iconDigest = current.icon.takeIf { current.iconEnabled && it.isNotEmpty() }
            ?.let(PrivateIconProvider::digest).orEmpty()
        val shared = current.copy(icon = iconDigest)
        val committed = context.getSharedPreferences(TweakSettings.FILE, Context.MODE_WORLD_READABLE)
            .edit()
            .putString(TweakSettings.SNAPSHOT, shared.encode())
            .putBoolean(TweakSettings.ALLOW_SCREENSHOTS, current.allowScreenshots)
            .commit()
        if (committed) context.sendBroadcast(Intent(Protocol.REFRESH_SCREENSHOTS).setPackage("android"))
        committed
    }.getOrDefault(false)

    private companion object {
        // Settings UI and the provider's binder thread can access the canonical copy concurrently.
        val privateFileLock = Any()
    }
}
