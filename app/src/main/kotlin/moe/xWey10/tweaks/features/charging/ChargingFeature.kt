// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.charging

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.core.TweakSettings
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.HookLog
import moe.xWey10.tweaks.hooks.TweakReader
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ChargingFeature(private val loader: ClassLoader, private val tweaks: TweakReader) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    fun install() = FeatureHooks("Charging info").install {
        val controller = XposedHelpers.findClass("com.android.systemui.statusbar.KeyguardIndicationController", loader)
        val areaField = controller.getDeclaredField("mIndicationArea").apply { isAccessible = true }
        val topField = controller.getDeclaredField("mTopIndicationView").apply { isAccessible = true }
        val visibleField = controller.getDeclaredField("mVisible").apply { isAccessible = true }
        val dozingField = controller.getDeclaredField("mDozing").apply { isAccessible = true }
        val indicationView = XposedHelpers.findClass("com.android.systemui.statusbar.phone.KeyguardIndicationTextView", loader)
        val newRow = indicationView.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
        val ids = XposedHelpers.findClass("com.android.systemui.res.R\$id", loader)
        val sensorId = ids.getField("device_entry_icon_view").getInt(null)
        val setArea = controller.getDeclaredMethod("setIndicationArea", ViewGroup::class.java)
        val booleanType = Boolean::class.javaPrimitiveType!!
        val update = controller.getDeclaredMethod("updateDeviceEntryIndication", booleanType)
        val setVisible = controller.getDeclaredMethod("setVisible", booleanType)
        val destroy = controller.getDeclaredMethod("destroy")
        var binding: IndicationBinding? = null

        add(setArea) { param ->
            val owner = param.thisObject
            val area = areaField.get(owner) as? LinearLayout
            if (binding?.area === area && area != null) return@add
            binding?.dispose()
            binding = null
            val top = topField.get(owner) as? TextView ?: return@add
            if (area == null || area.orientation != LinearLayout.VERTICAL || top.parent !== area) return@add
            binding = IndicationBinding(
                area, top, sensorId,
                visible = { visibleField.getBoolean(owner) },
                dozing = { dozingField.getBoolean(owner) },
                createRow = { newRow.newInstance(area.context, null) as TextView },
            ).also { it.refresh() }
        }
        add(update) { binding?.refresh() }
        add(setVisible) { binding?.refresh() }
        add(destroy) {
            binding?.dispose()
            binding = null
        }
        tweaks.observe { binding?.refresh() }
    }

    private inner class IndicationBinding(
        val area: LinearLayout,
        private val top: TextView,
        private val sensorId: Int,
        private val visible: () -> Boolean,
        private val dozing: () -> Boolean,
        private val createRow: () -> TextView,
    ) : View.OnAttachStateChangeListener {

        private var row: TextView? = null
        private val areaLocation = IntArray(2)
        private val sensorLocation = IntArray(2)
        private val rowLocation = IntArray(2)
        private var appliedShift = 0f
        private var lastTranslation: Float? = null
        private var positionObserver: ViewTreeObserver? = null
        private val position = ViewTreeObserver.OnPreDrawListener {
            safely { updatePosition() }
            true
        }
        private var disposed = false
        private var reading = false
        private var loggedFailure = false
        private var renderedSettings: TweakSettings? = null
        private var renderedDozing: Boolean? = null
        private val battery = area.context.getSystemService(BatteryManager::class.java)
        private val powerManager = area.context.getSystemService(PowerManager::class.java)
        private val sample = Runnable { safely { sample() } }

        init { area.addOnAttachStateChangeListener(this) }

        fun refresh() = safely {
            if (!canShow()) {
                hide()
                return@safely
            }
            if (renderedSettings != tweaks.settings || renderedDozing != dozing()) hide()
            if (!reading && !main.hasCallbacks(sample)) main.post(sample)
        }

        override fun onViewAttachedToWindow(view: View) = refresh()
        override fun onViewDetachedFromWindow(view: View) = safely { hide() }

        fun dispose() = safely {
            disposed = true
            area.removeOnAttachStateChangeListener(this)
            stopPositionUpdates()
            row?.let { (it.parent as? ViewGroup)?.removeView(it) }
            row = null
            main.removeCallbacks(sample)
        }

        private fun canShow(): Boolean {
            val settings = tweaks.settings
            if (disposed || !area.isAttachedToWindow || !visible() ||
                !settings.chargingEnabled || !settings.hasMetrics) return false
            return if (dozing()) {
                settings.aod && area.javaClass.name == "com.android.systemui.keyguard.ui.view.KeyguardIndicationArea"
            } else {
                powerManager?.isInteractive == true
            }
        }

        private fun hide() {
            main.removeCallbacks(sample)
            row?.visibility = View.GONE
            stopPositionUpdates()
            renderedSettings = null
            renderedDozing = null
        }

        private fun sample() {
            if (!canShow()) {
                hide()
                return
            }
            if (reading) return
            val settings = tweaks.settings
            val onAod = dozing()
            val context = area.context
            reading = true
            worker.execute {
                val metrics = runCatching { readMetrics(context, battery, settings) }.getOrNull()
                main.post {
                    reading = false
                    safely {
                        if (!canShow()) {
                            hide()
                        } else if (settings == tweaks.settings && onAod == dozing()) {
                            val locale = context.resources.configuration.locales[0]
                            render(metrics?.format(settings, locale).orEmpty(), onAod)
                            renderedSettings = settings
                            renderedDozing = onAod
                            main.postDelayed(sample, if (onAod) 20_000L else 4_000L)
                        } else {
                            main.post(sample)
                        }
                    }
                }
            }
        }

        private fun render(text: String, onAod: Boolean) {
            if (text.isEmpty()) {
                row?.visibility = View.GONE
                stopPositionUpdates()
                return
            }
            val host = area.parent as? ViewGroup ?: return
            row?.takeUnless { it.parent === host }?.let {
                stopPositionUpdates()
                (it.parent as? ViewGroup)?.removeView(it)
                row = null
            }
            val view = row ?: createRow().apply {
                id = View.generateViewId()
                visibility = View.GONE
                alpha = 0f
                gravity = Gravity.CENTER
                maxLines = 2
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                setTextDirection(View.TEXT_DIRECTION_LTR)
                host.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                row = this
            }
            view.typeface = top.typeface
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, top.textSize * 0.85f)
            view.setPaddingRelative(top.paddingStart, 0, top.paddingEnd, 0)
            if (onAod) view.setTextColor(Color.WHITE) else view.setTextColor(top.textColors)
            if (!android.text.TextUtils.equals(view.text, text)) view.text = text
            view.visibility = View.VISIBLE
            if (positionObserver?.isAlive != true) {
                positionObserver = area.viewTreeObserver.also { it.addOnPreDrawListener(position) }
            }
            updatePosition()
        }

        private fun updatePosition() {
            val view = row ?: return
            val sensor = area.rootView.findViewById<View>(sensorId)
            if (sensor == null || !sensor.isLaidOut || sensor.height <= 0 || !area.isShown ||
                !area.isLaidOut || area.isLayoutRequested || !view.isLaidOut) {
                view.alpha = 0f
                restoreTranslation()
                return
            }

            // The two rows have independent layout bounds. Align each against the actual sensor
            // after the OEM has applied keyguard constraints and AOD offsets.
            sensor.getLocationInWindow(sensorLocation)
            view.getLocationInWindow(rowLocation)
            val density = area.resources.displayMetrics.density
            val aboveGap = (8f * density).roundToInt()
            val belowGap = (4f * density).roundToInt()
            val metricsBottom = rowLocation[1] + view.height
            val metricsTranslation = view.translationY + sensorLocation[1] - aboveGap - metricsBottom
            if (view.translationY != metricsTranslation) view.translationY = metricsTranslation
            if (view.alpha != area.alpha) view.alpha = area.alpha

            val current = area.translationY
            val ownShift = if (current == lastTranslation) appliedShift else 0f
            area.getLocationInWindow(areaLocation)
            val nativeTop = areaLocation[1] - ownShift
            val sensorBottom = sensorLocation[1] + sensor.height
            val shift = (sensorBottom + belowGap - nativeTop).coerceAtLeast(0f)
            val desired = current - ownShift + shift
            appliedShift = shift
            lastTranslation = desired
            if (desired != current) area.translationY = desired
        }

        private fun stopPositionUpdates() {
            positionObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(position)
            positionObserver = null
            restoreTranslation()
        }

        private fun restoreTranslation() {
            if (area.translationY == lastTranslation && appliedShift != 0f) {
                area.translationY -= appliedShift
            }
            lastTranslation = null
            appliedShift = 0f
        }

        private fun safely(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                main.removeCallbacks(sample)
                runCatching {
                    row?.visibility = View.GONE
                    stopPositionUpdates()
                }
                if (!loggedFailure) HookLog.error("Charging info presentation failed", error)
                loggedFailure = true
            }
        }
    }

    private fun readMetrics(context: Context, battery: BatteryManager?, settings: TweakSettings): BatteryMetrics? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (!status.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return null
        val charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 &&
            status.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ==
                BatteryManager.BATTERY_STATUS_CHARGING
        if (settings.chargingOnly && !charging) return null
        val current = if (settings.current || settings.power) runCatching {
            battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull() else null
        return BatteryMetrics(
            millivolts = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).takeIf { it > 0 },
            microamps = current,
            deciCelsius = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE },
            charging = charging,
        )
    }
}
