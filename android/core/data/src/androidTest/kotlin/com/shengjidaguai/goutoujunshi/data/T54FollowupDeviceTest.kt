package com.shengjidaguai.goutoujunshi.data
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shengjidaguai.goutoujunshi.domain.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class T54FollowupDeviceTest {
 @Test fun encryptedReminderSurvivesReopenAndDeletedSourceNeverRoutes()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().context
  System.loadLibrary("sqlcipher")
  val name="t54-synthetic-followup.db";context.deleteDatabase(name)
  val key=ByteArray(32) {54}
  fun open()=Room.databaseBuilder(context,JunshiDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
  try {
   val db=open();val s=DataSession(db,1);val repo=FollowupRepository(s)
   ContactRepository(s).save(Contact("T54_OWNER_SECRET","T54_ALIAS_SECRET"))
   ConversationRepository(s).save(Conversation("conversation","T54_OWNER_SECRET",false))
   val request=AnalysisRequest(requestId="request",conversationId="conversation",contactId="T54_OWNER_SECRET")
   TimelineRepository(s).save(AnalysisRun(requestId="request",conversationId="conversation",contactId="T54_OWNER_SECRET",requestSnapshot=request))
   ConversationRepository(s).saveMessage(Message("answer","conversation",MessageRole.ASSISTANT,contactId="T54_OWNER_SECRET",requestId="request"))
   PlanRepository(s).saveFeedback(ActionPlan("plan","T54_OWNER_SECRET","T54_REPLY_SECRET",status=PlanStatus.SENT,sourceAnswerId="answer",sourceRequestId="request",conversationId="conversation"),0)
   val r=repo.schedule("T54_OWNER_SECRET","plan",2000,1000,true,true)!!
   val temporary=TemporarySessionStore();val lease=temporary.begin("temporary");temporary.put(lease,TemporarySession("temporary",draft="T54_RAM_ONLY"))
   assertNull(repo.schedule("temporary","plan",2000,1000,false,true));db.close()
   val reopened=open()
   try {
    val restored=FollowupRepository(DataSession(reopened,2))
    assertEquals("T54_ALIAS_SECRET",restored.current(r.id,r.generation) {_,c,_->c.alias})
    assertNull(restored.current(r.id,"old-generation") {_,c,_->c.alias})
    PlanRepository(DataSession(reopened,2)).delete("T54_OWNER_SECRET","plan")
    assertNull(restored.current(r.id,r.generation) {_,c,_->c.alias})
   } finally {reopened.close()}
   val raw=context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1)
   for(secret in listOf("T54_OWNER_SECRET","T54_ALIAS_SECRET","T54_REPLY_SECRET","T54_RAM_ONLY")) assertFalse(raw.contains(secret))
   assertNull(TemporarySessionStore().get(lease))
  } finally {key.fill(0);context.deleteDatabase(name)}
 }
}
