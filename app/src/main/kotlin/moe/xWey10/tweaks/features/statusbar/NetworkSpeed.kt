// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.statusbar

import android.annotation.SuppressLint
import android.net.TrafficStats
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.TweakReader

internal class NetworkSpeed(private val loader: ClassLoader, private val tweaks: TweakReader) {
    @SuppressLint("DiscouragedApi") // Resolve SystemUI’s clock resource ID at runtime.
    fun install() = FeatureHooks("Status bar network speed").install {
        val bar = XposedHelpers.findClass("com.android.systemui.statusbar.phone.PhoneStatusBarView", loader)
        val inflate = bar.getDeclaredMethod("onFinishInflate")
        val bindings = mutableMapOf<View, Binding>()
        add(inflate) { param ->
            val statusBar = param.thisObject as View
            val id = statusBar.resources.getIdentifier("clock", "id", statusBar.context.packageName)
            val clock = statusBar.findViewById<View>(id) as? TextView
                ?: runCatching { XposedHelpers.getObjectField(statusBar, "mClock") as? TextView }.getOrNull()
                ?: return@add
            val row = clock.parent as? LinearLayout ?: return@add
            val binding = Binding(clock, row)
            statusBar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    bindings[v] = binding
                    binding.setEnabled(tweaks.settings.statusNetworkSpeed)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    bindings.remove(v)?.setEnabled(false)
                }
            })
            if (statusBar.isAttachedToWindow) {
                bindings[statusBar] = binding
                binding.setEnabled(tweaks.settings.statusNetworkSpeed)
            }
        }
        tweaks.observe { bindings.values.forEach { it.setEnabled(tweaks.settings.statusNetworkSpeed) } }
    }

    private class Binding(private val clock: TextView, private val row: LinearLayout) : Runnable {
        private val power = row.context.getSystemService(PowerManager::class.java)
        private val label = TextView(row.context).apply {
            val density = resources.displayMetrics.density
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 8f)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setLineSpacing(0f, 0.95f)
            setPaddingRelative((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            visibility = View.GONE
        }
        private var stamp = 0L
        private var rx = -1L
        private var tx = -1L
        private var enabled = false

        fun setEnabled(value: Boolean) {
            if (enabled == value) return
            enabled = value
            if (value) {
                val layout = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(label, row.indexOfChild(clock) + 1, layout)
                run()
            } else {
                label.removeCallbacks(this)
                row.removeView(label)
                stamp = 0L
            }
        }

        override fun run() {
            if (!enabled || label.parent == null) return
            val visible = row.isShown && power?.isInteractive == true
            if (!visible) {
                stamp = 0L
                label.visibility = View.GONE
                label.postDelayed(this, 2000)
                return
            }
            val now = SystemClock.elapsedRealtime()
            val currentRx = TrafficStats.getTotalRxBytes()
            val currentTx = TrafficStats.getTotalTxBytes()
            val interval = now - stamp
            if (stamp != 0L && interval > 0 && rx >= 0 && tx >= 0 &&
                currentRx >= rx && currentTx >= tx) {
                val speed = "↓ " + rate(currentRx - rx, interval) + "\n↑ " + rate(currentTx - tx, interval)
                if (label.text.toString() != speed) label.text = speed
                label.setTextColor(clock.currentTextColor)
                label.visibility = View.VISIBLE
            } else {
                label.visibility = View.GONE
            }
            stamp = now
            rx = currentRx
            tx = currentTx
            label.postDelayed(this, 1000)
        }

        private fun rate(bytes: Long, milliseconds: Long): String =
            Formatter.formatShortFileSize(label.context, (bytes * (1000.0 / milliseconds)).toLong()) + "/s"
    }
}
