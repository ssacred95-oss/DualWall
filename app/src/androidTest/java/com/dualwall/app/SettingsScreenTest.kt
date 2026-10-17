package com.dualwall.app

import android.content.pm.PackageManager
import android.service.wallpaper.WallpaperService
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun showsBothSlotsAndApply() {
        rule.onNodeWithText("Вертикально").assertExists()
        rule.onNodeWithText("Горизонтально").assertExists()
        rule.onNodeWithTag("slot_portrait").assertExists()
        rule.onNodeWithTag("slot_landscape").assertExists()
        rule.onNodeWithTag("apply").assertExists()
    }

    @Test fun applyIsDisabledWithoutPictures() {
        WallpaperStore.clear(ctx)
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("apply").assertIsNotEnabled()
    }

    @Test fun applyIsEnabledWithAPicture() {
        WallpaperStore.clear(ctx)
        val f = java.io.File(ctx.cacheDir, "x.png")
        android.graphics.Bitmap.createBitmap(9, 16, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f.outputStream())
        WallpaperStore.savePending(ctx, Orientation.PORTRAIT, android.net.Uri.fromFile(f))
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("apply").assertIsEnabled()
        rule.onNodeWithText("своя картинка").assertExists()
        rule.onNodeWithTag("crop_portrait").assertExists()
        rule.onNodeWithTag("fit_portrait").assertExists()
        WallpaperStore.clear(ctx)
    }

    @Test fun wallpaperServiceIsRegisteredWithTheSystem() {
        val services = ctx.packageManager.queryIntentServices(
            android.content.Intent(WallpaperService.SERVICE_INTERFACE).setPackage(ctx.packageName),
            PackageManager.GET_META_DATA,
        )
        assertTrue(services.any { it.serviceInfo.name == DualWallpaperService::class.java.name })
    }

    @Test fun noNetworkPermission() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions?.toList().orEmpty()
        assertTrue(perms.toString(), "android.permission.INTERNET" !in perms)
    }
}
