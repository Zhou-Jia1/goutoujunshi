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
class T51TeachingRepositoryTest {
    private lateinit var db:JunshiDatabase
    private lateinit var session:DataSession
    private val request=AnalysisRequest(requestId="request",contactId="a",conversationId="conversation",userText="合成问题",relationType=RelationType.FRIEND,goal=Goal.REPLY)
    private val answer=StructuredAnswer(id="answer",reply=listOf("我理解，我们明天再聊。"),tips=listOf(Tip(name="先接后转",explanation="先理解再说明",example="我理解")),habit="先把事情说明白。")
    private val tip=TeachingPolicy.tip(answer)!!.copy(id="selected",savedAt=10,scenes=setOf(TipScene(RelationGroup.SOCIAL,Goal.REPLY)))
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).allowMainThreadQueries().build()
        session=DataSession(db,1)
        ContactRepository(session).save(Contact("a","合成甲"))
        ConversationRepository(session).save(Conversation("conversation","a"))
        session.transaction {val row=conversation("a","conversation")!!;put(row.copy(draft=request.userText))}
        ConversationRepository(session).saveMessage(Message("message","conversation",MessageRole.ASSISTANT,answerJson=encode(answer),requestId=request.requestId,status=MessageStatus.COMPLETED,contactId="a"))
        TimelineRepository(session).save(AnalysisRun(requestId=request.requestId,conversationId="conversation",contactId="a",status=RunStatus.COMPLETED,requestSnapshot=request))
    }
    @After fun close() {db.close()}
    @Test fun mergeCancelAndLegacyJsonWithoutLearningConsent()=runBlocking {
        val repo=TeachingRepository(session)
        assertFalse(ConsentRepository(session).get().enabled)
        repo.favorite(tip,request,answer.id,true) {true}
        val next=tip.copy(id="later",savedAt=20,scenes=setOf(TipScene(RelationGroup.WORK,Goal.REPORT)))
        TipRepository(session).save(next)
        TipRepository(session).save(next)
        val saved=TipRepository(session).list().single()
        assertEquals("selected",saved.id);assertEquals(10L,saved.savedAt);assertEquals(2,saved.scenes.size)
        assertTrue(decode<Tip>("""{"id":"old","name":"旧技巧"}""").scenes.isEmpty())
        repo.favorite(tip,request,answer.id,false) {true};assertTrue(TipRepository(session).list().isEmpty())
        assertFalse(ConsentRepository(session).get().enabled)
        assertTrue(StyleRepository(session).samples(RelationGroup.SOCIAL).isEmpty())
    }
    @Test fun habitDailyDedupDisableAndOwnerCascade()=runBlocking {
        val repo=TeachingRepository(session)
        assertTrue(repo.habit(request,answer.id,"2026-11-02",current={true}))
        assertFalse(repo.habit(request,answer.id,"2026-11-02",current={true}))
        assertTrue(repo.habit(request,answer.id,"2026-11-03",current={true}))
        repo.habit(request,answer.id,"2026-11-03",answer.habit) {true}
        assertFalse(repo.habit(request,answer.id,"2026-11-04",current={true}))
        ContactRepository(session).delete("a")
        assertTrue(session.transaction {records("a",OwnedRecordKind.TEACHING_HABIT.name)}.isEmpty())
    }
    @Test fun revokedAfterMutationRollsBackFavoriteAndFrequency()=runBlocking {
        val repo=TeachingRepository(session)
        var count=0
        assertTrue(runCatching {repo.favorite(tip,null,answer.id,true) {++count<3}}.isFailure)
        assertTrue(TipRepository(session).list().isEmpty())
        count=0
        assertTrue(runCatching {repo.habit(request,answer.id,"today",current={++count<4})}.isFailure)
        assertTrue(repo.habit(request,answer.id,"today",current={true}))
    }
    @Test fun staleAnswerOwnerSourcePrivacyAndSessionAreRejected()=runBlocking {
        val repo=TeachingRepository(session)
        assertTrue(runCatching {repo.favorite(tip,request,"other",true) {true}}.isFailure)
        assertTrue(runCatching {repo.favorite(tip,request.copy(contactId="b"),answer.id,true) {true}}.isFailure)
        RedactionRepository(session).save("a",0,RedactionProfile(revision=1))
        assertTrue(runCatching {repo.favorite(tip,request,answer.id,true) {true}}.isFailure)
        session.active=false
        assertTrue(runCatching {repo.favorite(tip,null,answer.id,true) {true}}.isFailure)
    }
    @Test fun temporaryExplicitSavePersistsOnlySelectedTip()=runBlocking {
        TeachingRepository(session).favorite(tip,null,answer.id,true) {true}
        val saved=TipRepository(session).list().single()
        assertEquals("我理解",saved.example)
        val payload=encode(saved)
        assertFalse(payload.contains(request.userText));assertFalse(payload.contains("contactId"));assertFalse(payload.contains(answer.habit))
    }
}
