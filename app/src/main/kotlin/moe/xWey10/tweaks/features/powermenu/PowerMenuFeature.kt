// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.powermenu

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.BuildConfig
import moe.xWey10.tweaks.R
import moe.xWey10.tweaks.core.Protocol
import moe.xWey10.tweaks.core.RebootTarget
import moe.xWey10.tweaks.hooks.ConfigReader
import moe.xWey10.tweaks.hooks.HookHealth
import moe.xWey10.tweaks.hooks.HookLog
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PowerMenuFeature(
    private val loader: ClassLoader,
    private val modulePath: String,
    private val config: ConfigReader,
    private val health: HookHealth,
) {
    private data class Injected(val owner: Any, val context: Context, val target: RebootTarget)
    private val tags = Collections.synchronizedMap(WeakHashMap<Any, Injected>())
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EssentialTweaks-reboot").apply { isDaemon = true }
    }
    private val rebooting = AtomicBoolean(false)
    private var confirmation: AlertDialog? = null
    private lateinit var profile: PowerMenuProfile

    fun install() {
        val hooks = mutableListOf<XC_MethodHook.Unhook>()
        try {
            profile = PowerMenuProfile(loader)
            // Install interceptors before any tagged RestartAction can be inserted.
            hooks += XposedBridge.hookMethod(profile.press, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val action = tags[param.thisObject]
                    if (action == null) {
                        maybeConfirmStock(param, powerOff = false)
                        return
                    }
                    param.result = null // Our action must never fall through to stock reboot.
                    try { onPress(action) }
                    catch (error: Throwable) {
                        HookLog.error("Custom action failed safely", error)
                        toast(action, R.string.action_unavailable)
                    }
                }
            })
            // PowerAction is OEM-optional; failure to resolve it must not break the
            // existing stock power menu or our injected actions.
            if (profile.powerPress == null) HookLog.info("No verified PowerAction; stock shutdown confirmation disabled")
            profile.powerPress?.let { powerPress ->
                hooks += XposedBridge.hookMethod(powerPress, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        maybeConfirmStock(param, powerOff = true)
                    }
                })
            }
            hooks += XposedBridge.hookMethod(profile.longPress, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (tags.containsKey(param.thisObject)) param.result = true
                }
            })
            hooks += XposedBridge.hookMethod(profile.enabled, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val action = tags[param.thisObject] ?: return
                    param.result = false
                    try { param.result = health.ready && gate(action, refresh = false) == null }
                    catch (error: Throwable) { HookLog.error("Action availability check failed", error) }
                }
            })
            hooks += XposedBridge.hookMethod(profile.createItems, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    try { inject(param.thisObject) }
                    catch (error: Throwable) {
                        health.unavailable("Runtime injection failed: ${error.javaClass.simpleName}")
                        HookLog.error("Injection disabled; stock power menu retained", error)
                    }
                }
            })
            health.installed()
            HookLog.info("v${BuildConfig.VERSION_NAME}: checked Nothing SystemUI 16.0.0 profile; stock confirmation hooks installed")
        } catch (error: Throwable) {
            hooks.asReversed().forEach { try { it.unhook() } catch (_: Throwable) { } }
            health.unavailable("Unsupported Global Actions: ${error.message}")
            HookLog.error("Profile unavailable; no power-menu changes installed", error)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun inject(owner: Any) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Global Actions called off main thread" }
        val items = profile.mainItems.get(owner) as MutableList<Any>
        val overflow = profile.overflowItems.get(owner) as MutableList<Any>
        // Only our own identities, including when native code reuses the lists.
        items.removeAll { tags.containsKey(it) }
        overflow.removeAll { tags.containsKey(it) }
        if (!health.ready) return
        val snapshot = config.read() ?: return
        val context = profile.context.get(owner) as Context
        val text = ModuleText(context, modulePath)
        val additions = RebootTarget.entries.filter(snapshot::enabled).mapNotNull { target ->
            val injected = Injected(owner, context, target)
            // The verified native grid/overflow do not consistently apply Action.isEnabled().
            // Hide unavailable additions, keeping every stock item's rendering and listeners intact.
            if (gate(injected, refresh = false) != null) return@mapNotNull null
            profile.restartConstructor.newInstance(owner).also { action ->
                profile.messageId.setInt(action, 0)
                profile.message.set(action, text.get(label(target)))
                tags[action] = injected
            }
        }
        // Native placement keeps the existing grid, overflow, animation and view factory.
        appendTransaction(items, overflow, additions) { profile.addItem.invoke(owner, it) }
        if (additions.isNotEmpty()) HookLog.info("Added ${additions.size} native actions, revision ${snapshot.revision}")
    }

    private fun maybeConfirmStock(param: XC_MethodHook.MethodHookParam, powerOff: Boolean) {
        try {
            val flags = config.read() ?: return // fail open: keep native restart/power off
            if (if (powerOff) !flags.confirmPowerOff else !flags.confirmNormalReboot) return
            if (Looper.myLooper() != Looper.getMainLooper()) return // do not change OEM off-main behavior
            if (confirmation?.isShowing == true) {
                param.result = null // suppress rapid duplicate presses
                return
            }
            val owner = XposedHelpers.getObjectField(param.thisObject, "this\$0")
            if (!profile.dialogClass.isInstance(owner)) return
            val ctx = profile.context.get(owner) as? Context ?: return
            val text = ModuleText(ctx, modulePath)
            val pressedAt = SystemClock.elapsedRealtime()
            val nativeMethod = param.method as Method
            val nativeAction = param.thisObject
            val dialog = profile.confirmationConstructor.newInstance(ctx) as AlertDialog
            dialog.setTitle(text.get(if (powerOff) R.string.power_off_title else R.string.normal_reboot_title))
            dialog.setMessage(text.get(if (powerOff) R.string.power_off_message else R.string.normal_reboot_message))
            dialog.setButton(DialogInterface.BUTTON_NEGATIVE, text.get(R.string.cancel)) { _, _ -> }
            dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                text.get(if (powerOff) R.string.power_off_action else R.string.reboot)) { _, _ ->
                if (SystemClock.elapsedRealtime() - pressedAt > 30_000) {
                    Toast.makeText(ctx, text.get(R.string.request_expired), Toast.LENGTH_SHORT).show()
                } else {
                    val stillEnabled = config.read()?.let {
                        if (powerOff) it.confirmPowerOff else it.confirmNormalReboot
                    } == true
                    if (!stillEnabled) return@setButton
                    runCatching {
                        // Do not call PowerManager or bypass Nothing's stock security handler.
                        XposedBridge.invokeOriginalMethod(nativeMethod, nativeAction, emptyArray<Any?>())
                    }.onFailure { HookLog.error("Stock power action failed", it) }
                }
            }
            dialog.setOnDismissListener { if (confirmation === dialog) confirmation = null }
            dialog.show()
            confirmation = dialog
            param.result = null
        } catch (error: Throwable) {
            HookLog.error("Stock confirmation unavailable; allowing unmodified OEM action", error)
            // Keep the stock action functional when the OEM contract changes.
        }
    }

    private fun gate(action: Injected, refresh: Boolean = true): Denial? {
        if (!health.ready) return Denial.UNAVAILABLE
        val snapshot = if (refresh) config.read() else config.cached
        val context = action.context
        val keyguard = context.getSystemService(KeyguardManager::class.java)
            ?: return Denial.UNAVAILABLE
        val users = context.getSystemService(UserManager::class.java) ?: return Denial.UNAVAILABLE
        val power = context.getSystemService(PowerManager::class.java) ?: return Denial.UNAVAILABLE
        val tracker = profile.userTracker.get(action.owner) ?: return Denial.UNAVAILABLE
        val restriction = when (action.target) {
            RebootTarget.RECOVERY -> UserManager.DISALLOW_FACTORY_RESET
            RebootTarget.BOOTLOADER, RebootTarget.SYSTEM_UI -> UserManager.DISALLOW_DEBUGGING_FEATURES
        }
        return denial(GateState(
            readable = snapshot != null,
            enabled = snapshot?.enabled(action.target) == true,
            deviceLocked = keyguard.isDeviceLocked,
            keyguardShowing = keyguard.isKeyguardLocked,
            interactive = power.isInteractive,
            userUnlocked = users.isUserUnlocked,
            stockVerificationRequired = profile.verify.invoke(action.owner) as Boolean,
            currentUserId = profile.userId.invoke(tracker) as Int,
            provisioned = profile.provisioned.getBoolean(action.owner),
            // Verified Nothing helpers; an unfinished user setup is blocked even outside CIT.
            userSetupComplete = profile.userSetup.invoke(action.owner, profile.userHandle.invoke(tracker)) as Boolean,
            ageTest = profile.ageTest.invoke(action.owner) as Boolean,
            restricted = users.hasUserRestriction(restriction),
            monkey = ActivityManager.isUserAMonkey(),
        ))
    }

    private fun onPress(action: Injected) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { try { onPress(action) } catch (error: Throwable) { HookLog.error("Action failed", error) } }
            return
        }
        if (rebooting.get() || confirmation?.isShowing == true) return
        gate(action)?.let { showDenial(action, it); return }
        missingPowerPermission(action)?.let { showMissingPowerPermission(action, it); return }
        val text = ModuleText(action.context, modulePath)
        val shownAt = SystemClock.elapsedRealtime()
        val dialog = profile.confirmationConstructor.newInstance(action.context) as AlertDialog
        val restartUi = action.target == RebootTarget.SYSTEM_UI
        dialog.setTitle(if (restartUi) text.get(R.string.restart_system_ui)
            else text.get(R.string.reboot_to, text.get(label(action.target))))
        dialog.setMessage(text.get(when (action.target) {
            RebootTarget.RECOVERY -> R.string.confirm_recovery
            RebootTarget.BOOTLOADER -> R.string.confirm_bootloader
            RebootTarget.SYSTEM_UI -> R.string.confirm_system_ui
        }))
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, text.get(R.string.cancel)) { _, _ -> }
        dialog.setButton(DialogInterface.BUTTON_POSITIVE, text.get(if (restartUi) R.string.restart else R.string.reboot)) { _, _ ->
            try {
                if (SystemClock.elapsedRealtime() - shownAt > 30_000) {
                    toast(action, R.string.request_expired)
                } else {
                    gate(action)?.let { showDenial(action, it); return@setButton }
                    executeConfirmed(action)
                }
            } catch (error: Throwable) {
                HookLog.error("Confirmation failed; reboot not dispatched", error)
                toast(action, R.string.action_unavailable)
            }
        }
        dialog.setOnDismissListener { if (confirmation === dialog) confirmation = null }
        confirmation = dialog
        // SystemUIDialog supplies native theme/window policy and dismisses on screen-off.
        dialog.show()
    }

    /** Require the OS-granted permissions of the *injected SystemUI process*, never app root. */
    private fun missingPowerPermission(action: Injected): MissingPowerPermission? {
        if (action.target == RebootTarget.SYSTEM_UI) return null
        val ctx = action.context
        return missingRebootPermission(
            action.target,
            canReboot = ctx.checkSelfPermission(Manifest.permission.REBOOT) == PackageManager.PERMISSION_GRANTED,
            canRecover = action.target != RebootTarget.RECOVERY ||
                ctx.checkSelfPermission("android.permission.RECOVERY") == PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun showMissingPowerPermission(action: Injected, permission: MissingPowerPermission) {
        HookLog.info("Blocked ${action.target.reason}: SystemUI missing ${permission.name} permission; no root fallback")
        toast(action, when (permission) {
            MissingPowerPermission.REBOOT -> R.string.missing_reboot_permission
            MissingPowerPermission.RECOVERY -> R.string.missing_recovery_permission
        })
    }

    @SuppressLint("MissingPermission") // Called within SystemUI, with both system permissions checked at runtime.
    private fun executeConfirmed(action: Injected) {
        if (!rebooting.compareAndSet(false, true)) return
        if (action.target == RebootTarget.SYSTEM_UI) {
            try {
                check(Application.getProcessName() == Protocol.SYSTEM_UI)
                HookLog.info("User confirmed System UI restart")
                Process.killProcess(Process.myPid())
            } finally {
                rebooting.set(false)
            }
            return
        }
        val deadline = SystemClock.elapsedRealtime() + 35_000
        HookLog.info("User confirmed ${action.target.reason}")
        worker.execute {
            try {
                // No root shell, no 'svc' and no retry through an elevated process.
                check(Application.getProcessName() == Protocol.SYSTEM_UI) { "Not running in SystemUI" }
                missingPowerPermission(action)?.let {
                    showMissingPowerPermission(action, it)
                    return@execute
                }
                if (!authorizeOnMain(action, deadline)) {
                    toast(action, R.string.action_unavailable)
                    return@execute
                }
                // Recheck after authorization. Nothing else may dispatch a reboot on failure.
                missingPowerPermission(action)?.let {
                    showMissingPowerPermission(action, it)
                    return@execute
                }
                val power = action.context.getSystemService(PowerManager::class.java)
                    ?: error("PowerManager unavailable")
                HookLog.info("Dispatching ${action.target.reason} through SystemUI PowerManager")
                power.reboot(action.target.reason)
                // A successful reboot never returns. Never retry after a Binder call.
                toast(action, R.string.reboot_timeout)
            } catch (error: SecurityException) {
                HookLog.error("SystemUI lacks permission to reboot; no su fallback", error)
                toast(action, R.string.reboot_permission_denied)
            } catch (error: Throwable) {
                HookLog.error("SystemUI reboot failed; no retry", error)
                toast(action, R.string.reboot_failed)
            } finally { rebooting.set(false) }
        }
    }

    private fun authorizeOnMain(action: Injected, deadline: Long): Boolean {
        val task = FutureTask {
            try {
                if (SystemClock.elapsedRealtime() > deadline) {
                    return@FutureTask false
                }
                val blocked = gate(action)
                if (blocked != null) {
                    showDenial(action, blocked)
                }
                blocked == null && SystemClock.elapsedRealtime() <= deadline
            } catch (error: Throwable) {
                HookLog.error("Final authorization failed; reboot cancelled", error)
                false
            }
        }
        main.post(task)
        return try { task.get(2, TimeUnit.SECONDS) }
        catch (_: Exception) { task.cancel(false); false }
    }

    private fun showDenial(action: Injected, reason: Denial) {
        HookLog.info("Blocked ${action.target.reason}: $reason")
        toast(action, when (reason) {
            Denial.LOCKED -> R.string.unlock_required
            Denial.SETTINGS -> R.string.settings_unavailable
            else -> R.string.action_unavailable
        })
    }

    private fun toast(action: Injected, message: Int) {
        main.post {
            try { Toast.makeText(action.context, ModuleText(action.context, modulePath).get(message), Toast.LENGTH_LONG).show() }
            catch (error: Throwable) { HookLog.error("Could not display result", error) }
        }
    }

    private fun label(target: RebootTarget): Int = when (target) {
        RebootTarget.RECOVERY -> R.string.recovery
        RebootTarget.BOOTLOADER -> R.string.bootloader
        RebootTarget.SYSTEM_UI -> R.string.restart_system_ui
    }
}
