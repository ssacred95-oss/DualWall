package com.dualwall.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/** Draws a decoded picture over the whole canvas. Shared by the wallpaper and its tests. */
object WallpaperRenderer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    /** Darkens the blurred background a little so the real picture stands out. */
    private val shade = Paint().apply { color = Color.argb(70, 0, 0, 0) }

    /**
     * @param bitmap what [WallpaperStore.decodeFramed] returned for this screen
     * @param framing [Framing.Fit]: whole picture centred, the rest filled by [background];
     *   [Framing.Stretch]: whole picture over the whole screen; [Framing.Crop]: already cut to the screen
     * @param background blurred tiny copy ([WallpaperStore.blurSource]), stretched over the screen
     */
    fun draw(
        canvas: Canvas,
        bitmap: Bitmap?,
        width: Int,
        height: Int,
        framing: Framing = Framing.Crop(NormCrop.FULL),
        background: Bitmap? = null,
    ) {
        canvas.drawColor(Color.BLACK)
        if (bitmap == null || width <= 0 || height <= 0) return
        if (framing == Framing.Stretch) {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
        } else if (framing == Framing.Fit) {
            if (background != null) {
                val bg = CropMath.centerCrop(background.width, background.height, width, height)
                canvas.drawBitmap(background, bg.toRect(), Rect(0, 0, width, height), paint)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            }
            canvas.drawBitmap(bitmap, null, CropMath.fit(bitmap.width, bitmap.height, width, height).toRect(), paint)
        } else {
            // Already cut to the screen's aspect; centre-crop only absorbs rounding.
            val crop = CropMath.centerCrop(bitmap.width, bitmap.height, width, height)
            canvas.drawBitmap(bitmap, crop.toRect(), Rect(0, 0, width, height), paint)
        }
    }

    private fun Box.toRect() = Rect(left, top, right, bottom)
}
