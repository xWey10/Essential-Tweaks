// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.view.View
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader
import kotlin.math.abs
import kotlin.math.max

/** A drawable in the existing status bar window. It creates no input surface. */
internal class CutoutBatteryRing(private val loader: ClassLoader, private val tweaks: TweakReader) {
    fun install() {
        bind("com.android.systemui.statusbar.phone.PhoneStatusBarView")
        bind("com.android.systemui.statusbar.phone.KeyguardStatusBarView")
    }

    private fun bind(name: String) = FeatureHooks("Camera battery ring on $name").install {
        val bar = XposedHelpers.findClass(name, loader)
        fun lifecycle(name: String) = generateSequence<Class<*>>(bar) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod(name) }.getOrNull() }.first()
        val windows = mutableMapOf<View, Binding>()
        add(lifecycle("onAttachedToWindow")) { p ->
            if (!bar.isInstance(p.thisObject)) return@add
            val view = p.thisObject as View
            windows.remove(view)?.stop()
            windows[view] = Binding(view).also { if (tweaks.settings.cutoutRing) it.start() }
        }
        add(lifecycle("onDetachedFromWindow"), before = true) { p ->
            if (bar.isInstance(p.thisObject)) windows.remove(p.thisObject as View)?.stop()
        }
        tweaks.observe {
            windows.values.forEach { if (tweaks.settings.cutoutRing) it.start() else it.stop() }
        }
    }

    private class Binding(private val bar: View) {
        val root = bar.rootView
        val ring = Ring(root.resources.displayMetrics.density)
        var active = false
        val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { update(intent) }
        }

        fun start() {
            if (active) return
            val intent = bar.context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED)
            active = true
            bar.overlay.add(ring)
            root.addOnLayoutChangeListener(layout)
            bar.addOnLayoutChangeListener(layout)
            intent?.let(::update)
            position()
        }

        fun stop() {
            if (!active) return
            active = false
            bar.context.unregisterReceiver(receiver)
            bar.overlay.remove(ring)
            root.removeOnLayoutChangeListener(layout)
            bar.removeOnLayoutChangeListener(layout)
        }

        fun update(intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
            ring.fraction = if (scale > 0 && level in 0..scale) level.toFloat() / scale else 0f
            position()
        }

        fun position() {
            val cutout = root.rootWindowInsets?.displayCutout ?: bar.display?.cutout
            ring.circle.setEmpty()
            if (cutout != null) {
                val hole = RectF()
                cutout.cutoutPath?.computeBounds(hole, true)
                if (!ring.compact(hole)) {
                    cutout.boundingRects.map(::RectF).firstOrNull(ring::compact)?.let(hole::set)
                }
                if (ring.compact(hole)) {
                    val origin = IntArray(2)
                    bar.getLocationOnScreen(origin)
                    hole.offset(-origin[0].toFloat(), -origin[1].toFloat())
                    val r = max(hole.width(), hole.height()) * .5f + ring.density * 1.5f
                    ring.circle.set(hole.centerX()-r, hole.centerY()-r, hole.centerX()+r, hole.centerY()+r)
                }
            }
            ring.setBounds(0, 0, bar.width, bar.height)
            ring.invalidateSelf()
        }
    }

    private class Ring(val density: Float) : Drawable() {
        val circle = RectF()
        var fraction = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        fun compact(rect: RectF): Boolean = !rect.isEmpty && max(rect.width(), rect.height()) < 60 * density &&
            abs(rect.width() - rect.height()) <= max(rect.width(), rect.height()) * .45f

        override fun draw(canvas: Canvas) {
            if (circle.isEmpty || fraction <= 0) return
            val sweep = fraction * 360
            paint.color = Color.BLACK
            paint.strokeWidth = density * 2.8f
            canvas.drawArc(circle, -90f, sweep, false, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = density * 1.7f
            canvas.drawArc(circle, -90f, sweep, false, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated by Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
