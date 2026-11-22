// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.hooks

class HookHealth {
    @Volatile var ready: Boolean = false
        private set
    @Volatile var detail: String = "Not initialized"
        private set

    fun installed() { detail = "Nothing GlobalActionsDialogLite profile installed"; ready = true }
    fun unavailable(message: String) { ready = false; detail = message.take(240) }
}
