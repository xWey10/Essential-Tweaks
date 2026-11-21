// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.lockscreen

internal class TapSequence(private val touchSlop: Int, private val doubleTapSlop: Int, private val timeout: Long) {
    private data class Point(val x: Float, val y: Float, val time: Long) {
        fun near(x: Float, y: Float, slop: Int): Boolean {
            val dx = this.x - x
            val dy = this.y - y
            return dx * dx + dy * dy <= slop * slop
        }
    }

    private var down: Point? = null
    private var previous: Point? = null

    fun down(x: Float, y: Float, time: Long) {
        if (previous?.near(x, y, doubleTapSlop) != true) previous = null
        down = Point(x, y, time)
    }

    fun move(x: Float, y: Float) {
        if (down?.near(x, y, touchSlop) == false) reset()
    }

    fun up(x: Float, y: Float, time: Long): Boolean {
        val start = down ?: return false
        down = null
        if (!start.near(x, y, touchSlop) || time - start.time !in 0L..timeout) {
            previous = null
            return false
        }
        val matched = previous?.let { start.time - it.time in 40L..timeout } == true
        previous = if (matched) null else Point(x, y, time)
        return matched
    }

    fun reset() {
        down = null
        previous = null
    }
}
