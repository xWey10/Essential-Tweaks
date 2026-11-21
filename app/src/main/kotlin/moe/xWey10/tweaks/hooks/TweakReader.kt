// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.hooks

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XSharedPreferences
import moe.xWey10.tweaks.BuildConfig
import moe.xWey10.tweaks.core.FingerprintImage
import moe.xWey10.tweaks.core.PrivateIconProvider
import moe.xWey10.tweaks.core.TweakSettings
import java.util.concurrent.Executors

/** Reads LSPosed preferences off the SystemUI main thread. Observers run on that thread. */
class TweakReader {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<() -> Unit>()
    private var preferences: XSharedPreferences? = null
    private var imageFailureLogged = false
    private var cachedImageKey = ""
    private var cachedImage: Bitmap? = null
    var settings = TweakSettings()
        private set
    var icon: Bitmap? = null
        private set

    fun observe(observer: () -> Unit) { observers.add(observer) }

    fun refresh(context: Context) {
        worker.execute {
            val next = runCatching {
                val prefs = preferences ?: XSharedPreferences(BuildConfig.APPLICATION_ID, TweakSettings.FILE)
                    .also { preferences = it }
                check(prefs.file.canRead())
                prefs.reload()
                TweakSettings.decode(requireNotNull(prefs.getString(TweakSettings.SNAPSHOT, null)))
            }.getOrElse {
                preferences = null
                TweakSettings()
            }
            val image = if (next.iconEnabled && next.icon.length == 64) {
                readIcon(context, next.icon)
            } else {
                cachedImageKey = ""
                cachedImage = null
                imageFailureLogged = false
                null
            }
            main.post {
                if (next == settings && image === icon) return@post
                settings = next
                icon = image
                observers.forEach { observer ->
                    runCatching(observer).onFailure { HookLog.error("Settings observer failed", it) }
                }
            }
        }
    }

    private fun readIcon(context: Context, key: String): Bitmap? {
        if (key == cachedImageKey && cachedImage != null) return cachedImage
        cachedImageKey = key
        cachedImage = runCatching {
            val image = context.contentResolver.call(
                PrivateIconProvider.URI, PrivateIconProvider.READ_ICON, key, null
            )?.getString(TweakSettings.SNAPSHOT)
            check(!image.isNullOrEmpty()) { "No matching image from settings app" }
            check(image.length <= TweakSettings.ICON_LIMIT && PrivateIconProvider.digest(image) == key) {
                "Image does not match settings"
            }
            checkNotNull(FingerprintImage.decode(image)) { "Image could not be decoded" }
        }.onFailure {
            if (!imageFailureLogged) HookLog.error("Fingerprint image could not be read", it)
            imageFailureLogged = true
        }.getOrNull()
        if (cachedImage != null) imageFailureLogged = false
        return cachedImage
    }
}
