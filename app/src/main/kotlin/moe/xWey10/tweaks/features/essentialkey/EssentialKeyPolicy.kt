// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.features.essentialkey

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import moe.xWey10.tweaks.BuildConfig
import moe.xWey10.tweaks.core.Protocol
import moe.xWey10.tweaks.core.TweakSettings
import moe.xWey10.tweaks.core.EssentialTarget
import moe.xWey10.tweaks.hooks.FeatureHooks
import moe.xWey10.tweaks.hooks.HookLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal object EssentialKeyPolicy {
    private const val SCAN_CODE = 250
    private const val TAP_TIMEOUT_MS = 320L

    fun install(loader: ClassLoader) = FeatureHooks("Essential Key input").install {
        if (listOf(Build.MANUFACTURER, Build.BRAND).none {
                it.startsWith("Nothing", ignoreCase = true) || it.equals("CMF", ignoreCase = true)
            }) return@install
        val policy = XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", loader)
        val policyInterface = XposedHelpers.findClass("android.view.WindowManagerPolicyConstants", loader)
        val interactive = XposedHelpers.findField(policyInterface, "FLAG_INTERACTIVE").getInt(null)
        val injected = XposedHelpers.findField(policyInterface, "FLAG_INJECTED").getInt(null)
        val currentUser = XposedHelpers.findField(policy, "mCurrentUserId")
        val locked = policy.getDeclaredMethod("isKeyguardLocked")
        val contextField = XposedHelpers.findField(policy, "mContext")
        val ready = policy.getDeclaredMethod("systemReady")
        val intercept = policy.getDeclaredMethod(
            "interceptKeyBeforeQueueing", KeyEvent::class.java, Int::class.javaPrimitiveType,
        )
        check(intercept.returnType == Int::class.javaPrimitiveType)
        val currentSession = AtomicReference<Session>()
        add(ready) { param ->
            val instance = param.thisObject
            val context = contextField.get(instance) as Context
            val session = Session(context) {
                currentUser.getInt(instance) == 0 && locked.invoke(instance) == false
            }
            session.register()
            currentSession.set(session)
        }
        add(intercept, before = true) { param ->
            val session = currentSession.get() ?: return@add
            val event = param.args[0] as KeyEvent
            val flags = param.args[1] as Int
            if (event.scanCode != SCAN_CODE || event.keyCode != KeyEvent.KEYCODE_UNKNOWN ||
                flags and injected != 0 || event.deviceId < 0
            ) return@add
            if (!session.isCapturing(event.deviceId)) {
                val device = event.device ?: return@add
                if (device.isExternal || device.isVirtual) return@add
            }
            if (session.handle(event, flags and interactive != 0)) param.result = 0
        }
    }

    private class Session(private val context: Context, private val isPrimaryUserUnlocked: () -> Boolean) {
        private val main = Handler(Looper.getMainLooper())
        private val power = context.getSystemService(PowerManager::class.java)
        private val worker = Executors.newSingleThreadExecutor()
        private var prefs: XSharedPreferences? = null
        private var bindings = emptyMap<EssentialKeyGesture, EssentialKeyCapture>()
        private var targets = emptyMap<EssentialKeyGesture, EssentialTarget>()
        private var capturedDevice: Int? = null
        private var capturedDownTime = 0L
        private var actionFailureLogged = false
        private val gestures = EssentialKeyGestures(TAP_TIMEOUT_MS, ViewConfiguration.getLongPressTimeout().toLong()) { gesture ->
            val action = bindings[gesture]
            if (action != null) main.post { perform(gesture, action) }
        }
        private val timeout = Runnable {
            synchronized(this) {
                gestures.advance(SystemClock.uptimeMillis())
                schedule()
            }
        }

        fun register() {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refresh()
            }, IntentFilter(Protocol.REFRESH_SCREENSHOTS), Protocol.QUERY_PERMISSION, null,
                Context.RECEIVER_EXPORTED)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refresh()
            }, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
            refresh()
        }

        private fun refresh() {
            worker.execute {
                val next = runCatching {
                    val file = prefs ?: XSharedPreferences(BuildConfig.APPLICATION_ID, TweakSettings.FILE)
                        .also { prefs = it }
                    check(file.file.canRead())
                    file.reload()
                    val snapshot = requireNotNull(file.getString(TweakSettings.SNAPSHOT, null))
                    TweakSettings.decode(snapshot).let { it.essentialKey to it.essentialTargets }
                }.getOrElse {
                    prefs = null
                    emptyMap<EssentialKeyGesture, EssentialKeyCapture>() to
                        emptyMap<EssentialKeyGesture, EssentialTarget>()
                }
                synchronized(this) {
                    if (next.first == bindings && next.second == targets) return@execute
                    bindings = next.first
                    targets = next.second
                    gestures.reset()
                    schedule()
                }
            }
        }

        @Synchronized
        fun isCapturing(device: Int) = capturedDevice == device

        @Synchronized
        fun handle(event: KeyEvent, interactive: Boolean): Boolean {
            val device = event.deviceId
            val samePress = capturedDevice == device && capturedDownTime == event.downTime
            if (event.action == KeyEvent.ACTION_UP) {
                if (!samePress) return false
                capturedDevice = null
                gestures.release(event.eventTime, event.isCanceled || !interactive)
            } else if (event.action == KeyEvent.ACTION_DOWN) {
                if (samePress) return true
                if (capturedDevice == device) {
                    capturedDevice = null
                    gestures.reset()
                    schedule()
                }
                if (capturedDevice != null || event.repeatCount != 0 || bindings.isEmpty() ||
                    !interactive || !isPrimaryUserUnlocked()
                ) return false
                capturedDevice = device
                capturedDownTime = event.downTime
                gestures.press(event.eventTime)
            } else {
                return false
            }
            schedule()
            return true
        }

        private fun schedule() {
            main.removeCallbacks(timeout)
            gestures.deadline?.let { main.postAtTime(timeout, it) }
        }

        private fun perform(gesture: EssentialKeyGesture, action: EssentialKeyCapture) {
            runCatching {
                if (synchronized(this) { bindings[gesture] } != action) return
                if (!isPrimaryUserUnlocked() || power?.isInteractive != true) return
                val target = synchronized(this) { targets[gesture] }
                if (action in setOf(EssentialKeyCapture.LAUNCH_APP, EssentialKeyCapture.LAUNCH_ACTIVITY) &&
                    target == null) return
                context.sendBroadcast(
                    Intent(Protocol.ESSENTIAL_KEY_CAPTURE).setPackage(Protocol.SYSTEM_UI)
                        .putExtra(Protocol.ESSENTIAL_KEY_ACTION, action.name)
                        .putExtra(Protocol.ESSENTIAL_KEY_PACKAGE, target?.packageName.orEmpty())
                        .putExtra(Protocol.ESSENTIAL_KEY_CLASS, target?.className.orEmpty()),
                    null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                )
            }.onFailure {
                if (!actionFailureLogged) HookLog.error("Essential Key could not dispatch a capture", it)
                actionFailureLogged = true
            }
        }
    }
}
