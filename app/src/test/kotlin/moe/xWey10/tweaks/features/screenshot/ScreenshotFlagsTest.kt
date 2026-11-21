// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.screenshot

import android.view.WindowManager.LayoutParams
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotFlagsTest {
    @Test fun appWindowsLoseOnlyTheirSecureFlag() {
        val flags = LayoutParams.FLAG_SECURE or LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals(LayoutParams.FLAG_KEEP_SCREEN_ON, screenshotFlags(LayoutParams.TYPE_APPLICATION, flags))
        assertEquals(LayoutParams.FLAG_KEEP_SCREEN_ON, screenshotFlags(LayoutParams.TYPE_APPLICATION_PANEL, flags))
    }

    @Test fun systemAndOverlayWindowsKeepTheirSecureFlag() {
        val flags = LayoutParams.FLAG_SECURE or LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals(flags, screenshotFlags(LayoutParams.TYPE_STATUS_BAR, flags))
        assertEquals(flags, screenshotFlags(LayoutParams.TYPE_APPLICATION_OVERLAY, flags))
    }
}
