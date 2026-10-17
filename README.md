# DualWall

Lightweight Android live wallpaper that shows a different picture in portrait and landscape.

- **Instant switching** — the wallpaper engine redraws in `onSurfaceChanged` when the screen rotates; no background service, no polling, nothing runs between rotations.
- **No permissions** — pictures are picked through the system gallery and copied into app storage. No internet, no ads, no trackers.
- **One wide picture is enough** — portrait can reuse the landscape picture with its own frame.
- **Framing per orientation** — crop editor (pan / pinch-zoom, rule-of-thirds grid), stretch to fill, or fit with a blurred backdrop.
- **Huge photos** — only the needed region is decoded (`BitmapRegionDecoder`), scaled to the screen and rotated per EXIF; home and lock screen share one cache.

## Build

Requirements: Android Studio (JDK 17+), Android SDK 37. Min Android 9 (API 28).

```
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # JVM tests (crop math)
./gradlew connectedDebugAndroidTest  # device tests
```

Open the app, choose pictures, tap **Применить** and confirm DualWall in the system wallpaper picker.

## Code

| File | Role |
| --- | --- |
| `DualWallpaperService.kt` | Live wallpaper engine, rotation handling, shared bitmap cache |
| `WallpaperStore.kt` | Picked files, pending/applied state, region decoding |
| `WallpaperRenderer.kt` | Draws a decoded picture on the wallpaper canvas |
| `CropMath.kt` | Pure geometry (crop, fit, sampling, EXIF), unit-tested on the JVM |
| `CropEditor.kt` | Compose crop editor and framed preview |
| `MainActivity.kt` | Settings screen |
