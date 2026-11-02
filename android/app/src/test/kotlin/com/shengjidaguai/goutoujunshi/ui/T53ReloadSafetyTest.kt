package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T53ReloadSafetyTest(private val signal:String,private val restart:Boolean) {
    @get:Rule val compose=createComposeRule()
    @Test fun savedOrdinaryBodyCannotReappearOnTargetPublicationOrFreshStartup()=runBlocking {
        var unavailable=false
        val classifier:(String)->SafetyLevel={text->
            check(!unavailable) {"synthetic unavailable vocabulary"}
            when {text.contains("合成危机")->SafetyLevel.CRISIS;text.contains("合成高情绪")->SafetyLevel.CAUTION;else->SafetyLevel.NONE}
        }
        val p=CounselControllerTest.MemoryPersistence().apply {people+=Contact("a","合成甲");people+=Contact("b","合成乙")}
        val first=CounselControllerTest.Fixture(p,wallClock={1000},safetyClassifier=classifier)
        var reopened:CounselControllerTest.Fixture?=null
        val observations=mutableListOf<Pair<SafetyLevel,Boolean>>()
        var watch:Job?=null
        suspend fun await(predicate:()->Boolean)=withTimeout(5000) {while(!predicate()) yield()}
        val body="T53_RELOADED_SAVED_BODY"
        val caution=signal.endsWith("caution")
        val expected=if(caution)SafetyLevel.CAUTION else SafetyLevel.CRISIS
        try {
            var c=first.controller;c.initialize();assertEquals("a",c.state.value.currentContact?.id)
            c.editDraft("合成普通问题");c.analyze();await {first.provider.calls.size==1}
            first.provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(id="saved-answer",reply=listOf(body))))
            await {c.state.value.hasCurrentAnswerSource() && !c.state.value.streaming}
            c.saveCoolingDraft(c.state.value.safetyActionReceipt(),body);await {c.state.value.conversation?.coolingDraft?.text==body}
            val saved=c.state.value.conversation!!.coolingDraft
            val shown=mutableStateOf(c)
            compose.setContent {
                val state by shown.value.state.collectAsState()
                JunshiTheme(calm=state.safetyPresentation().calm) {Surface {CounselContent(state,CounselUiActions(acknowledgeSafety=shown.value::acknowledgeSafety))}}
            }
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
            compose.onNode(hasText(body) and hasAnyAncestor(hasTestTag("cooling-draft"))).assertIsDisplayed()
            when(signal) {
                "local"->c.editDraft("合成危机")
                "local_caution"->c.editDraft("合成高情绪")
                "asset"->{unavailable=true;c.editDraft("合成普通新输入")}
                else->{
                    c.editDraft("合成普通新输入");c.analyze();await {first.provider.calls.size==2}
                    first.provider.outputs.last().send(AnalysisChunk.Section(SectionId.SAFETY,expected.name))
                    await {c.state.value.safety==expected};c.stop();await {!c.state.value.streaming}
                }
            }
            await {c.state.value.safety==expected}
            if(!caution) {
                compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("我现在是安全的"));compose.onNodeWithText("我现在是安全的").performClick()
                compose.waitUntil(5000) {c.state.value.safetyCollapsed}
            }
            c.selectContact("b");await {c.state.value.currentContact?.id=="b"}
            assertEquals(if(signal=="asset")SafetyLevel.CRISIS else SafetyLevel.NONE,c.state.value.safety)
            compose.onAllNodesWithText(body).assertCountEquals(0)
            if(restart) {
                first.close();reopened=CounselControllerTest.Fixture(p,wallClock={1000},safetyClassifier=classifier)
                c=reopened!!.controller;compose.runOnIdle {shown.value=c}
            }
            val observedController=c
            watch=CoroutineScope(Dispatchers.Unconfined).launch {
                observedController.state.collect {state->if(state.currentContact?.id=="a") observations+=state.safety to (state.visibleCoolingDraft()!=null)}
            }
            if(restart) c.initialize() else c.selectContact("a")
            await {c.state.value.currentContact?.id=="a" && !c.state.value.loading}
            assertEquals(expected,c.state.value.safety)
            assertTrue("target must never publish a lower level: $observations",observations.isNotEmpty() && observations.all {it.first==expected})
            assertEquals(saved,c.state.value.conversation?.coolingDraft)
            assertEquals(saved,p.saved.getValue("a").conversation.coolingDraft)
            if(caution) {
                assertTrue(c.state.value.safetyPresentation().calm)
                compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
                compose.onNodeWithText(body).assertDoesNotExist()
                compose.onNode(hasText("等你平静一点再看") and hasAnyAncestor(hasTestTag("cooling-draft"))).performScrollTo().performClick()
                compose.onNodeWithText(body).performScrollTo().assertIsDisplayed()
            } else {
                assertTrue(observations.all {!it.second})
                compose.onNodeWithTag("cooling-draft").assertDoesNotExist();compose.onNodeWithTag("counsel-answer").assertDoesNotExist()
                compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("我现在是安全的"));compose.onNodeWithText("我现在是安全的").performClick()
                compose.waitUntil(5000) {c.state.value.safetyCollapsed}
                compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("安全帮助"));compose.onNodeWithText("安全帮助").assertIsDisplayed()
                compose.onNodeWithTag("cooling-draft").assertDoesNotExist();compose.onAllNodesWithText(body).assertCountEquals(0)
            }
            assertEquals(saved,p.saved.getValue("a").conversation.coolingDraft)
        } finally {watch?.cancel();first.close();reopened?.close()}
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_restart={1}")
        fun cases()=listOf("local","model","asset","local_caution","model_caution").flatMap {s->listOf(false,true).map {arrayOf<Any>(s,it)}}
    }
}
