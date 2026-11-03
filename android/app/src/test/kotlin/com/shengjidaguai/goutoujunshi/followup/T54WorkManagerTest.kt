package com.shengjidaguai.goutoujunshi.followup
import android.app.*
import android.content.Context
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*
import java.util.concurrent.TimeUnit
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[35])
class T54WorkManagerTest {
 @Test fun productionWorkerRealDelayQuietRecheckPrivacyAndCancel()=runBlocking {
  val context=RuntimeEnvironment.getApplication()
  Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
  val db=Room.inMemoryDatabaseBuilder(context,JunshiDatabase::class.java).allowMainThreadQueries().build()
  val constructor=DataSession::class.java.getDeclaredConstructor(JunshiDatabase::class.java,java.lang.Long.TYPE,java.io.File::class.java).apply {isAccessible=true}
  val session=constructor.newInstance(db,1L,null)
  var now=Instant.parse("2026-11-03T10:00:00Z").toEpochMilli()
  val factory=object:WorkerFactory() {override fun createWorker(c:Context,name:String,p:WorkerParameters):ListenableWorker? = if(name==FollowupWorker::class.java.name) FollowupWorker(c,p,{session},{now},{ZoneId.of("UTC")}) else null}
  WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
  val work=WorkManager.getInstance(context);val driver=WorkManagerTestInitHelper.getTestDriver(context)!!
  val scheduler=FollowupScheduler(context,work);val repo=FollowupRepository(session);repo.bind(scheduler)
  ContactRepository(session).save(Contact("owner","绝不能在通知出现的合成代号"))
  ConversationRepository(session).save(Conversation("conversation","owner",false))
  val request=AnalysisRequest(requestId="request",conversationId="conversation",contactId="owner")
  TimelineRepository(session).save(AnalysisRun(requestId="request",conversationId="conversation",contactId="owner",requestSnapshot=request))
  ConversationRepository(session).saveMessage(Message("answer","conversation",MessageRole.ASSISTANT,contactId="owner",requestId="request"))
  PlanRepository(session).saveFeedback(ActionPlan("plan","owner","私密合成回复",status=PlanStatus.SENT,sourceAnswerId="answer",sourceRequestId="request",conversationId="conversation"),0)
  try {
   val r=repo.schedule("owner","plan",now+3600000,now,true,true)!!
   val requestWork=FollowupScheduler.request(r,r.due,now)
   assertEquals(setOf("id","generation"),requestWork.workSpec.input.keyValueMap.keys)
   work.enqueueUniqueWork(FollowupScheduler.name(r.id),ExistingWorkPolicy.REPLACE,requestWork).result.get(10,TimeUnit.SECONDS)
   assertEquals(WorkInfo.State.ENQUEUED,work.getWorkInfoById(requestWork.id).get()!!.state)
   val manager=context.getSystemService(NotificationManager::class.java)
   assertEquals(0,manager.activeNotifications.size)
   now=r.due;driver.setInitialDelayMet(requestWork.id)
   eventually {manager.activeNotifications.size==1}
   val notification=manager.activeNotifications.single().notification
   assertEquals(context.getString(com.shengjidaguai.goutoujunshi.R.string.t54_notification_body),notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
   val pending=Shadows.shadowOf(notification.contentIntent).savedIntent
   assertEquals(r.id to r.generation,FollowupRouter.read(pending))
   assertTrue(pending.extras==null || pending.extras!!.isEmpty)
   repo.cancel(r.id);eventually {manager.activeNotifications.isEmpty()}
   val quiet=repo.schedule("owner","plan",now+3600000,now,true,true)!!
   val quietWork=FollowupScheduler.request(quiet,quiet.due,now)
   work.enqueueUniqueWork(FollowupScheduler.name(quiet.id),ExistingWorkPolicy.REPLACE,quietWork).result.get()
   now=Instant.parse("2026-11-03T23:15:00Z").toEpochMilli();driver.setInitialDelayMet(quietWork.id)
   eventually {work.getWorkInfosForUniqueWork(FollowupScheduler.name(quiet.id)).get().size==2}
   assertEquals(0,manager.activeNotifications.size)
   val delayed=work.getWorkInfosForUniqueWork(FollowupScheduler.name(quiet.id)).get().single {it.id!=quietWork.id}
   eventually {work.getWorkInfoById(delayed.id).get()!!.state==WorkInfo.State.ENQUEUED}
   assertEquals(WorkInfo.State.ENQUEUED,work.getWorkInfoById(delayed.id).get()!!.state)
   now=Instant.parse("2026-11-04T08:00:00Z").toEpochMilli();driver.setInitialDelayMet(delayed.id)
   eventually {manager.activeNotifications.size==1}
   repo.cancel(quiet.id);eventually {manager.activeNotifications.isEmpty()}
   val early=repo.schedule("owner","plan",now+3600000,now,true,true)!!
   val earlyWork=FollowupScheduler.request(early,early.due,now)
   work.enqueueUniqueWork(FollowupScheduler.name(early.id),ExistingWorkPolicy.REPLACE,earlyWork).result.get()
   driver.setInitialDelayMet(earlyWork.id)
   eventually {work.getWorkInfosForUniqueWork(FollowupScheduler.name(early.id)).get().size==2}
   assertEquals(0,manager.activeNotifications.size);repo.cancel(early.id)
   val old=repo.schedule("owner","plan",now+3600000,now,true,true)!!
   val oldEnqueue=repo.current(old.id,old.generation) {record,_,_->scheduler.enqueue(record,record.due,now)}!!
   val newer=repo.schedule("owner","plan",now+7200000,now,true,true)!!
   val newEnqueue=repo.current(newer.id,newer.generation) {record,_,_->scheduler.enqueue(record,record.due,now)}!!
   newEnqueue.result.get();oldEnqueue.result.get() // observe completion after the replacement intent
   repo.cancel(old.id) // a late compensation owns only the old random identity
   eventually {work.getWorkInfosForUniqueWork(FollowupScheduler.name(old.id)).get().all {it.state==WorkInfo.State.CANCELLED}}
   assertEquals(newer.id,repo.current(newer.id,newer.generation) {record,_,_->record.id})
   assertTrue(work.getWorkInfosForUniqueWork(FollowupScheduler.name(newer.id)).get().all {it.state==WorkInfo.State.ENQUEUED})
   assertNull(repo.current(old.id,old.generation) {_,_,_->true})
   repo.cancel(newer.id)
   val cancelled=repo.schedule("owner","plan",now+1000,now,true,true)!!
   scheduler.enqueue(cancelled,cancelled.due,now);repo.cancel(cancelled.id)
   eventually {work.getWorkInfosForUniqueWork(FollowupScheduler.name(cancelled.id)).get().all {it.state==WorkInfo.State.CANCELLED}}
   assertEquals(0,manager.activeNotifications.size)
   val orphan=repo.schedule("owner","plan",now+3600000,now,true,true)!!
   scheduler.enqueue(orphan,orphan.due,now).result.get();scheduler.notify(orphan)
   val coldScheduler=FollowupScheduler(context,work) // no in-memory known IDs in this new instance
   coldScheduler.clear()
   eventually {work.getWorkInfosForUniqueWork(FollowupScheduler.name(orphan.id)).get().all {it.state==WorkInfo.State.CANCELLED}}
   eventually {context.getSystemService(NotificationManager::class.java).activeNotifications.none {it.tag?.startsWith(FollowupScheduler.PREFIX)==true}}
  } finally {work.cancelAllWork().result.get();db.close()}
 }
 private fun eventually(condition:()->Boolean) {repeat(200) {Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(10)};assertTrue(condition())}
}
