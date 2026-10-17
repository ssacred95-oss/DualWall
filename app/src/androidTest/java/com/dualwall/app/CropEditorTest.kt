package com.dualwall.app

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CropEditorTest {
    @get:Rule val rule = createComposeRule()

    // A 16:9 picture framed for a 16:25-ish portrait screen, like the user's case.
    private val image = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val portraitAspect = 1904f / 3040f
    private var result: NormCrop? = null
    private var cancelled = false

    private fun show(initial: NormCrop = NormCrop.FULL) {
        rule.setContent {
            MaterialTheme {
                CropEditor("Кадр", image, portraitAspect, initial, onDone = { result = it }, onCancel = { cancelled = true })
            }
        }
    }

    private fun aspectOf(c: NormCrop) = (c.width * 1600) / (c.height * 900)

    @Test fun done_withoutMoving_isCentredFullHeightFrame() {
        show()
        rule.onNodeWithTag("cropDone").performClick()
        val c = assertNotNull(result).let { result!! }
        assertEquals(0.5f, c.centerX, 0.001f)
        assertEquals(0f, c.top, 0.001f)
        assertEquals(1f, c.bottom, 0.001f)
        assertEquals(portraitAspect, aspectOf(c), 0.01f)
    }

    @Test fun swipe_movesFrameButKeepsAspectAndBounds() {
        show()
        // dragging the picture left reveals its right side
        rule.onNodeWithTag("cropArea").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("cropDone").performClick()
        val c = result!!
        assertTrue("centre ${c.centerX}", c.centerX > 0.55f)
        assertTrue(c.left >= 0f && c.right <= 1.0001f)
        assertEquals(portraitAspect, aspectOf(c), 0.01f)
    }

    @Test fun previousChoiceIsRestored() {
        val prev = NormCrop(0.6f, 0.1f, 0.6f + 0.25f * 0.8f, 0.9f).let {
            // make it exactly portrait-aspect so restoring is lossless
            val h = it.height
            val w = h * portraitAspect * 900 / 1600
            NormCrop(0.7f - w / 2, 0.1f, 0.7f + w / 2, 0.1f + h)
        }
        show(prev)
        rule.onNodeWithTag("cropDone").performClick()
        val c = result!!
        assertEquals(prev.centerX, c.centerX, 0.01f)
        assertEquals(prev.centerY, c.centerY, 0.01f)
        assertTrue(abs(prev.width - c.width) < 0.01f)
    }

    @Test fun centreButtonResets() {
        show(NormCrop(0.7f, 0f, 0.9f, 0.6f))
        rule.onNodeWithText("По центру").performClick()
        rule.onNodeWithTag("cropDone").performClick()
        assertEquals(0.5f, result!!.centerX, 0.001f)
        assertEquals(0.5f, result!!.centerY, 0.001f)
    }

    @Test fun cancel() {
        show()
        rule.onNodeWithText("Отмена").performClick()
        assertTrue(cancelled)
    }
}
