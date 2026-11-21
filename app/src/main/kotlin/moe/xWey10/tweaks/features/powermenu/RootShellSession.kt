// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

import moe.xWey10.tweaks.core.RebootTarget
import java.util.concurrent.TimeUnit

internal enum class RootResult { ABORTED, DENIED, FAILED, RETURNED, TIMEOUT }

/** No arbitrary command argument. Only the two device reboot targets can use the shell. */
internal class RootShellSession(
    private val createProcess: () -> Process = { ProcessBuilder("su").redirectErrorStream(true).start() },
    private val timeoutMillis: Long = 30_000,
) {
    fun execute(target: RebootTarget, authorizeAfterRootGrant: () -> Boolean): RootResult {
        require(target != RebootTarget.SYSTEM_UI)
        val process = createProcess()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val input = process.inputStream
        fun expired() = System.nanoTime() >= deadline
        fun nextByte(): Int {
            // One reader only. Do not block indefinitely in read() while a root prompt is open.
            while (!expired()) {
                if (input.available() > 0) return input.read()
                if (!process.isAlive) return -1
                Thread.sleep(15)
            }
            throw SessionTimeout()
        }
        try {
            val writer = process.outputStream.bufferedWriter()
            // Wait for an actual root shell before authorizing the privileged command.
            writer.write("printf 'ET_ROOT_READY\\n'; /system/bin/id -u\n")
            writer.flush()
            var budget = 4096
            var marker = false
            var root = false
            while (budget > 0) {
                val line = readLine(::nextByte, minOf(budget, 512)) ?: break
                budget -= line.length + 1
                if (marker) { root = line.trim() == "0"; break }
                marker = line.trim() == "ET_ROOT_READY"
            }
            if (!root) return RootResult.DENIED
            if (expired() || !authorizeAfterRootGrant()) return RootResult.ABORTED
            // Re-check after root-manager interaction, never queue a reboot while awaiting root.
            if (expired()) return RootResult.ABORTED
            writer.write("exec /system/bin/svc power reboot ${target.reason}\n")
            writer.flush()
            // svc waits for shutdown. A successful reboot destroys this process, not a success callback.
            var output = false
            var count = 0
            while (true) {
                val value = nextByte()
                if (value == -1) break
                if (!value.toChar().isWhitespace()) output = true
                if (++count > 8192) return RootResult.FAILED
            }
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) return RootResult.TIMEOUT
            return when {
                expired() -> RootResult.TIMEOUT
                process.exitValue() != 0 || output -> RootResult.FAILED
                else -> RootResult.RETURNED
            }
        } catch (_: SessionTimeout) {
            return RootResult.TIMEOUT
        } finally {
            stop(process)
        }
    }

    private class SessionTimeout : Exception()

    private fun readLine(nextByte: () -> Int, limit: Int): String? {
        val result = StringBuilder()
        repeat(limit) {
            val next = nextByte()
            if (next == -1) return if (result.isEmpty()) null else result.toString()
            if (next == 10) return result.toString()
            result.append(next.toChar())
        }
        error("Unexpected root-shell output")
    }

    private fun stop(process: Process) {
        // Closing stdin first prevents an abandoned shell from waiting for a later command.
        try { process.outputStream.close() } catch (_: Exception) { }
        try { process.destroyForcibly() } catch (_: Exception) { }
        try { process.inputStream.close() } catch (_: Exception) { }
        try { process.errorStream.close() } catch (_: Exception) { }
    }
}
