package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.Shadows.shadowOf
import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN")
class T34PermissionJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    @After fun cleanup() { FloatingRuntime.stop(RuntimeEnvironment.getApplication()) }
    @Test fun explicitStartExplainsPrivacyBeforeAnySystemRequestAndCanCancel() {
        ShadowSettings.setCanDrawOverlays(true)
        compose.onNodeWithText("开启悬浮军师").performClick()
        compose.onNodeWithText("只在你点击时看屏幕",substring=true).assertIsDisplayed()
        compose.onNodeWithText("稍后再说").performScrollTo().performClick()
        compose.onNodeWithText("开启悬浮军师").assertIsDisplayed()
        Assert.assertFalse(FloatingRuntime.active.value)
    }
    private fun requestNotification() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.onNodeWithText("开启悬浮军师").performClick()
        compose.onNodeWithText("了解并继续").performScrollTo().performClick()
        compose.onNodeWithText("跳过，继续开启").performScrollTo().performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun denyNotification() {
        val request=shadowOf(compose.activity).lastRequestedPermission
        Assert.assertNotNull(request)
        Assert.assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        compose.runOnIdle { compose.activity.onRequestPermissionsResult(request.requestCode,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),intArrayOf(PackageManager.PERMISSION_DENIED)) }
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun deniedNotificationWaitsForExplicitContinueAndDoesNotReRequest() {
        requestNotification();denyNotification()
        compose.onNodeWithText("通知没有开启，也可以继续").assertIsDisplayed()
        Assert.assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        compose.onNodeWithText("了解并继续").performScrollTo().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        Assert.assertTrue(shadowOf(compose.activity).nextStartedActivityForResult.requestCode in 3201..65000)
        Assert.assertFalse(FloatingRuntime.active.value)
    }
    @Test fun stoppedRequestDoesNotShowDeniedContinueOrOpenProjectionOnLateResult() {
        requestNotification()
        compose.runOnIdle { FloatingRuntime.session.stop() }
        denyNotification()
        compose.onNodeWithText("通知没有开启，也可以继续").assertDoesNotExist()
        Assert.assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        Assert.assertFalse(FloatingRuntime.active.value)
    }
    /** Android's saved outstanding-request guard rejects B synchronously; no synthetic flag edits. */
    @Test fun frameworkRejectsSecondNotificationRequestAfterRecreationAndLateAIsHarmless() {
        fun launchedKeys():List<String> {
            val saved=android.os.Bundle()
            compose.activity.activityResultRegistry.onSaveInstanceState(saved)
            return saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS").orEmpty()
        }
        fun frameworkOutstanding()=android.app.Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
            .apply {isAccessible=true}.getBoolean(compose.activity)
        requestNotification()
        val a=shadowOf(compose.activity).lastRequestedPermission.requestCode
        Assert.assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        Assert.assertEquals(listOf("activity_rq#0"),launchedKeys())
        Assert.assertTrue(frameworkOutstanding())
        compose.activityRule.scenario.recreate()
        Assert.assertTrue(frameworkOutstanding())
        requestNotification()
        Assert.assertNull("Framework must not start a second permissions transport",shadowOf(compose.activity).nextStartedActivityForResult)
        compose.onNodeWithText("通知没有开启，也可以继续").assertIsDisplayed()
        Assert.assertEquals(listOf("activity_rq#0"),launchedKeys())
        Assert.assertTrue(frameworkOutstanding())
        val authorityField=FloatingCounselActivity::class.java.getDeclaredField("notificationAuthority").apply {isAccessible=true}
        Assert.assertNull("B's synchronous empty result already consumed its callback authority",authorityField.get(compose.activity))
        val ticketField=FloatingCounselActivity::class.java.getDeclaredField("ticket").apply {isAccessible=true}
        val bTicket=ticketField.get(compose.activity) as Long
        val completion=android.content.Intent()
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES",arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS",intArrayOf(PackageManager.PERMISSION_GRANTED))
        // Complete the genuine A request through Activity's dispatcher, which owns and clears its flag.
        compose.runOnIdle {
            android.app.Activity::class.java.getDeclaredMethod("dispatchRequestPermissionsResult",Int::class.javaPrimitiveType,android.content.Intent::class.java)
                .apply {isAccessible=true}.invoke(compose.activity,a,completion)
        }
        shadowOf(Looper.getMainLooper()).idle()
        Assert.assertFalse(frameworkOutstanding());Assert.assertTrue(launchedKeys().isEmpty())
        Assert.assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        Assert.assertNull(authorityField.get(compose.activity))
        Assert.assertTrue(FloatingRuntime.session.valid(bTicket));Assert.assertEquals(bTicket,ticketField.get(compose.activity))
        compose.onNodeWithText("通知没有开启，也可以继续").assertIsDisplayed()
        compose.onNodeWithText("了解并继续").performScrollTo().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        Assert.assertTrue(shadowOf(compose.activity).nextStartedActivityForResult.requestCode in 3201..65000)
    }
}
