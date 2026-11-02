package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class T52PracticeControllerTest {
 private class Fixture(realKnowledge:Boolean=false,safety:((String)->SafetyLevel)?=null) {
  val base=CounselControllerTest.MemoryPersistence()
  var beforePracticeSource:(suspend ()->Unit)?=null
  var inspectTips:(()->Unit)?=null
  var beforeSourceRead:(suspend (String)->Unit)?=null
  var privacy:RedactionProfile?=null
  var runWrites=0
  val favorites=mutableListOf<Tip>()
  val persistence=object:CounselPersistence by base {
   override suspend fun contacts():List<Contact> {beforeSourceRead?.invoke("contacts");return base.contacts()}
   override suspend fun redactionProfile(contactId:String):RedactionProfile {beforeSourceRead?.invoke("privacy");return privacy ?: base.redactionProfile(contactId)}
   override suspend fun practiceSourceSnapshot(contact:Contact,conversationId:String):StoredCounsel? {beforeSourceRead?.invoke("snapshot");beforePracticeSource?.invoke();return base.saved[contact.id]?.takeIf {it.conversation.id==conversationId}}
   override suspend fun tips():List<Tip> {inspectTips?.invoke();return base.tips()}
   override suspend fun saveRun(contact:Contact,run:AnalysisRun) {runWrites++;base.saveRun(contact,run)}
   override suspend fun favoritePractice(lease:PracticeSummaryLease,current:()->Boolean) {check(current());favorites+=lease.tip;check(current())}
  }
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  val provider=CounselControllerTest.ControlledProvider()
  val classifier by lazy {LocalSafetyClassifier(File(System.getProperty("junshi.repositoryRoot"),"android/core/knowledge/src/main/assets/crisis-keywords.txt").readText())}
  val assembler=FrozenPromptAssembler(if(realKnowledge) run {
   val root=File(System.getProperty("junshi.repositoryRoot")!!);val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
   PromptBuilder(KnowledgeLibrary(KnowledgeSource {path -> File(if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes()}))
  } else object:PromptAssembler {
   override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic system","synthetic "+request.userText,listOf(KnowledgeReference("synthetic.md","a".repeat(64),9)),"v1")
  })
  val controller=CounselController(persistence,TemporarySessionStore(),assembler,provider,scope,localSafety=safety ?: classifier::classify)
  val origin get()=controller.state.value.conversation!!.id
  val id get()=controller.state.value.practice!!.id
  init {
   for(owner in listOf("a","b")) {
    base.people+=Contact(owner,"合成$owner")
    base.saved[owner]=StoredCounsel(Conversation("conversation:$owner",owner,false),"正式草稿$owner",emptyList(),listOf(EvidenceBlock("import:$owner","PASTE",listOf(ChatLine("line:$owner","import:$owner",1,Speaker.THEM,"对方消息$owner",speakerConfirmed=true,contactId=owner)))))
   }
  }
  suspend fun open() {controller.initialize();await {controller.state.value.currentContact?.id=="a"};controller.enterPractice(origin);await {controller.state.value.practice!=null}}
  suspend fun send(end:Boolean=false) {val count=provider.calls.size;controller.previewPractice(origin,id,end);await {controller.state.value.preview!=null};controller.sendPractice(origin,id,checkNotNull(controller.state.value.practicePreviewReceipt));await {provider.calls.size==count+1}}
  suspend fun result(text:String) {provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(freeText=text)));await {!controller.state.value.streaming}}
  fun zeroWrites() {assertEquals(0,runWrites);assertEquals(0,base.messageWrites);assertEquals(0,base.draftWrites);assertEquals("正式草稿a",base.saved.getValue("a").draft)}
 }
 private companion object {suspend fun await(test:()->Boolean)=withTimeout(5000) {while(!test()) yield()}}
 @Test fun formalOpeningRoundAndSummaryRemainRamAndExplicitSaveIsMinimum()=runBlocking {
  val f=Fixture();try {
   f.open();f.send();assertEquals("a",f.provider.calls.last().contactId);assertTrue(f.provider.calls.last().evidence.flatMap {it.lines}.all {it.contactId=="a" && it.speaker==Speaker.THEM})
   f.result("{\"counterpartLine\":\"怎么安排？\"}")
   f.controller.editPracticeInput(f.origin,f.id,"我明天先发方案");await {f.controller.state.value.practice?.draft=="我明天先发方案"};f.send()
   f.result("{\"counterpartLine\":\"几点能发？\",\"feedback\":\"你先说明了时间，可以再补一个确认请求。\",\"betterVersion\":\"我明天下午发方案，请你确认时间。\"}")
   val turn=f.controller.state.value.practice!!.turns.first();assertEquals("我明天先发方案",turn.userLine);assertNotNull(turn.feedback);assertNotNull(turn.betterVersion)
   f.send(true);f.result("{\"name\":\"确认下一步\",\"explanation\":\"把安排和确认请求连起来。\",\"example\":\"明天下午请你确认时间。\"}")
   assertTrue(f.favorites.isEmpty());val view=f.controller.state.value.practice!!;f.controller.savePracticeSummary(f.origin,f.id,view.summaryReceipt!!);await {f.favorites.size==1}
   assertFalse(f.favorites.single().id.isBlank());assertEquals(3,f.provider.calls.size);f.zeroWrites()
  } finally {f.scope.cancel()}
 }
 @Test fun malformedRoundRetriesSameIdAndDifficultyRevokesPreview()=runBlocking {
  val f=Fixture();try {
   f.open();f.send();val request=f.provider.calls.single();f.result("not JSON")
   assertEquals("PRACTICE_INVALID",f.controller.state.value.error?.code);assertTrue(f.controller.state.value.practice!!.turns.isEmpty());f.zeroWrites()
   f.controller.retry();await {f.controller.state.value.preview!=null};f.controller.sendPractice(f.origin,f.id,checkNotNull(f.controller.state.value.practicePreviewReceipt));await {f.provider.calls.size==2};assertEquals(request.requestId,f.provider.calls.last().requestId)
   f.controller.practiceDifficulty(f.origin,f.id,PracticeDifficulty.DIFFICULT);await {!f.controller.state.value.streaming};f.provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(freeText="{\"counterpartLine\":\"旧开场\"}")));yield()
   assertTrue(f.controller.state.value.practice!!.turns.isEmpty());assertNull(f.controller.state.value.preview);f.zeroWrites()
  } finally {f.scope.cancel()}
 }
 @Test fun localCrisisCancelsImmediatelyBeforeModelAndCannotReviveOnUnlock()=runBlocking {
  val f=Fixture();try {
   f.open();f.controller.previewPractice(f.origin,f.id);await {f.controller.state.value.preview!=null};f.controller.editPracticeInput(f.origin,f.id,"我不想活了")
   assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety);assertNull(f.controller.state.value.practice);assertNull(f.controller.state.value.preview);assertTrue(f.provider.calls.isEmpty());f.zeroWrites()
   f.controller.updateLockState(LockState(true,false));f.controller.updateLockState(LockState(true,true));assertNull(f.controller.state.value.teaching.tip);assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety)
  } finally {f.scope.cancel()}
 }
 @Test fun sourceRevocationAndContactSwitchRejectOldResponses()=runBlocking {
  for(action in listOf("source","switch","lock","clear")) {
   val f=Fixture();try {
    f.open();f.send();val output=f.provider.outputs.single()
    when(action) {
     "source"->f.base.people.replaceAll {if(it.id=="a") it.copy(sourceRevision=1) else it}
     "switch"->f.controller.selectContact("b")
     "lock"->f.controller.updateLockState(LockState(true,false))
     "clear"->f.controller.clearAll()
    }
    output.send(AnalysisChunk.Completed(StructuredAnswer(freeText="{\"counterpartLine\":\"旧消息\"}")));yield()
    if(action=="source") await {f.controller.state.value.practice==null}
    if(action=="switch") await {f.controller.state.value.currentContact?.id=="b"}
    assertNull(f.controller.state.value.practice);assertNull(f.controller.state.value.answer)
   } finally {f.scope.cancel()}
  }
 }
 @Test fun exitRevalidatesOriginalAnswerRowsEvenWhenCounterpartSourcesRemainCurrent()=runBlocking {
  for(revoked in listOf("message","run","none")) {
   val f=Fixture();try {
    f.controller.initialize();await {f.controller.state.value.currentContact?.id=="a"}
    f.controller.analyze(f.origin);await {f.provider.calls.size==1}
    f.provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(id="original-main",reply=listOf("原正式主答复"))))
    await {f.controller.state.value.answer?.id=="original-main"}
    f.controller.enterPractice(f.origin);await {f.controller.state.value.practice!=null}
    val prior=f.base.saved.getValue("a")
    f.base.saved["a"]=when(revoked) {"message"->prior.copy(messages=prior.messages.filterNot {it.role==MessageRole.ASSISTANT});"run"->prior.copy(runs=emptyList());else->prior}
    f.controller.exitPractice(f.origin,f.id);await {f.controller.state.value.practice==null}
    if(revoked=="none") assertEquals("original-main",f.controller.state.value.answer?.id) else assertNull(revoked,f.controller.state.value.answer)
    assertNull(f.controller.state.value.teaching.tip);assertEquals(1,f.provider.calls.size)
   } finally {f.scope.cancel()}
  }
 }

 @Test fun maximumTurnCountStillAllowsBudgetedSummaryAndExitWithoutDroppingRamHistory()=runBlocking {
  val f=Fixture(true);try {
   f.open();f.send();f.result("{\"counterpartLine\":\"怎么安排？\"}")
   repeat(19) {i ->
    val input=("第${i}轮"+"常".repeat(2000)).take(2000)
    f.controller.editPracticeInput(f.origin,f.id,input);await {f.controller.state.value.practice?.draft==input};f.send()
    f.result("{\"counterpartLine\":\"下一步呢？\",\"feedback\":\"你说明了安排，可以再说清确认时间。\",\"betterVersion\":\"明天下午请你确认时间。\"}")
   }
   assertEquals(20,f.controller.state.value.practice!!.turns.size);val calls=f.provider.calls.size
   f.controller.previewPractice(f.origin,f.id,false);yield();assertNull(f.controller.state.value.preview);assertEquals(calls,f.provider.calls.size)
   f.send(true)
   assertEquals(20,f.provider.calls.last().practice!!.turns.size)
   assertTrue(f.controller.state.value.preview!!.practiceSelection!!.omittedTurns>0)
   f.result("{\"name\":\"确认下一步\",\"explanation\":\"安排后附确认请求。\",\"example\":\"明天下午请你确认时间。\"}")
   assertEquals(20,f.controller.state.value.practice!!.turns.size);assertEquals(21,f.provider.calls.size);f.zeroWrites()
   f.controller.exitPractice(f.origin,f.id);await {f.controller.state.value.practice==null};assertNull(f.controller.state.value.answer)
  } finally {f.scope.cancel()}
 }
 @Test fun irreducibleRangeErrorPreservesEditableRamAndAllowsExitWithoutNetwork()=runBlocking {
  val f=Fixture(true);try {
   val prior=f.base.saved.getValue("a");val block=prior.evidence.single();f.base.saved["a"]=prior.copy(evidence=listOf(block.copy(lines=block.lines.map {it.copy(text="敏".repeat(2000))})))
   f.controller.initialize();await {f.controller.state.value.currentContact?.id=="a"}
   f.controller.saveRedactionProfile(f.origin,listOf("敏"),emptyList());await {f.controller.state.value.redactionProfile.revision==1L}
   f.controller.enterPractice(f.origin);await {f.controller.state.value.practice!=null};val id=f.id
   f.controller.previewPractice(f.origin,id);await {f.controller.state.value.error?.code=="PRACTICE_RANGE"}
   assertEquals(id,f.id);assertTrue(f.provider.calls.isEmpty());assertNull(f.controller.state.value.preview)
   f.controller.editPracticeInput(f.origin,id,"可编辑的合成草稿");await {f.controller.state.value.practice?.draft=="可编辑的合成草稿"}
   f.controller.exitPractice(f.origin,id);await {f.controller.state.value.practice==null};f.zeroWrites()
  } finally {f.scope.cancel()}
 }


 @Test fun staleConfirmationCannotSendNewFrozenPreview()=runBlocking {
  for(change in listOf("difficulty","input","same-input","same-request-retry")) {
   val f=Fixture(true);try {
    f.open();val origin=f.origin;val id=f.id
    if(change=="input") {
     f.send();f.result("{\"counterpartLine\":\"怎么安排？\"}")
     f.controller.editPracticeInput(origin,id,"A-old");await {f.controller.state.value.practice?.draft=="A-old"}
    }
    f.controller.previewPractice(origin,id);await {f.controller.state.value.preview!=null}
    val receiptA=checkNotNull(f.controller.state.value.practicePreviewReceipt)
    val oldUiConfirm={f.controller.sendPractice(origin,id,receiptA)}
    var retryRequestId:String?=null
    when(change) {
     "difficulty"->{f.controller.practiceDifficulty(origin,id,PracticeDifficulty.DIFFICULT);await {f.controller.state.value.practice?.difficulty==PracticeDifficulty.DIFFICULT}}
     "input"->{f.controller.editPracticeInput(origin,id,"B-new");await {f.controller.state.value.practice?.draft=="B-new"}}
     "same-request-retry"->{
      val before=f.provider.calls.size;oldUiConfirm();await {f.provider.calls.size==before+1};retryRequestId=f.provider.calls.last().requestId
      f.provider.outputs.last().send(AnalysisChunk.Failed(AnalysisError("NETWORK",retryable=true)));await {f.controller.state.value.error!=null}
     }
    }
    if(change=="same-request-retry") f.controller.retry() else f.controller.previewPractice(origin,id)
    await {f.controller.state.value.preview!=null && f.controller.state.value.practicePreviewReceipt!=receiptA}
    val shownB=checkNotNull(f.controller.state.value.preview);val receiptB=checkNotNull(f.controller.state.value.practicePreviewReceipt)
    assertNotEquals(receiptA,receiptB)
    val before=f.provider.calls.size;val barrier=f.controller.state.value.tipsReceipt
    oldUiConfirm();f.controller.openTips();await {f.controller.state.value.tipsReceipt!=barrier}
    assertEquals("Old confirmation must not send newer preview: $change",before,f.provider.calls.size)
    assertEquals(shownB,f.controller.state.value.preview);assertEquals(receiptB,f.controller.state.value.practicePreviewReceipt);assertFalse(f.controller.state.value.streaming)
    val confirmB={f.controller.sendPractice(origin,id,receiptB)}
    confirmB();await {f.provider.calls.size==before+1};assertNull(f.controller.state.value.practicePreviewReceipt)
    if(change=="difficulty") assertEquals(PracticeDifficulty.DIFFICULT,f.provider.calls.last().practice!!.difficulty)
    if(change=="input") assertEquals("B-new",f.provider.calls.last().userText)
    if(change=="same-request-retry") assertEquals(retryRequestId,f.provider.calls.last().requestId)
    f.result(if(change=="input") "{\"counterpartLine\":\"已确认B\",\"feedback\":\"你说明了安排，可以再确认时间。\",\"betterVersion\":\"明天下午请你确认。\"}" else "{\"counterpartLine\":\"已确认B\"}")
    val consumed=f.controller.state.value.tipsReceipt;confirmB();f.controller.openTips();await {f.controller.state.value.tipsReceipt!=consumed}
    assertEquals(before+1,f.provider.calls.size);f.zeroWrites()
   } finally {f.scope.cancel()}
  }
 }

 @Test fun suspendedContactEntryCannotReviveAfterLockSwitchOrClearAndNormalEntryStillWorks()=runBlocking {
  for(revoke in listOf("lock","switch","clear","none")) {
   val f=Fixture();try {
    f.controller.initialize();await {f.controller.state.value.currentContact?.id=="a"}
    val gate=CompletableDeferred<Unit>();f.base.beforeContacts=gate
    f.controller.practiceForContact("b");yield()
    when(revoke) {
     "lock"->{f.controller.updateLockState(LockState(true,false));f.controller.updateLockState(LockState(true,true))}
     "switch"->f.controller.selectContact("a")
     "clear"->f.controller.clearAll()
    }
    gate.complete(Unit);f.base.beforeContacts=null
    val barrier=f.controller.state.value.tipsReceipt;f.controller.openTips();await {f.controller.state.value.tipsReceipt!=barrier}
    if(revoke=="none") {assertNotNull(f.controller.state.value.practice);assertEquals("b",f.controller.state.value.currentContact?.id)}
    else assertNull("Entry submitted before $revoke must not activate practice",f.controller.state.value.practice)
    assertTrue(f.provider.calls.isEmpty())
   } finally {f.scope.cancel()}
  }
 }

 @Test fun suspendedNegativeSourceChecksCannotPolluteUnlockedState()=runBlocking {
  val polluted=mutableListOf<String>()
  for(operation in listOf("preview","send","start","retry","receive","save","exit","enter")) {
   val f=Fixture();try {
    if(operation=="enter") {f.controller.initialize();await {f.controller.state.value.currentContact?.id=="a"}} else f.open()
    if(operation in listOf("send","start")) {f.controller.previewPractice(f.origin,f.id);await {f.controller.state.value.practicePreviewReceipt!=null}}
    if(operation in listOf("retry","receive","save")) {f.send();if(operation=="retry") f.result("invalid")}
    if(operation=="save") {f.result("{\"counterpartLine\":\"下一步？\"}");f.send(true);f.result("{\"name\":\"确认\",\"explanation\":\"说明安排后请求确认。\",\"example\":\"明天请你确认时间。\"}")}
    val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var checks=0
    f.beforePracticeSource={checks++;if(operation!="start" || checks==2) {entered.complete(Unit);release.await()}}
    when(operation) {
     "preview"->f.controller.previewPractice(f.origin,f.id)
     "send","start"->f.controller.sendPractice(f.origin,f.id,checkNotNull(f.controller.state.value.practicePreviewReceipt))
     "retry"->f.controller.retry()
     "receive"->f.provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(freeText="{\"counterpartLine\":\"旧回复\"}")))
     "save"->f.controller.savePracticeSummary(f.origin,f.id,checkNotNull(f.controller.state.value.practice!!.summaryReceipt))
     "exit"->f.controller.exitPractice(f.origin,f.id)
     "enter"->f.controller.enterPractice(f.origin)
    }
    entered.await()
    val observed=CompletableDeferred<CounselError?>();f.inspectTips={observed.complete(f.controller.state.value.error)}
    f.controller.openTips() // Inspect between the old callback and legitimate queued lock cleanup.
    f.controller.updateLockState(LockState(true,false));f.controller.updateLockState(LockState(true,true))
    val expectedError=f.controller.state.value.error
    release.complete(Unit);f.beforePracticeSource=null
    val barrier=f.controller.state.value.tipsReceipt;f.controller.openTips();await {f.controller.state.value.tipsReceipt!=barrier}
    if(observed.await()!=expectedError) polluted+=operation
    assertNull(f.controller.state.value.error)
    assertNull(f.controller.state.value.practice);assertTrue(f.favorites.isEmpty());f.zeroWrites()
   } finally {f.scope.cancel()}
  }
  assertTrue("Old source results emitted SOURCE_CHANGED after unlock: $polluted",polluted.isEmpty())
 }
 @Test fun oldNegativeSourceResultCannotDiscardNewQueuedPracticeIntent()=runBlocking {
  val f=Fixture();try {
   f.open();val id=f.id;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
   f.beforePracticeSource={entered.complete(Unit);release.await()}
   f.controller.previewPractice(f.origin,id);entered.await();f.base.saved.remove("a")
   f.controller.practiceDifficulty(f.origin,id,PracticeDifficulty.DIFFICULT)
   release.complete(Unit);f.beforePracticeSource=null
   val barrier=f.controller.state.value.tipsReceipt;f.controller.openTips();await {f.controller.state.value.tipsReceipt!=barrier}
   assertEquals("Old revoked source query must not discard newer queued difficulty",PracticeDifficulty.DIFFICULT,f.controller.state.value.practice?.difficulty)
   assertNull(f.controller.state.value.error);assertTrue(f.provider.calls.isEmpty())
   f.controller.previewPractice(f.origin,id);await {f.controller.state.value.error?.code=="PRACTICE_SOURCE_CHANGED"};assertNull(f.controller.state.value.practice)
  } finally {f.scope.cancel()}
 }

 @Test fun everySourceAwaitChecksAuthorityBeforePositiveOrNegativeResultAndFurtherReads()=runBlocking {
  for(stage in listOf("contacts","snapshot","privacy")) for(negative in listOf(false,true)) {
   val f=Fixture();try {
    f.open();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val reads=mutableListOf<String>()
    f.beforeSourceRead={read->reads+=read;if(read==stage) {entered.complete(Unit);release.await()}}
    f.controller.previewPractice(f.origin,f.id);entered.await()
    if(negative) when(stage) {"contacts"->f.base.people.removeAll {it.id=="a"};"snapshot"->f.base.saved.remove("a");"privacy"->f.privacy=RedactionProfile(revision=1)}
    val observed=CompletableDeferred<CounselError?>();f.inspectTips={f.beforeSourceRead=null;observed.complete(f.controller.state.value.error)}
    f.controller.openTips();f.controller.updateLockState(LockState(true,false));f.controller.updateLockState(LockState(true,true));val expected=f.controller.state.value.error
    release.complete(Unit)
    assertEquals("$stage negative=$negative",expected,observed.await())
    assertEquals(listOf("contacts","snapshot","privacy").takeWhile {it!=stage}+stage,reads)
    val barrier=f.controller.state.value.tipsReceipt;f.controller.openTips();await {f.controller.state.value.tipsReceipt!=barrier}
    assertNull(f.controller.state.value.practice);assertTrue(f.provider.calls.isEmpty());assertTrue(f.favorites.isEmpty())
   } finally {f.scope.cancel()}
  }
 }
 @Test fun runtimeSafetyFactoryCachesActualVocabularyAndClassifiesEachInputOnce()=runBlocking {
  var loads=0
  val safety=CounselRuntime.localSafetyClassifier {loads++;File(System.getProperty("junshi.repositoryRoot"),"android/core/knowledge/src/main/assets/crisis-keywords.txt").readText()}
  repeat(3) {assertEquals(SafetyLevel.NONE,safety("明天讨论方案"));assertEquals(SafetyLevel.CRISIS,safety("我想自杀"))}
  assertEquals(1,loads)
  var classifications=0;val f=Fixture(safety={classifications++;safety(it)})
  try {
   f.open();assertEquals(1,classifications)
   f.controller.editPracticeInput(f.origin,f.id,"明天讨论方案");await {f.controller.state.value.practice?.draft=="明天讨论方案"};assertEquals(2,classifications)
   f.controller.editPracticeInput(f.origin,f.id,"我想自杀");assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety);assertNull(f.controller.state.value.practice)
   assertEquals(3,classifications);assertEquals(1,loads);assertTrue(f.provider.calls.isEmpty());f.zeroWrites()
  } finally {f.scope.cancel()}
 }
 @Test fun runtimeSafetyFactoryCachesLoadAndParseFailuresWithoutCrashOrSend()=runBlocking {
  for(failure in listOf("read","parse")) {
   var loads=0;val safety=CounselRuntime.localSafetyClassifier {loads++;if(failure=="read") throw java.io.IOException("synthetic unavailable");"# no vocabulary"}
   repeat(3) {assertTrue(runCatching {safety("neutral")}.isFailure);assertTrue(runCatching {safety("我想自杀")}.isFailure)}
   val f=Fixture(safety=safety);try {
    f.controller.initialize();await {f.controller.state.value.currentContact?.id=="a"}
    repeat(3) {
     f.controller.enterPractice(f.origin)
     val barrier=f.controller.state.value.tipsReceipt;f.controller.openTips();await {f.controller.state.value.tipsReceipt!=barrier}
     assertEquals("PRACTICE_SAFETY_UNAVAILABLE",f.controller.state.value.error?.code);assertNull(f.controller.state.value.practice)
    }
    assertEquals(1,loads);assertTrue(f.provider.calls.isEmpty());f.zeroWrites()
   } finally {f.scope.cancel()}
  }
 }

}
