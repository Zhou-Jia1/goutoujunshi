package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SensitiveTextFieldScreenshotTest(private val mode: String) {
    @get:Rule val compose = createComposeRule()
    @Test fun captureSecureInput() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (mode == "font260") 2f else 1f)) {
                PreviewSensitiveTextField(dark = mode == "dark", calm = mode == "calm",
                    fontScaleLevel = if (mode == "font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard)
            }
        }
        compose.onAllNodes(hasText("synthetic-preview-key"), useUnmergedTree = true).assertCountEquals(0)
        compose.onRoot().captureRoboImage("src/test/screenshots/SensitiveTextField_${mode}.png")
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf("light", "dark", "calm", "font260").map { arrayOf(it) }
    }
}
