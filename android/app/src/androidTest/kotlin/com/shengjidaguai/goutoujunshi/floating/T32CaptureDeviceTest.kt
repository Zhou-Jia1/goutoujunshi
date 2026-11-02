package com.shengjidaguai.goutoujunshi.floating

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.capture.SyntheticChatActivity
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.domain.Speaker
import com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide
import org.junit.*
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated API35 NoKey fixture. Real SystemUI consent and physical overlay gestures. */
class T32CaptureDeviceTest {
 @get:Rule val compose=createAndroidComposeRule<FloatingCounselActivity>()
 private val application get()=instrumentation.targetContext.applicationContext as android.app.Application
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private fun physicalBallBounds():Rect {
  val screen=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
  val physical=Rect(0,0,screen.width,screen.height);screen.recycle()
  var result:Rect?=null
  SystemProjectionConsent.waitFor {
   val snapshot=SystemProjectionConsent.nodes()
   val candidates=snapshot.filter {
    it.isVisibleToUser && it.contentDescription?.toString()==application.getString(com.shengjidaguai.goutoujunshi.R.string.floating_ball_menu) &&
      it.window?.displayId==android.view.Display.DEFAULT_DISPLAY
   }.mapNotNull { node ->
    val rect=Rect();node.getBoundsInScreen(rect)
    // Only the ball intentionally docks partly outside the display; tap its visible half.
    if(rect.intersect(physical) && !rect.isEmpty) rect else null
   }.distinct()
   result=candidates.singleOrNull();result!=null
  }
  return checkNotNull(result)
 }
 private fun physicalTap(bounds:Rect) {
  val now=SystemClock.uptimeMillis()
  val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }
  try { Assert.assertTrue(instrumentation.uiAutomation.injectInputEvent(down,true)) } finally { down.recycle() }
  SystemClock.sleep(60)
  val up=MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0).apply { source=android.view.InputDevice.SOURCE_TOUCHSCREEN }
  try { Assert.assertTrue(instrumentation.uiAutomation.injectInputEvent(up,true)) } finally { up.recycle() }
 }
 @After fun cleanup() {
  SystemProjectionConsent.cancelOwnDialog()
  instrumentation.runOnMainSync { FloatingRuntime.frameObserver=null;FloatingRuntime.stop(application) }
 }
 @Test fun physicalCaptureUsesPaddleAndExcludesBallPixels() {
  var baseline:android.graphics.Bitmap?=null
  ActivityScenario.launch(SyntheticChatActivity::class.java).use {
   SystemProjectionConsent.waitFor { SystemProjectionConsent.nodes().any { it.text?.toString()=="合成测试聊天" } }
   instrumentation.waitForIdleSync();SystemClock.sleep(500)
   baseline=instrumentation.uiAutomation.takeScreenshot()
  }
  try {
   compose.onNodeWithText("开启悬浮军师").performClick()
        SystemProjectionConsent.continueGuide(compose)
   SystemProjectionConsent.respond(true)
   SystemProjectionConsent.waitFor { FloatingRuntime.active.value }
   ActivityScenario.launch(SyntheticChatActivity::class.java).use {
    instrumentation.waitForIdleSync();SystemClock.sleep(500)
    val roi=physicalBallBounds()
    val before=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    val reference=checkNotNull(baseline)
    roi.intersect(0,0,reference.width,reference.height)
    var overlayDifference=0
    for(y in roi.top until roi.bottom) for(x in roi.left until roi.right) if(before.getPixel(x,y)!=reference.getPixel(x,y)) overlayDifference++
    before.recycle();Assert.assertTrue("Physical overlay must be visible before capture",overlayDifference>20)
    val observed=AtomicBoolean(false);val pixelsMatch=AtomicBoolean(false)
    instrumentation.runOnMainSync {
     FloatingRuntime.frameObserver={ ticket,frame ->
      if(ticket==FloatingRuntime.session.epoch()) {
       var different=0
       for(y in roi.top until roi.bottom) for(x in roi.left until roi.right) {
        val a=frame.getPixel(x,y);val b=reference.getPixel(x,y)
        if(kotlin.math.abs(android.graphics.Color.red(a)-android.graphics.Color.red(b))>3 || kotlin.math.abs(android.graphics.Color.green(a)-android.graphics.Color.green(b))>3 || kotlin.math.abs(android.graphics.Color.blue(a)-android.graphics.Color.blue(b))>3) different++
       }
       pixelsMatch.set(different<maxOf(2,roi.width()*roi.height()/100));observed.set(true)
      }
     }
    }
    physicalTap(roi)
    SystemProjectionConsent.waitFor(20_000) { observed.get() }
    Assert.assertTrue("Production frame must match synthetic background at physical ball ROI",pixelsMatch.get())
    val imports=CounselRuntime.imports(application)
    SystemProjectionConsent.waitFor(120_000) { imports.state.value?.recognized==true }
    val preview=checkNotNull(imports.state.value)
    Assert.assertTrue(preview.rows.any { it.text.contains("公园") })
    Assert.assertTrue(preview.rows.any { it.text.contains("三点") })
    instrumentation.runOnMainSync { imports.side(preview.id,OcrSide.RIGHT) }
    val rows=checkNotNull(imports.state.value).rows
    Assert.assertEquals(Speaker.ME,rows.first { it.text.contains("三点") }.speaker)
    Assert.assertEquals(Speaker.THEM,rows.first { it.text.contains("公园") }.speaker)
    Assert.assertNull(CounselRuntime.get(application).state.value.currentRequest)
   }
  } finally { baseline?.recycle() }
 }
 @Test fun deniedConsentNeverStartsProjectionOrNotification() {
  compose.onNodeWithText("开启悬浮军师").performClick();SystemProjectionConsent.continueGuide(compose);SystemProjectionConsent.respond(false)
  SystemProjectionConsent.waitFor { SystemProjectionConsent.nodes().none { it.viewIdResourceName=="com.android.systemui:id/screen_share_mode_spinner" } }
  compose.onNodeWithText(application.getString(com.shengjidaguai.goutoujunshi.R.string.floating_permission_denied)).assertIsDisplayed()
  Assert.assertFalse(FloatingRuntime.active.value)
  Assert.assertTrue(application.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id==FloatingCounselService.NOTIFICATION })
 }
 @Test fun physicalClickOverMainShowsReturnToChatHintAndPreservesDraftWithoutCapture() {
  compose.onNodeWithText(application.getString(com.shengjidaguai.goutoujunshi.R.string.floating_start)).performClick()
        SystemProjectionConsent.continueGuide(compose)
  SystemProjectionConsent.respond(true)
  SystemProjectionConsent.waitFor { FloatingRuntime.active.value }
  val frames=java.util.concurrent.atomic.AtomicInteger()
  instrumentation.runOnMainSync { FloatingRuntime.frameObserver={ _,_ -> frames.incrementAndGet() } }
  val imports=CounselRuntime.imports(application)
  val previous=imports.state.value?.id
  val controller=CounselRuntime.get(application)
  var mainActivity:com.shengjidaguai.goutoujunshi.MainActivity?=null
  ActivityScenario.launch(com.shengjidaguai.goutoujunshi.MainActivity::class.java).use { scenario ->
   scenario.onActivity { mainActivity=it }
   SystemProjectionConsent.waitFor { !CaptureOwnUi.snapshot().clear && !controller.state.value.loading && controller.state.value.conversation!=null }
   instrumentation.runOnMainSync { controller.editDraft("合成主界面私密草稿") }
   SystemProjectionConsent.waitFor { controller.state.value.draft=="合成主界面私密草稿" }
   val conversation=controller.state.value.conversation!!.id
   Assert.assertFalse(CaptureOwnUi.notice.value)
   val bounds=physicalBallBounds()
   physicalTap(bounds)
   try { SystemProjectionConsent.waitFor { CaptureOwnUi.notice.value } }
   catch(error:IllegalStateException) {
    throw AssertionError("Main gesture notice missing: mainVisible=${!CaptureOwnUi.snapshot().clear}, phase=${FloatingRuntime.session.capture.phase}, ball=$bounds",error)
   }
   compose.onNodeWithText(application.getString(com.shengjidaguai.goutoujunshi.R.string.capture_return_chat)).assertIsDisplayed()
   Assert.assertEquals(0,frames.get())
   Assert.assertFalse(checkNotNull(mainActivity).isFinishing)
   Assert.assertFalse(CaptureOwnUi.snapshot().clear)
   Assert.assertEquals(previous,imports.state.value?.id)
   Assert.assertEquals(conversation,controller.state.value.conversation!!.id)
   Assert.assertEquals("合成主界面私密草稿",controller.state.value.draft)
   Assert.assertNull(controller.state.value.currentRequest)
   Assert.assertEquals(CaptureState.Phase.READY,FloatingRuntime.session.capture.phase)
  }
 }

}
