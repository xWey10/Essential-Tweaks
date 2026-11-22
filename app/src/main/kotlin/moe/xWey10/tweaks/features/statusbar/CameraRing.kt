// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Point
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
import android.view.DisplayCutout
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.core.CameraRingMode
import moe.xWey10.tweaks.core.CameraRingShape
import moe.xWey10.tweaks.core.CameraRingStyle
import moe.xWey10.tweaks.hooks.HookLog
import moe.xWey10.tweaks.hooks.TweakReader
import kotlin.math.roundToInt

/** SystemUI owns a small passive window so status bar content clipping cannot crop the ring. */
internal class CameraRing(private val context: Context, private val tweaks: TweakReader) {
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val media = context.getSystemService(MediaSessionManager::class.java)
    private var mode = CameraRingMode.OFF
    private var style = CameraRingStyle()
    private var battery: Float? = null
    private var window: WindowManager? = null
    private var view: RingView? = null
    private var layout: WindowManager.LayoutParams? = null
    private var frame: Rect? = null
    private var windowFailed = false
    private var mediaUnavailable = false
    private val update = Runnable { render() }
    private var previousCutout: DisplayCutout? = null
    private var previousViewport = Point()
    private var previousDensity = 0f
    private var opening: CameraOpening? = null

    private data class CameraOpening(val bounds: RectF, val contour: Path?)

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
            val nextStyle = tweaks.settings.cameraRingStyle
            if (next == mode && nextStyle == style) return@observe
            if (mode == CameraRingMode.OFF && next != CameraRingMode.OFF) {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)?.let(::readBattery)
                displays?.registerDisplayListener(displayListener, main)
            } else if (mode != CameraRingMode.OFF && next == CameraRingMode.OFF) {
                context.unregisterReceiver(receiver)
                displays?.unregisterDisplayListener(displayListener)
            }
            if (mode != next) mediaUnavailable = false
            mode = next
            style = nextStyle
            windowFailed = false
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
        val bounds = display?.let(::ringBounds)
        if (display == null || bounds == null) {
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
        runCatching { show(display, bounds, progress) }.onFailure {
            HookLog.error("Camera ring window unavailable", it)
            windowFailed = true
            removeWindow()
        }
    }

    private fun ringBounds(display: Display): Rect? {
        val viewport = Point().also { point ->
            @Suppress("DEPRECATION")
            display.getRealSize(point)
        }
        if (viewport.x <= 0 || viewport.y <= 0) return null
        val density = context.resources.displayMetrics.density
        val cutout = display.cutout
        if (cutout != previousCutout || viewport != previousViewport || density != previousDensity) {
            opening = cutout?.let { cameraOpening(it, viewport, density) }
            previousCutout = cutout
            previousViewport = viewport
            previousDensity = density
        }
        val hole = opening?.bounds
        if (hole == null && style.leftDp == CameraRingStyle.AUTO && style.topDp == CameraRingStyle.AUTO) {
            return null
        }
        val autoWidth = (hole?.width() ?: 24f * density) + 8f * density
        val autoHeight = (hole?.height() ?: 24f * density) + 8f * density
        val width: Int
        val height: Int
        if (style.shape == CameraRingShape.CIRCLE) {
            width = dimension(style.widthDp, maxOf(autoWidth, autoHeight), density, minOf(viewport.x, viewport.y))
            height = width
        } else {
            width = dimension(style.widthDp, autoWidth, density, viewport.x)
            height = dimension(style.heightDp, autoHeight, density, viewport.y)
        }
        val autoLeft = (hole?.centerX() ?: viewport.x / 2f) - width / 2f
        val autoTop = hole?.let { it.centerY() - height / 2f } ?: 0f
        val left = (if (style.leftDp == CameraRingStyle.AUTO) autoLeft else style.leftDp * density)
            .roundToInt().coerceIn(0, viewport.x - width)
        val top = (if (style.topDp == CameraRingStyle.AUTO) autoTop else style.topDp * density)
            .roundToInt().coerceIn(0, viewport.y - height)
        return Rect(left, top, left + width, top + height)
    }

    private fun dimension(dp: Int, automatic: Float, density: Float, limit: Int): Int =
        (if (dp == CameraRingStyle.AUTO) automatic else dp * density).roundToInt().coerceIn(1, limit)

    private fun cameraOpening(cutout: DisplayCutout, viewport: Point, density: Float): CameraOpening? {
        // Allow elongated camera cutouts while excluding full-width lips and waterfall edges.
        fun fits(rect: RectF): Boolean =
            minOf(rect.width(), rect.height()) in (3 * density)..(72 * density) &&
                maxOf(rect.width(), rect.height()) <= 128 * density &&
                rect.intersects(0f, 0f, viewport.x.toFloat(), viewport.y.toFloat())
        val contours = mutableListOf<CameraOpening>()
        cutout.cutoutPath?.let { path ->
            val measure = PathMeasure(path, true)
            do {
                val contour = Path()
                if (measure.getSegment(0f, measure.length, contour, true)) {
                    contour.close()
                    val bounds = RectF()
                    @Suppress("DEPRECATION")
                    contour.computeBounds(bounds, true)
                    if (fits(bounds)) contours.add(CameraOpening(bounds, contour))
                }
            } while (measure.nextContour())
        }
        if (contours.isNotEmpty()) return contours.minBy { it.bounds.width() * it.bounds.height() }
        return cutout.boundingRects.map(::RectF).filter(::fits)
            .minByOrNull { it.width() * it.height() }?.let { CameraOpening(it, null) }
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
        ring.style = style
        ring.opening = opening
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
        var style = CameraRingStyle()
        var opening: CameraOpening? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bounds = RectF()
        private val track = Path()
        private val filled = Path()
        private val measure = PathMeasure()
        private var drawnStyle: CameraRingStyle? = null
        private var drawnWidth = 0
        private var drawnHeight = 0
        private var drawnOpening: CameraOpening? = null
        private var drawnDensity = 0f

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height)
            val density = resources.displayMetrics.density
            val stroke = (style.strokeDp * density).coerceAtMost(size / 2f)
            if (style != drawnStyle || width != drawnWidth || height != drawnHeight ||
                density != drawnDensity || opening !== drawnOpening) {
                val inset = stroke / 2f + density
                bounds.set(inset, inset, width - inset, height - inset)
                buildTrack()
                drawnStyle = style
                drawnWidth = width
                drawnHeight = height
                drawnOpening = opening
                drawnDensity = density
            }
            if (bounds.isEmpty) return
            paint.color = if (Color.luminance(style.color) < .15f) 0x99FFFFFF.toInt() else 0xAA000000.toInt()
            paint.strokeWidth = stroke + density
            canvas.drawPath(track, paint)
            paint.color = (style.color and 0x00FFFFFF) or 0x66000000
            paint.strokeWidth = stroke
            canvas.drawPath(track, paint)
            paint.color = style.color
            if (progress >= 1f) {
                canvas.drawPath(track, paint)
            } else if (progress > 0f) {
                filled.rewind()
                measure.getSegment(0f, measure.length * progress, filled, true)
                canvas.drawPath(filled, paint)
            }
        }

        private fun buildTrack() {
            track.rewind()
            val cutout = opening
            when {
                style.shape == CameraRingShape.CUTOUT && cutout?.contour != null -> {
                    val matrix = Matrix()
                    matrix.setRectToRect(cutout.bounds, bounds, Matrix.ScaleToFit.FILL)
                    cutout.contour.transform(matrix, track)
                }
                style.shape == CameraRingShape.CAPSULE -> {
                    val radius = minOf(bounds.width(), bounds.height()) / 2f
                    track.addRoundRect(bounds, radius, radius, Path.Direction.CW)
                }
                else -> track.addArc(bounds, -90f, 360f)
            }
            measure.setPath(track, true)
        }
    }
}
