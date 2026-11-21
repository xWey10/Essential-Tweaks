// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.lockscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSequenceTest {
    @Test fun firesAfterSecondRelease() {
        val taps = TapSequence(8, 64, 300)
        taps.down(10f, 10f, 100)
        assertFalse(taps.up(10f, 10f, 140))
        taps.down(16f, 10f, 220)
        assertTrue(taps.up(16f, 10f, 260))
        taps.down(16f, 10f, 340)
        assertFalse(taps.up(16f, 10f, 380))
    }

    @Test fun swipeOrCancelDiscardsSequence() {
        val taps = TapSequence(8, 64, 300)
        taps.down(10f, 10f, 100)
        assertFalse(taps.up(10f, 10f, 140))
        taps.down(10f, 10f, 220)
        taps.move(30f, 10f)
        assertFalse(taps.up(10f, 10f, 260))
        taps.down(10f, 10f, 290)
        taps.reset()
        assertFalse(taps.up(10f, 10f, 330))
    }

    @Test fun distantLateAndLongTapsCannotCompleteDoubleTap() {
        val taps = TapSequence(8, 64, 300)
        taps.down(10f, 10f, 100)
        assertFalse(taps.up(10f, 10f, 140))
        taps.down(90f, 10f, 220)
        assertFalse(taps.up(90f, 10f, 260))
        taps.down(90f, 10f, 600)
        assertFalse(taps.up(90f, 10f, 640))
        taps.down(90f, 10f, 700)
        assertFalse(taps.up(90f, 10f, 1010))
    }
}
