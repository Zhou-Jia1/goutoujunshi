package com.shengjidaguai.goutoujunshi.counsel
import android.app.Application
import android.net.Uri
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T53ImportLockRevocationTest {
 /** A publication bound stops the pre-fix feedback loop without waiting for a hung test. */
 private suspend fun lockBounded(f:CounselControllerTest.Fixture) {
  val before=f.controller.importAuthority
  val overflow=AtomicLong(-1)
  val monitor=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  monitor.launch(start=CoroutineStart.UNDISPATCHED) {f.controller.state.collect {
   val delta=f.controller.importAuthority-before
   if(delta>8) {overflow.compareAndSet(-1,delta);f.scope.cancel()}
  }}
  try {withTimeout(5000) {withContext(Dispatchers.Default) {f.controller.updateLockState(LockState(true,false))}}}
  finally {monitor.cancel()}
  assertEquals("Locked collector repeatedly republished authority generations: ${overflow.get()}",-1L,overflow.get())
 }
 @Test fun idleAndRepeatedLockedStatesHaveNoImportCancellationSideEffect()=runBlocking {
  val f=CounselControllerTest.Fixture()
  try {
   f.controller.initialize();val imports=ImportCoordinator(f.controller,f.scope,{true},{})
   val ticket=checkNotNull(imports.beginSelection())
   repeat(3) {val before=f.controller.importAuthority;lockBounded(f);assertEquals(before+1,f.controller.importAuthority)}
   assertFalse(imports.selectionIsCurrent(ticket));f.controller.updateLockState(LockState(true,true))
   assertFalse(imports.selectionIsCurrent(ticket))
   val before=f.controller.importAuthority;imports.cancel();assertEquals(before+1,f.controller.importAuthority)
  } finally {f.close()}
 }
 @Test fun activeCaptureIsRevokedOnceAndLateFrameCannotAffectNewRequest()=runBlocking {
  val f=CounselControllerTest.Fixture()
  try {
   f.controller.initialize();val imports=ImportCoordinator(f.controller,f.scope,{true},{})
   val id=checkNotNull(imports.beginCapture(77) {true});val before=f.controller.importAuthority
   lockBounded(f);assertEquals(before+2,f.controller.importAuthority);assertNull(imports.state.value)
   assertFalse(imports.captured(id,listOf(OcrLine("旧帧",.99f,.1f,.1f,.4f,.2f))))
   val locked=f.controller.importAuthority;lockBounded(f);assertEquals(locked+1,f.controller.importAuthority)
   f.controller.updateLockState(LockState(true,true));f.controller.editDraft("新请求 B");f.controller.analyze()
   withTimeout(5000) {while(f.provider.calls.isEmpty()) yield()}
   val current=f.controller.state.value.currentRequest;val currentEpoch=f.controller.importAuthority
   assertFalse(imports.captured(id,listOf(OcrLine("迟到旧帧",.99f,.1f,.1f,.4f,.2f))))
   assertEquals(currentEpoch,f.controller.importAuthority);assertEquals(current,f.controller.state.value.currentRequest);assertTrue(f.controller.state.value.streaming);assertEquals(1,f.provider.calls.size)
  } finally {f.close()}
 }
 @Test fun suspendedImageReadIsClearedBeforeReentryAndCannotCancelLaterMainRequest()=runBlocking {
  val f=CounselControllerTest.Fixture();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  try {
   f.controller.initialize()
   val imports=ImportCoordinator(f.controller,f.scope,{true},{},{withContext(NonCancellable) {entered.complete(Unit);release.await();listOf(listOf(OcrLine("迟到 OCR",.99f,.1f,.1f,.4f,.2f)))}})
   imports.images(listOf(Uri.parse("content://synthetic/image")))
   val children=f.scope.coroutineContext[Job]!!.children.toSet()
   imports.recognize();withTimeout(5000) {entered.await()}
   val readJob=f.scope.coroutineContext[Job]!!.children.single {it !in children}
   val before=f.controller.importAuthority;lockBounded(f);assertEquals(before+2,f.controller.importAuthority);assertNull(imports.state.value)
   val once=f.controller.importAuthority;lockBounded(f);assertEquals(once+1,f.controller.importAuthority)
   f.controller.updateLockState(LockState(true,true));f.controller.editDraft("新请求 B");f.controller.analyze()
   withTimeout(5000) {while(f.provider.calls.isEmpty()) yield()}
   val current=f.controller.state.value.currentRequest;val currentEpoch=f.controller.importAuthority
   release.complete(Unit);withTimeout(5000) {readJob.join()}
   assertNull(imports.state.value);assertEquals(currentEpoch,f.controller.importAuthority);assertEquals(current,f.controller.state.value.currentRequest);assertTrue(f.controller.state.value.streaming);assertEquals(1,f.provider.calls.size)
  } finally {release.complete(Unit);f.close()}
 }
}
