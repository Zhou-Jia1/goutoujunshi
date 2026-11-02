package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import com.shengjidaguai.goutoujunshi.data.network.safeDestination
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.designsystem.FontScaleLevel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T41StyleR04ScreenshotTest(private val mode:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun styleOutboundDestinationAndRulesAreReachable() {
        val (page,state)=styleOutboundFixture()
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {StyleContent(page,state,{}, {_,_,_->},{_,_->},{_,_->},{_,_->},{_,_->},{})}
            }
        }}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("补充屏蔽词与昵称代号"));compose.onNodeWithText("补充屏蔽词与昵称代号").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(state.modelSettings.saved.connection().safeDestination()))
        compose.onNodeWithText(state.modelSettings.saved.connection().safeDestination()).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/t41_r04_preview_${mode}.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("发送给所选模型服务商"));compose.onNodeWithText("发送给所选模型服务商").assertIsDisplayed()
    }
    companion object {@JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}") fun cases()=listOf("light","dark","calm","font260").map {arrayOf(it)}}
}
