package com.dualwall.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

/**
 * Live wallpaper that shows one picture in portrait and another in landscape.
 *
 * Why a live wallpaper instead of a background service calling WallpaperManager.setBitmap():
 *  - Android does not deliver orientation changes to manifest receivers, so that approach needs a
 *    permanently running foreground service with a notification.
 *  - setBitmap() re-encodes a full-size image on every rotation (slow, visible flicker).
 *  - Here the system itself tells the wallpaper about the new screen size via onSurfaceChanged().
 *    Between rotations the engine does nothing at all: no timers, no polling, no wake-ups.
 */
class DualWallpaperService : WallpaperService() {

    /** Decoded picture for one orientation, remembered with what it was decoded for. */
    private class Cached(
        val file: File,
        val framing: Framing,
        val width: Int,
        val height: Int,
        val bitmap: Bitmap,
        /** Blurred backdrop, only for [Framing.Fit]. */
        val background: Bitmap?,
    )

    // Shared by all engines: the home screen and the lock screen each get their own engine, but
    // they show the same pictures, so one decoded copy per orientation is enough.
    private val lock = Any()
    private val cache = HashMap<Orientation, Cached>()

    // Pre-decodes the picture of the *other* orientation, so the next rotation is instant.
    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("dualwall-decoder").apply { start() }
        workerHandler = Handler(worker.looper)
    }

    override fun onDestroy() {
        worker.quitSafely()
        synchronized(lock) { cache.clear() }
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = DualEngine()

    /** Returns the picture decoded for a [w]x[h] screen, decoding it only if needed. */
    private fun pictureFor(w: Int, h: Int): Cached? {
        if (w <= 0 || h <= 0) return null
        val o = Orientation.of(w, h)
        val (file, framing) = WallpaperStore.appliedFor(applicationContext, w, h) ?: return null
        synchronized(lock) {
            cache[o]?.let {
                if (it.file == file && it.framing == framing && it.width == w && it.height == h) return it
            }
        }
        val bitmap = WallpaperStore.decodeFramed(file, framing, w, h) ?: return null
        val background = if (framing == Framing.Fit) WallpaperStore.blurSource(bitmap) else null
        val cached = Cached(file, framing, w, h, bitmap, background)
        synchronized(lock) { cache[o] = cached }
        return cached
    }

    private inner class DualEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var surfaceWidth = 0
        private var surfaceHeight = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // No touch events, no offsets: we never need them, so the system won't send them.
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(false)
            WallpaperStore.prefs(applicationContext).registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            WallpaperStore.prefs(applicationContext).unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }

        /** Called on every rotation (and when the wallpaper first appears). */
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            draw()
            // Swapped dimensions = the screen after the next rotation.
            workerHandler.post { pictureFor(height, width) }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) draw()
        }

        /** The settings screen pressed "Apply": forget decoded pictures and redraw. */
        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key != WallpaperStore.KEY_VERSION) return
            synchronized(lock) { cache.clear() }
            draw()
            workerHandler.post { pictureFor(surfaceHeight, surfaceWidth) }
        }

        private fun draw() {
            val w = surfaceWidth
            val h = surfaceHeight
            if (w <= 0 || h <= 0) return
            val picture = pictureFor(w, h)
            val holder = surfaceHolder
            val canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: return
            try {
                WallpaperRenderer.draw(
                    canvas, picture?.bitmap, w, h,
                    framing = picture?.framing ?: Framing.Crop(NormCrop.FULL),
                    background = picture?.background,
                )
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
