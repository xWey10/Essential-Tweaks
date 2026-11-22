// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.hooks

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.xWey10.tweaks.core.Protocol
import moe.xWey10.tweaks.features.charging.ChargingFeature
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyPolicy
import moe.xWey10.tweaks.features.essentialkey.EssentialKeySystemUi
import moe.xWey10.tweaks.features.fingerprint.FingerprintFeature
import moe.xWey10.tweaks.features.lockscreen.DoubleTapSleep
import moe.xWey10.tweaks.features.lockscreen.DoubleTapWake
import moe.xWey10.tweaks.features.powermenu.PowerMenuFeature
import moe.xWey10.tweaks.features.screenshot.ScreenshotFeature
import moe.xWey10.tweaks.features.statusbar.StatusBarTweaks

class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var modulePath = ""

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == "android" && param.processName in setOf("android", "system_server")) {
            ScreenshotFeature.install(param.classLoader)
            EssentialKeyPolicy.install(param.classLoader)
            return
        }
        if (param.packageName != Protocol.SYSTEM_UI || param.processName != Protocol.SYSTEM_UI) return
        val essentialKey = EssentialKeySystemUi(param.classLoader)
        essentialKey.install()
        val health = HookHealth()
        val config = ConfigReader()
        try {
            PowerMenuFeature(param.classLoader, modulePath, config, health).install()
        } catch (error: Throwable) {
            health.unavailable("Hook initialization failed: ${error.javaClass.simpleName}")
            HookLog.error("Power menu feature disabled", error)
        }
        val tweaks = try {
            TweakReader().also { reader ->
                FingerprintFeature(param.classLoader, reader).install()
                ChargingFeature(param.classLoader, reader).install()
                DoubleTapWake(param.classLoader, reader).install()
                DoubleTapSleep(param.classLoader, reader).install()
                StatusBarTweaks(param.classLoader, reader).install()
            }
        } catch (error: Throwable) {
            HookLog.error("Could not initialize tweak settings", error)
            null
        }
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as Context
                        runCatching { essentialKey.register(context) }
                            .onFailure { HookLog.error("Essential Key receiver unavailable", it) }
                        try {
                            StatusBridge(health, config, tweaks).register(context)
                            tweaks?.let { reader ->
                                reader.refresh(context)
                                context.registerReceiver(object : BroadcastReceiver() {
                                    override fun onReceive(context: Context, intent: Intent) {
                                        if (intent.action == Intent.ACTION_USER_UNLOCKED) reader.refresh(context)
                                    }
                                }, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
                            }
                        }
                        catch (error: Throwable) { HookLog.error("Status bridge unavailable", error) }
                    }
                })
        } catch (error: Throwable) { HookLog.error("Could not attach status bridge", error) }
    }
}
