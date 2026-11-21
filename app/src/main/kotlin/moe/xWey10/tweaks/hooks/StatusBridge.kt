// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.hooks

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import moe.xWey10.tweaks.core.Protocol

/** Diagnostic data only. This receiver cannot change settings or perform any system action. */
class StatusBridge(
    private val health: HookHealth,
    private val config: ConfigReader,
    private val tweaks: TweakReader?,
) {
    fun register(context: Context) {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    if (intent.action != Protocol.QUERY) return
                    val nonce = intent.getStringExtra("nonce") ?: return
                    if (nonce.length !in 16..80) return
                    tweaks?.refresh(context)
                    val snapshot = config.read()
                    val response = Intent(Protocol.RESPONSE)
                        .putExtra("nonce", nonce)
                        .putExtra("ready", health.ready)
                        .putExtra("detail", health.detail)
                        .putExtra("readable", snapshot != null)
                        .putExtra("revision", snapshot?.revision ?: -1)
                    // Dynamic receiver, permission protected; no package-visibility bypass needed.
                    context.sendBroadcast(response, Protocol.QUERY_PERMISSION,
                        BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                } catch (error: Throwable) { HookLog.error("Status query failed", error) }
            }
        }, IntentFilter(Protocol.QUERY), Protocol.QUERY_PERMISSION, null, Context.RECEIVER_EXPORTED)
        HookLog.info("Read-only status bridge registered")
    }
}
