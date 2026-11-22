// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10
package moe.xWey10.tweaks.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Microphone foreground service. Never captures audio without a user-triggered action. */
class RecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var destination: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); stopSelf() }
            ACTION_START -> {
                if (!isRecording) {
                    runCatching { beginRecording() }.onFailure {
                        stopRecording(delete = true)
                        sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName))
                        stopSelf()
                    }
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Voice recording",
            NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Essential Tweaks is recording audio")
            .setContentText("Tap Stop to save your recording")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Essential-$date.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/EssentialTweaks")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        destination = uri
        val fd = checkNotNull(contentResolver.openFileDescriptor(uri, "w"))
        descriptor = fd
        val media = MediaRecorder(this)
        recorder = media
        media.setAudioSource(MediaRecorder.AudioSource.MIC)
        media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        media.setAudioEncodingBitRate(128_000)
        media.setAudioSamplingRate(44_100)
        media.setOutputFile(fd.fileDescriptor)
        media.prepare()
        media.start()
        started = true
        isRecording = true
        sendBroadcast(Intent(ACTION_STARTED).setPackage(packageName))
    }

    private fun stopRecording(delete: Boolean = false) {
        var invalid = delete || !started
        runCatching { if (started) recorder?.stop() }.onFailure { invalid = true }
        runCatching { recorder?.release() }
        recorder = null
        started = false
        isRecording = false
        runCatching { descriptor?.close() }
        descriptor = null
        destination?.let { uri ->
            runCatching {
                if (invalid) contentResolver.delete(uri, null, null)
                else contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }, null, null)
            }
        }
        destination = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        if (recorder != null || destination != null) stopRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "moe.xWey10.tweaks.RECORDER_START"
        const val ACTION_STOP = "moe.xWey10.tweaks.RECORDER_STOP"
        const val ACTION_STARTED = "moe.xWey10.tweaks.RECORDER_STARTED"
        const val ACTION_FAILED = "moe.xWey10.tweaks.RECORDER_FAILED"
        private const val CHANNEL = "essential_audio_recording"
        private const val NOTIFICATION_ID = 4721
        @Volatile var isRecording: Boolean = false
            private set
    }
}
