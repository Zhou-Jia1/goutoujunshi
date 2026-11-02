package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class T53EmotionControllerTest {
 @Test fun crisisTypedInputBlocksProviderAndAcknowledgementNeverOpensOrdinaryGate()=runBlocking {
  val f=CounselControllerTest.Fixture(safetyClassifier={if(it.contains("合成危机")) SafetyLevel.CRISIS else SafetyLevel.NONE})
  try {
   f.controller.initialize();f.controller.editDraft("合成危机")
   assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety)
   f.controller.preparePreview();f.controller.analyze()
   assertTrue(f.provider.calls.isEmpty());assertEquals(0,f.prepares)
   val receipt=f.controller.state.value.safetyActionReceipt()
   f.controller.acknowledgeSafety(receipt,true)
   assertTrue(f.controller.state.value.safetyCollapsed)
   assertFalse(f.controller.state.value.safetyPresentation().showOrdinaryStrategy)
   f.controller.analyze();assertTrue(f.provider.calls.isEmpty())
  } finally {f.close()}
 }
 @Test fun failedVocabularyClosesOrdinaryGateBeforeProvider()=runBlocking {
  val f=CounselControllerTest.Fixture(safetyClassifier={error("synthetic asset failure")})
  try {f.controller.initialize();f.controller.editDraft("普通合成输入");f.controller.analyze()
   assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety);assertTrue(f.provider.calls.isEmpty())
  } finally {f.close()}
 }
 @Test fun exactOldReceiptCannotCollapseNewInputOrSurviveLockCycle()=runBlocking {
  val f=CounselControllerTest.Fixture(safetyClassifier={SafetyLevel.CRISIS})
  try {f.controller.initialize();f.controller.editDraft("A");val old=f.controller.state.value.safetyActionReceipt()
   f.controller.editDraft("B");f.controller.acknowledgeSafety(old,true);assertFalse(f.controller.state.value.safetyCollapsed)
   val beforeLock=f.controller.state.value.safetyActionReceipt()
   f.controller.updateLockState(LockState(true,false));f.controller.updateLockState(LockState(true,true))
   f.controller.acknowledgeSafety(beforeLock,true);assertFalse(f.controller.state.value.safetyCollapsed)
  } finally {f.close()}
 }
 @Test fun optionalEmotionAndTenMinuteBoundary() {
  for(value in listOf(null,7)) assertFalse(SafetyPolicy.evaluate(value,SafetyLevel.NONE).calm)
  assertTrue(SafetyPolicy.evaluate(8,SafetyLevel.NONE).calm)
  assertTrue(SafetyPolicy.evaluate(null,SafetyLevel.CAUTION).calm)
  val draft=CoolingDraft("合成草稿",1000)
  assertFalse(draft.ready(600999));assertTrue(draft.ready(601000));assertFalse(draft.ready(999))
 }
 @Test fun oldCopyAndSaveCannotTouchNewAnswerEvenWhenTextIsIdentical()=runBlocking {
  val f=CounselControllerTest.Fixture()
  suspend fun complete() {
   f.controller.analyze()
   withTimeout(2000) {while(f.provider.calls.isEmpty()) yield()}
   f.provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(id="answer",reply=listOf("合成回复"))))
   withTimeout(2000) {while(f.controller.state.value.answer==null) yield()}
  }
  try {
   f.controller.initialize();f.controller.editDraft("A");complete()
   val receipt=f.controller.state.value.safetyActionReceipt()
   assertTrue(f.controller.copyReply(receipt,"合成回复") {})
   f.controller.editDraft("B")
   var writes=0
   assertFalse(f.controller.copyReply(receipt,"合成回复") {writes++})
   f.controller.saveCoolingDraft(receipt,"合成回复")
   assertNull(f.controller.state.value.conversation?.coolingDraft);assertEquals(0,writes)
  } finally {f.close()}
 }

 @Test fun explicitTemporaryCoolingDraftRemainsRamAndDoesNotEnablePersistence()=runBlocking {
  val p=CounselControllerTest.MemoryPersistence();val f=CounselControllerTest.Fixture(p,wallClock={1000})
  try {
   f.controller.initialize();f.controller.editDraft("合成输入");f.controller.analyze()
   withTimeout(2000) {while(f.provider.calls.isEmpty()) yield()}
   f.provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(id="answer",reply=listOf("合成回复"))))
   withTimeout(2000) {while(f.controller.state.value.answer==null) yield()}
   f.controller.saveCoolingDraft(f.controller.state.value.safetyActionReceipt(),"合成回复")
   assertEquals(CoolingDraft("合成回复",1000),f.controller.state.value.visibleCoolingDraft())
   assertTrue(p.saved.isEmpty())
  } finally {f.close()}
  val reopened=CounselControllerTest.Fixture(p)
  try {reopened.controller.initialize();assertNull(reopened.controller.state.value.visibleCoolingDraft())} finally {reopened.close()}
 }


 private class EntryFixture {
  val base=CounselControllerTest.MemoryPersistence()
  val persistence=object:CounselPersistence by base {
   override suspend fun practiceSourceSnapshot(contact:Contact,conversationId:String)=base.saved[contact.id]?.takeIf {it.conversation.id==conversationId}
  }
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  val provider=CounselControllerTest.ControlledProvider()
  val assets=java.io.File(System.getProperty("junshi.repositoryRoot"),"android/core/knowledge/src/main/assets")
  val classifier=com.shengjidaguai.goutoujunshi.knowledge.CachedLocalSafetyClassifier({assets.resolve("crisis-keywords.txt").readText()},{assets.resolve("calm-keywords.txt").readText()})
  val controller=CounselController(persistence,com.shengjidaguai.goutoujunshi.data.TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
   override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",listOf(KnowledgeReference("synthetic","a".repeat(64),1)),"v1")
  }),provider,scope,localSafety=classifier::classify)
  init {for(owner in listOf("a","b")) {
   base.people+=Contact(owner,"合成$owner")
   base.saved[owner]=StoredCounsel(Conversation("conversation:$owner",owner,false),"",emptyList(),listOf(EvidenceBlock("import:$owner","PASTE",listOf(ChatLine("line:$owner","import:$owner",1,Speaker.THEM,if(owner=="a") "我想自杀" else "明天讨论方案",speakerConfirmed=true,contactId=owner)))))
  }}
 }
 @Test fun loadedCrisisEvidenceStopsPracticeAtEntryBeforeAnyProvider()=runBlocking {
  val f=EntryFixture()
  try {f.controller.initialize();f.controller.enterPractice("conversation:a")
   assertEquals(SafetyLevel.CRISIS,f.controller.state.value.safety);assertNull(f.controller.state.value.practice);assertTrue(f.provider.calls.isEmpty())
  } finally {f.scope.cancel()}
 }
 @Test fun cleanBPracticeDoesNotReadAOrInheritItsCrisisText()=runBlocking {
  val f=EntryFixture()
  try {f.controller.initialize();f.controller.practiceForContact("b")
   withTimeout(2000) {while(f.controller.state.value.practice==null) yield()}
   assertEquals("b",f.controller.state.value.currentContact?.id);assertEquals(SafetyLevel.NONE,f.controller.state.value.safety)
   val id=f.controller.state.value.practice!!.id
   f.controller.previewPractice("conversation:b",id)
   withTimeout(2000) {while(f.controller.state.value.practicePreviewReceipt==null) yield()}
   val receipt=checkNotNull(f.controller.state.value.practicePreviewReceipt)
   f.controller.sendPractice("conversation:b",id,receipt)
   withTimeout(2000) {while(f.provider.calls.isEmpty()) yield()}
   assertEquals("b",f.provider.calls.single().contactId)
   assertTrue(f.provider.calls.single().evidence.flatMap {it.lines}.all {it.contactId=="b" && !it.text.contains("自杀")})
  } finally {f.scope.cancel()}
 }
 @Test fun loadedCautionEvidenceUsesSameCalmPresentationInPractice()=runBlocking {
  val f=EntryFixture()
  val before=f.base.saved.getValue("b")
  f.base.saved["b"]=before.copy(evidence=before.evidence.map {it.copy(lines=it.lines.map {line->line.copy(text="受不了了")})})
  try {f.controller.initialize();f.controller.practiceForContact("b")
   withTimeout(2000) {while(f.controller.state.value.practice==null) yield()}
   assertEquals(SafetyLevel.CAUTION,f.controller.state.value.safety);assertTrue(f.controller.state.value.safetyPresentation().calm)
  } finally {f.scope.cancel()}
 }

}
