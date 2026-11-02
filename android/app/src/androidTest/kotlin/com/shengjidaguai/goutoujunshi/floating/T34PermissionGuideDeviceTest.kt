package com.shengjidaguai.goutoujunshi.floating

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.R
import org.junit.*

/** Synthetic fixture; operator owns device permission initial conditions. No fake projection grant. */
class T34PermissionGuideDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    private fun text(id:Int)=compose.activity.getString(id)
    private fun click(id:Int)=compose.onNodeWithText(text(id)).performScrollTo().performClick()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext
    @After fun cleanup() { SystemProjectionConsent.cancelOwnDialog();instrumentation.runOnMainSync {FloatingRuntime.stop(app)} }
    private fun returned() {
        compose.waitUntil(15_000) { compose.activity.lifecycle.currentState==androidx.lifecycle.Lifecycle.State.RESUMED && compose.activity.hasWindowFocus() }
    }
    private fun systemClick(node:AccessibilityNodeInfo) {
        var target:AccessibilityNodeInfo?=node
        while(target!=null && !target.isClickable) target=target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    private fun settingsNodes()=SystemProjectionConsent.nodes().filter {it.packageName?.toString()=="com.android.settings"}
    private fun backFromSettings() {
        val automation=instrumentation.uiAutomation
        repeat(3) {
            if(compose.activity.lifecycle.currentState==androidx.lifecycle.Lifecycle.State.RESUMED && compose.activity.hasWindowFocus()) return
            if(automation.rootInActiveWindow?.packageName?.toString()=="com.android.settings") {
                check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                android.os.SystemClock.sleep(250)
            }
        }
        returned()
    }

    @Test fun explanationAndLaterTroubleshootingCanBeCancelledWithoutScreenSharing() {
        click(R.string.floating_start)
        compose.onNodeWithText(text(R.string.guide_intro_body)).assertIsDisplayed()
        click(R.string.guide_cancel)
        Assert.assertFalse(SystemProjectionConsent.ownDialog())
        click(R.string.guide_help)
        compose.onNodeWithText(text(R.string.guide_version_notice)).performScrollTo().assertIsDisplayed()
        click(R.string.guide_skip)
        compose.onNodeWithText(text(R.string.floating_start)).assertIsDisplayed()
        Assert.assertFalse(SystemProjectionConsent.ownDialog());Assert.assertFalse(FloatingRuntime.active.value)
    }
    /** Run with SAW allowed and POST_NOTIFICATIONS revoked, user-set/user-fixed flags cleared. */
    @Test fun deniedNotificationRequiresExplicitContinueBeforeRealProjectionConsent() {
        Assert.assertTrue(Settings.canDrawOverlays(compose.activity))
        Assert.assertEquals(PackageManager.PERMISSION_DENIED,compose.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        click(R.string.floating_start);SystemProjectionConsent.continueGuide(compose)
        val owners=setOf("com.android.permissioncontroller","com.google.android.permissioncontroller")
        val label=app.applicationInfo.loadLabel(app.packageManager).toString()
        SystemProjectionConsent.waitFor {
            val nodes=SystemProjectionConsent.nodes().filter {it.packageName?.toString() in owners}
            nodes.any {it.text?.contains(label)==true} && nodes.any {it.viewIdResourceName?.endsWith(":id/permission_deny_button")==true && it.isEnabled}
        }
        val deny=SystemProjectionConsent.nodes().single {it.packageName?.toString() in owners && it.viewIdResourceName?.endsWith(":id/permission_deny_button")==true && it.isEnabled}
        systemClick(deny)
        returned()
        compose.onNodeWithText(text(R.string.guide_notification_title)).assertIsDisplayed()
        Assert.assertFalse(SystemProjectionConsent.ownDialog());Assert.assertFalse(FloatingRuntime.active.value)
        click(R.string.guide_continue)
        SystemProjectionConsent.respond(true)
        compose.waitUntil(15_000) {FloatingRuntime.active.value}
        click(R.string.floating_stop)
        compose.waitUntil(10_000) {!FloatingRuntime.active.value}
    }
    /** Run with SAW denied. Returning without granting must never ask for projection. */
    @Test fun deniedOverlayReturnRechecksAndNeverOpensProjection() {
        Assert.assertFalse(Settings.canDrawOverlays(compose.activity))
        click(R.string.floating_start);click(R.string.guide_continue)
        click(R.string.guide_open_overlay)
        SystemProjectionConsent.waitFor {settingsNodes().isNotEmpty()}
        backFromSettings()
        compose.onNodeWithText(text(R.string.floating_start)).assertIsDisplayed()
        Assert.assertFalse(SystemProjectionConsent.ownDialog());Assert.assertFalse(FloatingRuntime.active.value)
    }
    /** Run with SAW denied and notifications granted; toggle only this app in genuine Settings. */
    @Test fun grantedOverlayReturnRechecksThenUsesRealProjectionConsent() {
        Assert.assertFalse(Settings.canDrawOverlays(app))
        Assert.assertEquals(PackageManager.PERMISSION_GRANTED,app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        click(R.string.floating_start);click(R.string.guide_continue);click(R.string.guide_open_overlay)
        val label=app.applicationInfo.loadLabel(app.packageManager).toString()
        SystemProjectionConsent.waitFor {settingsNodes().any {it.text?.toString()==label}}
        var switches=settingsNodes().filter {it.isCheckable && it.isEnabled}
        if(switches.isEmpty()) {
            systemClick(settingsNodes().first {it.text?.toString()==label})
            SystemProjectionConsent.waitFor {settingsNodes().any {it.text?.toString()==label} && settingsNodes().any {it.isCheckable && it.isEnabled}}
            switches=settingsNodes().filter {it.isCheckable && it.isEnabled}
        }
        check(settingsNodes().any {it.text?.toString()==label})
        val toggle=switches.single()
        check(!toggle.isChecked);systemClick(toggle)
        SystemProjectionConsent.waitFor {Settings.canDrawOverlays(app)}
        backFromSettings()
        compose.onNodeWithText(text(R.string.guide_background_title)).assertIsDisplayed()
        click(R.string.guide_skip)
        SystemProjectionConsent.respond(true)
        compose.waitUntil(15_000) {FloatingRuntime.active.value}
        click(R.string.floating_stop)
        compose.waitUntil(10_000) {!FloatingRuntime.active.value}
    }
}
