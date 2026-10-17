package com.dualwall.app

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Settings screen: pick pictures, choose how each orientation shows them, apply. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            // Wallpaper-based colours on Android 12+, a plain dark theme before that.
            val colors = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(context) else darkColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

/** True when DualWall is the current home-screen live wallpaper. */
fun isDualWallActive(c: Context): Boolean =
    WallpaperManager.getInstance(c).wallpaperInfo?.packageName == c.packageName

/** Opens the system screen that previews DualWall and lets the user set it (home and/or lock screen). */
fun openWallpaperPicker(c: Context) {
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(c, DualWallpaperService::class.java))
    try {
        c.startActivity(direct)
    } catch (_: Exception) {
        // Some launchers lack the direct preview: fall back to the generic live wallpaper list.
        c.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
    }
}

/** Google "Галерея" (Gallery Go): picked images come back with a read grant, so no permission is needed. */
private const val GALLERY_PACKAGE = "com.google.android.apps.photosgo"

/**
 * Intent that opens a gallery app to pick one image: the Gallery app when installed, otherwise
 * whatever handles ACTION_PICK (the system asks which one). Null when nothing can handle it.
 */
fun galleryPickIntent(c: Context): Intent? {
    val pick = Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
    val gallery = Intent(pick).setPackage(GALLERY_PACKAGE)
    return when {
        gallery.resolveActivity(c.packageManager) != null -> gallery
        pick.resolveActivity(c.packageManager) != null -> pick
        else -> null
    }
}

/** Screen proportions (width / height) in each orientation, from the real display. */
private fun screenAspect(c: Context, o: Orientation): Float {
    val dm = c.resources.displayMetrics
    val long = max(dm.widthPixels, dm.heightPixels).toFloat()
    val short = min(dm.widthPixels, dm.heightPixels).toFloat()
    return if (o == Orientation.PORTRAIT) short / long else long / short
}

