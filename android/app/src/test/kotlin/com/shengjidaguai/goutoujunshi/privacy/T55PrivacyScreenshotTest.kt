package com.shengjidaguai.goutoujunshi.privacy
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
import com.shengjidaguai.goutoujunshi.designsystem.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.*
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T55PrivacyScreenshotTest(private val page:String,private val mode:String) {
 @get:Rule val compose=createComposeRule()
 @Test fun settings() {
  compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260")2f else 1f)) {
   JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260")FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
    Surface(Modifier.fillMaxSize()) {when(page) {
        "alias" -> AliasNotice({},{})
        "clear" -> ClearNotice({},{})
        "welcome" -> PrivacyWelcome {}
        "lock" -> com.shengjidaguai.goutoujunshi.floating.FloatingLockGate({},{},onBiometric={})
        else -> PrivacyContent(true,60_000,{},{},{},{})
    }}
   }
  }}
  compose.onAllNodes(isRoot()).onLast().captureRoboImage("src/test/screenshots/t55_${if(page=="settings") "privacy" else page}_${mode}.png")
  if(page=="settings") compose.onNodeWithText("计算记录").performScrollTo().assertIsDisplayed()
  if(page=="welcome") compose.onNodeWithText("重新开始").performScrollTo().assertIsDisplayed()
  if(page=="lock") compose.onNodeWithText("使用生物识别或设备凭据").performScrollTo().assertIsDisplayed()
  if(page=="alias") {
    val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
    compose.onNodeWithText("切换").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {it(layouts)}
    Assert.assertEquals(if(mode=="font260")2.6f else 1f,layouts.single().layoutInput.density.fontScale,.001f)
  }
  if(page=="clear") {
    compose.onNodeWithText("清除全部资料").assertIsDisplayed()
    compose.onNodeWithText("关闭").assertIsDisplayed()
  }
 }
 companion object {@JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}") fun cases()=listOf("settings","alias","lock","welcome","clear").flatMap {page->listOf("light","dark","calm","font260").map {arrayOf(page,it)}}}
}
