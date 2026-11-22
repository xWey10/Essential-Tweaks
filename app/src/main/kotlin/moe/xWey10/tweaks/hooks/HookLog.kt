// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.hooks

import de.robv.android.xposed.XposedBridge

object HookLog {
    fun info(message: String) {
        try { XposedBridge.log("EssentialTweaks: $message") } catch (_: Throwable) { }
    }

    fun error(message: String, error: Throwable) {
        info("$message: ${error.javaClass.simpleName}: ${error.message}")
        try { XposedBridge.log(error) } catch (_: Throwable) { }
    }
}
