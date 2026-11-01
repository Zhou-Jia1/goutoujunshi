package com.shengjidaguai.goutoujunshi.floating
import android.app.Activity
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.projection.*
import android.os.Handler
import android.view.Surface
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow

/** JVM platform boundary only; device test uses genuine SystemUI/projection/Paddle. */
@Implements(MediaProjectionManager::class)
class ProjectionTestManager {
 @Implementation fun getMediaProjection(code:Int,data:Intent):MediaProjection? =
  if(code==Activity.RESULT_OK) Shadow.newInstanceOf(MediaProjection::class.java) else null
}
@Implements(MediaProjection::class)
class ProjectionTestProjection {
 companion object {
  var beforeDisplayReturn:(()->Unit)?=null
  var afterRegister:((ProjectionTestProjection)->Unit)?=null
  var nullDisplay=false
  var registered:ProjectionTestProjection?=null
 }
 var callback:MediaProjection.Callback?=null
 var displays=0
 var stops=0
 var display:VirtualDisplay?=null
 @Implementation fun registerCallback(value:MediaProjection.Callback,handler:Handler?) { callback=value;registered=this;afterRegister?.invoke(this) }
 @Implementation fun unregisterCallback(value:MediaProjection.Callback) { if(callback===value) callback=null }
 @Implementation fun createVirtualDisplay(name:String,width:Int,height:Int,dpi:Int,flags:Int,surface:Surface?,cb:VirtualDisplay.Callback?,handler:Handler?):VirtualDisplay? {
  check(callback!=null);displays++
  if(nullDisplay) { beforeDisplayReturn?.invoke();return null }
  return Shadow.newInstanceOf(VirtualDisplay::class.java).also { display=it;it.surface=surface;beforeDisplayReturn?.invoke() }
 }
 @Implementation fun stop() { stops++;callback?.onStop() }
}
@Implements(VirtualDisplay::class)
class ProjectionTestDisplay {
 var attached:Surface?=null
 var released=0
 var resizes=0
 @Implementation fun setSurface(value:Surface?) { attached=value }
 @Implementation fun getSurface():Surface?=attached
 @Implementation fun resize(width:Int,height:Int,dpi:Int) { resizes++ }
 @Implementation fun release() { released++ }
}
internal fun deliverProjectionResult(activity:FloatingCounselActivity,result:Int=Activity.RESULT_OK,data:Intent?=Intent()) {
 FloatingCounselActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible=true }.invoke(activity,3201,result,data)
}

internal fun putCurrentProjectionGrant(ticket:Long,data:Intent=Intent()) {
 val app=org.robolectric.RuntimeEnvironment.getApplication()
 ProjectionGrantStore.put(ticket,data,com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.get(app).lockRevision)
}
