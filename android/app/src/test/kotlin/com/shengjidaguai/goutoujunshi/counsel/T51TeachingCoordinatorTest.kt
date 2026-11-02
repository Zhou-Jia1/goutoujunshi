package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import android.app.Application
import androidx.room.Room
import com.shengjidaguai.goutoujunshi.data.*
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class T51TeachingCoordinatorTest {
    @Test fun reusedAnswerIdentityKeepsBothDifferentNamedFavoritesInRealRoom()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).build()
        // Same test-only construction seam as the existing Room wire tests; real transactions/upserts follow.
        val session=DataSession::class.java.declaredConstructors.single {it.parameterCount==3}.apply {isAccessible=true}.newInstance(db,1L,null) as DataSession
        try {
            var now=100L
            val helper=TeachingCoordinator({now},{ZoneId.of("Asia/Shanghai")})
            val tips=TipRepository(session)
            val saves=TeachingRepository(session)
            val request=AnalysisRequest(requestId="request-a",conversationId="temporary",relationType=RelationType.FRIEND,goal=Goal.REPLY)
            val firstAnswer=StructuredAnswer(id="reused-answer",reply=listOf("我理解，明天再聊。"),tips=listOf(Tip(name="先接后转",explanation="先理解再说明",example="我理解")))
            val first=helper.view(firstAnswer,request,tips.list(),false).tip!!
            saves.favorite(first,null,firstAnswer.id,true) {true}
            now=200L
            val secondAnswer=firstAnswer.copy(tips=listOf(Tip(name="明确时间",explanation="说明下一次沟通时间",example="明天再聊")))
            val second=helper.view(secondAnswer,request.copy(requestId="request-b"),tips.list(),false).tip!!
            saves.favorite(second,null,secondAnswer.id,true) {true}
            assertEquals(setOf("先接后转","明确时间"),tips.list().map {it.name}.toSet())
            assertNotEquals(first.id,second.id)
            now=300L
            val sameName=helper.view(firstAnswer,request.copy(requestId="request-c",relationType=RelationType.BOSS,goal=Goal.REPORT),tips.list(),false).tip!!
            saves.favorite(sameName,null,firstAnswer.id,true) {true}
            val retained=tips.list().single {it.name==first.name}
            assertEquals(first.id,retained.id);assertEquals(100L,retained.savedAt);assertEquals(2,retained.scenes.size)
            saves.favorite(retained,null,firstAnswer.id,false) {true}
            assertEquals(listOf("明确时间"),tips.list().map {it.name})
        } finally {db.close()}
    }

    @Test fun dayBoundaryZoneSwitchOwnerAndLockResetAreExplicit() {
        var time=Instant.parse("2026-11-02T15:59:00Z").toEpochMilli()
        val helper=TeachingCoordinator({time},{ZoneId.of("Asia/Shanghai")})
        assertTrue(helper.temporaryHabit("a","建议"));assertFalse(helper.temporaryHabit("a","另一个建议"))
        time+=120_000;assertTrue(helper.temporaryHabit("a","建议"))
        helper.temporaryHabit("a","建议",true)
        time+=86_400_000;assertFalse(helper.temporaryHabit("a","建议"))
        assertTrue(helper.temporaryHabit("b","建议"))
        helper.reset();assertTrue(helper.temporaryHabit("b","建议"))
    }
    @Test fun onlyOneTipAndLiteralReplyFragmentCanBeSavedOrHighlighted() {
        val answer=StructuredAnswer(id="a",reply=listOf("我理解。明天再聊。"),tips=listOf(Tip(name="先接后转",explanation="先理解",example="我理解"),Tip(name="第二个",explanation="不显示")))
        assertEquals("我理解",TeachingPolicy.tip(answer)!!.example)
        assertEquals("先接后转",TeachingPolicy.tip(answer)!!.name)
        assertEquals("",TeachingPolicy.tip(answer.copy(tips=listOf(answer.tips.first().copy(example="模型虚构片段"))))!!.example)
        assertNull(TeachingPolicy.tip(answer.copy(tips=emptyList())))
    }
}
