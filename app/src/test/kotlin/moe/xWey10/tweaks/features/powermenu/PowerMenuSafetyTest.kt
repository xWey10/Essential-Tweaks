// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

import moe.xWey10.tweaks.core.FeatureConfig
import moe.xWey10.tweaks.core.RebootTarget
import org.junit.Assert.*
import org.junit.Test

class PowerMenuSafetyTest {
    private val unlocked = GateState(
        readable = true, enabled = true, deviceLocked = false, keyguardShowing = false,
        interactive = true, userUnlocked = true, stockVerificationRequired = false,
        currentUserId = 0, provisioned = true, userSetupComplete = true, ageTest = false,
        restricted = false, monkey = false,
    )

    @Test fun `secure lock, visible keyguard and pending stock authentication all block reboot`() {
        assertNull(denial(unlocked))
        listOf(
            unlocked.copy(deviceLocked = true),
            unlocked.copy(keyguardShowing = true),
            unlocked.copy(interactive = false),
            unlocked.copy(userUnlocked = false),
            unlocked.copy(stockVerificationRequired = true),
        ).forEach { assertEquals(Denial.LOCKED, denial(it)) }
    }

    @Test fun `unknown settings and disabled actions fail closed`() {
        assertEquals(Denial.SETTINGS, denial(unlocked.copy(readable = false)))
        assertEquals(Denial.DISABLED, denial(unlocked.copy(enabled = false)))
    }

    @Test fun `secondary user, incomplete provisioning, policy and monkey block custom actions`() {
        listOf(unlocked.copy(currentUserId = 10), unlocked.copy(provisioned = false),
            unlocked.copy(restricted = true), unlocked.copy(monkey = true))
            .forEach { assertEquals(Denial.UNAVAILABLE, denial(it)) }
    }

    @Test fun `Nothing age testing blocks additions and confirmed reboot`() {
        assertEquals(Denial.UNAVAILABLE, denial(unlocked.copy(ageTest = true)))
    }

    @Test fun `global provisioning does not bypass incomplete per user setup or CIT protection`() {
        assertTrue(unlocked.provisioned)
        assertEquals(Denial.UNAVAILABLE, denial(unlocked.copy(userSetupComplete = false)))
    }

    @Test fun `two switches remain independent in all combinations`() {
        for (recovery in listOf(false, true)) for (bootloader in listOf(false, true)) {
            val config = FeatureConfig(recovery, bootloader)
            assertEquals(recovery, config.enabled(RebootTarget.RECOVERY))
            assertEquals(bootloader, config.enabled(RebootTarget.BOOTLOADER))
        }
        assertEquals("bootloader", RebootTarget.BOOTLOADER.reason)
        assertEquals("recovery", RebootTarget.RECOVERY.reason)
    }

    @Test fun `stock confirmations default off and remain independent of custom entries`() {
        val initial = FeatureConfig()
        assertFalse(initial.confirmNormalReboot)
        assertFalse(initial.confirmPowerOff)
        val configured = initial.copy(confirmNormalReboot = true, confirmPowerOff = false)
        assertTrue(configured.confirmNormalReboot)
        assertFalse(configured.confirmPowerOff)
        assertFalse(configured.enabled(RebootTarget.RECOVERY))
        val flipped = configured.copy(confirmPowerOff = true, confirmNormalReboot = false)
        assertFalse(flipped.confirmNormalReboot)
        assertTrue(flipped.confirmPowerOff)
    }

    @Test fun `native overflow preserves stock action identities and order`() {
        val power = Any(); val restart = Any(); val emergency = Any(); val extra = Any()
        val main = mutableListOf(power, restart)
        val overflow = mutableListOf(emergency)
        appendTransaction(main, overflow, listOf(extra)) { overflow += it }
        assertSame(power, main[0]); assertSame(restart, main[1])
        assertSame(emergency, overflow[0]); assertSame(extra, overflow[1])
    }

    @Test fun `partial native placement failure restores both lists`() {
        val main = mutableListOf("power", "restart")
        val overflow = mutableListOf("emergency")
        try {
            appendTransaction(main, overflow, listOf("recovery", "bootloader")) {
                if (it == "recovery") main += it else { overflow += it; error("Native layout failed") }
            }
            fail("Expected native placement failure")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("power", "restart"), main)
        assertEquals(listOf("emergency"), overflow)
    }
    @Test fun `reboot never asks for root and requires system grants`() {
        for (canReboot in listOf(false, true)) for (canRecover in listOf(false, true)) {
            assertNull(missingRebootPermission(RebootTarget.SYSTEM_UI, canReboot, canRecover))
            assertEquals(if (canReboot) null else MissingPowerPermission.REBOOT,
                missingRebootPermission(RebootTarget.BOOTLOADER, canReboot, canRecover))
            assertEquals(when {
                !canReboot -> MissingPowerPermission.REBOOT
                !canRecover -> MissingPowerPermission.RECOVERY
                else -> null
            }, missingRebootPermission(RebootTarget.RECOVERY, canReboot, canRecover))
        }
    }

}
