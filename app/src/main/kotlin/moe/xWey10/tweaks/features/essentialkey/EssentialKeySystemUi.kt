// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.essentialkey

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Process
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.core.Protocol
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.HookLog

internal class EssentialKeySystemUi(private val loader: ClassLoader) {
    @Volatile private var systemActions: Any? = null
    @Volatile private var tileFactory: Any? = null
    private var recordTile: Any? = null
    private var screenshot: (() -> Unit)? = null
    private var record: (() -> Unit)? = null

    fun install() {
        FeatureHooks("Essential Key screenshot").install {
            val actions = XposedHelpers.findClass("com.android.systemui.accessibility.SystemActions", loader)
            val start = actions.getDeclaredMethod("start")
            val capture = actions.getDeclaredMethod("handleTakeScreenshot").apply { isAccessible = true }
            add(start) { systemActions = it.thisObject }
            screenshot = { capture.invoke(checkNotNull(systemActions)) }
        }
        FeatureHooks("Essential Key screen recording").install {
            val factory = XposedHelpers.findClass("com.android.systemui.qs.tileimpl.QSFactoryImpl", loader)
            val recorder = XposedHelpers.findClass("com.android.systemui.qs.tiles.ScreenRecordTile", loader)
            val expandable = XposedHelpers.findClass("com.android.systemui.animation.Expandable", loader)
            val create = factory.getDeclaredMethod("createTile", String::class.java)
            val click = recorder.getDeclaredMethod("handleClick", expandable).apply { isAccessible = true }
            factory.declaredConstructors.forEach { constructor ->
                add(constructor) { tileFactory = it.thisObject }
            }
            // The native factory supplies the recording controller even without the tile in Quick Settings.
            record = {
                val tile = recordTile ?: checkNotNull(create.invoke(checkNotNull(tileFactory), "screenrecord"))
                    .also {
                        check(recorder.isInstance(it))
                        recordTile = it
                    }
                click.invoke(tile, null)
            }
        }
    }

    fun register(context: Context) {
        if (screenshot == null && record == null) return
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val loggedFailures = mutableSetOf<EssentialKeyCapture>()
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Protocol.ESSENTIAL_KEY_CAPTURE || sentFromUid != Process.SYSTEM_UID) return
                val action = when (intent.getStringExtra(Protocol.ESSENTIAL_KEY_ACTION)) {
                    EssentialKeyCapture.SCREENSHOT.name -> EssentialKeyCapture.SCREENSHOT
                    EssentialKeyCapture.RECORD.name -> EssentialKeyCapture.RECORD
                    else -> return
                }
                runCatching {
                    if (power?.isInteractive != true || keyguard?.isKeyguardLocked != false) return
                    checkNotNull(if (action == EssentialKeyCapture.SCREENSHOT) screenshot else record).invoke()
                }.onFailure {
                    if (loggedFailures.add(action)) HookLog.error("Essential Key capture unavailable: ${action.name}", it)
                }
            }
        }, IntentFilter(Protocol.ESSENTIAL_KEY_CAPTURE), "android.permission.DEVICE_POWER", null,
            Context.RECEIVER_EXPORTED)
    }
}
