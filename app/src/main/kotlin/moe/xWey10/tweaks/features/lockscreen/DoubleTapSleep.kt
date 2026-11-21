// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.lockscreen

import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader
import java.lang.ref.WeakReference

internal class DoubleTapSleep(private val loader: ClassLoader, private val tweaks: TweakReader) {
    fun install() = FeatureHooks("Double tap to sleep").install {
        val root = type("com.android.systemui.keyguard.ui.view.KeyguardRootView")
        val shade = type("com.android.systemui.shade.NotificationShadeWindowView")
        val controller = type("com.android.systemui.shade.NotificationShadeWindowViewController")
        val status = type("com.android.systemui.plugins.statusbar.StatusBarStateController")
        val service = type("com.android.systemui.statusbar.phone.CentralSurfaces")
        val qs = type("com.android.systemui.shade.QuickSettingsController")
        val ids = type("com.android.systemui.res.R\$id")
        val viewField = controller.getDeclaredField("mView").apply { isAccessible = true }
        val statusField = controller.getDeclaredField("mStatusBarStateController").apply { isAccessible = true }
        val serviceField = controller.getDeclaredField("mService").apply { isAccessible = true }
        val qsField = controller.getDeclaredField("mQuickSettingsController").apply { isAccessible = true }
        val state = status.getMethod("getState")
        val dozing = status.getMethod("isDozing")
        val bouncer = service.getMethod("isBouncerShowing")
        val qsExpanded = qs.getMethod("getExpanded")
        val touchable = root.getMethod("getTouchable")
        val rootDispatch = root.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        val shadeDispatch = shade.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        val protectedIds = listOf("device_entry_icon_view", "keyguard_indication_area", "start_button", "end_button")
            .map { ids.getField(it).getInt(null) }
        val notificationsId = ids.getField("notification_stack_scroller").getInt(null)

        var shadeController: WeakReference<Any>? = null
        var blankDownTime = Long.MIN_VALUE
        var sequence: TapSequence? = null
        var downView: View? = null
        val location = IntArray(2)
        val bounds = Rect()

        controller.declaredConstructors.forEach { constructor ->
            add(constructor) { shadeController = WeakReference(it.thisObject) }
        }
        add(rootDispatch) { param ->
            if (!tweaks.settings.doubleTapSleep) return@add
            val event = param.args[0] as MotionEvent
            if (event.actionMasked == MotionEvent.ACTION_DOWN && param.result == false &&
                touchable.invoke(param.thisObject) == true) blankDownTime = event.downTime
        }
        add(shadeDispatch) { param ->
            if (Looper.myLooper() != Looper.getMainLooper()) return@add
            val view = param.thisObject as View
            val event = param.args[0] as MotionEvent
            val owner = shadeController?.get()
            if (!tweaks.settings.doubleTapSleep || owner == null || viewField.get(owner) !== view) {
                sequence?.reset()
                return@add
            }
            val statusController = statusField.get(owner)
            val surfaces = serviceField.get(owner)
            val quickSettings = qsField.get(owner)
            val power = view.context.getSystemService(PowerManager::class.java)
            val accessibility = view.context.getSystemService(AccessibilityManager::class.java)
            if (power?.isInteractive != true || accessibility?.isTouchExplorationEnabled == true ||
                statusController == null || surfaces == null || quickSettings == null ||
                state.invoke(statusController) != KEYGUARD || dozing.invoke(statusController) != false ||
                bouncer.invoke(surfaces) != false || qsExpanded.invoke(quickSettings) != false ||
                event.pointerCount != 1) {
                sequence?.reset()
                return@add
            }

            if (view !== downView) {
                val configuration = ViewConfiguration.get(view.context)
                sequence = TapSequence(configuration.scaledTouchSlop, configuration.scaledDoubleTapSlop,
                    ViewConfiguration.getDoubleTapTimeout().toLong())
                downView = view
            }
            val taps = sequence ?: return@add
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val wasBlank = blankDownTime == event.downTime
                    blankDownTime = Long.MIN_VALUE
                    val edge = 24 * view.resources.displayMetrics.density
                    val onControl = protectedIds.any { hit(view.findViewById(it), event, bounds, location) }
                    val notifications = view.findViewById<ViewGroup>(notificationsId)
                    val onNotification = notifications != null && (0 until notifications.childCount)
                        .any { hit(notifications.getChildAt(it), event, bounds, location) }
                    if (wasBlank && !onControl && !onNotification &&
                        event.x in edge..(view.width - edge) && event.y in edge..(view.height - edge)) {
                        taps.down(event.rawX, event.rawY, event.eventTime)
                    } else {
                        taps.reset()
                    }
                }
                MotionEvent.ACTION_MOVE -> taps.move(event.rawX, event.rawY)
                MotionEvent.ACTION_UP -> {
                    if (taps.up(event.rawX, event.rawY, event.eventTime) &&
                        view.context.checkSelfPermission(DEVICE_POWER) == PackageManager.PERMISSION_GRANTED) {
                        XposedHelpers.callMethod(power, "goToSleep", SystemClock.uptimeMillis())
                    }
                }
                else -> taps.reset()
            }
        }
        tweaks.observe { if (!tweaks.settings.doubleTapSleep) sequence?.reset() }
    }

    private fun type(name: String): Class<*> = Class.forName(name, false, loader)

    private fun hit(view: View?, event: MotionEvent, bounds: Rect, location: IntArray): Boolean {
        if (view == null || !view.isShown || view.alpha <= 0f || !view.getLocalVisibleRect(bounds)) return false
        view.getLocationOnScreen(location)
        return bounds.contains((event.rawX - location[0]).toInt(), (event.rawY - location[1]).toInt())
    }

    private companion object {
        const val KEYGUARD = 1
        const val DEVICE_POWER = "android.permission.DEVICE_POWER"
    }
}
