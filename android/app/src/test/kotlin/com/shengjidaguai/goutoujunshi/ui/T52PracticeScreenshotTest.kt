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
class T52PracticeScreenshotTest(private val page:String,private val mode:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun practiceStages() {
        val round=PracticeRound("你打算怎么安排这件事？","我想先整理方案。","你先说明了安排，可以再补一个确认请求。","我明天下午发方案，请你帮我确认时间。")
        val view=PracticeView("synthetic-practice",0,turns=if(page=="opening") emptyList() else listOf(round,PracticeRound("那我什么时候给你答复？")),summary=if(page=="summary") Tip("synthetic-tip","说清下一步","把安排和确认请求连起来。","明天下午请你确认时间。") else null,summaryReceipt=if(page=="summary") "synthetic-receipt" else null)
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260")2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260")FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) {PracticeContent(CounselState(loading=false,conversation=Conversation("synthetic-conversation"),practice=view),CounselUiActions())}
            }
        }}
        compose.onRoot().captureRoboImage("src/test/screenshots/t52_${page}_${mode}.png")
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}")
        fun cases()=listOf("opening","round","summary").flatMap {p->listOf("light","dark","calm").map {arrayOf(p,it)}}+listOf(arrayOf("opening","font260"))
    }
}
