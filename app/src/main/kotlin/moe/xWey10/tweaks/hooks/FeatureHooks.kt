// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member

/** Resolve the complete OEM contract before installing hooks and roll back partial installs. */
internal class FeatureHooks(private val name: String) {
    private val installed = mutableListOf<XC_MethodHook.Unhook>()
    private var loggedFailure = false

    fun install(block: FeatureHooks.() -> Unit) {
        try {
            block()
            HookLog.info("$name hooks installed")
        } catch (error: Throwable) {
            installed.asReversed().forEach { runCatching { it.unhook() } }
            HookLog.error("$name unavailable", error)
        }
    }

    fun add(member: Member, before: Boolean = false, callback: (XC_MethodHook.MethodHookParam) -> Unit) {
        installed += XposedBridge.hookMethod(member, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (before) invokeSafely(param)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (!before && !param.hasThrowable()) invokeSafely(param)
            }

            private fun invokeSafely(param: MethodHookParam) {
                try {
                    callback(param)
                } catch (error: Throwable) {
                    if (!loggedFailure) HookLog.error("$name callback failed", error)
                    loggedFailure = true
                }
            }
        })
    }
}
