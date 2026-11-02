package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class T53EmotionScreenshotTest(private val page:String,private val mode:String) {
 @get:Rule val compose=createComposeRule()
 @Test fun productionEmotionSafety() {
  val answer=StructuredAnswer(id="synthetic",empathy="这件事让你很难受，可以先给自己一点时间。",next="先喝口水，把手机放下一会儿。",reply=listOf("我想先整理一下，晚点再和你说。"))
  val initial=CounselState(loading=false,conversation=Conversation("synthetic",coolingDraft=if(page=="draft") CoolingDraft("我想先整理一下，晚点再和你说。",Long.MAX_VALUE-600001) else null),answer=answer,safety=if(page.contains("crisis")) SafetyLevel.CRISIS else SafetyLevel.CAUTION)
  val request=AnalysisRequest(requestId="synthetic-request",conversationId="synthetic")
  val state=initial.copy(answerRequest=request,answerConfigRevision="",messages=listOf(Message("synthetic-message","synthetic",MessageRole.ASSISTANT,requestId=request.requestId,status=MessageStatus.COMPLETED,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer))))
  compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260")2f else 1f)) {
   JunshiTheme(darkTheme=mode=="dark",calm=state.safetyPresentation().calm,fontScaleLevel=if(mode=="font260")FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
    Surface(Modifier.fillMaxSize()) {if(page.startsWith("floating")) Box(Modifier.fillMaxWidth().height(490.dp).testTag("t53-floating-host")) {com.shengjidaguai.goutoujunshi.floating.FloatingPanelContent(state)} else CounselContent(state,CounselUiActions())}
   }
  }}
  if(!page.startsWith("floating")) compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag(if(page=="crisis") "counsel-safety" else if(page=="draft") "cooling-draft" else "counsel-answer"))
  if(page=="breathing") {
   compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("等你平静一点再看"));compose.onNodeWithText("等你平静一点再看").performClick()
   compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("全部复制"));compose.onAllNodesWithText("全部复制").onFirst().performClick()
  }
  (if(page.startsWith("floating")) compose.onNodeWithTag("t53-floating-host") else compose.onAllNodes(isRoot()).onLast()).captureRoboImage("src/test/screenshots/t53_${page}_${mode}.png")
 }
 companion object {
  @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}") fun cases()=listOf("calm","crisis","draft","breathing","floating_calm","floating_crisis").flatMap {p->listOf("light","dark","calm","font260").map {arrayOf(p,it)}}
 }
}
