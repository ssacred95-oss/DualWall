package com.dualwall.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lets the user choose which part of a picture fills the screen.
 *
 * A fixed frame with the screen's proportions sits in the middle; the picture moves under it
 * (drag) and scales (pinch, double tap resets). The frame can never leave the picture, so the
 * wallpaper never shows empty space.
 *
 * @param frameAspect width / height of the screen in that orientation
 * @param initial previous choice; [NormCrop.FULL] means "centred, no zoom"
 */
@Composable
fun CropEditor(
    title: String,
    image: ImageBitmap,
    frameAspect: Float,
    initial: NormCrop,
    onDone: (NormCrop) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val imageAspect = image.width.toFloat() / image.height
    // Largest frame (as fractions of the picture) at zoom 1.
    val baseW = if (imageAspect > frameAspect) frameAspect / imageAspect else 1f
    val baseH = if (imageAspect > frameAspect) 1f else imageAspect / frameAspect

    val start = if (initial == NormCrop.FULL) null else initial
    var zoom by remember { mutableFloatStateOf(start?.let { (baseW / it.width).coerceIn(1f, MAX_ZOOM) } ?: 1f) }
    var cx by remember { mutableFloatStateOf(start?.centerX ?: 0.5f) }
    var cy by remember { mutableFloatStateOf(start?.centerY ?: 0.5f) }

    fun cropW() = baseW / zoom
    fun cropH() = baseH / zoom
    fun clamp() {
        cx = cx.coerceIn(cropW() / 2, 1 - cropW() / 2)
        cy = cy.coerceIn(cropH() / 2, 1 - cropH() / 2)
    }
    fun reset() { zoom = 1f; cx = 0.5f; cy = 0.5f }
    fun result() = NormCrop(cx - cropW() / 2, cy - cropH() / 2, cx + cropW() / 2, cy + cropH() / 2)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
        )
        Text(
            "Двигайте картинку пальцем, увеличивайте щипком. Двойное касание — по центру.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("cropArea"),
        ) {
            val density = LocalDensity.current
            val areaW = with(density) { maxWidth.toPx() }
            val areaH = with(density) { maxHeight.toPx() }
            val margin = with(density) { 24.dp.toPx() }
            // Frame: as large as possible inside the area, with the screen's proportions.
            val frameW = min(areaW - 2 * margin, (areaH - 2 * margin) * frameAspect)
            val frameH = frameW / frameAspect
            val frameLeft = (areaW - frameW) / 2
            val frameTop = (areaH - frameH) / 2

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                            // Picture follows the finger: moving it right shows more of its left side.
                            val shownW = frameW / cropW()
                            val shownH = frameH / cropH()
                            cx -= pan.x / shownW
                            cy -= pan.y / shownH
                            clamp()
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) },
            ) {
                val shownW = frameW / cropW()
                val shownH = frameH / cropH()
                val left = frameLeft - (cx - cropW() / 2) * shownW
                val top = frameTop - (cy - cropH() / 2) * shownH
                drawImage(
                    image,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(shownW.roundToInt(), shownH.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
                // Dim everything outside the frame.
                val dim = Color.Black.copy(alpha = 0.6f)
                drawRect(dim, Offset.Zero, Size(size.width, frameTop))
                drawRect(dim, Offset(0f, frameTop + frameH), Size(size.width, size.height - frameTop - frameH))
                drawRect(dim, Offset(0f, frameTop), Size(frameLeft, frameH))
                drawRect(dim, Offset(frameLeft + frameW, frameTop), Size(size.width - frameLeft - frameW, frameH))
                // Rule-of-thirds guides help put the subject where it should be.
                val guide = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    val x = frameLeft + frameW * i / 3
                    val y = frameTop + frameH * i / 3
                    drawLine(guide, Offset(x, frameTop), Offset(x, frameTop + frameH), 1.5f)
                    drawLine(guide, Offset(frameLeft, y), Offset(frameLeft + frameW, y), 1.5f)
                }
                drawRect(Color.White, Offset(frameLeft, frameTop), Size(frameW, frameH), style = Stroke(3f))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Отмена") }
            OutlinedButton(onClick = { reset() }) { Text("По центру") }
            Button(onClick = { onDone(result()) }, modifier = Modifier.testTag("cropDone")) { Text("Готово") }
        }
    }
}

private const val MAX_ZOOM = 8f

/** Preview of a framing inside a small card: the cropped part, or the whole picture over a blur. */
@Composable
fun FramedPreview(image: ImageBitmap, framing: Framing, modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            when (framing) {
                Framing.Stretch -> drawImage(
                    image,
                    dstSize = IntSize(w, h),
                    filterQuality = FilterQuality.Medium,
                )
                Framing.Fit -> {
                    // Backdrop: the picture centre-cropped and darkened (the real wallpaper also blurs it).
                    val bg = CropMath.centerCrop(image.width, image.height, w, h)
                    drawImage(
                        image,
                        srcOffset = IntOffset(bg.left, bg.top),
                        srcSize = IntSize(bg.width, bg.height),
                        dstSize = IntSize(w, h),
                        alpha = 0.35f,
                    )
                    val f = CropMath.fit(image.width, image.height, w, h)
                    drawImage(
                        image,
                        dstOffset = IntOffset(f.left, f.top),
                        dstSize = IntSize(f.width, f.height),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                is Framing.Crop -> {
                    val b = CropMath.frame(image.width, image.height, w, h, framing.rect)
                    drawImage(
                        image,
                        srcOffset = IntOffset(b.left, b.top),
                        srcSize = IntSize(b.width, b.height),
                        dstSize = IntSize(w, h),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
    }
}
