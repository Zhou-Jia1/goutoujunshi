package com.shengjidaguai.goutoujunshi.data
import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class T52PracticeRepositoryTest {
 private lateinit var db:JunshiDatabase
 private lateinit var session:DataSession
 private lateinit var lease:PracticeSummaryLease
 @Before fun setup()=runBlocking {
  db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).allowMainThreadQueries().build();session=DataSession(db,1)
  ContactRepository(session).save(Contact("a","合成甲"));ConversationRepository(session).save(Conversation("conversation","a"))
  val lines=listOf(ChatLine("line","import",1,Speaker.THEM,"原始合成对方消息",speakerConfirmed=true,contactId="a"))
  ImportRepository(session).save(ChatImport("import","a",ImportSource.PASTE,"synthetic-hash"),lines,0)
  val source=PracticeSourceLease("a",1,"conversation",0,listOf(EvidenceBlock("import","PASTE",lines)),0,"")
  lease=PracticeSummaryLease("ram-practice",1,source,Tip("independent-uuid","确认下一步","安排后附一个确认请求。","明天下午请你确认时间。",10,setOf(TipScene(RelationGroup.SOCIAL))))
 }
 @After fun close() {db.close()}
 @Test fun explicitSaveWritesOnlyTipAndNeverEnablesLearning()=runBlocking {
  TeachingRepository(session).favoritePractice(lease) {true}
  val saved=TipRepository(session).list().single()
  assertEquals(lease.tip,saved);assertFalse(encode(saved).contains("原始合成对方消息"));assertFalse(encode(saved).contains("ram-practice"))
  assertTrue(ConversationRepository(session).messages("a","conversation").isEmpty());assertTrue(TimelineRepository(session).runs("a").isEmpty())
  assertFalse(ConsentRepository(session).get().enabled);assertTrue(StyleRepository(session).samples(RelationGroup.SOCIAL).isEmpty())
  TeachingRepository(session).favoritePractice(lease.copy(tip=lease.tip.copy(id="later",savedAt=20,scenes=setOf(TipScene(RelationGroup.WORK))))) {true}
  assertEquals("independent-uuid",TipRepository(session).list().single().id);assertEquals(10L,TipRepository(session).list().single().savedAt);assertEquals(2,TipRepository(session).list().single().scenes.size)
 }
 @Test fun revokedCurrentRollsBackAndSourcePrivacyChangesReject()=runBlocking {
  var checks=0
  assertTrue(runCatching {TeachingRepository(session).favoritePractice(lease) {++checks<3}}.isFailure)
  assertTrue(TipRepository(session).list().isEmpty())
  RedactionRepository(session).save("a",0,RedactionProfile(revision=1))
  assertTrue(runCatching {TeachingRepository(session).favoritePractice(lease) {true}}.isFailure)
  ImportRepository(session).delete("a","import")
  assertTrue(runCatching {TeachingRepository(session).favoritePractice(lease.copy(source=lease.source.copy(privacyRevision=1))) {true}}.isFailure)
  assertTrue(TipRepository(session).list().isEmpty())
 }
}
