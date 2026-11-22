// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.core.CameraRingMode
import moe.xWey10.tweaks.hooks.HookLog
import moe.xWey10.tweaks.hooks.TweakReader
import kotlin.math.ceil
import kotlin.math.floor

/** SystemUI owns a small passive window so status bar content clipping cannot crop the ring. */
internal class CameraRing(private val context: Context, private val tweaks: TweakReader) {
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val media = context.getSystemService(MediaSessionManager::class.java)
    private var mode = CameraRingMode.OFF
    private var battery: Float? = null
    private var window: WindowManager? = null
    private var view: RingView? = null
    private var layout: WindowManager.LayoutParams? = null
    private var frame: Rect? = null
    private var windowFailed = false
    private var mediaUnavailable = false
    private val update = Runnable { render() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
            render()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) render()
        }
    }

    fun install() {
        tweaks.observe {
            val next = tweaks.settings.cameraRing
            if (next == mode) return@observe
            if (mode == CameraRingMode.OFF) {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)?.let(::readBattery)
                displays?.registerDisplayListener(displayListener, main)
            } else if (next == CameraRingMode.OFF) {
                context.unregisterReceiver(receiver)
                displays?.unregisterDisplayListener(displayListener)
            }
            mode = next
            windowFailed = false
            mediaUnavailable = false
            render()
        }
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
        battery = if (scale > 0 && level in 0..scale) level.toFloat() / scale else null
    }

    private fun render() {
        main.removeCallbacks(update)
        if (mode == CameraRingMode.OFF || power?.isInteractive != true ||
            windowFailed || displays == null) {
            removeWindow()
            return
        }
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val density = context.resources.displayMetrics.density
        val hole = display?.let { cameraBounds(it, density) }
        if (display == null || hole == null) {
            removeWindow()
            return
        }
        if (mode != CameraRingMode.BATTERY && !mediaUnavailable) main.postDelayed(update, 1000)
        val mediaFraction = if (mode != CameraRingMode.BATTERY) mediaFraction() else null
        val progress = if (mode == CameraRingMode.MEDIA) mediaFraction else mediaFraction ?: battery
        if (progress == null) {
            removeWindow()
            return
        }
        val radius = maxOf(hole.width(), hole.height()) / 2f + 4f * density
        val bounds = Rect(floor(hole.centerX() - radius).toInt(), floor(hole.centerY() - radius).toInt(),
            ceil(hole.centerX() + radius).toInt(), ceil(hole.centerY() + radius).toInt())
        runCatching { show(display, bounds, progress) }.onFailure {
            HookLog.error("Camera ring window unavailable", it)
            windowFailed = true
            removeWindow()
        }
    }

    private fun cameraBounds(display: Display, density: Float): RectF? {
        val cutout = display.cutout ?: return null
        val viewport = android.graphics.Point().also { point ->
            @Suppress("DEPRECATION")
            display.getRealSize(point)
        }
        val path = RectF()
        @Suppress("DEPRECATION")
        cutout.cutoutPath?.computeBounds(path, true)
        // Ignore waterfall/full-edge shapes. The path, when compact, gives the physical camera center.
        fun isHole(rect: RectF) = !rect.isEmpty &&
            rect.width() <= 55 * density && rect.height() <= 72 * density &&
            rect.width() >= 3 * density && rect.height() >= 3 * density &&
            rect.intersects(0f, 0f, viewport.x.toFloat(), viewport.y.toFloat())
        if (isHole(path)) return path
        return cutout.boundingRects.map(::RectF).filter(::isHole)
            .minByOrNull { kotlin.math.abs(it.width() - it.height()) }
    }

    private fun mediaFraction(): Float? {
        if (mediaUnavailable || media == null) return null
        return runCatching {
            var paused: Float? = null
            for (controller in media.getActiveSessions(null)) {
                val state = controller.playbackState ?: continue
                val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                if (duration <= 0 || state.position < 0 ||
                    state.state !in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
                        PlaybackState.STATE_BUFFERING)) continue
                val playing = state.state == PlaybackState.STATE_PLAYING
                val elapsed = if (playing && state.lastPositionUpdateTime > 0) {
                    (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) *
                        state.playbackSpeed
                } else 0f
                val fraction = ((state.position + elapsed) / duration).coerceIn(0f, 1f)
                if (playing) return fraction
                if (paused == null) paused = fraction
            }
            paused
        }.onFailure {
            mediaUnavailable = true
            HookLog.error("Camera ring media sessions unavailable", it)
        }.getOrNull()
    }

    @Suppress("RtlHardcoded") // DisplayCutout coordinates are physical, even on RTL devices.
    private fun show(display: Display, bounds: Rect, progress: Float) {
        val ring = view ?: run {
            // Hidden system window type, used from the already-privileged SystemUI process.
            val type = XposedHelpers.getStaticIntField(WindowManager.LayoutParams::class.java,
                "TYPE_STATUS_BAR_ADDITIONAL")
            val windowContext = context.createWindowContext(display, type, null)
            val manager = checkNotNull(windowContext.getSystemService(WindowManager::class.java))
            val params = WindowManager.LayoutParams(bounds.width(), bounds.height(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                x = bounds.left
                y = bounds.top
                title = "Essential Tweaks camera ring"
            }
            RingView(windowContext).also {
                manager.addView(it, params)
                window = manager
                layout = params
                view = it
                frame = Rect(bounds)
            }
        }
        if (frame != bounds) {
            val params = checkNotNull(layout)
            params.x = bounds.left
            params.y = bounds.top
            params.width = bounds.width()
            params.height = bounds.height()
            window?.updateViewLayout(ring, params)
            frame = Rect(bounds)
        }
        ring.progress = progress
        ring.invalidate()
    }

    private fun removeWindow() {
        view?.let { ring -> runCatching { window?.removeViewImmediate(ring) } }
        view = null
        layout = null
        frame = null
        window = null
    }

    private class RingView(context: Context) : View(context) {
        var progress = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val arc = RectF()

        override fun onDraw(canvas: Canvas) {
            val density = resources.displayMetrics.density
            val inset = 2f * density
            arc.set(inset, inset, width - inset, height - inset)
            paint.color = 0xAA000000.toInt()
            paint.strokeWidth = 3.4f * density
            canvas.drawOval(arc, paint)
            paint.color = 0x66FFFFFF
            paint.strokeWidth = 1.4f * density
            canvas.drawOval(arc, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = 2f * density
            canvas.drawArc(arc, -90f, progress * 360f, false, paint)
        }
    }
}
