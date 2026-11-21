// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.fingerprint

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.os.Looper
import android.view.View
import android.widget.ImageView
import moe.xWey10.tweaks.core.DotsMode
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.random.Random

class FingerprintFeature(private val loader: ClassLoader, private val tweaks: TweakReader) {
    private fun type(name: String): Class<*> = Class.forName(name, false, loader)

    fun install() {
        installDots()
        installIdleIcon()
    }

    private fun installDots() = FeatureHooks("Fingerprint dots").install {
        val overlay = type(OVERLAY)
        val lottieView = type("com.airbnb.lottie.LottieAnimationView")
        val callback = type("$OVERLAY\$updateFPColor\$1")
        val frameInfo = type("com.airbnb.lottie.value.LottieFrameInfo")
        val keyPath = type("com.airbnb.lottie.model.KeyPath")
        val simpleCallback = type("com.airbnb.lottie.value.SimpleLottieValueCallback")
        val scanView = overlay.getDeclaredField("mScanningAnimView").apply { isAccessible = true }
        val fingerDown = overlay.getDeclaredField("isFingerDown").apply { isAccessible = true }
        val onInflate = overlay.getDeclaredMethod("onFinishInflate")
        val onDown = overlay.getDeclaredMethod("onFingerDown")
        val onUp = overlay.getDeclaredMethod("onFingerUp")
        val onDetach = overlay.getDeclaredMethod("onDetachedFromWindow")
        val addCallback = lottieView.getDeclaredMethod("addValueCallback", keyPath, Any::class.java, simpleCallback)
        val getColor = callback.declaredMethods.single {
            it.name == "getValue" && it.parameterTypes.contentEquals(arrayOf(frameInfo)) &&
                it.returnType == ColorFilter::class.java
        }

        val owners = WeakHashMap<Any, WeakReference<Any>>()
        val callbacks = WeakHashMap<Any, WeakReference<Any>>()
        val randomFilters = WeakHashMap<Any, ColorFilter>()
        var customColor = tweaks.settings.dotsColor
        var customFilter = colorFilter(customColor)

        add(onInflate) { param ->
            scanView.get(param.thisObject)?.let { owners[it] = WeakReference(param.thisObject) }
        }
        add(addCallback, before = true) { param ->
            val registered = param.args[2]
            if (callback.isInstance(registered) && onMainThread()) {
                owners[param.thisObject]?.let { callbacks[registered] = it }
            }
        }
        add(onDown, before = true) { param ->
            val view = param.thisObject
            if (tweaks.settings.dots == DotsMode.RANDOM && !fingerDown.getBoolean(view)) {
                val color = Color.HSVToColor(floatArrayOf(Random.nextInt(360).toFloat(), 0.78f, 1f))
                randomFilters[view] = colorFilter(color)
            }
        }
        add(onUp) { randomFilters.remove(it.thisObject) }
        add(onDetach) { randomFilters.remove(it.thisObject) }
        add(getColor, before = true) { param ->
            if (!onMainThread()) return@add
            val owner = callbacks[param.thisObject]?.get() ?: return@add
            val filter = when (tweaks.settings.dots) {
                DotsMode.STOCK -> null
                DotsMode.RANDOM -> randomFilters[owner]
                DotsMode.CUSTOM -> {
                    val color = tweaks.settings.dotsColor
                    if (customColor != color) {
                        customColor = color
                        customFilter = colorFilter(color)
                    }
                    customFilter
                }
            }
            if (filter != null) param.result = filter
        }
    }

