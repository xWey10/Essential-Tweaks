// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.essentialkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EssentialKeyPasteTest {
    @Test fun pasteContainsBothCommandsWithoutSubmittingEither() {
        val command = EssentialKeyAction.manualPasteCommand
        assertFalse(command.any { it == '\r' || it == '\n' })
        assertEquals(
            listOf(
                "sudo pm disable-user --user 0 com.nothing.ntessentialrecorder",
                "sudo pm disable-user --user 0 com.nothing.ntessentialspace",
            ),
            command.split("; "),
        )
    }

    @Test fun onlySuccessfulRestoreMayClearCustomBindings() {
        assertTrue(EssentialKeyResult.RESTORED.restoresStock)
        assertTrue(EssentialKeyResult.ALREADY_RESTORED.restoresStock)
        assertFalse(EssentialKeyResult.ROOT_REQUIRED.restoresStock)
        assertFalse(EssentialKeyResult.TIMEOUT.restoresStock)
        assertFalse(EssentialKeyResult.PARTIAL.restoresStock)
        assertFalse(EssentialKeyResult.NOT_INSTALLED.restoresStock)
    }

    @Test fun restorePasteReenablesBothOriginalApplications() {
        val command = EssentialKeyAction.manualRestorePasteCommand
        assertFalse(command.any { it == '\r' || it == '\n' })
        assertEquals(
            listOf(
                "sudo pm enable --user 0 com.nothing.ntessentialrecorder",
                "sudo pm enable --user 0 com.nothing.ntessentialspace",
            ),
            command.split("; "),
        )
    }
}
