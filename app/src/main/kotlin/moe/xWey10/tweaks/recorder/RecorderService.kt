// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10
package moe.xWey10.tweaks.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import moe.xWey10.tweaks.core.LocalTweaks
import moe.xWey10.tweaks.core.RecorderDestination
import moe.xWey10.tweaks.core.RecorderFormat
import moe.xWey10.tweaks.core.RecorderNaming
import moe.xWey10.tweaks.core.RecorderQuality
import moe.xWey10.tweaks.core.TweakSettings

/** User-initiated recording controlled by a SystemUI-only explicit service intent. */
class RecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var destination: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var mediaStoreDestination = false
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
            ACTION_TOGGLE -> {
                if (started || recorder != null) {
                    stopRecording()
                    stopSelf()
                } else {
                    runCatching { beginRecording() }.onFailure { error ->
                        Log.e(TAG, "Unable to start microphone recording", error)
                        stopRecording(delete = true)
                        notifyUser("Audio recording failed. Check microphone permission and destination.")
                        stopSelf()
                    }
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission not granted"
        }
        val options = LocalTweaks(this).current
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

        val fileName = RecorderNaming.fileBase(options.recorderNameTemplate) + extension(options.recorderFormat)
        val uri = createDestination(options, fileName)
        destination = uri
        val fd = checkNotNull(contentResolver.openFileDescriptor(uri, "w"))
        descriptor = fd
        val media = MediaRecorder(this)
        recorder = media
        media.setAudioSource(MediaRecorder.AudioSource.MIC)
        when (options.recorderFormat) {
            RecorderFormat.AAC_M4A -> {
                media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                media.setAudioEncodingBitRate(when (options.recorderQuality) {
                    RecorderQuality.LOW -> 64_000
                    RecorderQuality.NORMAL -> 128_000
                    RecorderQuality.HIGH -> 192_000
                })
                media.setAudioSamplingRate(44_100)
            }
            RecorderFormat.OPUS_OGG -> {
                media.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                media.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                media.setAudioEncodingBitRate(when (options.recorderQuality) {
                    RecorderQuality.LOW -> 48_000
                    RecorderQuality.NORMAL -> 96_000
                    RecorderQuality.HIGH -> 128_000
                })
                media.setAudioSamplingRate(48_000)
            }
            RecorderFormat.AMR_3GP -> {
                media.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                media.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                media.setAudioEncodingBitRate(12_200)
                media.setAudioSamplingRate(8_000)
            }
        }
        media.setOutputFile(fd.fileDescriptor)
        media.prepare()
        media.start()
        started = true
    }

    private fun createDestination(options: TweakSettings, fileName: String): Uri {
        if (options.recorderDestination == RecorderDestination.CUSTOM) {
            val tree = Uri.parse(options.recorderTreeUri)
            check(options.recorderTreeUri.isNotBlank() && contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isWritePermission
            }) { "Recording folder permission is missing" }
            mediaStoreDestination = false
            val directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val created = DocumentsContract.createDocument(contentResolver, directory,
                mimeType(options.recorderFormat), fileName)
            return checkNotNull(created) { "Cannot create a recording in the selected folder" }
        }
        val folder = if (options.recorderDestination == RecorderDestination.RECORDINGS) {
            "Recordings/EssentialTweaks"
        } else "Music/EssentialTweaks"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType(options.recorderFormat))
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        mediaStoreDestination = true
        return checkNotNull(contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) {
            "Cannot create a MediaStore audio item"
        }
    }

    private fun stopRecording(delete: Boolean = false) {
        var invalid = delete || !started
        runCatching { if (started) recorder?.stop() }.onFailure { invalid = true }
        runCatching { recorder?.release() }
        recorder = null
        started = false
        runCatching { descriptor?.close() }
        descriptor = null
        destination?.let { uri ->
            runCatching {
                if (invalid) {
                    if (mediaStoreDestination) contentResolver.delete(uri, null, null)
                    else DocumentsContract.deleteDocument(contentResolver, uri)
                } else if (mediaStoreDestination) {
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }, null, null)
                }
            }.onFailure { Log.e(TAG, "Unable to finalize the recording", it) }
        }
        destination = null
        mediaStoreDestination = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (!delete && invalid) notifyUser("Recording was too short or could not be saved")
    }

    private fun notifyUser(message: String) = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        if (recorder != null || destination != null) stopRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "moe.xWey10.tweaks.RECORDER_TOGGLE"
        const val ACTION_STOP = "moe.xWey10.tweaks.RECORDER_STOP"
        private const val CHANNEL = "essential_audio_recording"
        private const val NOTIFICATION_ID = 4721
        private const val TAG = "EssentialTweaksRecorder"

        private fun extension(format: RecorderFormat): String = when (format) {
            RecorderFormat.AAC_M4A -> ".m4a"
            RecorderFormat.OPUS_OGG -> ".ogg"
            RecorderFormat.AMR_3GP -> ".3gp"
        }
        private fun mimeType(format: RecorderFormat): String = when (format) {
            RecorderFormat.AAC_M4A -> "audio/mp4"
            RecorderFormat.OPUS_OGG -> "audio/ogg"
            RecorderFormat.AMR_3GP -> "audio/3gpp"
        }
    }
}
