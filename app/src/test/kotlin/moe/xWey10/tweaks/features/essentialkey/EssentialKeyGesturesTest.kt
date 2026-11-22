// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.essentialkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EssentialKeyGesturesTest {
    private val seen = mutableListOf<EssentialKeyGesture>()
    private val tracker = EssentialKeyGestures(320, 500, seen::add)

    @Test fun singlePressWaitsForTheNextPress() {
        tap(0)
        tracker.advance(369)
        assertTrue(seen.isEmpty())
        tracker.advance(370)
        assertEquals(listOf(EssentialKeyGesture.PRESS), seen)
        assertNull(tracker.deadline)
    }

    @Test fun doublePressWaitsForAPossibleHold() {
        tap(0)
        tap(140)
        tracker.advance(509)
        assertTrue(seen.isEmpty())
        tracker.advance(510)
        assertEquals(listOf(EssentialKeyGesture.DOUBLE_PRESS), seen)
    }

    @Test fun allThreeHoldsFireOnceWithoutACompetingTap() {
        val expected = listOf(
            EssentialKeyGesture.HOLD,
            EssentialKeyGesture.PRESS_AND_HOLD,
            EssentialKeyGesture.DOUBLE_PRESS_AND_HOLD,
        )
        for ((prefix, gesture) in expected.withIndex()) {
            seen.clear()
            repeat(prefix) { tap(it * 120L) }
            val lastDown = prefix * 120L
            tracker.press(lastDown)
            tracker.advance(lastDown + 499)
            assertTrue(seen.isEmpty())
            tracker.advance(lastDown + 500)
            tracker.advance(lastDown + 650)
            tracker.release(lastDown + 800, cancelled = false)
            tracker.advance(lastDown + 1200)
            assertEquals(listOf(gesture), seen)
        }
    }

    @Test fun retryDownDoesNotRestartTheHoldDeadline() {
        tracker.press(0)
        tracker.press(350)
        tracker.release(501, cancelled = false)
        tracker.advance(1500)
        assertEquals(listOf(EssentialKeyGesture.HOLD), seen)
    }

    @Test fun cancelledEventAndConfigurationResetDiscardPendingGestures() {
        tap(0)
        tracker.press(120)
        tracker.release(730, cancelled = true)
        tap(800)
        tracker.reset()
        tracker.advance(1300)
        assertTrue(seen.isEmpty())
        assertNull(tracker.deadline)
    }

    @Test fun unrelatedTripleTapDoesNotTriggerDoublePress() {
        repeat(3) { tap(it * 110L) }
        tracker.advance(1000)
        assertTrue(seen.isEmpty())
        assertNull(tracker.deadline)
    }

    @Test fun pressesPastTheDeadlineStartAnIndependentGesture() {
        tap(0)
        tap(440)
        tracker.advance(810)
        assertEquals(listOf(EssentialKeyGesture.PRESS, EssentialKeyGesture.PRESS), seen)
    }

    private fun tap(at: Long) {
        tracker.press(at)
        tracker.release(at + 50, cancelled = false)
    }
}
