package com.dualwall.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/**
 * Keeps the pictures and the user's framing as private copies inside the app (files/wallpapers/):
 *
 *   portrait, landscape                   – applied pictures, used by the wallpaper
 *   portrait.frame, landscape.frame       – applied framing: "fit" or "l,t,r,b" fractions
 *   *.pending, *.pending.frame            – picked/edited but not applied yet
 *
 * One picture can serve both orientations: when there is no portrait picture, portrait uses the
 * landscape one with its own framing (a wide photo shown whole in landscape, cropped in portrait).
 *
 * Copying instead of remembering the gallery Uri means no storage permission is needed and the
 * wallpaper keeps working even if the original is moved or deleted. [apply] promotes the pending
 * files and bumps a version in SharedPreferences, which the running wallpaper listens to.
 */
object WallpaperStore {
    const val PREFS = "dualwall"
    const val KEY_VERSION = "version"

    private fun dir(c: Context) = File(c.filesDir, "wallpapers").apply { mkdirs() }

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The picture of this orientation used by the wallpaper. */
    fun applied(c: Context, o: Orientation) = File(dir(c), o.fileName)

    /** A newly picked picture that is not applied yet. */
    fun pending(c: Context, o: Orientation) = File(dir(c), "${o.fileName}.pending")

    private fun appliedFrameFile(c: Context, o: Orientation) = File(dir(c), "${o.fileName}.frame")
    private fun pendingFrameFile(c: Context, o: Orientation) = File(dir(c), "${o.fileName}.pending.frame")

    private fun readFrame(f: File): Framing? = if (f.exists()) Framing.decode(f.readText()) else null

    // --- what the settings screen shows (pending state wins) ---

    /** Own picture of [o], pending first. */
    fun ownPicture(c: Context, o: Orientation): File? =
        pending(c, o).takeIf { it.exists() } ?: applied(c, o).takeIf { it.exists() }

    /** Picture [o] will show: its own, or the other orientation's when it has none. */
    fun current(c: Context, o: Orientation): File? = ownPicture(c, o) ?: ownPicture(c, o.other)

    /** True when [o] has no picture of its own and borrows the other orientation's. */
    fun borrows(c: Context, o: Orientation) = ownPicture(c, o) == null && ownPicture(c, o.other) != null

    /** Framing that goes with [current]. */
    fun currentFraming(c: Context, o: Orientation): Framing {
        readFrame(pendingFrameFile(c, o))?.let { return it }
        // A new picture (own or borrowed) invalidates the old framing.
        val source = if (borrows(c, o)) o.other else o
        if (pending(c, source).exists()) return Framing.default(o)
        return readFrame(appliedFrameFile(c, o)) ?: Framing.default(o)
    }

    /** Stores the framing chosen on the settings screen (pending until [apply]). */
    fun saveFraming(c: Context, o: Orientation, framing: Framing) {
        pendingFrameFile(c, o).writeText(framing.encode())
    }

