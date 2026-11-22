// SPDX-License-Identifier: GPL-3.0-only

package moe.xWey10.tweaks.ui

import android.app.Activity
import android.app.Application
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import moe.xWey10.tweaks.core.Protocol
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class RestartTarget(val packageName: String) {
    SYSTEM_UI(Protocol.SYSTEM_UI),
    LAUNCHER(Protocol.NOTHING_LAUNCHER),
}

internal class ApplyTweaksViewModel(application: Application) : AndroidViewModel(application) {
    enum class Result { DONE, PARTIAL, ROOT_REQUIRED, TIMED_OUT, FAILED }

    private val worker = Executors.newSingleThreadExecutor()
    private val mutableRunning = MutableLiveData(false)
    val running: LiveData<Boolean> = mutableRunning
    private val mutableResult = MutableLiveData<Result?>(null)
    val result: LiveData<Result?> = mutableResult

    fun restart(targets: Set<RestartTarget>) {
        if (targets.isEmpty() || mutableRunning.value == true) return
        mutableResult.value = null
        mutableRunning.value = true
        worker.execute {
            val result = runCatching { restartProcesses(targets) }.getOrDefault(Result.FAILED)
            mutableRunning.postValue(false)
            mutableResult.postValue(result)
        }
    }

    fun consumeResult() { mutableResult.value = null }

    private fun restartProcesses(targets: Set<RestartTarget>): Result {
        if (RestartTarget.LAUNCHER !in targets) return restartWithRoot(targets)
        val launcherRestart = restartLauncher()
        val remaining = targets - RestartTarget.LAUNCHER
        return when (launcherRestart) {
            true -> if (remaining.isEmpty() || restartWithRoot(remaining) == Result.DONE) Result.DONE else Result.PARTIAL
            false -> restartWithRoot(targets)
            null -> if (remaining.isNotEmpty() && restartWithRoot(remaining) == Result.DONE) Result.PARTIAL else Result.TIMED_OUT
        }
    }

    // A device that has not injected the updated module yet answers with the initial result code.
    private fun restartLauncher(): Boolean? {
        val signal = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val intent = Intent(Protocol.RESTART_LAUNCHER).setPackage(Protocol.NOTHING_LAUNCHER)
            .putExtra(Protocol.RESTART_DEADLINE, SystemClock.elapsedRealtime() + Protocol.RESTART_WINDOW_MS)
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        getApplication<Application>().sendOrderedBroadcast(intent, null, options,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    accepted.set(resultCode == Activity.RESULT_OK)
                    signal.countDown()
                }
            }, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
        return if (signal.await(Protocol.RESTART_WINDOW_MS + 500, TimeUnit.MILLISECONDS)) accepted.get() else null
    }

    private fun restartWithRoot(targets: Set<RestartTarget>): Result {
        val application = getApplication<Application>()
        val manager = application.packageManager
        val installed = targets.mapNotNull { target ->
            try {
                target to manager.getPackageUid(target.packageName, PackageManager.PackageInfoFlags.of(0))
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        if (installed.isEmpty()) return Result.FAILED

        val command = buildString {
            appendLine(RESTART_SCRIPT)
            for ((target, uid) in installed) {
                val name = target.packageName
                appendLine("if restart_process '$name' $uid; then printf '%s\\n' 'ET_APPLIED:$name'; fi")
            }
        }
        val output = File.createTempFile("apply-tweaks-", ".log", application.cacheDir)
        try {
            val process = try {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).redirectOutput(output).start()
            } catch (_: IOException) {
                return Result.ROOT_REQUIRED
            }
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) return Result.TIMED_OUT
                val lines = output.bufferedReader().useLines { it.take(64).toSet() }
                if (ROOT_MARKER !in lines) return Result.ROOT_REQUIRED
                val applied = installed.count { (target, _) -> ("ET_APPLIED:" + target.packageName) in lines }
                return when {
                    applied == targets.size -> Result.DONE
                    applied > 0 -> Result.PARTIAL
                    else -> Result.FAILED
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            output.delete()
        }
    }

    override fun onCleared() { worker.shutdown() }

    private companion object {
        const val ROOT_MARKER = "ET_APPLY_ROOT"
        // The caller supplies only enum package names and the package manager's UID for this user.
        // Signal the exact process without force-stopping the package or changing its enabled state.
        val RESTART_SCRIPT = """
            [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 1
            printf '%s\n' '$ROOT_MARKER'
            restart_process() {
                pids=${'$'}("/system/bin/pidof" "${'$'}1") || [ "${'$'}?" -eq 1 ] || return 1
                [ -n "${'$'}pids" ] || return 0
                matched=0
                for pid in ${'$'}pids; do
                    case "${'$'}pid" in ''|*[!0-9]*) continue ;; esac
                    [ "${'$'}("/system/bin/tr" '\000' '\n' < "/proc/${'$'}pid/cmdline" | /system/bin/head -n 1)" = "${'$'}1" ] || continue
                    /system/bin/grep -Eq "^Uid:[[:space:]]+${'$'}2[[:space:]]" "/proc/${'$'}pid/status" || continue
                    kill -KILL "${'$'}pid" || return 1
                    matched=1
                done
                [ "${'$'}matched" -eq 1 ]
            }
        """.trimIndent()
    }
}
