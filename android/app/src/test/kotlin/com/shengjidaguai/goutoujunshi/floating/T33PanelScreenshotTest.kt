package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.shengjidaguai.goutoujunshi.designsystem.FontScaleLevel
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.CounselState
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T33PanelScreenshotTest(private val mode:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun panel() {
        val state=CounselState(loading=false,conversation=Conversation("t33",null,true),
            answer=StructuredAnswer(id="t33-answer",verdict="先接住对方的关心，再说明边界。",reply=listOf("谢谢你的关心，我想先按自己的节奏试试看。")),
            emotion=if(mode=="calm") 8 else null,safety=if(mode=="crisis") SafetyLevel.CRISIS else SafetyLevel.NONE)
        val answer=state.answer!!
        val request=AnalysisRequest(requestId="t33-request",conversationId="t33")
        val current=state.copy(answerRequest=request,answerConfigRevision="",messages=listOf(Message("t33-assistant","t33",MessageRole.ASSISTANT,
            answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId="t33-request",status=MessageStatus.COMPLETED)))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="large") 2f else 1f)) { JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="large") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
            Box(Modifier.fillMaxWidth().height(490.dp)) { FloatingPanelContent(current) }
        } } }
        compose.onRoot().captureRoboImage("src/test/screenshots/t33_panel_${mode}.png")
        if(mode!="crisis") compose.onNodeWithText("补充一句").performScrollTo().assertIsDisplayed()
    }
    @Test fun singleScrollKeepsSideSupplementAndSendReachableWithFullBody() {
        val preview=PreparedPrompt("虚构知识".repeat(8000),"虚构用户",listOf(KnowledgeReference("synthetic","a".repeat(64),1)),"synthetic-v1")
        val state=CounselState(loading=false,conversation=Conversation("t33-scroll",null,true),preview=preview,floatingPreviewReady=true,
            floatingCapture=com.shengjidaguai.goutoujunshi.counsel.FloatingCaptureView("synthetic-capture",com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT))
        var side=0;var done=0;var sent=0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="large") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="large") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Box(Modifier.fillMaxWidth().height(490.dp)) { FloatingPanelContent(state,onSide={side++},onSupplement={done++},onSend={sent++}) }
            }
        } }
        compose.onNodeWithText("一键切换左右").performScrollTo().performClick()
        compose.onNodeWithText("补充一句").performScrollTo().performClick()
        compose.onNodeWithText("完成补充").performScrollTo().performClick()
        compose.onNodeWithText(org.robolectric.RuntimeEnvironment.getApplication().getString(com.shengjidaguai.goutoujunshi.R.string.live_send)).performScrollTo().performClick()
        org.junit.Assert.assertEquals(1,side);org.junit.Assert.assertEquals(1,done);org.junit.Assert.assertEquals(1,sent)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
    }
    @Test fun manualSpeakerSourceHasMoreAndSupplementWithoutPhysicalSwap() {
        val state=CounselState(loading=false,conversation=Conversation("manual",null,true),
            floatingCapture=com.shengjidaguai.goutoujunshi.counsel.FloatingCaptureView("manual",com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT,canSwapPhysicalSide=false))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="large") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="large") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Box(Modifier.fillMaxWidth().height(490.dp)) { FloatingPanelContent(state) }
            }
        } }
        compose.onNodeWithText("一键切换左右").assertDoesNotExist()
        compose.onNodeWithText("已逐行确认说话人；当前截图无法按左右整体切换").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("补充一句").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("再往上截一屏").performScrollTo().assertIsDisplayed()
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}")
        fun modes()=listOf(arrayOf("light"),arrayOf("dark"),arrayOf("calm"),arrayOf("crisis"),arrayOf("large"))
    }
}
