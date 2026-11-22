// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.lockscreen

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.Settings
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader

internal class LockShortcuts(private val loader: ClassLoader, private val reader: TweakReader) {
    fun install() = FeatureHooks("Lockscreen shortcuts").install {
        fun type(name: String) = XposedHelpers.findClass(name, loader)
        val interactor = type("com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor")
        val trigger = interactor.getDeclaredMethod("onQuickAffordanceTriggered", String::class.java,
            type("com.android.systemui.animation.Expandable"), String::class.java)
        val tracker = interactor.getDeclaredField("userTracker").apply { isAccessible = true }
        val userContext = type("com.android.systemui.settings.UserTracker").getMethod("getUserContext")
        val userHandle = type("com.android.systemui.settings.UserTracker").getMethod("getUserHandle")
        val starter = interactor.getDeclaredField("activityStarter").apply { isAccessible = true }
        val launch = type("com.android.systemui.plugins.ActivityStarter").getMethod(
            "postStartActivityDismissingKeyguard", Intent::class.java, Int::class.javaPrimitiveType,
            type("com.android.systemui.animation.ActivityTransitionAnimator\$Controller"), String::class.java, UserHandle::class.java)
        add(trigger, before = true) { p ->
            val target = when (p.args[2]) {
                "bottom_start" -> reader.settings.leftSlot
                "bottom_end" -> reader.settings.rightSlot
                else -> return@add
            }
            if (target.isEmpty()) return@add
            val user = tracker.get(p.thisObject)
            val ctx = userContext.invoke(user) as Context
            val intent = when (target) {
                ":settings" -> Intent(Settings.ACTION_SETTINGS)
                ":alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                else -> ctx.packageManager.getLaunchIntentForPackage(target)
            } ?: return@add
            if (intent.resolveActivity(ctx.packageManager) == null) return@add
            launch.invoke(starter.get(p.thisObject), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0, null, null, userHandle.invoke(user))
            p.result = null
        }
    }
}