private fun Orientation.label() = if (this == Orientation.PORTRAIT) "Вертикально" else "Горизонтально"

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Bumped after every change, so previews reload from disk.
    var revision by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(isDualWallActive(context)) }
    // Crop editor: which orientation is being framed, and the picture it shows.
    var editing by remember { mutableStateOf<Orientation?>(null) }
    var editorImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var pickingFor by remember { mutableStateOf<Orientation?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { active = isDualWallActive(context) }

    fun openEditor(o: Orientation) {
        val file = WallpaperStore.current(context, o) ?: return
        editing = o
        editorImage = null
        scope.launch {
            editorImage = withContext(Dispatchers.IO) { WallpaperStore.decode(file, 1600, 1600) }?.asImageBitmap()
            if (editorImage == null) {
                Toast.makeText(context, "Не удалось открыть картинку", Toast.LENGTH_SHORT).show()
                editing = null
            }
        }
    }

    fun onPicked(uri: Uri?) {
        val o = pickingFor ?: return
        pickingFor = null
        uri ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { WallpaperStore.savePending(context, o, uri) }.isSuccess }
            revision++
            if (!ok) {
                Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Portrait must be framed by the user: open the editor right away — for the picked
            // portrait picture, or for a landscape picture that portrait borrows.
            if (o == Orientation.PORTRAIT || WallpaperStore.borrows(context, Orientation.PORTRAIT)) {
                if (WallpaperStore.currentFraming(context, Orientation.PORTRAIT) is Framing.Crop) openEditor(Orientation.PORTRAIT)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPicked(it.data?.data)
    }
    // Last resort when no gallery app handles ACTION_PICK.
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { onPicked(it) }

    fun pick(o: Orientation) {
        pickingFor = o
        val intent = galleryPickIntent(context)
        if (intent != null) galleryLauncher.launch(intent)
        else systemPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val o = editing
    val img = editorImage
    if (o != null) {
        if (img == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val initial = (WallpaperStore.currentFraming(context, o) as? Framing.Crop)?.rect ?: NormCrop.FULL
            CropEditor(
                title = "Кадр: ${o.label().lowercase()}",
                image = img,
                frameAspect = screenAspect(context, o),
                initial = initial,
                onDone = {
                    WallpaperStore.saveFraming(context, o, Framing.Crop(it))
                    editing = null
                    revision++
                },
                onCancel = { editing = null },
            )
        }
        return
    }

    SettingsScreen(
        revision = revision,
        active = active,
        onPick = ::pick,
        onFrame = ::openEditor,
        onChanged = { revision++ },
    )
}

@Composable
private fun SettingsScreen(
    revision: Int,
    active: Boolean,
    onPick: (Orientation) -> Unit,
    onFrame: (Orientation) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val hasAny = remember(revision) { WallpaperStore.hasAny(context) }
    val hasPending = remember(revision) { WallpaperStore.hasPending(context) }

    fun onApply() {
        WallpaperStore.apply(context)
        onChanged()
        if (isDualWallActive(context)) Toast.makeText(context, "Обои обновлены", Toast.LENGTH_SHORT).show()
        else openWallpaperPicker(context)
    }

    val portraitAspect = screenAspect(context, Orientation.PORTRAIT)
    val landscapeAspect = screenAspect(context, Orientation.LANDSCAPE)

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Text("DualWall", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Отдельные обои для вертикального и горизонтального положения экрана.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (active) "● Обои DualWall установлены" else "○ Обои DualWall ещё не установлены",
                modifier = Modifier.testTag("status"),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                PictureSlot(Orientation.PORTRAIT, portraitAspect, revision, Modifier.weight(portraitAspect), onPick, onFrame, onChanged)
                PictureSlot(Orientation.LANDSCAPE, landscapeAspect, revision, Modifier.weight(landscapeAspect), onPick, onFrame, onChanged)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = ::onApply,
                enabled = hasAny,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("apply"),
            ) { Text(if (active && !hasPending) "Применить снова" else "Применить") }

            if (active) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openWallpaperPicker(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Установить также на экран блокировки")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Одной широкой картинки достаточно: для каждого положения выберите кадр — что останется " +
                    "на экране. «Растянуть» покажет всю картинку, слегка сжав её. Приложение не работает " +
                    "в фоне и не использует интернет — картинку меняет сама система, когда поворачивает экран.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun PictureSlot(
    orientation: Orientation,
    aspect: Float,
    revision: Int,
    modifier: Modifier,
    onPick: (Orientation) -> Unit,
    onFrame: (Orientation) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val framing = remember(revision) { WallpaperStore.currentFraming(context, orientation) }
    val borrows = remember(revision) { WallpaperStore.borrows(context, orientation) }
    val ownExists = remember(revision) { WallpaperStore.ownPicture(context, orientation) != null }
    val otherExists = remember(revision) { WallpaperStore.ownPicture(context, orientation.other) != null }

    LaunchedEffect(revision) {
        val file = WallpaperStore.current(context, orientation)
        preview = withContext(Dispatchers.IO) { file?.let { WallpaperStore.decode(it, 900, 900) } }?.asImageBitmap()
    }

    Column(modifier) {
        Text(orientation.label(), style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                preview == null -> " "
                borrows -> "та же картинка, что и в другом положении"
                else -> "своя картинка"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Card(
            onClick = { onPick(orientation) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect).testTag("slot_${orientation.fileName}"),
        ) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = preview
                if (bmp != null) {
                    FramedPreview(bmp, framing, Modifier.fillMaxSize())
                } else {
                    Text("Нажмите,\nчтобы выбрать\nв Галерее", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (preview != null) {
            Spacer(Modifier.height(8.dp))
            // Wraps in the narrow portrait column instead of squeezing the chips.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Fill with a chosen frame (default) / fill by squeezing / whole picture with bars.
                FilterChip(
                    selected = framing is Framing.Crop,
                    onClick = { onFrame(orientation) },
                    label = { Text("Кадр…") },
                    modifier = Modifier.testTag("crop_${orientation.fileName}"),
                )
                FilterChip(
                    selected = framing == Framing.Stretch,
                    onClick = {
                        WallpaperStore.saveFraming(context, orientation, Framing.Stretch)
                        onChanged()
                    },
                    label = { Text("Растянуть") },
                    modifier = Modifier.testTag("stretch_${orientation.fileName}"),
                )
                FilterChip(
                    selected = framing == Framing.Fit,
                    onClick = {
                        WallpaperStore.saveFraming(context, orientation, Framing.Fit)
                        onChanged()
                    },
                    label = { Text("С полосами") },
                    modifier = Modifier.testTag("fit_${orientation.fileName}"),
                )
            }
            FlowRow {
                TextButton(onClick = { onPick(orientation) }) { Text(if (borrows) "Своя картинка" else "Заменить") }
                if (ownExists && otherExists) {
                    TextButton(onClick = {
                        WallpaperStore.removeOwn(context, orientation)
                        onChanged()
                    }) { Text("Как в другом") }
                }
            }
        }
    }
}
