package com.shengjidaguai.goutoujunshi.floating

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Debug-only RAM observations contain counts and geometry, never pixels or grant data. */
internal data class ProjectionDebugSnapshot(
 val captures:Int,val resizeCallbacks:Int,val visibilityCallbacks:Int,val visible:Boolean,
 val images:Int,val staleDrops:Int,val idleDrops:Int,val invisibleDrops:Int,val sizeDrops:Int,val errors:Int,
 val readerWidth:Int,val readerHeight:Int,val generation:Long,val pending:Boolean,val attached:Boolean,
 val lastImageWidth:Int,val lastImageHeight:Int
)

/** One projection, one VirtualDisplay. All ownership changes run on the main looper. */
internal class SingleFrameProjection(private val context:Context, private val stopped:()->Unit) : AutoCloseable {
 private var projection:MediaProjection?=null
 private var display:VirtualDisplay?=null
 private var reader:ImageReader?=null
 private var pending:CancellableContinuation<Bitmap?>?=null
 private var generation=0L
 private var closed=false
 private var stopping=false
 private var visible=true
 private var debugCaptures=0;private var debugResizes=0;private var debugVisibility=0
 private var debugImages=0;private var debugStale=0;private var debugIdle=0
 private var debugInvisible=0;private var debugSize=0;private var debugErrors=0
 private var debugImageWidth=0;private var debugImageHeight=0
 private val handler=Handler(Looper.getMainLooper())
 private val callback=object:MediaProjection.Callback() {
  override fun onStop() { revoke() }
  override fun onCapturedContentResize(width:Int,height:Int) { if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) debugResizes++;resize(width,height) }
  override fun onCapturedContentVisibilityChanged(isVisible:Boolean) { if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) debugVisibility++;visible=isVisible }
 }
 internal fun debugSnapshot():ProjectionDebugSnapshot? = if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG && !closed)
  ProjectionDebugSnapshot(debugCaptures,debugResizes,debugVisibility,visible,debugImages,debugStale,debugIdle,
   debugInvisible,debugSize,debugErrors,reader?.width ?: 0,reader?.height ?: 0,generation,pending?.isActive==true,
   display?.surface!=null,debugImageWidth,debugImageHeight) else null
 fun start(data:Intent) {
  check(projection==null && !closed)
  try {
   val p=checkNotNull(context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,data))
   projection=p;p.registerCallback(callback,handler)
   if(closed) return
   val wm=context.getSystemService(WindowManager::class.java)
   val bounds=if(android.os.Build.VERSION.SDK_INT>=30) wm.maximumWindowMetrics.bounds else android.graphics.Rect(0,0,context.resources.displayMetrics.widthPixels,context.resources.displayMetrics.heightPixels)
   val r=newReader(bounds.width(),bounds.height());reader=r
   val created=checkNotNull(p.createVirtualDisplay("Junshi single frame",r.width,r.height,context.resources.configuration.densityDpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,null,null,handler)) { "PROJECTION_DISPLAY_UNAVAILABLE" }
   // onStop can run inline before createVirtualDisplay returns its handle.
   if(closed) { runCatching { created.surface=null };runCatching { created.release() };return }
   display=created
  } catch(e:RuntimeException) { close();throw e }
 }
 private fun newReader(width:Int,height:Int):ImageReader {
  require(width>0 && height>0 && width.toLong()*height<=12_000_000)
  val epoch=++generation
  return ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2).also { r ->
   r.setOnImageAvailableListener({ source ->
    val image=runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
    try {
     if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) { debugImages++;debugImageWidth=image.width;debugImageHeight=image.height }
     val waiter=pending
     if(epoch!=generation) { if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) debugStale++;return@setOnImageAvailableListener }
     if(waiter==null || !waiter.isActive) { if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) debugIdle++;return@setOnImageAvailableListener }
     if(!visible) { if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) debugInvisible++;return@setOnImageAvailableListener }
     if(image.width!=source.width || image.height!=source.height) { if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) debugSize++;return@setOnImageAvailableListener }
     val plane=image.planes[0];val paddedWidth=plane.rowStride/plane.pixelStride
     val padded=Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
     val bitmap=try { padded.copyPixelsFromBuffer(plane.buffer);Bitmap.createBitmap(padded,0,0,image.width,image.height).let { if(it===padded) padded.copy(Bitmap.Config.ARGB_8888,false) else it } } finally { padded.recycle() }
     pending=null;display?.surface=null
     waiter.resume(bitmap) { _,value,_ -> if(!value.isRecycled) value.recycle() }
    } catch(_:RuntimeException) {
     if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) debugErrors++
     display?.surface=null;pending?.let { pending=null;if(it.isActive) it.resume(null) }
    } finally { image.close() }
   },handler)
  }
 }
 suspend fun capture():Bitmap? {
  check(!closed && pending==null)
  if(com.shengjidaguai.goutoujunshi.BuildConfig.DEBUG) debugCaptures++
  val old=reader ?: return null
  display?.surface=null
  val r=newReader(old.width,old.height);reader=r;old.close()
  return withTimeoutOrNull(4000) { suspendCancellableCoroutine { continuation ->
   pending=continuation
   continuation.invokeOnCancellation { handler.post { if(pending===continuation) { pending=null;display?.surface=null } } }
   display?.surface=r.surface
  } }
 }
 fun displayResized() {
  val wm=context.getSystemService(WindowManager::class.java)
  if(android.os.Build.VERSION.SDK_INT>=30) wm.maximumWindowMetrics.bounds.let { resize(it.width(),it.height()) }
  else {
   val metrics=android.util.DisplayMetrics()
   @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
   resize(metrics.widthPixels,metrics.heightPixels)
  }
 }
 fun resize(width:Int,height:Int) {
  if(closed || reader?.let { it.width==width && it.height==height }==true) return
  try {
   val waiter=pending
   display?.surface=null
   val old=reader;val next=newReader(width,height);reader=next
   old?.close()
   display?.resize(width,height,context.resources.configuration.densityDpi)
   if(!closed && pending===waiter && waiter?.isActive==true) display?.surface=next.surface
  } catch(_:RuntimeException) { revoke() }
 }
 private fun revoke() {
  if(closed || stopping) return
  stopping=true
  // Invalidate the owner's authority before releasing a waiter that could publish UI.
  try { stopped() } finally { close() }
 }
 override fun close() {
  if(closed) return
  closed=true;generation++
  pending?.let { pending=null;if(it.isActive) it.resume(null) }
  val vd=display;display=null
  val input=reader;reader=null
  val mp=projection;projection=null
  runCatching { vd?.surface=null };runCatching { vd?.release() }
  runCatching { input?.close() }
  runCatching { mp?.unregisterCallback(callback) };runCatching { mp?.stop() }
 }
}
