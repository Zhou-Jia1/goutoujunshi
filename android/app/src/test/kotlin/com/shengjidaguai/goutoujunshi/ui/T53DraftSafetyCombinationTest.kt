package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T53DraftSafetyCombinationTest(private val formal:Boolean,private val escalation:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun actualSavedReplyDisappearsAfterEscalationAndAckWithoutErasingStorage()=runBlocking {
        var failed=false
        val f=CounselControllerTest.Fixture(wallClock={1000},safetyClassifier={
            check(!failed) {"synthetic missing vocabulary"}
            if(it.contains("合成危机")) SafetyLevel.CRISIS else SafetyLevel.NONE
        })
        suspend fun await(predicate:()->Boolean)=withTimeout(5000) {while(!predicate()) yield()}
        val text="T53_SAVED_ORDINARY_BODY"
        try {
            val c=f.controller;c.initialize()
            if(formal) c.createContact("合成甲",RelationType.FRIEND)
            c.editDraft("合成普通问题");c.analyze();await {f.provider.calls.size==1}
            f.provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(id="saved-answer",reply=listOf(text))))
            await {c.state.value.hasCurrentAnswerSource() && !c.state.value.streaming}
            c.saveCoolingDraft(c.state.value.safetyActionReceipt(),text)
            await {c.state.value.conversation?.coolingDraft?.text==text}
            val stored=c.state.value.conversation!!.coolingDraft
            val owner=c.state.value.currentContact?.id
            compose.setContent {
                val state by c.state.collectAsState()
                JunshiTheme(calm=state.safetyPresentation().calm) {Surface {
                    CounselContent(state,CounselUiActions(acknowledgeSafety=c::acknowledgeSafety))
                }}
            }
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("cooling-draft"))).assertIsDisplayed()
            when(escalation) {
                "local"->c.editDraft("合成危机")
                "asset"->{failed=true;c.editDraft("合成词库失败")}
                else->{
                    c.editDraft("合成另一个问题");c.analyze();await {f.provider.calls.size==2}
                    f.provider.outputs.last().send(AnalysisChunk.Section(SectionId.SAFETY,"CRISIS"))
                }
            }
            await {c.state.value.safety==SafetyLevel.CRISIS}
            compose.waitForIdle()
            compose.onNodeWithTag("cooling-draft").assertDoesNotExist()
            compose.onAllNodesWithText(text).assertCountEquals(0)
            compose.onNodeWithTag("counsel-answer").assertDoesNotExist()
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("我现在是安全的"))
            compose.onNodeWithText("我现在是安全的").performClick()
            compose.waitUntil(5000) {c.state.value.safetyCollapsed}
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("安全帮助"))
            compose.onNodeWithText("安全帮助").assertIsDisplayed()
            compose.onNodeWithTag("cooling-draft").assertDoesNotExist()
            compose.onAllNodesWithText(text).assertCountEquals(0)
            assertFalse(c.state.value.safetyPresentation().showOrdinaryStrategy)
            assertEquals(stored,c.state.value.conversation?.coolingDraft)
            if(formal) assertEquals(stored,f.persistence.saved.getValue(owner!!).conversation.coolingDraft)
            else assertTrue(f.persistence.saved.isEmpty())
        } finally {f.close()}
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="formal={0}_{1}")
        fun cases()=listOf(false,true).flatMap {formal->listOf("local","model","asset").map {arrayOf<Any>(formal,it)}}
    }
}
