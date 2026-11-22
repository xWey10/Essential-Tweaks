// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.lockscreen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.WeakHashMap

internal class LockClock(private val loader: ClassLoader, private val reader: TweakReader) {
    // Track only our contribution. Keyguard may animate these same properties.
    private class Transform {
        private var factor = 1f
        private var offset = 0f
        private var lastX = Float.NaN
        private var lastY = Float.NaN
        private var lastOffset = Float.NaN

        fun apply(v: View, nextFactor: Float, nextOffset: Float) {
            val x = (if (v.scaleX == lastX) v.scaleX / factor else v.scaleX) * nextFactor
            val y = (if (v.scaleY == lastY) v.scaleY / factor else v.scaleY) * nextFactor
            val t = (if (v.translationY == lastOffset) v.translationY - offset else v.translationY) + nextOffset
            if (x != v.scaleX) v.scaleX = x
            if (y != v.scaleY) v.scaleY = y
            if (t != v.translationY) v.translationY = t
            lastX = x
            lastY = y
            lastOffset = t
            factor = nextFactor
            offset = nextOffset
        }
    }

    fun install() = FeatureHooks("Nothing lock clock").install {
        val clock = XposedHelpers.findClass("com.nothing.systemui.shared.clocks.view.NTClockView", loader)
        val draw = clock.getDeclaredMethod("onDraw", Canvas::class.java)
        val flags = listOf("isDoze", "isScreenOff", "isSnapshotClockView").map { clock.getDeclaredMethod(it) }
        val dark = clock.getDeclaredMethod("isRegionDark")
        val attached = WeakHashMap<View, Transform?>()
        val ticks = WeakHashMap<View, Runnable>()
        fun cancelTick(v: View) { ticks.remove(v)?.let(v::removeCallbacks) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        var format: SimpleDateFormat? = null
        var formatKey = ""
        add(clock.getDeclaredMethod("onAttachedToWindow")) { attached[it.thisObject as View] = null }
        add(clock.getDeclaredMethod("onDetachedFromWindow")) { val v = it.thisObject as View
            cancelTick(v)
            attached.remove(v)?.apply(v, 1f, 0f) }
        reader.observe { attached.keys.toList().forEach(View::invalidate) }
        add(draw, before = true) { p ->
            val s = reader.settings
            val v = p.thisObject as View
            val customize = s.clockScale != 100 || s.clockOffset != 0 || s.lockStyle != 0 || s.lockSeconds
            val active = customize && v.isShown && flags.none { it.invoke(v) == true }
            if (active && (s.clockScale != 100 || s.clockOffset != 0)) {
                val transform = attached[v] ?: Transform().also { attached[v] = it }
                transform.apply(v, s.clockScale / 100f, s.clockOffset * v.resources.displayMetrics.density)
            } else if (attached[v] != null) {
                attached[v]?.apply(v, 1f, 0f)
                attached[v] = null
            }
            if (!active || !s.lockSeconds) cancelTick(v)
            if (!active) return@add
            val canvas = p.args[0] as Canvas
            val cx = v.width / 2f
            val cy = v.height / 2f
            // Composite faces render date/weather and digits in dispatchDraw.
            // Leave their native content intact.
            if ((v as? android.view.ViewGroup)?.childCount != 0) return@add
            if (s.lockStyle == 0 && !s.lockSeconds) return@add
            val now = System.currentTimeMillis()
            val locale = v.resources.configuration.locales[0]
            val pattern = (if (DateFormat.is24HourFormat(v.context)) "HH:mm" else "h:mm") +
                (if (s.lockSeconds) ":ss" else "")
            val key = "${locale.toLanguageTag()} $pattern"
            if (formatKey != key) {
                format = SimpleDateFormat(pattern, locale)
                formatKey = key
            }
            val time = requireNotNull(format).apply { timeZone = TimeZone.getDefault() }.format(Date(now))
            paint.typeface = if (s.lockStyle == 2) Typeface.MONOSPACE else Typeface.DEFAULT
            paint.color = if (dark.invoke(v) == false) Color.BLACK else Color.WHITE
            paint.textSize = v.height * 0.75f
            val width = paint.measureText(time)
            if (width > v.width && width > 0) paint.textSize *= v.width * 0.96f / width
            canvas.drawText(time, cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
            p.result = null
            if (s.lockSeconds && v.windowVisibility == View.VISIBLE && v.isAttachedToWindow && v !in ticks) {
                val tick = Runnable { ticks.remove(v); v.invalidate() }
                ticks[v] = tick
                v.postDelayed(tick, 1000L - now % 1000)
            }
        }
    }
}
