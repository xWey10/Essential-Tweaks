// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderNamingTest {
    @Test fun defaultsToTimestamp() {
        assertEquals(RecorderNaming.DEFAULT, RecorderNaming.normalizeTemplate("   "))
    }

    @Test fun expandsEpochAndSanitizesSlashes() {
        assertEquals("voice_12345", RecorderNaming.fileBase("voice/{epoch}", 12345L))
        assertEquals("my_file_abc", RecorderNaming.fileBase("my/file:abc", 12345L))
    }

    @Test fun limitsLengthAndDoesNotAllowPathTraversal() {
        val name = RecorderNaming.fileBase("../" + "a".repeat(200), 0L)
        assertTrue(name.length <= 110)
        assertFalse(name.contains('/'))
        assertFalse(name.startsWith('.'))
    }
}
