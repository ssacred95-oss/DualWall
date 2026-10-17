package com.dualwall.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WallpaperStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** Left half [left], right half [right]: lets tests see which part of a picture was shown. */
    private fun twoTone(name: String, w: Int, h: Int, left: Int, right: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(left)
            clipRect(w / 2, 0, w, h)
            drawColor(right)
        }
        return File(ctx.cacheDir, name).apply { outputStream().use { b.compress(format, 100, it) } }
    }

    private fun uri(f: File) = Uri.fromFile(f)
    private fun close(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) < 40 && abs(Color.green(a) - Color.green(b)) < 40 && abs(Color.blue(a) - Color.blue(b)) < 40
    private fun abs(x: Int) = kotlin.math.abs(x)

    @Before fun setUp() = WallpaperStore.clear(ctx)
    @After fun tearDown() = WallpaperStore.clear(ctx)

    @Test fun pickIsPendingUntilApplied() {
        WallpaperStore.savePending(ctx, Orientation.LANDSCAPE, uri(twoTone("a.png", 160, 90, Color.RED, Color.BLUE)))
        assertTrue(WallpaperStore.hasPending(ctx))
        assertNull(WallpaperStore.appliedFor(ctx, 3040, 1904))
        val before = WallpaperStore.prefs(ctx).getLong(WallpaperStore.KEY_VERSION, 0)
        WallpaperStore.apply(ctx)
        assertFalse(WallpaperStore.hasPending(ctx))
        assertEquals(before + 1, WallpaperStore.prefs(ctx).getLong(WallpaperStore.KEY_VERSION, 0))
        assertEquals(Framing.Crop(NormCrop.FULL), WallpaperStore.appliedFor(ctx, 3040, 1904)!!.second)
    }

    @Test fun oneWidePicture_portraitBorrowsItWithOwnCrop() {
        WallpaperStore.savePending(ctx, Orientation.LANDSCAPE, uri(twoTone("w.png", 1600, 900, Color.RED, Color.BLUE)))
        assertTrue(WallpaperStore.borrows(ctx, Orientation.PORTRAIT))
        // the user frames the right (blue) part for portrait
        val crop = NormCrop(0.7f, 0f, 0.9f, 1f)
        WallpaperStore.saveFraming(ctx, Orientation.PORTRAIT, Framing.Crop(crop))
        WallpaperStore.apply(ctx)

        val (file, framing) = WallpaperStore.appliedFor(ctx, 900, 1600)!!
        assertEquals("landscape", file.name)
        assertEquals(Framing.Crop(crop), framing)
        val bmp = WallpaperStore.decodeFramed(file, framing, 90, 160)!!
        assertTrue(close(Color.BLUE, bmp.getPixel(bmp.width / 2, bmp.height / 2)))
        // landscape keeps its own (default, filling) framing
        assertEquals(Framing.Crop(NormCrop.FULL), WallpaperStore.appliedFor(ctx, 1600, 900)!!.second)
    }

    @Test fun newPictureResetsOldCrop() {
        WallpaperStore.savePending(ctx, Orientation.LANDSCAPE, uri(twoTone("a.png", 1600, 900, Color.RED, Color.BLUE)))
        WallpaperStore.saveFraming(ctx, Orientation.PORTRAIT, Framing.Crop(NormCrop(0.7f, 0f, 0.9f, 1f)))
        WallpaperStore.apply(ctx)
        WallpaperStore.savePending(ctx, Orientation.LANDSCAPE, uri(twoTone("b.png", 1600, 900, Color.GREEN, Color.GREEN)))
        assertEquals(Framing.default(Orientation.PORTRAIT), WallpaperStore.currentFraming(ctx, Orientation.PORTRAIT))
        WallpaperStore.apply(ctx)
        assertEquals(Framing.default(Orientation.PORTRAIT), WallpaperStore.appliedFor(ctx, 900, 1600)!!.second)
    }

    @Test fun ownPortraitPictureAndRemoveOwn() {
        WallpaperStore.savePending(ctx, Orientation.LANDSCAPE, uri(twoTone("l.png", 160, 90, Color.RED, Color.RED)))
        WallpaperStore.savePending(ctx, Orientation.PORTRAIT, uri(twoTone("p.png", 90, 160, Color.GREEN, Color.GREEN)))
        WallpaperStore.apply(ctx)
        assertEquals("portrait", WallpaperStore.appliedFor(ctx, 900, 1600)!!.first.name)
        WallpaperStore.removeOwn(ctx, Orientation.PORTRAIT)
        assertEquals("landscape", WallpaperStore.appliedFor(ctx, 900, 1600)!!.first.name)
    }

    @Test fun nonImageIsRejected() {
        val f = File(ctx.cacheDir, "notes.txt").apply { writeText("hello") }
        try {
            WallpaperStore.savePending(ctx, Orientation.PORTRAIT, uri(f))
            fail("should reject")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(WallpaperStore.hasAny(ctx))
    }

    @Test fun fitDecodesWholePictureNearFittedSize() {
        val f = twoTone("big.jpg", 4000, 2250, Color.RED, Color.BLUE, Bitmap.CompressFormat.JPEG)
        val bmp = WallpaperStore.decodeFramed(f, Framing.Fit, 800, 500)!!
        // whole picture: both colours present, aspect kept, not much bigger than needed
        assertTrue(close(Color.RED, bmp.getPixel(5, bmp.height / 2)))
        assertTrue(close(Color.BLUE, bmp.getPixel(bmp.width - 5, bmp.height / 2)))
        assertTrue(abs(bmp.width * 9 - bmp.height * 16) < 40)
        assertTrue(bmp.width in 800..1600)
        assertTrue(bmp.config != Bitmap.Config.HARDWARE)
    }

    @Test fun cropDecodesOnlyTheRegion() {
        val f = twoTone("big2.jpg", 4000, 2250, Color.RED, Color.BLUE, Bitmap.CompressFormat.JPEG)
        val bmp = WallpaperStore.decodeFramed(f, Framing.Crop(NormCrop(0.05f, 0f, 0.3f, 1f)), 100, 200)!!
        assertTrue(close(Color.RED, bmp.getPixel(bmp.width / 2, bmp.height / 2)))
        assertTrue(bmp.width < 1200) // a quarter of the picture, subsampled
    }

    @Test fun exifRotatedPhotoIsShownUpright() {
        // Stored landscape 400x200 (left red, right blue) but EXIF says "rotate 90° CW":
        // upright it is 200x400 with red on TOP.
        val f = twoTone("rot.jpg", 400, 200, Color.RED, Color.BLUE, Bitmap.CompressFormat.JPEG)
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bmp = WallpaperStore.decodeFramed(f, Framing.Crop(NormCrop.FULL), 200, 400)!!
        assertTrue(bmp.height > bmp.width)
        assertTrue(close(Color.RED, bmp.getPixel(bmp.width / 2, 10)))
        assertTrue(close(Color.BLUE, bmp.getPixel(bmp.width / 2, bmp.height - 10)))
    }

    @Test fun rendererFit_wholePictureAndNoBlackBars() {
        val src = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val screen = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        WallpaperRenderer.draw(Canvas(screen), src, 160, 100, Framing.Fit, WallpaperStore.blurSource(src))
        // picture in the middle, blurred (darkened) copy in the bars — never plain black
        assertEquals(Color.GREEN, screen.getPixel(80, 50))
        assertTrue(screen.getPixel(80, 1) != Color.BLACK)
        assertTrue(Color.green(screen.getPixel(80, 1)) > 100)
    }

    @Test fun stretchDecodesWholePictureAtScreenSize() {
        val f = twoTone("s.jpg", 1600, 900, Color.RED, Color.BLUE, Bitmap.CompressFormat.JPEG)
        val bmp = WallpaperStore.decodeFramed(f, Framing.Stretch, 300, 200)!!
        assertEquals(300, bmp.width)
        assertEquals(200, bmp.height)
        // nothing cut: both halves present, edge to edge
        assertTrue(close(Color.RED, bmp.getPixel(3, 100)))
        assertTrue(close(Color.BLUE, bmp.getPixel(296, 100)))
    }

    @Test fun rendererStretch_fillsScreenEdgeToEdge() {
        val src = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val screen = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        WallpaperRenderer.draw(Canvas(screen), src, 160, 100, Framing.Stretch)
        for (y in listOf(0, 99)) for (x in listOf(0, 159)) assertEquals(Color.GREEN, screen.getPixel(x, y))
    }

    @Test fun rendererCrop_fillsScreen() {
        val src = Bitmap.createBitmap(50, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val screen = Bitmap.createBitmap(50, 100, Bitmap.Config.ARGB_8888)
        WallpaperRenderer.draw(Canvas(screen), src, 50, 100)
        for (y in listOf(0, 99)) for (x in listOf(0, 49)) assertEquals(Color.RED, screen.getPixel(x, y))
    }
}
