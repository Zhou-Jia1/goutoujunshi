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
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T53ConfirmedInputSafetyTest(private val operation:String,private val signal:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun confirmedTargetIsClassifiedBeforeNewInputCanExposeOldSavedReply()=runBlocking {
        var unavailable=false;var checking=false
        lateinit var c:CounselController
        val duringClassification=mutableListOf<Triple<Boolean,SafetyLevel,Boolean>>()
        val text=when(signal) {"crisis"->"合成危机";"caution"->"合成高情绪";else->"合成中性"}
        val f=CounselControllerTest.Fixture(wallClock={1000},safetyClassifier={input->
            if(checking) {
                val state=c.state.value
                val newPublished=if(operation=="edit")state.draft==text else state.evidence.flatMap {it.lines}.any {it.text==text}
                duringClassification+=Triple(newPublished,state.safety,state.visibleCoolingDraft()!=null)
            }
            check(!unavailable) {"synthetic failed vocabulary"}
            when {input.contains("合成危机")->SafetyLevel.CRISIS;input.contains("合成高情绪")->SafetyLevel.CAUTION;else->SafetyLevel.NONE}
        })
        suspend fun await(predicate:()->Boolean)=withTimeout(5000) {while(!predicate()) yield()}
        try {
            c=f.controller;c.initialize();c.editDraft("合成原始输入");c.analyze();await {f.provider.calls.size==1}
            f.provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(id="answer",reply=listOf("T53_OLD_RAM_BODY"))))
            await {c.state.value.hasCurrentAnswerSource() && !c.state.value.streaming}
            c.saveCoolingDraft(c.state.value.safetyActionReceipt(),"T53_OLD_RAM_BODY")
            await {c.state.value.visibleCoolingDraft()!=null};val saved=c.state.value.conversation!!.coolingDraft
            compose.setContent {val state by c.state.collectAsState();JunshiTheme(calm=state.safetyPresentation().calm) {Surface {CounselContent(state,CounselUiActions(acknowledgeSafety=c::acknowledgeSafety))}}}
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
            compose.onNode(hasText("T53_OLD_RAM_BODY") and hasAnyAncestor(hasTestTag("cooling-draft"))).assertIsDisplayed()
            checking=true;unavailable=signal=="asset"
            if(operation=="edit") c.editDraft(text)
            else {
                val preview=ImportPreview(rows=listOf(ImportRow("合成对方",text,speaker=Speaker.THEM)),target=ImportTarget(),rightsAccepted=true)
                val completed=CompletableDeferred<Boolean>();c.commitImport(preview) {completed.complete(it)};assertTrue(completed.await())
            }
            val expected=when(signal) {"crisis","asset"->SafetyLevel.CRISIS;"caution"->SafetyLevel.CAUTION;else->SafetyLevel.NONE}
            assertEquals(expected,c.state.value.safety)
            assertTrue("confirmed input must be classified",duringClassification.isNotEmpty())
            if(expected!=SafetyLevel.NONE) assertTrue("unsafe target was already published before classification: $duringClassification",duringClassification.none {it.first && it.second==SafetyLevel.NONE && it.third})
            assertEquals(saved,c.state.value.conversation?.coolingDraft)
            assertEquals(1,f.provider.calls.size);assertTrue(f.persistence.saved.isEmpty());assertEquals(0,f.persistence.messageWrites);assertEquals(0,f.persistence.draftWrites)
            if(expected==SafetyLevel.CRISIS) {
                compose.onNodeWithTag("cooling-draft").assertDoesNotExist()
                compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("我现在是安全的"));compose.onNodeWithText("我现在是安全的").performClick()
                compose.waitUntil(5000) {c.state.value.safetyCollapsed}
                compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("安全帮助"));compose.onNodeWithText("安全帮助").assertIsDisplayed()
                compose.onNodeWithTag("cooling-draft").assertDoesNotExist();compose.onAllNodesWithText("T53_OLD_RAM_BODY").assertCountEquals(0)
            } else {
                compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
                if(expected==SafetyLevel.CAUTION) {
                    compose.onNodeWithText("T53_OLD_RAM_BODY").assertDoesNotExist()
                    compose.onNode(hasText("等你平静一点再看") and hasAnyAncestor(hasTestTag("cooling-draft"))).performScrollTo().performClick()
                }
                compose.onNode(hasText("T53_OLD_RAM_BODY") and hasAnyAncestor(hasTestTag("cooling-draft"))).performScrollTo().assertIsDisplayed()
            }
            checking=false;c.createContact("合成干净乙",RelationType.FRIEND);await {c.state.value.currentContact!=null}
            assertEquals(if(unavailable)SafetyLevel.CRISIS else SafetyLevel.NONE,c.state.value.safety)
            assertNull(c.state.value.conversation?.coolingDraft);compose.onAllNodesWithText("T53_OLD_RAM_BODY").assertCountEquals(0)
            assertTrue(f.persistence.saved.values.all {it.conversation.coolingDraft==null && it.evidence.isEmpty()})
        } finally {f.close()}
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}")
        fun cases()=listOf("import","edit").flatMap {op->listOf("none","caution","crisis","asset").map {arrayOf(op,it)}}
    }
}
