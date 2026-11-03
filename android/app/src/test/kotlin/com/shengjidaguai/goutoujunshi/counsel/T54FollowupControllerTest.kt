package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
class T54FollowupControllerTest {
 private class Fixture {
  val base=CounselControllerTest.MemoryPersistence()
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  val provider=CounselControllerTest.ControlledProvider()
  var schedules=0;var reads=0;var loads=0
  var routeGate:CompletableDeferred<Unit>?=null
  var target:Pair<Contact,StoredCounsel>?=null
  val persistence=object:CounselPersistence by base {
   override suspend fun loadOrCreate(contact:Contact):StoredCounsel {loads++;return base.loadOrCreate(contact)}
   override suspend fun scheduleFollowup(offer:FollowupOffer,due:Long,permission:Boolean,current:()->Boolean):String? {
    if(!permission || !current()) return null;schedules++;return "opaque"
   }
   override suspend fun followupTarget(id:String,generation:String):Pair<Contact,StoredCounsel>? {reads++;routeGate?.await();return target}
  }
  val controller=CounselController(persistence,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
   override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",listOf(KnowledgeReference("synthetic","a".repeat(64),1)),"v1")
  }),provider,scope,localSafety={SafetyLevel.NONE})
  init {base.people+=listOf(Contact("A","合成甲"),Contact("B","合成乙"))}
  suspend fun answer() {
   controller.initialize();controller.editDraft("合成输入");controller.analyze()
   withTimeout(2000) {while(provider.outputs.isEmpty()) yield()}
   provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(id="synthetic-answer",reply=listOf("合成回复"))))
   withTimeout(2000) {while(controller.state.value.answer==null) yield()}
  }
 }
 @Test fun sentOffersUncheckedConsentAndReminderActionsMakeZeroExtraModelCalls()=runBlocking {
  val f=Fixture()
  try {f.answer();val state=f.controller.state.value
   f.controller.feedback(state.conversation!!.id,state.answer!!.id)
   val offer=f.controller.state.value.followupOffer!!;assertEquals("A",offer.owner);assertEquals(0,f.schedules)
   f.controller.scheduleFollowup(offer,System.currentTimeMillis()+1000,false);assertEquals(0,f.schedules)
   f.controller.scheduleFollowup(offer,System.currentTimeMillis()+1000,true);assertEquals(1,f.schedules)
   assertEquals(1,f.provider.calls.size)
   f.controller.selectContact("B");f.controller.scheduleFollowup(offer,System.currentTimeMillis()+1000,true);assertEquals(1,f.schedules)
  } finally {f.scope.cancel()}
 }
 @Test fun permissionCallbackCannotScheduleAfterLockCycleOrSourceEdit()=runBlocking {
  val f=Fixture()
  try {f.answer();val s=f.controller.state.value;f.controller.feedback(s.conversation!!.id,s.answer!!.id)
   val old=f.controller.state.value.followupOffer!!
   f.controller.updateLockState(LockState(true,false));f.controller.updateLockState(LockState(true,true))
   f.controller.scheduleFollowup(old,System.currentTimeMillis()+1000,true);assertEquals(0,f.schedules)
   f.controller.editDraft("新的合成输入");f.controller.scheduleFollowup(old,System.currentTimeMillis()+1000,true);assertEquals(0,f.schedules)
  } finally {f.scope.cancel()}
 }
 @Test fun temporarySentNeverCreatesAnOfferOrPersistentFeedbackOrReminder()=runBlocking {
  val f=Fixture();f.base.people.clear()
  try {f.answer();val s=f.controller.state.value
   assertNull(s.currentContact);f.controller.feedback(s.conversation!!.id,s.answer!!.id)
   assertTrue(f.controller.state.value.feedbackSaved);assertNull(f.controller.state.value.followupOffer)
   f.controller.scheduleFollowup(FollowupOffer("A","synthetic-plan",s.conversation.id,s.actionEpoch,s.actionLock),System.currentTimeMillis()+1000,true)
   assertEquals(0,f.schedules);assertTrue(f.base.feedbackPlans.isEmpty());assertTrue(f.base.saved.isEmpty());assertEquals(1,f.provider.calls.size)
  } finally {f.scope.cancel()}
 }
 @Test fun validRouteUsesExistingConversationAndLockedOrDeletedRouteCannotRecreate()=runBlocking {
  val f=Fixture()
  try {f.controller.initialize();f.target=f.base.people.first() to f.base.saved.getValue("A");val loads=f.loads
   f.controller.openFollowup("id","generation");assertTrue(f.controller.state.value.followupQuestion);assertEquals(loads,f.loads);assertTrue(f.provider.calls.isEmpty())
   val reads=f.reads;f.controller.updateLockState(LockState(true,false));f.controller.openFollowup("id","generation");assertEquals(reads,f.reads)
   f.controller.updateLockState(LockState(true,true));f.target=null;f.controller.openFollowup("id","generation");assertEquals(loads,f.loads)
  } finally {f.scope.cancel()}
 }
 @Test fun suspendedRouteFailureCannotPolluteNewContact()=runBlocking {
  val f=Fixture()
  try {f.controller.initialize();f.routeGate=CompletableDeferred();f.controller.openFollowup("old","old-generation")
   assertEquals(1,f.reads);f.controller.selectContact("B");f.routeGate!!.completeExceptionally(IllegalStateException("synthetic old failure"))
   withTimeout(2000) {while(f.controller.state.value.currentContact?.id!="B") yield()}
   assertFalse(f.controller.state.value.followupQuestion);assertNull(f.controller.state.value.error)
  } finally {f.scope.cancel()}
 }
 @Test fun suspendedNegativeRouteCannotPolluteNewContact()=runBlocking {
  val f=Fixture()
  try {f.controller.initialize();f.routeGate=CompletableDeferred();f.controller.openFollowup("old","old-generation")
   assertEquals(1,f.reads);f.controller.selectContact("B");f.target=null;f.routeGate!!.complete(Unit)
   withTimeout(2000) {while(f.controller.state.value.currentContact?.id!="B") yield()}
   assertFalse(f.controller.state.value.followupQuestion);assertNull(f.controller.state.value.error)
  } finally {f.scope.cancel()}
 }
}