    /** Copies a picked image into private storage as the pending picture for [o]. */
    fun savePending(c: Context, o: Orientation, uri: Uri) {
        val target = pending(c, o)
        val tmp = File(target.path + ".tmp")
        c.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $uri" }
            tmp.outputStream().use { input.copyTo(it) }
        }
        // Make sure it really is an image before accepting it.
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(tmp)) { d, _, _ -> d.setTargetSize(8, 8) }
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalArgumentException("Not an image", e)
        }
        check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target)))
        // A new picture starts with the default framing — for itself and for whoever borrows it.
        pendingFrameFile(c, o).delete()
        if (ownPicture(c, o.other) == null) pendingFrameFile(c, o.other).delete()
    }

    /** Removes the own picture of [o] (pending and applied), so it borrows the other one again. */
    fun removeOwn(c: Context, o: Orientation) {
        pending(c, o).delete()
        applied(c, o).delete()
        pendingFrameFile(c, o).delete()
        appliedFrameFile(c, o).delete()
        bumpVersion(c)
    }

    /** True when at least one picture exists, so the wallpaper has something to draw. */
    fun hasAny(c: Context) = Orientation.entries.any { ownPicture(c, it) != null }

    fun hasPending(c: Context) =
        Orientation.entries.any { pending(c, it).exists() || pendingFrameFile(c, it).exists() }

    /** Promotes pending pictures / framings and tells a running wallpaper to reload. */
    fun apply(c: Context) {
        // Framings tied to a picture that is about to be replaced must go, own or borrowed.
        for (o in Orientation.entries) {
            val source = if (ownPicture(c, o) == null) o.other else o
            if (pending(c, source).exists() && !pendingFrameFile(c, o).exists()) appliedFrameFile(c, o).delete()
        }
        for (o in Orientation.entries) {
            val p = pending(c, o)
            if (p.exists()) {
                val a = applied(c, o)
                a.delete()
                check(p.renameTo(a)) { "Cannot apply ${o.fileName}" }
            }
            val pf = pendingFrameFile(c, o)
            if (pf.exists()) {
                val af = appliedFrameFile(c, o)
                af.delete()
                check(pf.renameTo(af)) { "Cannot apply framing ${o.fileName}" }
            }
        }
        bumpVersion(c)
    }

    private fun bumpVersion(c: Context) {
        val prefs = prefs(c)
        prefs.edit().putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, 0) + 1).apply()
    }

    /** Drops everything (used by tests). */
    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
        bumpVersion(c)
    }

    /** What the wallpaper draws on a [width]x[height] screen: applied picture + framing, or null. */
    fun appliedFor(c: Context, width: Int, height: Int): Pair<File, Framing>? {
        val o = Orientation.of(width, height)
        val file = applied(c, o).takeIf { it.exists() } ?: applied(c, o.other).takeIf { it.exists() } ?: return null
        return file to (readFrame(appliedFrameFile(c, o)) ?: Framing.default(o))
    }

    // --- decoding ---

    /** Small, full-picture decode for previews and the crop editor. */
    fun decode(file: File, width: Int, height: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val size = CropMath.coverSize(info.size.width, info.size.height, width, height)
            decoder.setTargetSize(size.width, size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    /**
     * Decodes what [framing] shows on a [width]x[height] screen: the framed part for [Framing.Crop],
     * the whole picture (at fitted size) for [Framing.Fit]. The result is never much larger than
     * the screen.
     *
     * BitmapRegionDecoder reads only the needed region, subsampled, so even a 400-megapixel picture
     * with a strong zoom costs no more memory than the screen. It ignores EXIF rotation, so the
     * region is mapped to raw pixels and the result rotated back. Formats it cannot read
     * (GIF, AVIF…) fall back to a full ImageDecoder decode.
     * Software bitmaps only: lockCanvas() canvases cannot draw hardware ones.
     */
    fun decodeFramed(file: File, framing: Framing, width: Int, height: Int): Bitmap? {
        runCatching { decodeRegion(file, framing, width, height) }.getOrNull()?.let { return it }
        val full = decode(file, width, height) ?: return null
        if (framing is Framing.Fit) return full
        if (framing is Framing.Stretch) return Bitmap.createScaledBitmap(full, width, height, true)
        val b = CropMath.frame(full.width, full.height, width, height, (framing as Framing.Crop).rect)
        return Bitmap.createBitmap(full, b.left, b.top, b.width, b.height)
    }

    private fun decodeRegion(file: File, framing: Framing, width: Int, height: Int): Bitmap? {
        val degrees = exifRotation(file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null
        val swap = degrees == 90 || degrees == 270
        val upW = if (swap) rawH else rawW
        val upH = if (swap) rawW else rawH

        val (region, needW, needH) = when (framing) {
            Framing.Fit -> {
                val f = CropMath.fit(upW, upH, width, height)
                Triple(Box(0, 0, upW, upH), f.width, f.height)
            }
            // Whole picture, scaled unevenly to the exact screen size below.
            Framing.Stretch -> Triple(Box(0, 0, upW, upH), width, height)
            is Framing.Crop -> Triple(CropMath.frame(upW, upH, width, height, framing.rect), width, height)
        }
        val raw = CropMath.uprightToRaw(region, rawW, rawH, degrees)
        val sample = CropMath.sampleSize(region.width, region.height, needW.coerceAtLeast(1), needH.coerceAtLeast(1))
        val decoder = BitmapRegionDecoder.newInstance(file.path)
        val bitmap = try {
            decoder.decodeRegion(
                Rect(raw.left, raw.top, raw.right, raw.bottom),
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } finally {
            decoder.recycle()
        } ?: return null
        // Power-of-two subsampling can leave up to ~2x the needed pixels (4x the memory):
        // rotate and scale to exactly the size that will be drawn in one pass.
        val outW = if (swap) needH else needW
        val outH = if (swap) needW else needH
        val m = Matrix().apply {
            if (bitmap.width > outW && bitmap.height > outH) {
                postScale(outW.toFloat() / bitmap.width, outH.toFloat() / bitmap.height)
            }
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        if (m.isIdentity) return bitmap
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }

    /** Clockwise rotation a camera photo needs to look upright (mirrored variants are rare and ignored). */
    private fun exifRotation(file: File): Int = runCatching {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /**
     * Tiny copy used as the blurred background behind a fitted picture. Scaling down in steps with
     * bilinear filtering and drawing it stretched gives a soft blur without RenderEffect, which
     * software wallpaper canvases do not support.
     */
    fun blurSource(bitmap: Bitmap): Bitmap {
        var b = bitmap
        while (b.width > 96 || b.height > 96) {
            val next = Bitmap.createScaledBitmap(b, (b.width / 2).coerceAtLeast(1), (b.height / 2).coerceAtLeast(1), true)
            if (b !== bitmap) b.recycle()
            b = next
        }
        // A few extra down/up passes widen the blur radius.
        repeat(2) {
            val small = Bitmap.createScaledBitmap(b, (b.width / 2).coerceAtLeast(1), (b.height / 2).coerceAtLeast(1), true)
            val back = Bitmap.createScaledBitmap(small, b.width, b.height, true)
            small.recycle()
            if (b !== bitmap) b.recycle()
            b = back
        }
        return b
    }
}
