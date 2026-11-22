// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.features.charging

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import moe.xWey10.tweaks.core.TweakSettings

internal class LockWidgets {
    private var stamp = 0L
    private var rx = 0L
    private var tx = 0L
    private var rate = ""

    @Synchronized fun reset() { stamp = 0; rate = "" }

    @Synchronized private fun network(context: Context): String {
        val now = SystemClock.elapsedRealtime()
        val received = TrafficStats.getTotalRxBytes()
        val sent = TrafficStats.getTotalTxBytes()
        if (received < 0 || sent < 0) { reset(); return "" }
        val interval = now - stamp
        if (stamp != 0L && interval < 1000) return rate
        fun speed(delta: Long) = Formatter.formatShortFileSize(context, delta * 1000 / interval) + "/s"
        rate = if (stamp != 0L && received >= rx && sent >= tx)
            "↓ ${speed(received - rx)}   ↑ ${speed(sent - tx)}" else "↓ —   ↑ —"
        stamp = now
        rx = received
        tx = sent
        return rate
    }

    fun format(ctx: Context, intent: Intent?, s: TweakSettings, charging: String): String {
        val lines = mutableMapOf('c' to charging)
        if (s.networkWidget) lines['n'] = network(ctx) else reset()
        if (s.batteryWidget && intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
            if (level >= 0 && scale > 0) {
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                lines['b'] = (if (plugged) "⚡ " else "") + "${level * 100 / scale}%"
            }
        }
        if (s.systemWidget) {
            val memory = ActivityManager.MemoryInfo()
            ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val used = Formatter.formatShortFileSize(ctx, memory.totalMem - memory.availMem)
            val total = Formatter.formatShortFileSize(ctx, memory.totalMem)
            val cpu = Build.SOC_MODEL.takeUnless { it.isBlank() || it == Build.UNKNOWN } ?: Build.HARDWARE
            lines['s'] = "RAM $used / $total   ·   CPU $cpu"
        }
        return s.widgetOrder.mapNotNull { lines[it]?.takeIf(String::isNotEmpty) }.joinToString("\n")
    }
}
