package com.dualwall.app

import kotlin.math.max
import kotlin.math.roundToInt

/** Which of the two pictures a screen of the given size should show. */
enum class Orientation(val fileName: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape");

    val other: Orientation get() = if (this == PORTRAIT) LANDSCAPE else PORTRAIT

    companion object {
        /** A square screen counts as portrait. */
        fun of(width: Int, height: Int): Orientation = if (width > height) LANDSCAPE else PORTRAIT
    }
}

/** Plain rectangle, so this file stays free of Android classes and is unit-tested on the JVM. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

data class Size(val width: Int, val height: Int)

/**
 * User-chosen frame inside a picture, as fractions of the picture's (upright) width and height,
 * so it stays valid at any decode resolution. [FULL] means "no choice made": centre-crop.
 */
data class NormCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun encode() = "$left,$top,$right,$bottom"

    companion object {
        val FULL = NormCrop(0f, 0f, 1f, 1f)

        fun decode(s: String?): NormCrop? = runCatching {
            val p = s!!.trim().split(',').map { it.toFloat() }
            NormCrop(p[0], p[1], p[2], p[3]).takeIf {
                it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f && it.width > 0f && it.height > 0f
            }
        }.getOrNull()
    }
}

/** How a picture is placed on the screen. */
sealed interface Framing {
    /** Whole picture visible, centred; the leftover bars show a blurred copy of it. */
    data object Fit : Framing

    /** Whole picture squeezed to fill the screen exactly (slight distortion, no bars, nothing cut). */
    data object Stretch : Framing

    /** Fills the screen with the user's chosen part of the picture ([NormCrop.FULL] = centred). */
    data class Crop(val rect: NormCrop) : Framing

    fun encode(): String = when (this) {
        Fit -> "fit"
        Stretch -> "stretch"
        is Crop -> rect.encode()
    }

    companion object {
        fun decode(s: String?): Framing? = when (s?.trim()) {
            "fit" -> Fit
            "stretch" -> Stretch
            else -> NormCrop.decode(s)?.let { Crop(it) }
        }

        /** Both orientations fill the screen, centred until the user picks a frame. */
        @Suppress("UNUSED_PARAMETER")
        fun default(o: Orientation): Framing = Crop(NormCrop.FULL)
    }
}

object CropMath {

    /** Where a whole [srcW]x[srcH] picture lands when fitted, centred, inside a [dstW]x[dstH] screen. */
    fun fit(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0)
        return if (srcW.toLong() * dstH > dstW.toLong() * srcH) {
            // Wider than the screen: full width, bars above and below.
            val h = (dstW.toLong() * srcH / srcW).toInt()
            val top = (dstH - h) / 2
            Box(0, top, dstW, top + h)
        } else {
            val w = (dstH.toLong() * srcW / srcH).toInt()
            val left = (dstW - w) / 2
            Box(left, 0, left + w, dstH)
        }
    }

    /**
     * The frame to show on a [dstW]x[dstH] screen: the user's [crop] (in [srcW]x[srcH] pixels),
     * adjusted to exactly the screen's aspect ratio around the same centre and kept inside the image.
     *
     * The crop is normally made for this very aspect, so the adjustment only absorbs rounding,
     * split-screen or a different display. Without a crop this is plain [centerCrop].
     */
    fun frame(srcW: Int, srcH: Int, dstW: Int, dstH: Int, crop: NormCrop?): Box {
        if (crop == null || crop == NormCrop.FULL) return centerCrop(srcW, srcH, dstW, dstH)
        val target = dstW.toDouble() / dstH
        var w = crop.width.toDouble() * srcW
        var h = crop.height.toDouble() * srcH
        // Grow the short side to reach the screen aspect (keeps everything the user framed)…
        if (w / h < target) w = h * target else h = w / target
        // …and shrink back proportionally if that no longer fits in the image.
        val fit = minOf(1.0, srcW / w, srcH / h)
        w *= fit
        h *= fit
        val cx = crop.centerX.toDouble() * srcW
        val cy = crop.centerY.toDouble() * srcH
        val left = (cx - w / 2).coerceIn(0.0, srcW - w)
        val top = (cy - h / 2).coerceIn(0.0, srcH - h)
        val l = left.roundToInt()
        val t = top.roundToInt()
        return Box(l, t, (l + w.roundToInt()).coerceAtMost(srcW), (t + h.roundToInt()).coerceAtMost(srcH))
    }

    /**
     * Largest power-of-two subsampling that still leaves a [region] at least [dstW]x[dstH]
     * pixels, so a huge photo (or a strong zoom) never decodes more than about twice the screen.
     */
    fun sampleSize(regionW: Int, regionH: Int, dstW: Int, dstH: Int): Int {
        var s = 1
        while (regionW / (s * 2) >= dstW && regionH / (s * 2) >= dstH) s *= 2
        return s
    }

    /**
     * Converts a box in *upright* (displayed) coordinates into the raw pixel grid of a photo whose
     * EXIF says it must be rotated by [degrees] clockwise to look upright. [rawW]x[rawH] is the
     * stored size, i.e. before rotation.
     */
    fun uprightToRaw(box: Box, rawW: Int, rawH: Int, degrees: Int): Box = when (degrees) {
        // 90° CW: upright (x, y) comes from raw (y, rawH - x)
        90 -> Box(box.top, rawH - box.right, box.bottom, rawH - box.left)
        180 -> Box(rawW - box.right, rawH - box.bottom, rawW - box.left, rawH - box.top)
        270 -> Box(rawW - box.bottom, box.left, rawW - box.top, box.right)
        else -> box
    }

    /**
     * The part of a [srcW]x[srcH] image that fills a [dstW]x[dstH] screen without distortion
     * ("center crop"): the largest centred region with the screen's aspect ratio.
     */
    fun centerCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0)
        // Compare aspect ratios without floating point: srcW/srcH vs dstW/dstH.
        return if (srcW.toLong() * dstH > dstW.toLong() * srcH) {
            // Image is wider than the screen: cut the sides.
            val w = (srcH.toLong() * dstW / dstH).toInt()
            val left = (srcW - w) / 2
            Box(left, 0, left + w, srcH)
        } else {
            // Image is taller (or equal): cut top and bottom.
            val h = (srcW.toLong() * dstH / dstW).toInt()
            val top = (srcH - h) / 2
            Box(0, top, srcW, top + h)
        }
    }

    /**
     * Decode size that still covers a [dstW]x[dstH] screen after center-cropping, keeping the
     * image's aspect ratio. Images are never upscaled — a small picture stays small in memory
     * and is stretched only when drawn.
     */
    fun coverSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Size {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0)
        val scale = max(dstW.toDouble() / srcW, dstH.toDouble() / srcH).coerceAtMost(1.0)
        return Size(
            (srcW * scale).roundToInt().coerceAtLeast(1),
            (srcH * scale).roundToInt().coerceAtLeast(1),
        )
    }
}
