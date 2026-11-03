package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.FollowupOffer
import com.shengjidaguai.goutoujunshi.designsystem.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.*
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T54FollowupScreenshotTest(private val page:String,private val mode:String) {
 @get:Rule val compose=createComposeRule()
 @Test fun followup() {
  var schedules=0
  compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260")2f else 1f)) {
   JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260")FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
    Surface(Modifier.fillMaxSize()) {if(page=="temporary") CounselContent(t54FeedbackState(false),CounselUiActions()) else Column(Modifier.verticalScroll(rememberScrollState())) {
     if(page=="denied") FollowupDenied {} else if(page=="offer") FollowupPrompt(FollowupOffer("synthetic-owner","synthetic-plan","synthetic-conversation",0,0),{schedules++}) else FollowupQuestion({},{},{})
    }}
   }
  }}
  if(page=="temporary") {
   val notice=org.robolectric.RuntimeEnvironment.getApplication().getString(com.shengjidaguai.goutoujunshi.R.string.t54_temporary_notice)
   compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(notice));compose.onNodeWithText(notice).assertIsDisplayed()
  }
  compose.onAllNodes(isRoot()).onLast().captureRoboImage("src/test/screenshots/t54_${page}_${mode}.png")
  Assert.assertEquals(0,schedules)
  if(page=="offer") {
   compose.onNode(isToggleable()).assertIsOff()
   compose.onNodeWithText("一小时后").performScrollTo().performClick()
   compose.onNodeWithText("确认提醒时间").assertIsNotEnabled()
   compose.onNode(isToggleable()).performScrollTo().performClick()
   compose.onNodeWithText("确认提醒时间").performScrollTo().performClick()
   Assert.assertEquals(1,schedules)
  }
 }
 companion object {@JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}") fun cases()=listOf("offer","question","denied","temporary").flatMap {p->listOf("light","dark","calm","font260").map {arrayOf(p,it)}}}
}
