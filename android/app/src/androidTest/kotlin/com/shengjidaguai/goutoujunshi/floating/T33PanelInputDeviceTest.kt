package com.shengjidaguai.goutoujunshi.floating

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*

/** Genuine consent, real overlay windows and physical input; no semantic text injection. */
class T33PanelInputDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun removeOwnTasks() {
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val component=android.content.ComponentName(context,T33SyntheticInputActivity::class.java)
            context.getSystemService(android.app.ActivityManager::class.java).appTasks.filter { it.taskInfo?.baseIntent?.component==component }.forEach { it.finishAndRemoveTask() }
        }
    }
    @Before fun before() { removeOwnTasks();T33NativeUi.pump={ compose.mainClock.advanceTimeByFrame();compose.waitForIdle() } }
    @After fun cleanup() {
        SystemProjectionConsent.cancelOwnDialog()
        instrumentation.runOnMainSync { FloatingRuntime.stop(instrumentation.targetContext) }
        removeOwnTasks();T33NativeUi.pump={}
    }
    private fun diagnose(stage:String)=T33NativeUi.diagnose(stage)
    private fun tap(node:AccessibilityNodeInfo)=T33NativeUi.tap(node)
    private fun visible(text:String)=T33NativeUi.visible(text)
    private fun scrollTo(text:String,windowId:Int)=T33NativeUi.scrollTo(text,windowId)
    private fun editor(activity:T33SyntheticInputActivity):EditText {
        val fields=java.util.ArrayList<View>()
        activity.window.decorView.findViewsWithText(fields,activity.getString(com.shengjidaguai.goutoujunshi.R.string.t33_synthetic_input),View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return fields.filterIsInstance<EditText>().single()
    }
    @Test fun panelAndSupplementPreserveAndRestoreNativeInputFocus() {
        compose.onNodeWithText("开启悬浮军师").performClick()
        SystemProjectionConsent.respond(true)
        compose.waitUntil(10000) { FloatingRuntime.active.value }
        if(InstrumentationRegistry.getArguments().getString("t33AwaitFont260")=="true") {
            instrumentation.sendStatus(0,android.os.Bundle().apply { putString("t33.awaitFont","true") })
            compose.waitUntil(120000) { instrumentation.targetContext.applicationContext.resources.configuration.fontScale>=2.59f }
            compose.waitForIdle()
        }

        val context=instrumentation.targetContext
        ActivityScenario.launch<T33SyntheticInputActivity>(Intent(context,T33SyntheticInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { target ->
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A)
            target.onActivity { activity ->
                assertTrue(editor(activity).hasFocus());assertEquals("a",editor(activity).text.toString())
                checkNotNull(FloatingRuntime.showAnswer) { "The production panel hook must exist" }.invoke()
            }
            compose.waitUntil(15000) { visible("拖动调整面板高度")!=null }
            val handle=checkNotNull(visible("拖动调整面板高度"))
            diagnose("panel-visible-before-physical-keys")
            val panel=checkNotNull(handle.window)
            val panelBounds=Rect();panel.getBoundsInScreen(panelBounds)
            val screen=instrumentation.targetContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            assertTrue(panelBounds.height()>0)
            assertTrue("Actual overlay exceeds 55%",panelBounds.height()<=screen.height()*.55f+1)
            var scrollBefore=0;var scrollAfter=0
            target.onActivity { activity -> scrollBefore=(editor(activity).parent.parent as android.widget.ScrollView).scrollY }
            T33NativeUi.swipeUnderlyingAbovePanel(panelBounds)
            compose.waitUntil(5000) {
                target.onActivity { activity -> scrollAfter=(editor(activity).parent.parent as android.widget.ScrollView).scrollY }
                scrollAfter>scrollBefore
            }
            instrumentation.sendStatus(0,android.os.Bundle().apply { putString("t33.underlyingScroll","before=$scrollBefore after=$scrollAfter changed=${scrollAfter>scrollBefore}") })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_B)
            target.onActivity { activity ->
                assertTrue(editor(activity).hasFocus());assertEquals("ab",editor(activity).text.toString())
            }
            tap(scrollTo("补充一句",handle.windowId))
            try {
                compose.waitUntil(15000) { T33NativeUi.nodes().any { it.windowId==handle.windowId && it.isFocused && it.isEditable } }
            } catch(error:IllegalStateException) { diagnose("supplement-focus-timeout-after-physical-tap");throw error }
            diagnose("supplement-focused-with-ime")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_C)
            compose.waitUntil(15000) { T33NativeUi.nodes().any { it.windowId==handle.windowId && it.isEditable && it.text?.toString()?.contains("c")==true } }
            tap(scrollTo("完成补充",handle.windowId))
            compose.waitUntil(15000) { visible("完成补充")==null }
            diagnose("supplement-done-restore")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_D)
            target.onActivity { activity ->
                assertTrue(editor(activity).hasFocus());assertEquals("abd",editor(activity).text.toString())
            }
        }
    }
}
