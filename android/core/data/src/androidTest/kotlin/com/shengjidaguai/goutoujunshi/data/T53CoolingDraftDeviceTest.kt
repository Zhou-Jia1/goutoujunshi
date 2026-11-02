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
class T53CoolingDraftDeviceTest {
 @Test fun encryptedDraftReopensAndTemporaryTextNeverEntersDatabase()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().context
  System.loadLibrary("sqlcipher")
  val name="t53-synthetic-draft.db";context.deleteDatabase(name)
  val key=ByteArray(32) {42}
  fun open()=Room.databaseBuilder(context,JunshiDatabase::class.java,name).openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
  try {
   val db=open();val s=DataSession(db,1);ContactRepository(s).save(Contact("owner","合成"))
   val c=Conversation("conversation","owner",false);val repo=ConversationRepository(s);repo.save(c)
   repo.updateDraft("owner",0,c.copy(revision=1,coolingDraft=CoolingDraft("T53_SECRET_SELECTED",1000)),"输入",0)
   val temp=TemporarySessionStore();val lease=temp.begin("temporary");temp.put(lease,TemporarySession("temporary",draft="T53_RAM_ONLY"))
   db.close()
   val reopened=open();val draft=ConversationRepository(DataSession(reopened,2)).list("owner").single().coolingDraft!!
   assertEquals("T53_SECRET_SELECTED",draft.text);assertFalse(draft.ready(600999));assertTrue(draft.ready(601000))
   assertTrue(ConversationRepository(DataSession(reopened,2)).list("temporary").isEmpty());reopened.close()
   val raw=context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1)
   assertFalse(raw.contains("T53_SECRET_SELECTED"));assertFalse(raw.contains("T53_RAM_ONLY"))
   assertNull(TemporarySessionStore().get(lease))
  } finally {key.fill(0);context.deleteDatabase(name)}
 }
}
