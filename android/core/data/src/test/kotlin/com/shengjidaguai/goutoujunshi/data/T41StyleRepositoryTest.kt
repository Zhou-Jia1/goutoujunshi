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
class T41StyleRepositoryTest {
    @Test fun confirmedImportsLearnOnlyMeWithDeterministicQuotaAndRetry() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).build()
        try {
            val session=DataSession(db,1)
            ContactRepository(session).save(Contact("a","合成甲",RelationType.FRIEND))
            ConsentRepository(session).update(true,false)
            val imported=ChatImport("batch","a",ImportSource.PASTE,"synthetic")
            val lines=(0..39).map { index -> ChatLine("line$index",imported.id,seq=index,speaker=Speaker.ME,
                text="合成消息".repeat(index+1),speakerConfirmed=true,contactId="a") } +
                ChatLine("them",imported.id,speaker=Speaker.THEM,text="不是本人",speakerConfirmed=true,contactId="a")
            ImportRepository(session).save(imported,lines)
            val repo=StyleRepository(session)
            val samples=repo.samples(RelationGroup.SOCIAL)
            assertEquals(30,samples.size)
            assertTrue(samples.all { it.sourceContactId=="a" && it.sourceImportId=="batch" && it.sourceMessageId!="them" })
            assertTrue(samples.any {it.sourceMessageId=="line39"})
            assertFalse(samples.any {it.sourceMessageId=="line10"})
            ImportRepository(session).save(imported,lines)
            assertEquals(samples,repo.samples(RelationGroup.SOCIAL))
            assertTrue(repo.samples(RelationGroup.WORK).isEmpty())
            assertEquals(StyleFeatureExtractor.extract(samples.map {it.text}),repo.profile(RelationGroup.SOCIAL)!!.features)
        } finally {db.close()}
    }
    @Test fun longerAndDistinctExamplesWinWithoutChangingSourceIdentity() {
        val samples=(0..39).map {StyleSample(id="s$it",text=if(it<10) "较长的代表消息".repeat(8) else "嗯",createdAt=it.toLong())}
        val kept=StylePersistencePolicy.retainSamples(samples)
        assertEquals(30,kept.size)
        assertTrue((0..9).all {id->kept.any {it.id=="s$id"}})
        assertEquals(kept,StylePersistencePolicy.retainSamples(samples.reversed()))
    }
    @Test fun unrelatedGroupWritesPreserveManualDescriptionAndSourceDeletionRevokesIt()=runBlocking {
        withSession {session ->
            val contacts=ContactRepository(session)
            contacts.save(Contact("friend","合成朋友",RelationType.FRIEND))
            contacts.save(Contact("boss","合成领导",RelationType.BOSS))
            ConsentRepository(session).update(true)
            saveLines(session,"friend","social",3)
            val repo=StyleRepository(session)
            repo.editDescription(RelationGroup.SOCIAL,"喜欢短句和语气词",repo.snapshot().stamp)
            val old=repo.snapshot().stamp
            saveLines(session,"boss","work",3)
            assertEquals("喜欢短句和语气词",repo.profile(RelationGroup.SOCIAL)!!.description)
            assertTrue(runCatching {repo.editDescription(RelationGroup.SOCIAL,"迟到描述",old)}.isFailure)
            ImportRepository(session).delete("friend","social")
            assertNull(repo.profile(RelationGroup.SOCIAL))
            assertTrue(repo.samples(RelationGroup.SOCIAL).isEmpty())
            assertTrue(repo.context("boss",RelationGroup.WORK).samples.all {it.sourceContactId=="boss"})
        }
    }
    @Test fun pauseDenialQuizDefaultAndFreshConsentAreEnforced()=runBlocking {
        withSession {session ->
            ContactRepository(session).save(Contact("a","合成朋友",RelationType.FRIEND))
            saveLines(session,"a","denied",16)
            val repo=StyleRepository(session)
            assertTrue(repo.samples(RelationGroup.SOCIAL).isEmpty())
            assertTrue(runCatching {repo.saveQuiz(List(5) {"选择$it"},repo.snapshot().stamp)}.isFailure)
            ConsentRepository(session).update(true)
            val before=repo.snapshot().stamp
            repo.saveQuiz(List(5) {"选择$it"},before)
            assertEquals(5,repo.samples(RelationGroup.DEFAULT).size)
            assertTrue(repo.samples(RelationGroup.DEFAULT).all {it.sourceContactId==null && it.origin==StyleOrigin.QUIZ})
            ConsentRepository(session).update(true,true)
            saveLines(session,"a","paused",16)
            assertTrue(repo.samples(RelationGroup.SOCIAL).isEmpty())
            assertEquals(StyleOutboundContext(),repo.context("a",RelationGroup.SOCIAL))
            assertTrue(runCatching {repo.saveQuiz(List(5) {"迟到$it"},before)}.isFailure)
        }
    }
    @Test fun deletionResetAndDescriptionEditsRevokeOldCasAndNeverRelearnOnAckRetry()=runBlocking {
        withSession {session ->
            ContactRepository(session).save(Contact("a","合成朋友",RelationType.FRIEND))
            ConsentRepository(session).update(true)
            saveLines(session,"a","source",16)
            val repo=StyleRepository(session)
            val first=repo.samples(RelationGroup.SOCIAL).first()
            val before=repo.snapshot().stamp
            repo.removeSample(first.id,before)
            assertEquals(15,repo.samples(RelationGroup.SOCIAL).size)
            assertTrue(runCatching {repo.editDescription(RelationGroup.SOCIAL,"旧模型返回",before)}.isFailure)
            ImportRepository(session).save(ChatImport("source","a",ImportSource.PASTE,"source"),emptyList())
            assertEquals(15,repo.samples(RelationGroup.SOCIAL).size)
            repo.editDescription(RelationGroup.SOCIAL,"用户手改",repo.snapshot().stamp)
            val editStamp=repo.snapshot().stamp
            assertTrue(runCatching {repo.editDescription(RelationGroup.SOCIAL,"过期编辑",before)}.isFailure)
            repo.reset(RelationGroup.SOCIAL,editStamp)
            assertTrue(repo.samples(RelationGroup.SOCIAL).isEmpty())
            assertNull(repo.profile(RelationGroup.SOCIAL))
            ImportRepository(session).save(ChatImport("source","a",ImportSource.PASTE,"source"),emptyList())
            assertTrue(repo.samples(RelationGroup.SOCIAL).isEmpty())
        }
    }
    @Test fun cancelledDescriptionWriteRollsBackAndForeignRawSamplesStayOut()=runBlocking {
        withSession {session ->
            ContactRepository(session).save(Contact("a","合成甲",RelationType.FRIEND))
            ContactRepository(session).save(Contact("b","合成乙",RelationType.FRIEND))
            ConsentRepository(session).update(true)
            saveLines(session,"a","a-source",3);saveLines(session,"b","b-source",16)
            val repo=StyleRepository(session)
            val before=repo.snapshot()
            var checks=0
            assertTrue(runCatching {repo.editDescription(RelationGroup.SOCIAL,"应回滚",before.stamp) {++checks==1}}.isFailure)
            assertEquals(before,repo.snapshot())
            FloatingIdentityRepository(session).save(FloatingIdentityBinding("style-source-binding","b",FloatingSourceContext("合成应用","源账号标识"),"来源头部真实昵称",true,ContactRepository(session).get("b")!!.sourceRevision))
            val projection=repo.context("a",RelationGroup.SOCIAL)
            assertTrue(projection.samples.all {it.sourceContactId=="a"})
            assertFalse(projection.samples.any {it.sourceContactId=="b"})
            assertTrue(projection.sourceAliases.containsAll(listOf("合成甲","合成乙","来源头部真实昵称","源账号标识")))
        }
    }
    @Test fun threeOriginsEditAsExplicitOverridesWithoutChangingOriginalsAndSourceDeletionRevokesThem()=runBlocking {
        withSession {session ->
            ContactRepository(session).save(Contact("a","合成朋友",RelationType.FRIEND));ConsentRepository(session).update(true)
            saveLines(session,"a","edit-source",3)
            val repo=StyleRepository(session)
            repo.saveQuiz(List(5) {"原测验选项$it"},repo.snapshot().stamp)
            val conversation=Conversation("edit-conversation","a",false)
            ConversationRepository(session).save(conversation)
            ConversationRepository(session).saveMessage(Message("edited-answer",conversation.id,MessageRole.ASSISTANT,"原建议",requestId="edit-run",status=MessageStatus.COMPLETED,contactId="a"))
            val evidence=EvidenceBlock("edit-source","PASTE",ImportRepository(session).lines("a","edit-source"))
            TimelineRepository(session).save(AnalysisRun(requestId="edit-run",conversationId=conversation.id,contactId="a",status=RunStatus.COMPLETED,
                requestSnapshot=AnalysisRequest(requestId="edit-run",contactId="a",conversationId=conversation.id,evidence=listOf(evidence),speakersConfirmed=true)))
            val stamp=session.stamp("a")
            repo.saveSample(StyleSample("original-edited",RelationGroup.SOCIAL,"原先我改了再发",StyleOrigin.EDITED_REPLY,"a",sourceAnswerId="edited-answer",consentVersion=stamp.consentVersion,sourceRevision=stamp.sourceRevision),stamp)
            val originals=listOf(repo.samples(RelationGroup.SOCIAL).first {it.origin==StyleOrigin.IMPORT},repo.samples(RelationGroup.DEFAULT).first(),repo.samples(RelationGroup.SOCIAL).first {it.origin==StyleOrigin.EDITED_REPLY})
            for(original in originals) {
                val before=repo.snapshot().stamp
                val replacement="本人明确改写 ${original.origin} 🙂"
                repo.editSample(original.id,replacement,before)
                val after=repo.samples(original.relationGroup).single {it.id==original.id}
                assertEquals(original.text,after.text);assertEquals(original.origin,after.origin)
                assertEquals(original.sourceContactId,after.sourceContactId);assertEquals(original.sourceMessageId,after.sourceMessageId);assertEquals(original.sourceAnswerId,after.sourceAnswerId)
                assertEquals(replacement,after.effectiveText);assertNotNull(after.userEdit)
                assertTrue(runCatching {repo.editSample(original.id,"迟到编辑",before)}.isFailure)
                assertEquals(StyleFeatureExtractor.extract(repo.samples(original.relationGroup).map {it.effectiveText}),repo.profile(original.relationGroup)!!.features)
            }
            assertEquals("合成表达0，嗯好呀～",ImportRepository(session).lines("a","edit-source").first().text)
            val before=repo.snapshot();var checks=0
            assertTrue(runCatching {repo.editSample(originals.first().id,"取消的编辑",before.stamp) {++checks==1}}.isFailure)
            assertEquals(before,repo.snapshot())
            ImportRepository(session).delete("a","edit-source")
            assertTrue(repo.samples(RelationGroup.SOCIAL).isEmpty())
            assertNull(repo.profile(RelationGroup.SOCIAL))
            assertEquals(5,repo.samples(RelationGroup.DEFAULT).size)
            assertTrue(runCatching {repo.editSample(originals.first().id,"来源已删的迟到编辑",repo.snapshot().stamp)}.isFailure)
        }
    }
    @Test fun pauseHidesButResumePreservesUserDescriptionOnlyWhileSourcesSurvive()=runBlocking {
        withSession {session ->
            ContactRepository(session).save(Contact("a","合成朋友",RelationType.FRIEND));ConsentRepository(session).update(true)
            saveLines(session,"a","pause-source",3)
            val repo=StyleRepository(session)
            repo.editDescription(RelationGroup.SOCIAL,"用户手改的长期风格说明",repo.snapshot().stamp)
            val old=repo.snapshot().stamp
            val originalRow=session.transaction {profile(RelationGroup.SOCIAL.name)}
            ConsentRepository(session).update(true,true)
            assertEquals(originalRow,session.transaction {profile(RelationGroup.SOCIAL.name)})
            assertNull(repo.profile(RelationGroup.SOCIAL));assertEquals(StyleOutboundContext(),repo.context("a",RelationGroup.SOCIAL))
            assertTrue(runCatching {repo.editDescription(RelationGroup.SOCIAL,"暂停中的迟到模型",old)}.isFailure)
            ConsentRepository(session).update(true,false)
            assertEquals("用户手改的长期风格说明",repo.profile(RelationGroup.SOCIAL)!!.description)
            assertTrue(runCatching {repo.editDescription(RelationGroup.SOCIAL,"恢复后迟到模型",old)}.isFailure)
            ConsentRepository(session).update(true,true)
            val pausedSample=repo.samples(RelationGroup.SOCIAL).first()
            repo.removeSample(pausedSample.id,repo.snapshot().stamp)
            assertNull(session.transaction {profile(RelationGroup.SOCIAL.name)})
            ImportRepository(session).delete("a","pause-source")
            ConsentRepository(session).update(true,false)
            assertNull(repo.profile(RelationGroup.SOCIAL));assertTrue(repo.samples(RelationGroup.SOCIAL).isEmpty())
            saveLines(session,"a","fresh-source",3)
            assertEquals("",repo.profile(RelationGroup.SOCIAL)!!.description)
            repo.editDescription(RelationGroup.SOCIAL,"撤回前的描述",repo.snapshot().stamp)
            ConsentRepository(session).update(false)
            ConsentRepository(session).update(true)
            assertNull(repo.profile(RelationGroup.SOCIAL))
        }
    }
    @Test fun ownerInsufficientWithoutGroupDescriptionFallsBackToRealDefaultQuizOverrides()=runBlocking {
        withSession {session ->
            ContactRepository(session).save(Contact("a","合成甲",RelationType.BOSS));ContactRepository(session).save(Contact("b","合成乙",RelationType.BOSS))
            ConsentRepository(session).update(true);saveLines(session,"a","work-a",2);saveLines(session,"b","work-b",2)
            val repo=StyleRepository(session)
            repo.saveQuiz(List(5) {"默认选项$it"},repo.snapshot().stamp)
            val original=repo.samples(RelationGroup.DEFAULT).first()
            repo.editSample(original.id,"用户明确改写的默认示例",repo.snapshot().stamp)
            val fallback=repo.context("a",RelationGroup.WORK)
            assertEquals(5,fallback.samples.size)
            assertTrue(fallback.samples.all {it.relationGroup==RelationGroup.DEFAULT && it.origin==StyleOrigin.QUIZ && it.sourceContactId==null})
            assertEquals("用户明确改写的默认示例",fallback.samples.single {it.id==original.id}.effectiveText)
            assertEquals(original.text,fallback.samples.single {it.id==original.id}.text)
            repo.editDescription(RelationGroup.WORK,"对领导习惯正式短句",repo.snapshot().stamp)
            val described=repo.context("a",RelationGroup.WORK)
            assertEquals("对领导习惯正式短句",described.description)
            assertTrue(described.samples.isEmpty())
        }
    }
    private suspend fun saveLines(session:DataSession,owner:String,id:String,count:Int) {
        ImportRepository(session).save(ChatImport(id,owner,ImportSource.PASTE,id),(0 until count).map {index ->
            ChatLine("$id:$index",id,seq=index,speaker=Speaker.ME,text="合成表达$index，嗯好呀～",speakerConfirmed=true,contactId=owner)
        })
    }
    private suspend fun withSession(block:suspend (DataSession)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).build()
        try {block(DataSession(db,1))} finally {db.close()}
    }

}
