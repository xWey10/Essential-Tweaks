// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.essentialkey

enum class EssentialKeyGesture {
    PRESS, DOUBLE_PRESS, HOLD, PRESS_AND_HOLD, DOUBLE_PRESS_AND_HOLD,
}

enum class EssentialKeyCapture {
    NONE, SCREENSHOT, RECORD, LAUNCH_APP, LAUNCH_ACTIVITY, SOUND_RECORDER, NOTIFICATIONS, CAMERA,
}

internal class EssentialKeyGestures(
    private val tapTimeout: Long,
    private val holdTimeout: Long,
    private val onGesture: (EssentialKeyGesture) -> Unit,
) {
    private var taps = 0
    private var held = false
    private var down = false
    var deadline: Long? = null
        private set

    fun press(at: Long) {
        advance(at)
        if (down) return
        down = true
        deadline = at + holdTimeout
    }

    fun release(at: Long, cancelled: Boolean) {
        if (cancelled) {
            reset()
            return
        }
        advance(at)
        if (!down) return
        down = false
        if (held || ++taps == 3) {
            reset()
        } else {
            deadline = at + tapTimeout
        }
    }

    fun advance(at: Long) {
        val due = deadline ?: return
        if (at < due) return
        deadline = null
        if (down) {
            held = true
            onGesture(when (taps) {
                0 -> EssentialKeyGesture.HOLD
                1 -> EssentialKeyGesture.PRESS_AND_HOLD
                else -> EssentialKeyGesture.DOUBLE_PRESS_AND_HOLD
            })
        } else {
            val gesture = if (taps == 1) EssentialKeyGesture.PRESS else EssentialKeyGesture.DOUBLE_PRESS
            reset()
            onGesture(gesture)
        }
    }

    fun reset() {
        taps = 0
        held = false
        down = false
        deadline = null
    }
}
