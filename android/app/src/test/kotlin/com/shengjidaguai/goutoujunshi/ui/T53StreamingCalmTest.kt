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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T53StreamingCalmTest(private val signal:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun unsegmentedStreamFoldsButExplicitExpansionSurvivesFollowingChunks()=runBlocking {
        val first="T53_FREE_TEXT_FIRST"
        val all="$first\nT53_FREE_TEXT_SECOND"
        val f=CounselControllerTest.Fixture(safetyClassifier={if(signal=="caution")SafetyLevel.CAUTION else SafetyLevel.NONE})
        suspend fun await(predicate:()->Boolean)=withTimeout(5000) {while(!predicate()) yield()}
        try {
        val c=f.controller;c.initialize();c.editDraft("合成流式问题")
        if(signal=="emotion") c.setEmotion(8)
        c.analyze();await {f.provider.calls.size==1}
        val output=f.provider.outputs.single()
        output.send(AnalysisChunk.Section(SectionId.FREE_TEXT,first));await {c.state.value.partial[SectionId.FREE_TEXT]==first}
        compose.setContent {val state by c.state.collectAsState();JunshiTheme(calm=state.safetyPresentation().calm) {Surface {CounselContent(state,CounselUiActions())}}}
        if(signal!="none") {
            compose.onAllNodesWithText(first).assertCountEquals(0)
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("等你平静一点再看"))
            compose.onAllNodesWithText(first).assertCountEquals(0)
            compose.onNodeWithText("等你平静一点再看").performClick()
        }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(first))
        compose.onNodeWithText(first).assertIsDisplayed()
        output.send(AnalysisChunk.Section(SectionId.FREE_TEXT,"\nT53_FREE_TEXT_SECOND"));await {c.state.value.partial[SectionId.FREE_TEXT]==all}
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(all))
        compose.onNodeWithText(all).assertIsDisplayed()
        // T27 stop/error recovery still exposes the complete received body after explicit expansion.
        c.stop();await {!c.state.value.streaming}
        compose.onNodeWithText(all).assertExists()
        Unit
        } finally {f.close()}
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}")
        fun cases()=listOf("emotion","caution","none").map {arrayOf(it)}
    }
}
