package com.shengjidaguai.goutoujunshi.floating
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.media.projection.MediaProjection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],shadows=[ProjectionTestManager::class,ProjectionTestProjection::class,ProjectionTestDisplay::class])
class SingleFrameProjectionTest {
 @After fun resetBoundary() {
  ProjectionTestProjection.beforeDisplayReturn=null;ProjectionTestProjection.afterRegister=null
  ProjectionTestProjection.nullDisplay=false;ProjectionTestProjection.registered=null
 }
 private fun reader(session:SingleFrameProjection)=SingleFrameProjection::class.java.getDeclaredField("reader").apply { isAccessible=true }.get(session) as android.media.ImageReader
 private fun assertClosed(reader:android.media.ImageReader) {
  assertThrows(IllegalStateException::class.java) { reader.acquireLatestImage()?.close() }
 }
 @Test fun displayCreationFailureClosesRegisteredProjectionAndReaderExactlyOnce() {
  val session=SingleFrameProjection(RuntimeEnvironment.getApplication()) {}
  var input:android.media.ImageReader?=null
  ProjectionTestProjection.beforeDisplayReturn={ input=reader(session);throw SecurityException("fixture display creation") }
  try {
   assertThrows(SecurityException::class.java) { session.start(Intent()) }
   val projection=checkNotNull(ProjectionTestProjection.registered)
   assertNull(projection.callback);assertEquals(1,projection.stops);assertClosed(checkNotNull(input))
   session.close();assertEquals(1,projection.stops)
  } finally { session.close() }
 }
 @Test fun callbackRegistrationFailureClosesProjectionExactlyOnce() {
  val session=SingleFrameProjection(RuntimeEnvironment.getApplication()) {}
  ProjectionTestProjection.afterRegister={ throw SecurityException("fixture registration") }
  try {
   assertThrows(SecurityException::class.java) { session.start(Intent()) }
   val projection=checkNotNull(ProjectionTestProjection.registered)
   assertNull(projection.callback);assertEquals(1,projection.stops);assertEquals(0,projection.displays)
   session.close();assertEquals(1,projection.stops)
  } finally { session.close() }
 }
 @Test fun nullDisplayIsFailureAndClosesRegisteredProjectionAndReader() {
  val session=SingleFrameProjection(RuntimeEnvironment.getApplication()) {}
  var input:android.media.ImageReader?=null
  ProjectionTestProjection.nullDisplay=true
  ProjectionTestProjection.beforeDisplayReturn={ input=reader(session) }
  try {
   assertThrows(IllegalStateException::class.java) { session.start(Intent()) }
   val projection=checkNotNull(ProjectionTestProjection.registered)
   assertNull(projection.callback);assertEquals(1,projection.stops);assertClosed(checkNotNull(input))
   session.close();assertEquals(1,projection.stops)
  } finally { session.close() }
 }
 @Test fun inlineStopDuringRegistrationCannotCreateDisplay() {
  lateinit var session:SingleFrameProjection
  session=SingleFrameProjection(RuntimeEnvironment.getApplication()) { session.close() }
  ProjectionTestProjection.afterRegister={ it.callback!!.onStop() }
  try {
   session.start(Intent())
   val projection=checkNotNull(ProjectionTestProjection.registered)
   assertEquals(0,projection.displays);assertNull(projection.callback);assertEquals(1,projection.stops)
   assertNull(session.debugSnapshot())
  } finally { session.close() }
 }
 @Test fun inlineStopBeforeDisplayReturnReleasesLateDisplayAndCannotRevive() {
  lateinit var session:SingleFrameProjection
  session=SingleFrameProjection(RuntimeEnvironment.getApplication()) { session.close() }
  var input:android.media.ImageReader?=null
  ProjectionTestProjection.beforeDisplayReturn={
   input=reader(session);checkNotNull(ProjectionTestProjection.registered).callback!!.onStop()
  }
  try {
   session.start(Intent())
   val projection=checkNotNull(ProjectionTestProjection.registered)
   assertEquals(1,Shadow.extract<ProjectionTestDisplay>(checkNotNull(projection.display)).released)
   assertNull(projection.callback);assertEquals(1,projection.stops);assertClosed(checkNotNull(input))
   session.close();assertEquals(1,projection.stops)
  } finally { session.close() }
 }
 @Test fun oneDisplayResizePausedSurfaceCancellationAndIdempotentClose()=runBlocking {
  val session=SingleFrameProjection(RuntimeEnvironment.getApplication()) {}
  session.start(Intent())
  val mp=SingleFrameProjection::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(session) as MediaProjection
  val projection=Shadow.extract<ProjectionTestProjection>(mp)
  val vd=checkNotNull(projection.display);val display=Shadow.extract<ProjectionTestDisplay>(vd)
  assertEquals(1,projection.displays);assertNull(vd.surface)
  val job=launch(Dispatchers.Unconfined) { session.capture() }
  assertNotNull(vd.surface)
  job.cancelAndJoin();Shadows.shadowOf(Looper.getMainLooper()).idle()
  assertNull(vd.surface)
  session.resize(400,600);assertEquals(1,projection.displays);assertEquals(1,display.resizes)
  session.close();session.close();assertEquals(1,display.released);assertNull(projection.callback)
 }
 @Test fun queuedOldStopAfterCloseCannotStopReplacement() {
  var stops=0
  val old=SingleFrameProjection(RuntimeEnvironment.getApplication()) { stops++ };old.start(Intent())
  val mp=SingleFrameProjection::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(old) as MediaProjection
  val callback=checkNotNull(Shadow.extract<ProjectionTestProjection>(mp).callback)
  old.close()
  val replacement=SingleFrameProjection(RuntimeEnvironment.getApplication()) { stops++ };replacement.start(Intent())
  callback.onStop();assertEquals(0,stops)
  replacement.close()
 }
 @Test @Config(sdk=[33],qualifiers="w400dp-h800dp-port")
 fun legacyRotationResizesExistingDisplayWithoutApi34Callback() {
  val session=SingleFrameProjection(RuntimeEnvironment.getApplication()) {};session.start(Intent())
  try {
   val mp=SingleFrameProjection::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(session) as MediaProjection
   val projection=Shadow.extract<ProjectionTestProjection>(mp)
   val display=Shadow.extract<ProjectionTestDisplay>(checkNotNull(projection.display))
   RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
   session.displayResized()
   assertEquals(1,projection.displays);assertEquals(1,display.resizes)
   val reader=SingleFrameProjection::class.java.getDeclaredField("reader").apply { isAccessible=true }.get(session) as android.media.ImageReader
   assertEquals(800,reader.width);assertEquals(400,reader.height)
   assertNull(projection.display?.surface)
  } finally { session.close() }
 }

