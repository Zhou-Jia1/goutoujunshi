package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h2200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T51TeachingScreenshotTest(private val page:String,private val mode:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun teachingAndBook() {
        val tip=Tip("synthetic-tip","先接后转","先回应对方的关注，再说明自己的安排。","谢谢你告诉我你的想法。",1,setOf(TipScene(RelationGroup.WORK,Goal.REPORT),TipScene(RelationGroup.SOCIAL,Goal.REPLY)))
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260")2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260")FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) {
                    if(page=="book") TipsContent(listOf(tip),{})
                    else {
                        val answer=StructuredAnswer(id="synthetic-answer",verdict="先理解，再说明安排。",reply=listOf(tip.example,"我想先确认时间，明天再和你商量。"),tips=listOf(tip))
                        val request=AnalysisRequest(requestId="synthetic-request",conversationId="synthetic-conversation")
                        val state=CounselState(loading=false,conversation=Conversation(request.conversationId),answer=answer,answerRequest=request,answerConfigRevision="",answerDemo=false,
                            teaching=TeachingView(answer.id,tip,true,"这次可以先说事情，再补充解释。","synthetic-receipt"),
                            messages=listOf(Message("synthetic-message",request.conversationId,MessageRole.ASSISTANT,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId=request.requestId,status=MessageStatus.COMPLETED)))
                        CounselContent(state,CounselUiActions())
                    }
                }
            }
        }}
        if(page=="answer") compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("teaching-content"))
        if(page=="reply") compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-answer"))
        (if(page!="book") compose.onNodeWithTag("counsel-answer") else compose.onRoot()).captureRoboImage("src/test/screenshots/t51_${page}_${mode}.png")
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}")
        fun cases()=listOf("answer","reply","book").flatMap {p->listOf("light","dark","calm","font260").map {arrayOf(p,it)}}
    }
}
