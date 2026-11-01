package com.shengjidaguai.goutoujunshi.floating

import android.Manifest
import android.app.*
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN")
class FloatingActivityStatusTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    @After fun cleanup() { FloatingRuntime.stop(RuntimeEnvironment.getApplication()) }
    @Test fun rejectedStartAndRealServiceStopCannotLeaveSuccessText() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowSettings.setCanDrawOverlays(true)
        compose.onNodeWithText("开启悬浮军师").performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val rejectedIntent=shadowOf(app).nextStartedService!!
        ShadowSettings.setCanDrawOverlays(false)
        val rejected=Robolectric.buildService(FloatingCounselService::class.java).create()
        try {
            compose.runOnIdle { rejected.get().onStartCommand(rejectedIntent,0,1) }
            assertFalse(FloatingRuntime.active.value)
            assertEquals(0,app.getSystemService(NotificationManager::class.java).activeNotifications.size)
            compose.onNodeWithText("悬浮军师已开启",substring=true).assertDoesNotExist()
        } finally { rejected.destroy() }
        ShadowSettings.setCanDrawOverlays(true)
        compose.onNodeWithText("开启悬浮军师").performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val acceptedIntent=shadowOf(app).nextStartedService!!
        val accepted=Robolectric.buildService(FloatingCounselService::class.java).create()
        try {
            compose.runOnIdle { accepted.get().onStartCommand(acceptedIntent,0,2) }
            compose.onNodeWithText("悬浮军师已开启",substring=true).assertIsDisplayed()
            assertTrue(FloatingRuntime.active.value)
            assertEquals(1,app.getSystemService(NotificationManager::class.java).activeNotifications.size)
            compose.runOnIdle { accepted.get().onStartCommand(Intent().setAction(FloatingCounselService.STOP),0,3) }
            compose.onNodeWithText("悬浮军师已开启",substring=true).assertDoesNotExist()
            assertFalse(FloatingRuntime.active.value)
            assertEquals(0,app.getSystemService(NotificationManager::class.java).activeNotifications.size)
        } finally { accepted.destroy() }
    }
}
