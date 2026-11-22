// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.createBitmap
import moe.xWey10.tweaks.core.TweakSettings
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal fun loadFingerprintImage(resolver: ContentResolver, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val edge = maxOf(info.size.width, info.size.height)
        val scale = minOf(1.0, 1024.0 / edge)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setTargetSize(
            (info.size.width * scale).roundToInt().coerceAtLeast(1),
            (info.size.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

internal fun encodeCircle(bitmap: Bitmap, x: Float, y: Float, radius: Float): String {
    val edge = TweakSettings.ICON_EDGE
    val center = edge / 2f
    val scale = center / radius
    val cropped = createBitmap(edge, edge)
    val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    shader.setLocalMatrix(Matrix().apply {
        setScale(scale, scale)
        postTranslate(center - x * scale, center - y * scale)
    })
    Canvas(cropped).drawCircle(center, center, center, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = shader
    })
    return ByteArrayOutputStream().use { output ->
        check(cropped.compress(Bitmap.CompressFormat.PNG, 100, output))
        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP).also {
            check(it.length <= TweakSettings.ICON_LIMIT)
        }
    }
}
