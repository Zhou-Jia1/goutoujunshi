package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.designsystem.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T32CaptureScreenshotTest(private val mode: String) {
    @get:Rule val compose=createComposeRule()
    @Test fun permissionExplanation() {
        var selected=false
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",fontScaleLevel=if(mode=="font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) { FloatingControls(false,true,R.string.floating_off,{},{},{selected=true},{},{}) }
            }
        } }
        compose.onNodeWithText("截屏分析").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("src/test/screenshots/t32_permission_${mode}.png")
        compose.onNodeWithText("选择联系人").performScrollTo().performClick()
        Assert.assertTrue(selected)
        compose.onNodeWithText("开启悬浮军师").performScrollTo().assertIsDisplayed()
    }
    @Test fun emptyFrameOffersRealRecaptureWithoutUriImageControls() {
        var retries=0
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",fontScaleLevel=if(mode=="font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) {
                    com.shengjidaguai.goutoujunshi.ui.ImportContent(
                        com.shengjidaguai.goutoujunshi.importing.ImportPreview(source=com.shengjidaguai.goutoujunshi.domain.ImportSource.SCREENSHOT,
                            captureSession=8,recognized=true,error="OCR_EMPTY",imageIds=listOf("ram-frame")),
                        emptyList(),retry={ retries++ })
                }
            }
        } }
        compose.onNodeWithText(app.getString(R.string.ocr_up)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.ocr_down)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.capture_retry)).performScrollTo().performClick()
        Assert.assertEquals(1,retries)
        compose.onRoot().captureRoboImage("src/test/screenshots/t32_empty_frame_${mode}.png")
    }
    companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}") fun modes()=listOf(arrayOf("light"),arrayOf("dark"),arrayOf("font260")) }
}