 @Test fun resizeDuringPendingCaptureKeepsOneWaiterOnFreshReaderAndSameDisplay()=runBlocking {
  val session=SingleFrameProjection(RuntimeEnvironment.getApplication()) {};session.start(Intent())
  try {
   val mp=SingleFrameProjection::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(session) as MediaProjection
   val projection=Shadow.extract<ProjectionTestProjection>(mp);val vd=checkNotNull(projection.display)
   val oldReader=SingleFrameProjection::class.java.getDeclaredField("reader").apply { isAccessible=true }.get(session)
   val job=launch(Dispatchers.Unconfined) { session.capture() }
   val oldSurface=vd.surface
   assertTrue(job.isActive)
   session.resize(600,400)
   assertTrue("The same click must keep waiting for the newly sized frame",job.isActive)
   assertNotNull(vd.surface);assertNotSame(oldSurface,vd.surface)
   val reader=SingleFrameProjection::class.java.getDeclaredField("reader").apply { isAccessible=true }.get(session) as android.media.ImageReader
   assertNotSame(oldReader,reader);assertEquals(600,reader.width);assertEquals(400,reader.height)
   assertEquals(1,projection.displays)
   session.close();job.join();assertTrue(job.isCompleted)
   assertNull(vd.surface)
   assertEquals(1,Shadow.extract<ProjectionTestDisplay>(vd).released)
  } finally { session.close() }
 }
 @Test fun platformStopRevokesOwnerBeforePendingResultCanResume()=runBlocking {
  var ownerStopped=false;var resumedBeforeRevocation=false
  val session=SingleFrameProjection(RuntimeEnvironment.getApplication()) { ownerStopped=true };session.start(Intent())
  try {
   val mp=SingleFrameProjection::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(session) as MediaProjection
   val callback=checkNotNull(Shadow.extract<ProjectionTestProjection>(mp).callback)
   val job=launch(Dispatchers.Unconfined) { session.capture();resumedBeforeRevocation=!ownerStopped }
   callback.onStop();job.join()
   assertTrue(ownerStopped);assertFalse("No pending result may run before owner invalidation",resumedBeforeRevocation)
  } finally { session.close() }
 }

}
