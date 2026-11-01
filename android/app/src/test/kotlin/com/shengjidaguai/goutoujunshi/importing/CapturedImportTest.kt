package com.shengjidaguai.goutoujunshi.importing
import android.app.Application
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest.Fixture
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class CapturedImportTest {
 private fun line(text:String)=OcrLine(text,.99f,.7f,.3f,.9f,.4f)
 @Test fun upwardPagePrependsDeduplicatesAndRequiresIdentityConfirmation()=runBlocking {
  val f=Fixture();f.controller.initialize()
  val c=ImportCoordinator(f.controller,f.scope,{true},{})
  try {
   val origin=f.controller.state.value.conversation?.id
   val id=c.beginCapture(7) { true }!!
   assertNotEquals(origin,f.controller.state.value.conversation?.id)
   c.captured(id,listOf(line("共同消息"),line("末尾消息")))
   c.captured(id,listOf(line("较早消息"),line("共同消息")))
   assertEquals(listOf("较早消息","共同消息","末尾消息"),c.state.value!!.rows.filterNot { it.excluded }.map { it.text })
   c.target(ImportTarget());assertFalse(c.state.value!!.canConfirm)
   c.side(id,OcrSide.RIGHT);assertTrue(c.state.value!!.canConfirm)
   assertTrue(f.provider.calls.isEmpty())
   c.confirm();assertTrue(f.persistence.imports.isEmpty())
  } finally { c.cancel();f.close() }
 }
 @Test fun oldCaptureCannotPublishOrCancelLaterManualImport()=runBlocking {
  val f=Fixture();f.controller.initialize();val c=ImportCoordinator(f.controller,f.scope,{true},{})
  try {
   val id=c.beginCapture(8) { true }!!
   c.text("我：新的手动内容")
   val manual=c.state.value!!.id
   assertFalse(c.captured(id,listOf(line("迟到内容"))))
   c.cancelCapture(8);assertEquals(manual,c.state.value!!.id)
  } finally { c.cancel();f.close() }
 }
 @Test fun revokedBeforeMailboxCannotChangeContext()=runBlocking {
  val f=Fixture();f.controller.initialize();val c=ImportCoordinator(f.controller,f.scope,{true},{})
  try {
   val origin=f.controller.state.value.conversation?.id
   assertNull(c.beginCapture(9) { false })
   assertEquals(origin,f.controller.state.value.conversation?.id)
  } finally { c.cancel();f.close() }
 }
 @OptIn(ObsoleteCoroutinesApi::class,ExperimentalCoroutinesApi::class)
 @Test fun stoppedCaptureBehindRealActorSuspensionCannotReplaceManualOwner()=runBlocking {
  val actor=newSingleThreadContext("capture-actor-test")
  val actorScope=CoroutineScope(SupervisorJob()+actor)
  val uiScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  val f=Fixture(scope=actorScope)
  val c=ImportCoordinator(f.controller,uiScope,{true},{})
  try {
   f.controller.initialize()
   withTimeout(5000) { f.controller.state.first { !it.loading && it.conversation!=null } }
   f.controller.createContact("合成联系人",com.shengjidaguai.goutoujunshi.domain.RelationType.FRIEND)
   withTimeout(5000) { f.controller.state.first { it.currentContact!=null } }
   f.controller.editDraft("合成未保存草稿")
   withTimeout(5000) { f.controller.state.first { it.draft=="合成未保存草稿" } }
   val origin=f.controller.state.value.conversation!!.id
   val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
   f.persistence.beforeUpdate=gate;f.persistence.updateEntered=entered
   val live=java.util.concurrent.atomic.AtomicBoolean(true)
   val pending=async { c.beginCapture(44) { live.get() } }
   withTimeout(5000) { entered.await() }
   live.set(false)
   c.text("我：更新的手动导入")
   val manual=c.state.value!!.id
   gate.complete(Unit)
   assertNull(withTimeout(5000) { pending.await() })
   assertEquals(origin,f.controller.state.value.conversation!!.id)
   assertEquals(manual,c.state.value!!.id)
   c.cancelCapture(44);assertEquals(manual,c.state.value!!.id)
  } finally { c.cancel();f.close();uiScope.cancel();actor.close() }
 }

 @OptIn(ObsoleteCoroutinesApi::class,ExperimentalCoroutinesApi::class)
 @Test fun coldCaptureRevokedDuringInitializeOrContactsDoesNotCreateContextAndStartupRecovers()=runBlocking {
  for(stage in listOf("initialize","contacts")) {
   val actor=newSingleThreadContext("cold-capture-$stage")
   val actorScope=CoroutineScope(SupervisorJob()+actor)
   val uiScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
   val f=Fixture(scope=actorScope);val c=ImportCoordinator(f.controller,uiScope,{true},{})
   try {
    val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
    if(stage=="initialize") { f.persistence.beforeInitialize=gate;f.persistence.initializeEntered=entered }
    else { f.persistence.beforeContacts=gate;f.persistence.contactsEntered=entered }
    val state=com.shengjidaguai.goutoujunshi.floating.CaptureState();val epoch=state.request()
    val pending=async { c.beginCapture(99,{ action -> state.commit(epoch,action) }) { state.revision==epoch } }
    withTimeout(5000) { entered.await() }
    state.stop();gate.complete(Unit)
    assertNull(withTimeout(5000) { pending.await() })
    assertNull(f.controller.state.value.conversation)
    assertNull(c.state.value)
    f.controller.initialize()
    withTimeout(5000) { f.controller.state.first { !it.loading && it.conversation!=null } }
    assertNotNull(f.controller.state.value.conversation)
    assertTrue(f.provider.calls.isEmpty())
   } finally { c.cancel();f.close();uiScope.cancel();actor.close() }
  }
 }

 @Test fun savingAndInitializationRetryKeepTheEditedCaptureWhileOnlyOcrRecaptures() {
  val p=ImportPreview(captureSession=3)
  assertTrue(p.copy(error="OCR_EMPTY").retryNeedsCapture())
  assertFalse(p.copy(error="IMPORT_SAVE").retryNeedsCapture())
  assertFalse(p.copy(error="IMPORT_INITIALIZE").retryNeedsCapture())
  assertFalse(p.retryNeedsCapture())
  assertFalse(p.copy(captureSession=null,error="OCR_EMPTY").retryNeedsCapture())
 }

 @Test fun captureSaveRetryRetainsEditsAndRetriesTheSameImport()=runBlocking {
  val f=Fixture();f.controller.initialize();val c=ImportCoordinator(f.controller,f.scope,{true},{})
  try {
   val id=c.beginCapture(77) { true }!!
   c.captured(id,listOf(line("合成编辑前")))
   c.side(id,OcrSide.RIGHT)
   val row=c.state.value!!.rows.single()
   c.editRow(id,row.id) { it.copy(text="合成编辑后",reviewed=true) }
   c.target(ImportTarget(newAlias="合成保存对象"))
   f.persistence.failImportAfterCommit=true
   c.confirm()
   assertEquals("IMPORT_SAVE",c.state.value!!.error)
   assertFalse(c.state.value!!.retryNeedsCapture())
   assertEquals("合成编辑后",c.state.value!!.rows.single().text)
   c.retry()
   assertNull(c.state.value)
   assertEquals(1,f.persistence.imports.size)
   assertEquals("合成编辑后",f.controller.state.value.evidence.single().lines.single().text)
   assertTrue(f.provider.calls.isEmpty())
  } finally { c.cancel();f.close() }
 }

 @Test fun emptyOcrRetryDoesNotConsumePageHistoryOrEraseEarlierText()=runBlocking {
  val f=Fixture();f.controller.initialize();val c=ImportCoordinator(f.controller,f.scope,{true},{})
  try {
   val id=c.beginCapture(88) { true }!!
   c.captured(id,listOf(line("已有页面")))
   c.captured(id,emptyList())
   assertEquals("OCR_EMPTY",c.state.value!!.error)
   assertEquals(1,c.state.value!!.imageIds.size)
   assertEquals("已有页面",c.state.value!!.rows.single().text)
   c.captured(id,listOf(line("更早页面")))
   assertNull(c.state.value!!.error)
   assertEquals(2,c.state.value!!.imageIds.size)
   assertEquals(listOf("更早页面","已有页面"),c.state.value!!.rows.map { it.text })
  } finally { c.cancel();f.close() }
 }

 @Test fun userExcludingUiAfterBothPagesRecomputesOverlapAndPreservesExplicitRestore()=runBlocking {
  val f=Fixture();f.controller.initialize();val c=ImportCoordinator(f.controller,f.scope,{true},{})
  try {
   val id=c.beginCapture(123) { true }!!
   c.captured(id,listOf(line("共同消息"),line("末尾消息"),line("合成底部操作")))
   val firstFooter=c.state.value!!.rows.last()
   c.editRow(id,firstFooter.id) { it.copy(excluded=true,exclusionOverride=true) }
   c.captured(id,listOf(line("较早消息"),line("共同消息"),line("合成底部操作")))
   val secondFooter=c.state.value!!.rows.first { it.text=="合成底部操作" && !it.excluded }
   c.editRow(id,secondFooter.id) { it.copy(excluded=true,exclusionOverride=true) }
   assertEquals(listOf("较早消息","共同消息","末尾消息"),c.state.value!!.rows.filterNot { it.excluded }.map { it.text })
   val duplicate=c.state.value!!.rows.last { it.text=="共同消息" }
   assertTrue(duplicate.excluded)
   c.editRow(id,duplicate.id) { it.copy(excluded=false,exclusionOverride=false) }
   assertEquals(2,c.state.value!!.rows.count { it.text=="共同消息" && !it.excluded })
   assertTrue(c.state.value!!.rows.filter { it.text=="合成底部操作" }.all { it.excluded && it.exclusionOverride==true })
  } finally { c.cancel();f.close() }
 }

}
