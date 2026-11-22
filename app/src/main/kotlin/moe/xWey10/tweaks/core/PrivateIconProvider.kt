// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import androidx.core.net.toUri
import java.security.MessageDigest
import java.util.HexFormat

/** Transfers the resized image only to the SystemUI UID, never through shared preferences. */
class PrivateIconProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = context ?: return null
        checkSystemUiCaller(app)
        if (method != READ_ICON || arg?.length != 64) return null
        val settings = LocalTweaks(app).current
        val image = settings.icon
        if (!settings.iconEnabled || image.isEmpty() || digest(image) != arg) return null
        return Bundle().apply { putString(TweakSettings.SNAPSHOT, image) }
    }

    private fun checkSystemUiCaller(app: Context) {
        app.enforceCallingPermission(Protocol.PLATFORM_SENDER_PERMISSION, null)
        val uid = app.packageManager.getPackageUid(Protocol.SYSTEM_UI, 0)
        if (Binder.getCallingUid() != uid || callingPackage != Protocol.SYSTEM_UI) {
            throw SecurityException("Only SystemUI can read the fingerprint icon")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?) = 0

    companion object {
        const val READ_ICON = "read_fingerprint_icon"
        val URI: Uri = "content://moe.xWey10.tweaks.private_icon".toUri()

        fun digest(encoded: String): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        )
    }
}
