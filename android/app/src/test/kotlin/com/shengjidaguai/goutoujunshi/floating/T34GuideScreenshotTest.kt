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
import com.shengjidaguai.goutoujunshi.designsystem.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T34GuideScreenshotTest(private val mode:String) {
    @get:Rule val compose=createComposeRule()
    private fun render(page:GuidePage,brand:GuideBrand=GuideBrand.XIAOMI) {
        var continued=false
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",fontScaleLevel=if(mode=="font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) { FloatingPermissionGuide(page,brand,{continued=true},{},{}) }
            }
        } }
        compose.onRoot().captureRoboImage("src/test/screenshots/t34_${page.name.lowercase()}_${brand.name.lowercase()}_${mode}.png")
        val primary=if(page==GuidePage.BACKGROUND) "跳过，继续开启" else "了解并继续"
        compose.onNodeWithText(primary).performScrollTo().assertIsDisplayed().performClick()
        Assert.assertTrue(continued)
        compose.onRoot().captureRoboImage("src/test/screenshots/t34_${page.name.lowercase()}_${brand.name.lowercase()}_${mode}_actions.png")
    }
    @Test fun intro()=render(GuidePage.INTRO)
    @Test fun manufacturer()=render(GuidePage.BACKGROUND)
    @Test fun unknown()=render(GuidePage.BACKGROUND,GuideBrand.UNKNOWN)
    @Test fun notificationDenied()=render(GuidePage.NOTIFICATION_DENIED)
    companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}") fun modes()=listOf(arrayOf("light"),arrayOf("dark"),arrayOf("font260")) }
}
