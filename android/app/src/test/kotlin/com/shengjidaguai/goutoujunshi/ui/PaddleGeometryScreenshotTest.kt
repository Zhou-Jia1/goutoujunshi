package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide
import com.shengjidaguai.goutoujunshi.importing.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaddleGeometryScreenshotTest(private val page:String,private val mode:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun ocrPreview() {
        val preview=ImportPreview(id="synthetic-ocr",source=ImportSource.SCREENSHOT,
            images=if(page=="order" || page=="side") listOf(Uri.parse("content://synthetic/1"),Uri.parse("content://synthetic/2")) else emptyList(),
            imageIds=if(page=="order" || page=="side") listOf("synthetic-1","synthetic-2") else emptyList(),
            recognized=page!="order", rightsAccepted=true,
            rows=if(page=="geometry") listOf(ImportRow(text="合成左侧消息",side=OcrSide.LEFT,id="left"),ImportRow(text="合成右侧消息",side=OcrSide.RIGHT,id="right")) else if(page=="review") listOf(ImportRow("合成小林","合成测试：明天一起讨论方案",speaker=Speaker.THEM,id="row-1",side=OcrSide.LEFT,ocrConfidence=.55f)) else emptyList())
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",fontScaleLevel=if(mode=="font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface { ImportContent(preview,emptyList()) }
            }
        } }
        if(page=="side") compose.onNodeWithTag("import-preview").performScrollToNode(hasText("哪边是我？请确认一次，本次导入会沿用。居中文字和群聊昵称请逐行核对。"))
        if(page=="geometry") compose.onNodeWithTag("import-preview").performScrollToIndex(2)
        if(page=="review") compose.onNodeWithTag("import-preview").performScrollToNode(hasText("这一行识别把握较低，请点开核对文字和说话人。"))
        compose.onRoot().captureRoboImage("src/test/screenshots/t22_${page}_${mode}.png")
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}")
        fun cases()=listOf("geometry").flatMap { page -> listOf("light","dark","font260").map { arrayOf(page,it) } }
    }
}
