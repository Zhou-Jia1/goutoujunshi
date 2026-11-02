package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.designsystem.FontScaleLevel
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
class T41StyleScreenshotTest(private val page:String,private val mode:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun stylePages() {
        val samples=(0..15).map {StyleSample(id="synthetic-$it",relationGroup=RelationGroup.SOCIAL,text="嗯好呀，周末我们再聊～",origin=StyleOrigin.IMPORT,sourceContactId="a",sourceImportId="source",sourceMessageId="line$it")}
        val snapshot=StyleManagementSnapshot(ConsentState(enabled=true,paused=page=="paused"),WriteStamp(1,1,1),mapOf(RelationGroup.SOCIAL to StyleGroupSnapshot(RelationGroup.SOCIAL,samples,StyleProfile(description="你喜欢自然的短句，常用语气词和波浪号。",features=StyleFeatureExtractor.extract(samples.map {it.text})))))
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {StyleContent(StyleUiState(snapshot,"synthetic-receipt"),CounselState(loading=false),{}, {_,_,_->},{_,_->},{_,_->},{_,_->},{_,_->},{})}
            }
        }}
        if(page=="quiz") {compose.onNode(hasScrollAction()).performScrollToNode(hasText("哪句更像你"));compose.onNodeWithText("哪句更像你").performClick();compose.onNode(hasScrollAction()).performScrollToNode(hasText("朋友约你周末见面，你更常说哪句？"))}
        else if(page=="paused") {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("删除这条样本"));compose.onAllNodesWithText("删除这条样本")[0].assertIsDisplayed()
            compose.onAllNodesWithText("编辑描述").assertCountEquals(0);compose.onAllNodesWithText("编辑这条示例").assertCountEquals(0)
        }
        else if(page=="sample-edit") {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("编辑这条示例"));compose.onAllNodesWithText("编辑这条示例")[0].performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("合成用户改写示例，嗯好呀～")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("保存风格示例"));compose.onNodeWithText("保存风格示例").assertIsDisplayed()
        } else {compose.onNode(hasScrollAction()).performScrollToNode(hasText("编辑描述"));compose.onNodeWithText("编辑描述").assertIsDisplayed()}
        compose.onRoot().captureRoboImage("src/test/screenshots/t41_${page}_${mode}.png")
        if(page=="profile") {compose.onNode(hasScrollAction()).performScrollToNode(hasText("删除这条样本"));compose.onAllNodesWithText("删除这条样本")[0].assertIsDisplayed()}
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}")
        fun cases()=listOf("profile","quiz","sample-edit","paused").flatMap {page->listOf("light","dark","calm","font260").map {arrayOf(page,it)}}
    }
}
