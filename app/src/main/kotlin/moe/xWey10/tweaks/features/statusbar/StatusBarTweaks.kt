// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10
package moe.xWey10.tweaks.features.statusbar

import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.HookLog
import moe.xWey10.tweaks.hooks.TweakReader
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/** Optional status-bar gestures. Process-local; neither su nor input injection is used. */
internal class StatusBarTweaks(private val loader: ClassLoader, private val reader: TweakReader) {
    private data class Swipe(val x: Float, val y: Float, val start: Float, var value: Float,
        var active: Boolean = false)
    private val swipes = WeakHashMap<View, Swipe>()
    private data class Tap(var downX: Float = 0f, var downY: Float = 0f, var downAt: Long = 0L,
        var lastUpAt: Long = 0L, var lastX: Float = 0f, var lastY: Float = 0f)
    private val taps = WeakHashMap<View, Tap>()
    private var sleepFailureLogged = false
    private val clocks = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private var brightnessErrorLogged = false

    fun install() {
        FeatureHooks("Status bar gestures").install {
            val statusBar = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView", loader)
            // The OEM class often *inherits* dispatchTouchEvent from ViewGroup.
            // getDeclaredMethod() on PhoneStatusBarView alone silently disabled both
            // gesture features when it was not declared on the concrete class.
            val dispatch = inheritedMethod(statusBar, "dispatchTouchEvent", MotionEvent::class.java)
            add(dispatch, before = true) { param ->
                val view = param.thisObject as? View ?: return@add
                if (!statusBar.isInstance(view)) return@add // inherited method is shared by many views
                val event = param.args[0] as MotionEvent
                if (!reader.settings.brightnessSwipe) swipes.remove(view)
                if (!reader.settings.statusDoubleTapSleep) taps.remove(view)
                val brightnessConsumed = reader.settings.brightnessSwipe && onSwipe(view, event, dispatch)
                val sleepConsumed = !brightnessConsumed && reader.settings.statusDoubleTapSleep &&
                    onDoubleTap(view, event)
                if (brightnessConsumed || sleepConsumed) param.result = true
            }
            HookLog.info("Status bar touch hook on ${dispatch.declaringClass.name}.${dispatch.name}")
        }
        FeatureHooks("Status bar clock seconds").install {
            val clock = XposedHelpers.findClass("com.android.systemui.statusbar.policy.Clock", loader)
            val attach = inheritedMethod(clock, "onAttachedToWindow")
            val detach = inheritedMethod(clock, "onDetachedFromWindow")
            val tuner = inheritedMethod(clock, "onTuningChanged", String::class.java, String::class.java)
            add(attach) { param ->
                if (!clock.isInstance(param.thisObject)) return@add
                clocks.add(param.thisObject)
                forceTuning(param.thisObject)
            }
            add(detach) { param -> if (clock.isInstance(param.thisObject)) clocks.remove(param.thisObject) }
            add(tuner, before = true) { param ->
                if (clock.isInstance(param.thisObject) && reader.settings.clockSeconds &&
                    param.args[0] == "clock_seconds") param.args[1] = "1"
            }
            reader.observe { clocks.toList().forEach(::forceTuning) }
        }
    }

    /** Hooks only a method that will actually be invoked, even if it is inherited. */
    private fun inheritedMethod(type: Class<*>, name: String, vararg args: Class<*>): Method {
        var cursor: Class<*>? = type
        while (cursor != null) {
            try {
                return cursor.getDeclaredMethod(name, *args).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) { cursor = cursor.superclass }
        }
        error("Missing method ${type.name}.$name")
    }

    private fun forceTuning(clock: Any) {
        runCatching {
            XposedHelpers.callMethod(clock, "onTuningChanged", "clock_seconds",
                if (reader.settings.clockSeconds) "1" else "0")
        }.onFailure { HookLog.error("Could not update seconds on OEM clock", it) }
    }

