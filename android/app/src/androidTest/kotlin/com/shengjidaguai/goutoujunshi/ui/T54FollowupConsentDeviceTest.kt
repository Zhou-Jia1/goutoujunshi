package com.shengjidaguai.goutoujunshi.ui
import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.counsel.FollowupOffer
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class T54FollowupConsentDeviceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun explicitOptinRequestsRealPermissionAndDenialReturnsWithoutScheduling()=exercise(false)
 @Test fun explicitOptinRequestsRealPermissionAndGrantReturnsChosenReceipt()=exercise(true)
 private fun exercise(grant:Boolean) {
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
  assertEquals("Root must revoke and clear user-set/user-fixed permission flags before EACH method",PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
  val result=java.util.concurrent.atomic.AtomicReference<Boolean?>()
  val selected=java.util.concurrent.atomic.AtomicReference<FollowupOffer?>()
  val offer=FollowupOffer("synthetic-owner","synthetic-plan","synthetic-conversation",0,0)
  compose.setContent {T54DeviceTheme {Surface {Column(Modifier.verticalScroll(rememberScrollState())) {
   val permission=rememberFollowupPermission {receipt,_,allowed->selected.set(receipt);result.set(allowed)}
   FollowupPrompt(offer,{permission(offer,it)})
  }}}}
  compose.onNode(isToggleable()).assertIsOff()
  assertNull(result.get());assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
  compose.onNodeWithText("一小时后").performScrollTo().performClick()
  compose.onNodeWithText("确认提醒时间").assertIsNotEnabled()
  assertNull(result.get())
  compose.onNode(isToggleable()).performScrollTo().performClick()
  compose.onNodeWithText("确认提醒时间").performScrollTo().performClick()
  val serviceInfo=instrumentation.uiAutomation.serviceInfo
  serviceInfo.flags=serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
  instrumentation.uiAutomation.serviceInfo=serviceInfo
  val viewId="com.android.permissioncontroller:id/"+if(grant) "permission_allow_button" else "permission_deny_button"
  val deadline=System.currentTimeMillis()+10000
  var clicked=false
  while(System.currentTimeMillis()<deadline && !clicked) {
   val nodes=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
   clicked=nodes.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
   if(!clicked) Thread.sleep(50)
  }
  assertTrue("The real Android13+ notification permission dialog must appear",clicked)
  compose.waitUntil(timeoutMillis=10000) {result.get()!=null}
  assertEquals(grant,result.get());assertEquals(offer,selected.get())
  if(!grant) {
   val expected=if(InstrumentationRegistry.getArguments().getString("t54Mode","light")=="font260") 2.6f else 1f
   for(text in listOf("没有通知权限，未安排提醒。可以继续使用军师。","关闭")) {
    val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
    compose.onNodeWithText(text).assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {it(layouts)}
    val effective=layouts.single().layoutInput.density.fontScale
    println("T54_DIALOG_FONT expected=$expected actual=$effective height=${layouts.single().size.height}")
    assertEquals("Dialog contents, not only the parent theme, inherit the effective font scale",expected,effective,0.01f)
   }
   compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
  }
 }
}
