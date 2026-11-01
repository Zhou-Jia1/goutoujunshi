package com.shengjidaguai.goutoujunshi.floating

import android.app.NotificationManager
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*

/** Requires root operator to grant SAW and notification permission on the dedicated emulator. */
class FloatingCounselDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    @After fun cleanup() { SystemProjectionConsent.cancelOwnDialog();FloatingRuntime.stop(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext) }
    @Test fun userStartCreatesForegroundOverlayAndStopRemovesNotification() {
        Assert.assertTrue(Settings.canDrawOverlays(compose.activity))
        compose.onNodeWithText("截屏分析").assertIsNotEnabled()
        compose.onNodeWithText("开启悬浮军师").performClick()
        SystemProjectionConsent.respond(true)
        val notifications=compose.activity.getSystemService(NotificationManager::class.java)
        compose.waitUntil(10_000) { FloatingRuntime.active.value && notifications.activeNotifications.any { it.id==FloatingCounselService.NOTIFICATION } }
        Assert.assertTrue(notifications.activeNotifications.any { it.id==FloatingCounselService.NOTIFICATION })
        compose.onNodeWithText("关闭悬浮军师").performScrollTo().performClick()
        compose.waitUntil(10_000) { !FloatingRuntime.active.value && notifications.activeNotifications.none { it.id==FloatingCounselService.NOTIFICATION } }
    }
}