    private fun onDoubleTap(view: View, event: MotionEvent): Boolean {
        if (event.pointerCount != 1) {
            taps.remove(view)
            return false
        }
        val tap = taps.getOrPut(view) { Tap() }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tap.downX = event.x; tap.downY = event.y; tap.downAt = event.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop * 2f
                if (event.eventTime - tap.downAt > 250 ||
                    abs(event.x - tap.downX) > slop || abs(event.y - tap.downY) > slop) {
                    tap.lastUpAt = 0
                    return false
                }
                val isDouble = tap.lastUpAt > 0 && event.eventTime - tap.lastUpAt <
                    ViewConfiguration.getDoubleTapTimeout() &&
                    abs(event.x - tap.lastX) < slop * 2 && abs(event.y - tap.lastY) < slop * 2
                tap.lastUpAt = event.eventTime; tap.lastX = event.x; tap.lastY = event.y
                if (isDouble) {
                    tap.lastUpAt = 0
                    runCatching {
                        val power = checkNotNull(view.context.getSystemService(PowerManager::class.java))
                        // Privileged SystemUI PowerManager; the module app never needs DEVICE_POWER.
                        XposedHelpers.callMethod(power, "goToSleep", SystemClock.uptimeMillis())
                    }.onFailure {
                        if (!sleepFailureLogged) HookLog.error("Status bar double tap sleep unavailable", it)
                        sleepFailureLogged = true
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> tap.lastUpAt = 0
        }
        return false
    }

    private fun onSwipe(view: View, event: MotionEvent, dispatch: Method): Boolean {
        if (event.pointerCount != 1) {
            swipes.remove(view)
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val brightness = getBrightness(view)
                swipes[view] = Swipe(event.x, event.y, brightness, brightness)
            }
            MotionEvent.ACTION_MOVE -> {
                val swipe = swipes[view] ?: return false
                val dx = event.x - swipe.x
                val dy = event.y - swipe.y
                if (!swipe.active) {
                    val slop = ViewConfiguration.get(view.context).scaledTouchSlop * 1.5f
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        swipes.remove(view)
                        return false // Native pull-down gesture has priority over vertical motion.
                    }
                    if (abs(dx) < slop || abs(dx) < abs(dy) * 1.4f) return false
                    swipe.active = true
                    // Invalidate any pending click/tap handled by the original status bar view.
                    taps.remove(view)
                    val cancel = MotionEvent.obtain(event)
                    try {
                        cancel.action = MotionEvent.ACTION_CANCEL
                        XposedBridge.invokeOriginalMethod(dispatch, view, arrayOf(cancel))
                    } finally { cancel.recycle() }
                }
                swipe.value = (swipe.start + dx / view.width.coerceAtLeast(1)).coerceIn(.02f, 1f)
                setBrightness(view, swipe.value, temporary = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val swipe = swipes.remove(view) ?: return false
                if (!swipe.active) return false
                setBrightness(view, if (event.actionMasked == MotionEvent.ACTION_CANCEL) swipe.start
                    else swipe.value, temporary = false)
                return true
            }
        }
        return false
    }

    private fun getBrightness(view: View): Float {
        val display = view.display?.displayId ?: Display.DEFAULT_DISPLAY
        val manager = view.context.getSystemService(DisplayManager::class.java)
        val actual = runCatching {
            (XposedHelpers.callMethod(checkNotNull(manager), "getBrightness", display) as Number).toFloat()
        }.getOrNull()
        if (actual != null && actual.isFinite() && actual >= 0f) return actual.coerceIn(.02f, 1f)
        return (Settings.System.getInt(view.context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(.02f, 1f)
    }

    private fun setBrightness(view: View, value: Float, temporary: Boolean) {
        if (!temporary) runCatching {
            Settings.System.putInt(view.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        val success = runCatching {
            val manager = checkNotNull(view.context.getSystemService(DisplayManager::class.java))
            val id = view.display?.displayId ?: Display.DEFAULT_DISPLAY
            // The QS brightness controller uses DisplayManager as well; Settings.System
            // alone does not reliably update the display on Android 16.
            XposedHelpers.callMethod(manager, if (temporary) "setTemporaryBrightness"
                else "setBrightness", id, value)
        }.isSuccess
        if (success) return
        // OEM fallback if the hidden display method or its system grant is unavailable.
        runCatching {
            val resolver = view.context.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            check(Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                (value * 255).toInt().coerceIn(5, 255)))
        }.onFailure {
            if (!brightnessErrorLogged) HookLog.error("Status bar brightness control unavailable", it)
            brightnessErrorLogged = true
        }
    }
}
