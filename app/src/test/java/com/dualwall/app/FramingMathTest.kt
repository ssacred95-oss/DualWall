package com.dualwall.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FramingMathTest {

    // --- fit (landscape: whole picture visible) ---

    @Test fun fit_widePictureOnTablet_barsAboveAndBelow() {
        // The user's 16:9 picture on the 16:10 tablet: full width, ~96 px bars.
        val b = CropMath.fit(6688, 3764, 3040, 1904)
        assertEquals(0, b.left)
        assertEquals(3040, b.right)
        assertEquals(1710, b.height) // 3040 * 3764 / 6688 = 1710.9
        assertEquals((1904 - 1710) / 2, b.top)
    }

    @Test fun fit_tallPicture_barsLeftAndRight() {
        val b = CropMath.fit(1000, 2000, 3040, 1904)
        assertEquals(1904, b.height)
        assertEquals(952, b.width)
        assertEquals((3040 - 952) / 2, b.left)
    }

    // --- frame (portrait: user's crop) ---

    @Test fun frame_withoutCrop_isCentreCrop() {
        assertEquals(CropMath.centerCrop(6688, 3764, 1904, 3040), CropMath.frame(6688, 3764, 1904, 3040, null))
        assertEquals(CropMath.centerCrop(6688, 3764, 1904, 3040), CropMath.frame(6688, 3764, 1904, 3040, NormCrop.FULL))
    }

    @Test fun frame_keepsUsersCentreAndScreenAspect() {
        // A portrait frame put on the right third of a wide picture.
        val cropW = (3764f * 1904 / 3040) / 6688 // full height, screen aspect
        val crop = NormCrop(0.8f - cropW / 2, 0f, 0.8f + cropW / 2, 1f)
        val b = CropMath.frame(6688, 3764, 1904, 3040, crop)
        assertEquals(0, b.top)
        assertEquals(3764, b.bottom)
        assertTrue(abs((b.left + b.right) / 2.0 - 0.8 * 6688) < 2)
        assertTrue(abs(b.width.toDouble() / b.height - 1904.0 / 3040) < 0.001)
    }

    @Test fun frame_isClampedInsideThePicture() {
        // Centre too close to the edge: the frame slides back inside instead of leaving it.
        val b = CropMath.frame(1000, 500, 100, 200, NormCrop(0.9f, 0f, 1f, 1f))
        assertTrue(b.left >= 0 && b.right <= 1000 && b.top >= 0 && b.bottom <= 500)
        assertEquals(1000, b.right)
    }

    @Test fun frame_zoomedCropIsSmall() {
        val b = CropMath.frame(4000, 2000, 1000, 2000, NormCrop(0.45f, 0.4f, 0.55f, 0.8f))
        assertTrue(b.width < 1000)
        assertTrue(abs(b.width * 2 - b.height) <= 2)
    }

    // --- decoding helpers ---

    @Test fun sampleSize_keepsAtLeastScreenSize() {
        assertEquals(1, CropMath.sampleSize(3000, 2000, 3040, 1904))
        // 8 would give 1882 px of height, less than the 1904 px screen
        assertEquals(4, CropMath.sampleSize(26752, 15056, 3040, 1904))
        assertEquals(4, CropMath.sampleSize(9000, 15056, 1904, 3040))
    }

    @Test fun uprightToRaw_roundTripsForAllRotations() {
        val rawW = 400
        val rawH = 300
        // A box in upright coordinates (upright size depends on rotation)
        for (deg in listOf(0, 90, 180, 270)) {
            val upW = if (deg % 180 == 0) rawW else rawH
            val upH = if (deg % 180 == 0) rawH else rawW
            val up = Box(10, 20, upW - 30, upH - 40)
            val raw = CropMath.uprightToRaw(up, rawW, rawH, deg)
            assertTrue("$deg: $raw", raw.left >= 0 && raw.top >= 0 && raw.right <= rawW && raw.bottom <= rawH)
            // area is preserved
            assertEquals(up.width * up.height, raw.width * raw.height)
        }
        // 90° CW: the upright top-left corner comes from the raw bottom-left.
        assertEquals(Box(0, 290, 10, 300), CropMath.uprightToRaw(Box(0, 0, 10, 10), rawW, rawH, 90))
    }

    // --- serialisation ---

    @Test fun framingEncodeDecode() {
        assertEquals(Framing.Fit, Framing.decode("fit"))
        val c = Framing.Crop(NormCrop(0.1f, 0.2f, 0.4f, 0.9f))
        assertEquals(c, Framing.decode(c.encode()))
        assertNull(Framing.decode("garbage"))
        assertNull(Framing.decode("0.5,0.5,0.4,0.9")) // right < left
        assertNull(Framing.decode(null))
    }

    @Test fun defaults_bothOrientationsFillTheScreen() {
        assertEquals(Framing.Crop(NormCrop.FULL), Framing.default(Orientation.LANDSCAPE))
        assertEquals(Framing.Crop(NormCrop.FULL), Framing.default(Orientation.PORTRAIT))
    }

    @Test fun stretchEncodeDecode() {
        assertEquals(Framing.Stretch, Framing.decode(Framing.Stretch.encode()))
    }
}
