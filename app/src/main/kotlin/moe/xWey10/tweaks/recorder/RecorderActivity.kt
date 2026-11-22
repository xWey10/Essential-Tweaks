// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10
package moe.xWey10.tweaks.recorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.view.WindowManager

/** Permission gate and brief foreground handoff for a microphone foreground service. */
class RecorderActivity : Activity() {
    private val requestCode = 901
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private val status = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RecorderService.ACTION_FAILED) {
                Toast.makeText(context, "Audio recording failed", Toast.LENGTH_LONG).show()
            }
            if (intent.action == RecorderService.ACTION_STARTED || intent.action == RecorderService.ACTION_FAILED) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Translucent, windowless handoff: Android requires a foreground Activity
        // when starting a microphone foreground service (while-in-use permission).
        // Never show a recorder interface or steal touches; the permission dialog
        // on first use and the mandatory recording indicator remain OS-owned.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        if (RecorderService.isRecording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startRecording() {
        registerReceiver(status, IntentFilter().apply {
            addAction(RecorderService.ACTION_STARTED)
            addAction(RecorderService.ACTION_FAILED)
        }, Context.RECEIVER_NOT_EXPORTED)
        registered = true
        runCatching {
            startForegroundService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START))
        }.onFailure {
            Toast.makeText(this, "Could not start audio recording", Toast.LENGTH_LONG).show()
            finish()
        }
        main.postDelayed({ if (!isFinishing) finish() }, 7000L)
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(status)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
