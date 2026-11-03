package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import android.content.Intent
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.domain.LockState
import com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime
import org.junit.*
import org.junit.Assert.*

class T55SecureOverlayDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @After fun cleanup() {SystemProjectionConsent.cancelOwnDialog();instrumentation.runOnMainSync {FloatingRuntime.stop(instrumentation.targetContext);PrivacyRuntime.setDelay(0);PrivacyRuntime.setEnabled(false);CounselRuntime.get(instrumentation.targetContext.applicationContext as Application).updateLockState(LockState())}}
    @Test fun actualWindowManagerPanelIsSecureAndLockRemovesOverlayProjectionAndService() {
        val context=instrumentation.targetContext
        val controller=CounselRuntime.get(context.applicationContext as Application)
        instrumentation.runOnMainSync {PrivacyRuntime.setDelay(0);PrivacyRuntime.setEnabled(true);controller.updateLockState(LockState(true,true))}
        compose.onNodeWithText("开启悬浮军师").performClick()
        SystemProjectionConsent.continueGuide(compose);SystemProjectionConsent.respond(true)
        compose.waitUntil(15000) {FloatingRuntime.active.value}
        ActivityScenario.launch<T33SyntheticInputActivity>(Intent(context,T33SyntheticInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {target ->
            target.onActivity {checkNotNull(FloatingRuntime.showAnswer).invoke()}
            compose.waitUntil(15000) {WindowInspector.getGlobalWindowViews().any {view ->(view.layoutParams as? WindowManager.LayoutParams)?.let {it.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && it.height>200 && it.flags and WindowManager.LayoutParams.FLAG_SECURE!=0}==true}}
            instrumentation.runOnMainSync {controller.updateLockState(LockState(true))}
            compose.waitUntil(15000) {!FloatingRuntime.active.value && WindowInspector.getGlobalWindowViews().none {(it.layoutParams as? WindowManager.LayoutParams)?.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY}}
            assertFalse(FloatingRuntime.active.value)
            println("T55_NATIVE actual_secure_overlay_and_lock_cleanup_pass")
        }
    }
}
