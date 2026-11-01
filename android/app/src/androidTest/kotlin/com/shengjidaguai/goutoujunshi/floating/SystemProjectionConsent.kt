package com.shengjidaguai.goutoujunshi.floating
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
internal object SystemProjectionConsent {
 private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
 fun nodes():List<AccessibilityNodeInfo> {
  val info=automation.serviceInfo
  if(info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS==0) {
   info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
   automation.serviceInfo=info
  }
  val roots=automation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
  fun walk(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).flatMap { node.getChild(it)?.let(::walk).orEmpty() }
  return roots.flatMap(::walk)
 }
 fun waitFor(timeout:Long=15_000,predicate:()->Boolean) {
  val end=SystemClock.uptimeMillis()+timeout
  while(SystemClock.uptimeMillis()<end) { if(predicate()) return;SystemClock.sleep(100) }
  check(predicate()) { "Expected fixture UI did not appear" }
 }
 fun ownDialog():Boolean {
  val dump=automation.executeShellCommand("dumpsys activity activities").use { descriptor ->
   android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
  }
  val resumed=Regex("(?:topResumedActivity=|mResumedActivity:)\\s*ActivityRecord\\{([^\\n]+)").find(dump)?.groupValues?.get(1) ?: return false
  if(!resumed.contains("com.android.systemui") || !resumed.contains("MediaProjectionPermissionActivity")) return false
  val token=resumed.substringBefore(' ')
  val caller=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
  return Regex("(?=\\s*\\* Hist\\s+#\\d+:)").split(dump).any { block ->
   val first=block.trimStart().lineSequence().firstOrNull().orEmpty()
   first.contains("ActivityRecord{$token ") && first.contains("MediaProjectionPermissionActivity") &&
    Regex("launchedFromPackage=([^\\s]+)").find(block)?.groupValues?.get(1)==caller
  }
 }
 private fun physical(node:AccessibilityNodeInfo) {
  check(ownDialog()) { "Projection dialog is not owned by this fixture" }
  val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
  check(!bounds.isEmpty && node.isEnabled)
  val now=SystemClock.uptimeMillis()
  val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
  down.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
  try { check(automation.injectInputEvent(down,true)) } finally { down.recycle() }
  SystemClock.sleep(50)
  val up=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
  up.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
  try { check(automation.injectInputEvent(up,true)) } finally { up.recycle() }
 }
 private fun click(id:String) {
  waitFor { nodes().any { it.viewIdResourceName==id && it.packageName?.toString()=="com.android.systemui" && it.isEnabled } }
  physical(nodes().single { it.viewIdResourceName==id && it.packageName?.toString()=="com.android.systemui" && it.isEnabled })
 }
 fun cancelOwnDialog() { if(ownDialog()) runCatching { click("android:id/button2") } }
 fun respond(allow:Boolean) {
  waitFor { ownDialog() && nodes().any { it.viewIdResourceName=="com.android.systemui:id/screen_share_mode_spinner" } }
  if(!allow) { click("android:id/button2");return }
  click("com.android.systemui:id/screen_share_mode_spinner")
  val labels=setOf("Entire screen","整个屏幕","整个屏幕内容")
  waitFor { nodes().any { it.packageName?.toString()=="com.android.systemui" && it.text?.toString() in labels } }
  val option=nodes().single { it.packageName?.toString()=="com.android.systemui" && it.text?.toString() in labels }
  physical(option)
  click("android:id/button1")
 }
}
