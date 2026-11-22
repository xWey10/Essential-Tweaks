// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.screenshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.view.WindowManager.LayoutParams
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.BuildConfig
import moe.xWey10.tweaks.core.Protocol
import moe.xWey10.tweaks.core.TweakSettings
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.HookLog
import java.util.concurrent.Executors

internal object ScreenshotFeature {
    @Volatile private var enabled = false

    fun install(loader: ClassLoader) = FeatureHooks("Framework screenshot policy").install {
        val state = XposedHelpers.findClass("com.android.server.wm.WindowState", loader)
        val manager = XposedHelpers.findClass("com.android.server.wm.WindowManagerService", loader)
        val attrsField = XposedHelpers.findField(state, "mAttrs")
        val managerField = XposedHelpers.findField(state, "mWmService")
        val lockField = XposedHelpers.findField(manager, "mGlobalLock")
        val contextField = XposedHelpers.findField(manager, "mContext")
        val secure = state.getDeclaredMethod("isSecureLocked")
        val uid = state.getDeclaredMethod("getOwningUid").apply { isAccessible = true }
        val ready = manager.getDeclaredMethod("systemReady")
        val refreshSurfaces = manager.getDeclaredMethod("refreshScreenCaptureDisabled").apply { isAccessible = true }
        check(secure.returnType == Boolean::class.javaPrimitiveType && attrsField.type == LayoutParams::class.java)

        add(secure, before = true) { param ->
            if (!enabled) return@add
            val window = param.thisObject
            val attrs = attrsField.get(window) as LayoutParams
            val original = attrs.flags
            val flags = screenshotFlags(attrs.type, original)
            if (flags == original || (uid.invoke(window) as Int) < Process.FIRST_APPLICATION_UID) return@add
            val lock = lockField.get(managerField.get(window)) ?: return@add
            if (!Thread.holdsLock(lock)) return@add

            // Let WindowManager reevaluate its other checks under the real WM lock.
            attrs.flags = flags
            try {
                param.result = XposedBridge.invokeOriginalMethod(secure, window, emptyArray())
            } finally {
                attrs.flags = original
            }
        }
        add(ready) { param ->
            val service = param.thisObject
            val context = contextField.get(service) as Context
            val worker = Executors.newSingleThreadExecutor { task ->
                Thread(task, "EssentialTweaks-ScreenshotPolicy").apply { isDaemon = true }
            }
            var prefs: XSharedPreferences? = null
            fun refresh() {
                worker.execute {
                    val next = try {
                        val file = prefs ?: XSharedPreferences(BuildConfig.APPLICATION_ID, TweakSettings.FILE)
                            .also { prefs = it }
                        if (file.file.canRead()) {
                            file.reload()
                            file.getBoolean(TweakSettings.ALLOW_SCREENSHOTS, false)
                        } else {
                            prefs = null
                            false
                        }
                    } catch (error: Exception) {
                        prefs = null
                        HookLog.error("Screenshot preference unavailable", error)
                        false
                    }
                    if (enabled != next) {
                        enabled = next
                        runCatching { refreshSurfaces.invoke(service) }
                            .onFailure { HookLog.error("Could not refresh secure window surfaces", it) }
                    }
                }
            }
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Protocol.REFRESH_SCREENSHOTS) refresh()
                }
            }, IntentFilter(Protocol.REFRESH_SCREENSHOTS), Protocol.QUERY_PERMISSION, null,
                Context.RECEIVER_EXPORTED)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_USER_UNLOCKED) refresh()
                }
            }, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
            refresh()
        }
    }
}

internal fun screenshotFlags(type: Int, flags: Int): Int {
    val appWindow = type in LayoutParams.FIRST_APPLICATION_WINDOW..LayoutParams.LAST_APPLICATION_WINDOW ||
        type in LayoutParams.FIRST_SUB_WINDOW..LayoutParams.LAST_SUB_WINDOW
    return if (appWindow) flags and LayoutParams.FLAG_SECURE.inv() else flags
}
