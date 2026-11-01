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
@Config(application=Application::class,sdk=[35],shadows=[ProjectionTestManager::class,ProjectionTestProjection::class,ProjectionTestDisplay::class],qualifiers="zh-rCN")
class FloatingActivityStatusTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    @After fun cleanup() { FloatingRuntime.stop(RuntimeEnvironment.getApplication()) }
    private fun grantIntent():Intent {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowSettings.setCanDrawOverlays(true)
        compose.onNodeWithText("开启悬浮军师").performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.runOnIdle { deliverProjectionResult(compose.activity) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return checkNotNull(shadowOf(app).nextStartedService)
    }
    @Test fun rejectedStartFinishesOwnedWindowWithoutSuccessOrNotification() {
        val intent=grantIntent();val activity=compose.activity
        ShadowSettings.setCanDrawOverlays(false)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        try {
            compose.runOnIdle { holder.get().onStartCommand(intent,0,1) }
            assertFalse(FloatingRuntime.active.value);assertTrue(activity.isFinishing)
            assertEquals(0,activity.getSystemService(NotificationManager::class.java).activeNotifications.size)
        } finally { holder.destroy() }
    }
    @Test fun realServiceStopClosesOwnedControlAndRemovesNotification() {
        val intent=grantIntent();val activity=compose.activity
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        try {
            compose.runOnIdle { holder.get().onStartCommand(intent,0,1) }
            compose.onNodeWithText("悬浮军师已开启",substring=true).assertIsDisplayed()
            assertTrue(FloatingRuntime.active.value)
            assertEquals(1,activity.getSystemService(NotificationManager::class.java).activeNotifications.size)
            compose.runOnIdle { holder.get().onStartCommand(Intent().setAction(FloatingCounselService.STOP),0,2) }
            assertTrue(activity.isFinishing);assertFalse(FloatingRuntime.active.value)
            assertEquals(0,activity.getSystemService(NotificationManager::class.java).activeNotifications.size)
        } finally { holder.destroy() }
    }
}
