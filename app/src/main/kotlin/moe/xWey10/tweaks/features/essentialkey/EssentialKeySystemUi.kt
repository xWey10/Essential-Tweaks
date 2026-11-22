// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10
package moe.xWey10.tweaks.features.essentialkey

import android.app.KeyguardManager
import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import moe.xWey10.tweaks.recorder.RecorderActivity
import android.widget.Toast
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
            factory.declaredConstructors.forEach { constructor -> add(constructor) { tileFactory = it.thisObject } }
            record = {
                val tile = recordTile ?: checkNotNull(create.invoke(checkNotNull(tileFactory), "screenrecord"))
                    .also { check(recorder.isInstance(it)); recordTile = it }
                click.invoke(tile, null)
            }
        }
    }

    fun register(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val loggedFailures = mutableSetOf<EssentialKeyCapture>()
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Protocol.ESSENTIAL_KEY_CAPTURE || sentFromUid != Process.SYSTEM_UID) return
                val action = EssentialKeyCapture.entries.firstOrNull {
                    it.name == intent.getStringExtra(Protocol.ESSENTIAL_KEY_ACTION)
                } ?: return
                if (action == EssentialKeyCapture.NONE) return
                runCatching {
                    if (power?.isInteractive != true || keyguard?.isKeyguardLocked != false) return
                    when (action) {
                        EssentialKeyCapture.SCREENSHOT -> checkNotNull(screenshot).invoke()
                        EssentialKeyCapture.RECORD -> checkNotNull(record).invoke()
                        EssentialKeyCapture.LAUNCH_APP -> {
                            val pkg = intent.getStringExtra(Protocol.ESSENTIAL_KEY_PACKAGE).orEmpty()
                            check(pkg.matches(PACKAGE_PATTERN))
                            val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(pkg))
                            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        EssentialKeyCapture.LAUNCH_ACTIVITY -> {
                            val pkg = intent.getStringExtra(Protocol.ESSENTIAL_KEY_PACKAGE).orEmpty()
                            val clazz = intent.getStringExtra(Protocol.ESSENTIAL_KEY_CLASS).orEmpty()
                            check(pkg.matches(PACKAGE_PATTERN) && clazz.length in 1..300)
                            val component = ComponentName(pkg, clazz)
                            val info = context.packageManager.getActivityInfo(component, 0)
                            // Refuse private/disabled components, even though the hook runs in SystemUI.
                            check(info.enabled && info.exported)
                            context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(component)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        EssentialKeyCapture.SOUND_RECORDER -> {
                            context.startActivity(Intent().setClassName("moe.xWey10.tweaks", RecorderActivity::class.java.name)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        }
                        EssentialKeyCapture.NOTIFICATIONS -> {
                            val statusBar = context.getSystemService(StatusBarManager::class.java)
                                ?: error("Status bar service unavailable")
                            XposedHelpers.callMethod(statusBar, "expandNotificationsPanel")
                        }
                        EssentialKeyCapture.CAMERA -> {
                            context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        EssentialKeyCapture.NONE -> Unit
                    }
                }.onFailure {
                    if (loggedFailures.add(action)) HookLog.error("Essential Key action unavailable: ${action.name}", it)
                    Toast.makeText(context, "Essential Tweaks: action unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        }, IntentFilter(Protocol.ESSENTIAL_KEY_CAPTURE), "android.permission.DEVICE_POWER", null,
            Context.RECEIVER_EXPORTED)
    }

    companion object {
        private val PACKAGE_PATTERN = Regex("[A-Za-z_][\\w]*(\\.[A-Za-z_][\\w]*)+")
    }
}
