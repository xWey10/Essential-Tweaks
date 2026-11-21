// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

object FingerprintImage {
    fun decode(encoded: String): Bitmap? {
        if (encoded.isEmpty() || encoded.length > TweakSettings.ICON_LIMIT) return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val edge = TweakSettings.ICON_EDGE
        if (bounds.outWidth !in 1..edge || bounds.outHeight !in 1..edge ||
            bounds.outMimeType != "image/png") return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
