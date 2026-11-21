// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

import moe.xWey10.tweaks.core.RebootTarget
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class RootShellSessionTest {
    private class Shell(reply: String, private val code: Int = 0, private val alive: Boolean = false) : Process() {
        val commands = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(reply.toByteArray())
        var stopped = false
        override fun getOutputStream() = commands
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
        override fun exitValue() = code
        override fun isAlive() = alive
        override fun destroy() { stopped = true }
        override fun destroyForcibly(): Process { stopped = true; return this }
    }

    @Test fun `root grant precedes final authorization and only one whitelisted command follows`() {
        val shell = Shell("ET_ROOT_READY\n0\n")
        var authorizations = 0
        val result = RootShellSession({ shell }).execute(RebootTarget.BOOTLOADER) {
            authorizations++
            assertFalse(shell.commands.toString().contains("svc power reboot"))
            true
        }
        assertEquals(RootResult.RETURNED, result)
        assertEquals(1, authorizations)
        assertEquals(1, Regex("svc power reboot bootloader").findAll(shell.commands.toString()).count())
        assertFalse(shell.commands.toString().contains("fastbootd"))
        assertTrue(shell.stopped)
    }

    @Test fun `locking or disabling action during root prompt prevents dispatch`() {
        val shell = Shell("ET_ROOT_READY\n0\n")
        assertEquals(RootResult.ABORTED, RootShellSession({ shell }).execute(RebootTarget.RECOVERY) { false })
        assertFalse(shell.commands.toString().contains("svc power reboot"))
        assertTrue(shell.stopped)
    }

    @Test fun `denied root never reaches authorization or reboot`() {
        val shell = Shell("ET_ROOT_READY\n2000\n")
        assertEquals(RootResult.DENIED, RootShellSession({ shell }).execute(RebootTarget.RECOVERY) {
            fail("Non-root shell cannot authorize reboot"); true
        })
        assertFalse(shell.commands.toString().contains("svc power reboot"))
    }

    @Test fun `zero exit with svc error output is still a failure without retry`() {
        val shell = Shell("ET_ROOT_READY\n0\nFailed to reboot: permission denied\n")
        assertEquals(RootResult.FAILED, RootShellSession({ shell }).execute(RebootTarget.RECOVERY) { true })
        assertEquals(1, Regex("svc power reboot recovery").findAll(shell.commands.toString()).count())
    }

    @Test fun `root prompt timeout destroys process without a privileged command`() {
        val shell = Shell("", alive = true)
        assertEquals(RootResult.TIMEOUT, RootShellSession({ shell }, 0).execute(RebootTarget.RECOVERY) {
            fail("Expired root prompt must not authorize"); true
        })
        assertTrue(shell.stopped)
        assertFalse(shell.commands.toString().contains("svc power reboot"))
    }

    @Test fun `authorization exception closes root shell without dispatch`() {
        val shell = Shell("ET_ROOT_READY\n0\n")
        try {
            RootShellSession({ shell }).execute(RebootTarget.BOOTLOADER) { error("Gate unavailable") }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertTrue(shell.stopped)
        assertFalse(shell.commands.toString().contains("svc power reboot"))
    }
    @Test fun systemUiRestartCannotOpenRootShell() {
        val session = RootShellSession({ error("System UI restart must never open a shell") })
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            session.execute(RebootTarget.SYSTEM_UI) { true }
        }
    }
}
