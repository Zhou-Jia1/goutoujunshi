package com.shengjidaguai.goutoujunshi.followup
import android.app.*
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.MainActivity
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class T54FollowupRouteDeviceTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun actualPendingIntentUnlocksAndLoadsOnlyTheExistingEncryptedConversation()=runBlocking {
  val ruleActivity=compose.activity
  val ruleLaunchIntent=Intent(ruleActivity.intent)
  fun phase(value:String) {println("T54_ROUTE_PHASE $value")}
  phase("rule_launch_captured")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  assertTrue("Root fixture must explicitly grant POST_NOTIFICATIONS",androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
  val app=context.applicationContext as Application
  val controller=CounselRuntime.get(app)
  compose.waitUntil(15000) {!controller.state.value.loading && controller.state.value.conversation!=null}
  val originalLock=controller.state.value.lockState
  val created=mutableListOf<String>()
  val session=CounselRuntime.store(app).open()
  try {
   controller.updateLockState(LockState(false,true))
   val alias="T54_ROUTE_SYNTHETIC_"+java.util.UUID.randomUUID().toString()
   controller.createContact(alias,RelationType.FRIEND)
   compose.waitUntil(15000) {controller.state.value.currentContact?.alias==alias}
   val a=controller.state.value.currentContact!!;created+=a.id
   val conversation=controller.state.value.conversation!!
   val requestId=java.util.UUID.randomUUID().toString();val messageId=java.util.UUID.randomUUID().toString();val planId=java.util.UUID.randomUUID().toString()
   val request=AnalysisRequest(requestId=requestId,conversationId=conversation.id,contactId=a.id,sourceRevision=a.sourceRevision,conversationRevision=conversation.revision)
   TimelineRepository(session).save(AnalysisRun(requestId=requestId,conversationId=conversation.id,contactId=a.id,requestSnapshot=request,status=RunStatus.COMPLETED))
   ConversationRepository(session).saveMessage(Message(messageId,conversation.id,MessageRole.ASSISTANT,contactId=a.id,requestId=requestId,status=MessageStatus.COMPLETED))
   PlanRepository(session).saveFeedback(ActionPlan(planId,a.id,"合成回复",status=PlanStatus.SENT,sourceAnswerId=messageId,sourceRequestId=requestId,conversationId=conversation.id,conversationRevision=conversation.revision,sourceRevision=a.sourceRevision),0)
   val repository=FollowupRepository(session);val now=System.currentTimeMillis()
   val r=repository.schedule(a.id,planId,now-500,now-1000,true,true)!!
   val scheduler=FollowupScheduler(context);repository.bind(scheduler)
   assertTrue(repository.deliver(r.id,r.generation,scheduler::permitted) {scheduler.notify(it)})
   val notification=context.getSystemService(NotificationManager::class.java).activeNotifications.single {it.tag==FollowupScheduler.tag(r.id)}
   phase("encrypted_record_and_notification_ready")
   val pending=notification.notification.contentIntent
   assertTrue(pending.isImmutable)
   val bAlias="T54_OTHER_SYNTHETIC_"+java.util.UUID.randomUUID().toString()
   controller.createContact(bAlias,RelationType.FRIEND)
   compose.waitUntil(15000) {controller.state.value.currentContact?.alias==bAlias}
   val b=controller.state.value.currentContact!!;created+=b.id
   controller.updateLockState(LockState(true,false))
   pending.send()
   compose.waitForIdle();assertEquals(b.id,controller.state.value.currentContact?.id)
   compose.onNodeWithText("上次你发了那条消息，对方回了吗？").assertDoesNotExist()
   phase("locked_delivery_hides_question_and_keeps_b")
   controller.updateLockState(LockState(true,true))
   compose.waitUntil(15000) {controller.state.value.followupQuestion && controller.state.value.currentContact?.id==a.id}
   compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("上次你发了那条消息，对方回了吗？"))
   compose.onNodeWithText("上次你发了那条消息，对方回了吗？").assertIsDisplayed()
   phase("unlocked_delivery_reaches_a_and_visible_question")
   PlanRepository(session).delete(a.id,planId)
   controller.selectContact(b.id);compose.waitUntil(15000) {controller.state.value.currentContact?.id==b.id && !controller.state.value.followupQuestion}
   pending.send();compose.waitForIdle()
   assertEquals(b.id,controller.state.value.currentContact?.id);assertFalse(controller.state.value.followupQuestion)
   assertTrue(PlanRepository(session).list(a.id).isEmpty())
   phase("deleted_source_old_pending_intent_rejected_by_b")
   phase("all_body_assertions_completed")
  } finally {
   try {
    controller.updateLockState(originalLock)
    created.forEach {ContactRepository(session).delete(it)}
    phase("synthetic_sources_cleaned")
   } finally {
    // ActivityScenario1.7 observes only lifecycle events matching its launch Intent.
    // Keep production onNewIntent/consumption unchanged throughout every assertion.
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
     ruleActivity.intent=Intent(ruleLaunchIntent)
     phase("rule_launch_intent_restored_for_real_teardown")
    }
   }
  }
 }
}
