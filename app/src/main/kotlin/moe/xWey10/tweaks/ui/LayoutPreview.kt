// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import moe.xWey10.tweaks.R
import moe.xWey10.tweaks.core.TweakSettings
import moe.xWey10.tweaks.features.charging.BatteryMetrics
import kotlin.math.roundToInt

internal class LayoutPreview(context: Context, settings: TweakSettings, save: (String) -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(10), dp(16), dp(10), dp(12))
        background = GradientDrawable().apply { setColor(0xff191919.toInt()); cornerRadius = dp(18).toFloat() }
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        addView(rows, LayoutParams(-1, -2))
        val locale = resources.configuration.locales[0]
        val samples = mapOf(
            'c' to BatteryMetrics(4210, 1840000, 296, true, 2100000).format(settings, locale)
                .ifEmpty { context.getString(R.string.charging_info) },
            'b' to listOfNotNull("68%".takeIf { settings.batteryWidget || !settings.eta },
                "≈ 6:42 → 0%".takeIf { settings.eta }).joinToString(" · "),
            'n' to "↓ 24 KB/s    ↑ 6 KB/s",
            's' to "RAM 4 GB / 8 GB   ·   CPU ${android.os.Build.SOC_MODEL}"
        )
        val enabled = mapOf('c' to (settings.chargingEnabled && settings.hasMetrics),
            'b' to (settings.batteryWidget || settings.eta), 'n' to settings.networkWidget, 's' to settings.systemWidget)
        val original = settings.widgetOrder
        val items = original.associateWith { key ->
            TextView(context).apply {
                text = samples.getValue(key)
                tag = key
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                minHeight = dp(42)
                alpha = if (enabled[key] == true) 1f else 0.38f
                setOnLongClickListener { v -> v.startDragAndDrop(null, View.DragShadowBuilder(v), v, 0) }
                rows.addView(this, LayoutParams(-1, -2))
            }
        }
        rows.setOnDragListener { _, event ->
            val item = event.localState as? TextView
            if (item == null || items[item.tag as? Char] !== item) return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> item.visibility = View.INVISIBLE
                DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DROP -> {
                    val index = rows.indexOfChild(item)
                    val dest = (0 until rows.childCount).lastOrNull { i ->
                        val row = rows.getChildAt(i)
                        event.y >= row.top + if (i > index) row.height / 2f else 0f
                    } ?: 0
                    if (index != dest) {
                        rows.removeView(item)
                        rows.addView(item, dest)
                    }
                    if (event.action == DragEvent.ACTION_DROP) {
                        val order = (0 until rows.childCount).joinToString("") { rows.getChildAt(it).tag.toString() }
                        if (order != original) rows.post { save(order) }
                    }
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    item.visibility = View.VISIBLE
                    if (!event.result) {
                        rows.removeAllViews()
                        original.forEach { rows.addView(items.getValue(it)) }
                    }
                }
            }
            true
        }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_fingerprint)
            setColorFilter(Color.LTGRAY)
        }, LayoutParams(dp(29), dp(29)).apply { topMargin = dp(settings.layoutGap) })
    }
}
