package com.dualwall.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CropMathTest {

    @Test fun orientation() {
        assertEquals(Orientation.LANDSCAPE, Orientation.of(3040, 1904))
        assertEquals(Orientation.PORTRAIT, Orientation.of(1904, 3040))
        assertEquals(Orientation.PORTRAIT, Orientation.of(1000, 1000))
        assertEquals(Orientation.LANDSCAPE, Orientation.PORTRAIT.other)
    }

    @Test fun wideImageOnPortraitScreen_cutsSides() {
        // 4000x2000 photo on a 1000x2000 screen: keep a centred 1000x2000 strip.
        assertEquals(Box(1500, 0, 2500, 2000), CropMath.centerCrop(4000, 2000, 1000, 2000))
    }

    @Test fun tallImageOnLandscapeScreen_cutsTopAndBottom() {
        // 1000x3000 on a 2000x1000 screen: keep a centred 1000x500 band.
        assertEquals(Box(0, 1250, 1000, 1750), CropMath.centerCrop(1000, 3000, 2000, 1000))
    }

    @Test fun sameAspect_keepsWholeImage() {
        assertEquals(Box(0, 0, 1904, 3040), CropMath.centerCrop(1904, 3040, 952, 1520))
    }

    @Test fun cropAlwaysHasScreenAspect() {
        val screens = listOf(1904 to 3040, 3040 to 1904, 1080 to 2400, 2400 to 1080, 800 to 800)
        val images = listOf(4032 to 3024, 3024 to 4032, 1920 to 1080, 500 to 500, 7 to 13)
        for ((sw, sh) in screens) for ((iw, ih) in images) {
            val b = CropMath.centerCrop(iw, ih, sw, sh)
            assert(b.left >= 0 && b.top >= 0 && b.right <= iw && b.bottom <= ih) { "$b out of $iw x $ih" }
            // aspect matches the screen up to one pixel of rounding on the side that was cut
            val dw = kotlin.math.abs(b.width - b.height.toDouble() * sw / sh)
            val dh = kotlin.math.abs(b.height - b.width.toDouble() * sh / sw)
            assert(minOf(dw, dh) <= 1.0) { "$iw x $ih on $sw x $sh -> $b" }
            // and one side is always kept whole
            assert(b.width == iw || b.height == ih) { "$b" }
        }
    }

    @Test fun coverSize_shrinksBigPhotosToScreen() {
        // 4032x3024 photo for a 3040x1904 landscape screen: width is the binding side.
        val s = CropMath.coverSize(4032, 3024, 3040, 1904)
        assertEquals(3040, s.width)
        assertEquals(2280, s.height)
        assert(s.width >= 3040 && s.height >= 1904)
    }

    @Test fun coverSize_coversTheOtherDimensionToo() {
        // Panorama on a portrait screen: height decides, width ends up much larger.
        val s = CropMath.coverSize(16000, 4000, 1904, 3040)
        assertEquals(3040, s.height)
        assertEquals(12160, s.width)
    }

    @Test fun coverSize_neverUpscales() {
        assertEquals(Size(640, 480), CropMath.coverSize(640, 480, 3040, 1904))
    }
}
