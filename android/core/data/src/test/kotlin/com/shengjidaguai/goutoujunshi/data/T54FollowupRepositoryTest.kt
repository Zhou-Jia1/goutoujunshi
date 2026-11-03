package com.shengjidaguai.goutoujunshi.data
import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class T54FollowupRepositoryTest {
 private lateinit var db:JunshiDatabase
 private lateinit var session:DataSession
 private lateinit var repo:FollowupRepository
 private val cancelled=mutableSetOf<String>()
 @Before fun setup()=runBlocking {
  db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).allowMainThreadQueries().build()
  session=DataSession(db,1);repo=FollowupRepository(session)
  repo.bind(object:FollowupEffects {var known=setOf<String>();override fun cancel(id:String) {cancelled+=id};override fun retain(ids:Set<String>) {(known-ids).forEach(::cancel);known=ids}})
  ContactRepository(session).save(Contact("owner","合成代号"))
  ConversationRepository(session).save(Conversation("conversation","owner",false))
  val request=AnalysisRequest(requestId="request",conversationId="conversation",contactId="owner")
  TimelineRepository(session).save(AnalysisRun(requestId="request",conversationId="conversation",contactId="owner",requestSnapshot=request))
  ConversationRepository(session).saveMessage(Message("answer","conversation",MessageRole.ASSISTANT,contactId="owner",requestId="request"))
  PlanRepository(session).saveFeedback(ActionPlan("plan","owner","合成回复",status=PlanStatus.SENT,sourceAnswerId="answer",sourceRequestId="request",conversationId="conversation"),0)
 }
 @Test fun workerWakeBeforeDueMustNotDeliver()=runBlocking {
  val r=repo.schedule("owner","plan",System.currentTimeMillis()+3600000,System.currentTimeMillis(),true,true)!!
  assertFalse("An early wake must not display a notification",repo.deliver(r.id,r.generation,{true}) {})
 }
 @Test fun notifyAndCancelSerializeWithCompensatingRemoval()=runBlocking {
  val shown=java.util.concurrent.atomic.AtomicInteger()
  repo.bind(object:FollowupEffects {override fun cancel(id:String) {shown.set(0)};override fun retain(ids:Set<String>) {}})
  val r=repo.schedule("owner","plan",2000,1000,true,true)!!
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val delivery=async(Dispatchers.Default) {repo.deliver(r.id,r.generation,{true}) {entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));shown.set(1)}}
  check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
  val cancellation=async(Dispatchers.Default) {repo.cancel(r.id)}
  yield();assertFalse(cancellation.isCompleted);release.countDown();delivery.await();cancellation.await()
  assertEquals(0,shown.get());assertFalse(repo.deliver(r.id,r.generation,{true}) {shown.incrementAndGet()})
 }
 @Test fun lateCancelOfOldScheduleNeverCancelsTheNewOpaqueIdentity()=runBlocking {
  val old=repo.schedule("owner","plan",2000,1000,true,true)!!
  val next=repo.schedule("owner","plan",3000,1000,true,true)!!
  assertNotEquals(old.id,next.id);assertNotEquals(old.generation,next.generation)
  repo.cancel(old.id)
  assertNull(repo.current(old.id,old.generation) {_,_,_->true})
  assertEquals(next.id,repo.current(next.id,next.generation) {r,_,_->r.id})
  assertFalse(next.id in cancelled)
 }
 @Test fun temporaryOptinCannotPersistAnything()=runBlocking {
  assertTrue(runCatching {repo.schedule("temporary","plan",2000,1000,true,true)}.isFailure)
  assertTrue(db.dao().followups().isEmpty())
 }
 @Test fun clearMustRevokeAuthorityEvenWhenReminderDaoCannotBeRead()=runBlocking {
  db.close()
  runCatching {session.closeForClear()}
  assertFalse("DAO failure must never preserve a live session",session.active)
  assertTrue(runCatching {repo.schedule("owner","plan",2000,1000,true,true)}.isFailure)
 }
 @Test fun failedCancellationPortCannotLeaveAnOldWorkerAuthorized()=runBlocking {
  val r=repo.schedule("owner","plan",2000,1000,true,true)!!
  session.reminderEffects=object:FollowupEffects {override fun cancel(id:String) {error("synthetic cancel failure")};override fun retain(ids:Set<String>) {error("synthetic cancellation failure")}}
  runCatching {session.closeForClear()}
  var shown=0
  runCatching {repo.deliver(r.id,r.generation,{true}) {shown++}}
  assertEquals("Revocation precedes every fallible reminder side effect",listOf(false,0),listOf(session.active,shown))
 }
 @After fun close() {db.close()}
 @Test fun noOptinAndDeniedPermissionCreateZeroRecords()=runBlocking {
  assertNull(repo.schedule("owner","plan",2000,1000,false,true));assertNull(repo.schedule("owner","plan",2000,1000,true,false))
  assertTrue(db.dao().followups().isEmpty())
 }
 @Test fun cancelledOrChangedGenerationCannotNotifyAndDisplayedNotificationRemoved()=runBlocking {
  val r=repo.schedule("owner","plan",2000,1000,true,true)!!;var count=0
  assertFalse(repo.deliver(r.id,"old",{true}) {count++})
  assertTrue(repo.deliver(r.id,r.generation,{true}) {count++});assertEquals(1,count)
  repo.cancel(r.id);assertTrue(r.id in cancelled)
  assertFalse(repo.deliver(r.id,r.generation,{true}) {count++});assertEquals(1,count)
 }
 @Test fun deleteOwnerAndPlanRevokeUnderTheSameGate()=runBlocking {
  val a=repo.schedule("owner","plan",2000,1000,true,true)!!
  PlanRepository(session).delete("owner","plan");assertNull(repo.current(a.id,a.generation) {_,_,_->true});assertTrue(a.id in cancelled)
 }
 @Test fun staleSourceCannotScheduleOrRoute()=runBlocking {
  ContactRepository(session).update(Contact("owner","新合成代号"),0)
  assertTrue(runCatching {repo.schedule("owner","plan",2000,1000,true,true)}.isFailure)
 }
}
