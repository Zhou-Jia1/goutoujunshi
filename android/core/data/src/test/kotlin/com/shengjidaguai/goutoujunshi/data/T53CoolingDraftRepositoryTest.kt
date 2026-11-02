package com.shengjidaguai.goutoujunshi.data
import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class T53CoolingDraftRepositoryTest {
 @Test fun savedDraftIsCasGuardedAndSourceDeletionRemovesSelectedText()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).allowMainThreadQueries().build()
  try {
   val session=DataSession(db,1);val owners=ContactRepository(session);val repo=ConversationRepository(session)
   owners.save(Contact("owner","合成"));val c=Conversation("conversation","owner",false);repo.save(c)
   ImportRepository(session).save(ChatImport("import","owner",ImportSource.PASTE,"hash"),emptyList())
   val next=c.copy(revision=1,coolingDraft=CoolingDraft("合成敏感草稿",1000,1))
   assertTrue(runCatching {repo.updateDraft("owner",0,next,"输入",1) {false}}.isFailure)
   assertNull(repo.list("owner").single().coolingDraft)
   var checks=0
   assertTrue(runCatching {repo.updateDraft("owner",0,next,"输入",1) {++checks==1}}.isFailure)
   assertNull(repo.list("owner").single().coolingDraft)
   repo.updateDraft("owner",0,next,"输入",1) {true}
   assertEquals(next.coolingDraft,repo.list("owner").single().coolingDraft)
   ImportRepository(session).delete("owner","import")
   assertNull(repo.list("owner").single().coolingDraft)
   owners.delete("owner");assertTrue(repo.list("owner").isEmpty())
  } finally {db.close()}
 }
}