    private fun installIdleIcon() = FeatureHooks("Fingerprint idle icon").install {
        val overlay = type(OVERLAY)
        val entry = type("com.android.systemui.keyguard.ui.view.DeviceEntryIconView")
        val controller = type("com.android.systemui.biometrics.UdfpsController")
        val iconField = entry.getDeclaredField("iconView").apply {
            check(type == ImageView::class.java)
            isAccessible = true
        }
        val stockIcon = entry.getDeclaredField("ntFpDrawable").apply {
            check(type.name == "com.airbnb.lottie.LottieDrawable")
            isAccessible = true
        }
        val active = controller.getDeclaredField("mOnFingerDown").apply {
            check(type == Boolean::class.javaPrimitiveType)
            isAccessible = true
        }
        val longType = Long::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!
        val boolType = Boolean::class.javaPrimitiveType!!
        val touch = arrayOf(intType, floatType, floatType, floatType, floatType, floatType, longType, longType, boolType)
        val controllerDown = controller.getDeclaredMethod("onFingerDown", longType, *touch)
        val controllerUp = controller.getDeclaredMethod("onFingerUp", longType, View::class.java, *touch)
        val onDetach = overlay.getDeclaredMethod("onDetachedFromWindow")
        val onDown = overlay.getDeclaredMethod("onFingerDown")
        val onUp = overlay.getDeclaredMethod("onFingerUp")
        val draw = ImageView::class.java.getDeclaredMethod("onDraw", Canvas::class.java)

        val icons = WeakHashMap<ImageView, WeakReference<Drawable>>()
        val scanningOverlays = WeakHashMap<Any, Unit>()
        var currentController: WeakReference<Any>? = null
        var starting = false
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val circle = Path()
        val destination = RectF()
        fun invalidate() { icons.keys.toList().forEach(View::invalidate) }
        fun updateScan() { if (tweaks.icon != null) invalidate() }

        check(controller.declaredConstructors.isNotEmpty())
        controller.declaredConstructors.forEach { constructor ->
            add(constructor) { param ->
                currentController = WeakReference(param.thisObject)
                updateScan()
            }
        }
        entry.declaredConstructors.forEach { constructor ->
            add(constructor) { param ->
                val view = iconField.get(param.thisObject) as? ImageView ?: return@add
                val drawable = stockIcon.get(param.thisObject) as? Drawable ?: return@add
                icons[view] = WeakReference(drawable)
            }
        }
        add(controllerDown, before = true) {
            starting = true
            updateScan()
        }
        add(controllerDown) {
            starting = false
            updateScan()
        }
        add(controllerUp) {
            starting = false
            updateScan()
        }
        // Nothing can also start AOD illumination directly on its overlay.
        add(onDown, before = true) { param ->
            scanningOverlays[param.thisObject] = Unit
            updateScan()
        }
        add(onUp) { param ->
            if (scanningOverlays.remove(param.thisObject) != null) updateScan()
        }
        add(onDetach) { param ->
            if (scanningOverlays.remove(param.thisObject) != null) updateScan()
        }
        add(draw, before = true) { param ->
            if (!onMainThread()) return@add
            val bitmap = tweaks.icon ?: return@add
            val view = param.thisObject as ImageView
            val stock = icons[view]?.get() ?: return@add
            val asset = view.drawable as? DrawableContainer ?: return@add
            if (asset.current !== stock || starting || scanningOverlays.isNotEmpty()) return@add
            val udfps = currentController?.get() ?: return@add
            if (active.getBoolean(udfps)) return@add

            val width = view.width - view.paddingLeft - view.paddingRight
            val height = view.height - view.paddingTop - view.paddingBottom
            if (width <= 0 || height <= 0) return@add
            val edge = minOf(width, height) * tweaks.settings.iconSize / 100f
            val scale = edge / minOf(bitmap.width, bitmap.height)
            val halfWidth = bitmap.width * scale / 2
            val halfHeight = bitmap.height * scale / 2
            val x = view.paddingLeft + width / 2f
            val y = view.paddingTop + height / 2f
            destination.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
            paint.alpha = view.imageAlpha
            val canvas = param.args[0] as Canvas
            val save = canvas.save()
            try {
                circle.rewind()
                circle.addCircle(x, y, edge / 2, Path.Direction.CW)
                canvas.clipPath(circle)
                canvas.drawBitmap(bitmap, null, destination, paint)
            } finally {
                canvas.restoreToCount(save)
            }
            param.result = null
        }
        tweaks.observe(::invalidate)
    }

    private fun onMainThread() = Looper.myLooper() == Looper.getMainLooper()

    private fun colorFilter(color: Int) = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)

    private companion object {
        const val OVERLAY = "com.nothing.systemui.biometrics.NTUdfpsView"
    }
}
