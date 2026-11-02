package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.shengjidaguai.goutoujunshi.counsel.CounselState
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class T33CopyCollapseUiTest {
    @get:Rule val compose=createComposeRule()
    private val scheduler=TestCoroutineScheduler()
    private val scope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(scheduler))
    private val timer=FloatingCopyCollapse(scope)
    private var folds=0
    private var editingB=false
    @After fun close() { timer.close();scope.cancel() }
    private fun render() {
        val answer=StructuredAnswer(id="copy-a",reply=listOf("合成首选回复"))
        val request=AnalysisRequest(requestId="copy-r",conversationId="copy-c")
        val state=CounselState(loading=false,conversation=Conversation("copy-c"),answer=answer,answerRequest=request,answerConfigRevision="",
            messages=listOf(Message("copy-m","copy-c",MessageRole.ASSISTANT,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId="copy-r",status=MessageStatus.COMPLETED)))
        compose.setContent { JunshiTheme { Box(Modifier.fillMaxWidth().height(490.dp)) {
            FloatingPanelContent(state,onInteraction=timer::newInteraction,onCopy={ timer.copied({true}) {folds++} },onFocus={ if(it) editingB=true })
        } } }
    }
    @Test fun realCopyActionThenSupplementInSamePanelDoesNotFoldNewEditing() {
        render()
        compose.onNodeWithText("复制").performScrollTo().performTouchInput { click() }
        scheduler.runCurrent();scheduler.advanceTimeBy(400)
        compose.onNodeWithText("补充一句").performScrollTo().performClick()
        assertTrue(editingB)
        scheduler.advanceTimeBy(1000);scheduler.runCurrent()
        assertEquals("Copy A timer must not fold the same panel after the B editing action",0,folds)
    }
    @Test fun physicalCopyUpDoesNotCancelItsOwnOneSecondCollapse() {
        render()
        compose.onNodeWithText("复制").performScrollTo().performTouchInput { click() }
        scheduler.runCurrent();scheduler.advanceTimeBy(1000);scheduler.runCurrent()
        assertEquals(1,folds)
    }
}
