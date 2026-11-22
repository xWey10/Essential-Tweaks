// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import moe.xWey10.tweaks.R

// The editor constructs this view directly with its decoded image.
@SuppressLint("ViewConstructor")
internal class CircleCropView(context: Context, private val bitmap: Bitmap) : View(context) {
    private var x = bitmap.width / 2f
    private var y = bitmap.height / 2f
    private val maxRadius = minOf(bitmap.width, bitmap.height) / 2f
    private var radius = maxRadius * 0.8f
    private val image = RectF()
    private val mask = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    init {
        isFocusable = true
        contentDescription = context.getString(R.string.crop_hint)
    }

    fun setDiameter(percent: Int) {
        radius = maxRadius * percent.coerceIn(20, 100) / 100f
        moveTo(x, y)
    }

    fun encodedCircle() = encodeCircle(bitmap, x, y, radius)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        if (scale <= 0f) return
        val left = (width - bitmap.width * scale) / 2
        val top = (height - bitmap.height * scale) / 2
        image.set(left, top, width - left, height - top)
        val cx = left + x * scale
        val cy = top + y * scale
        canvas.drawBitmap(bitmap, null, image, bitmapPaint)
        mask.rewind()
        mask.fillType = Path.FillType.EVEN_ODD
        mask.addRect(image, Path.Direction.CW)
        mask.addCircle(cx, cy, radius * scale, Path.Direction.CW)
        canvas.drawPath(mask, shade)
        canvas.drawCircle(cx, cy, radius * scale, outline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (image.isEmpty) return false
                moveTo((event.x - image.left) / image.width() * bitmap.width,
                    (event.y - image.top) / image.height() * bitmap.height)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = maxOf(1f, maxRadius / 20)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveTo(x - step, y)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo(x + step, y)
            KeyEvent.KEYCODE_DPAD_UP -> moveTo(x, y - step)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveTo(x, y + step)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun moveTo(newX: Float, newY: Float) {
        x = newX.coerceIn(radius, bitmap.width - radius)
        y = newY.coerceIn(radius, bitmap.height - radius)
        invalidate()
    }
}
