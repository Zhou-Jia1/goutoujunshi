package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
class JunshiThemeTest(private val dark: Boolean, private val calm: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test fun actualTextBackgroundPairsMeetAA() {
        val ratios = linkedMapOf<String, Double>()
        var actualCalm = false
        compose.setContent {
            JunshiTheme(darkTheme = dark, calm = calm) {
                val colors = MaterialTheme.colorScheme
                val extended = JunshiTheme.extendedColors
                actualCalm = JunshiTheme.calm
                ratios["body/surface"] = contrast(colors.onSurface, colors.surface)
                ratios["body/background"] = contrast(colors.onBackground, colors.background)
                ratios["secondary/surfaceVariant"] = contrast(colors.onSurfaceVariant, colors.surfaceVariant)
                ratios["primaryButton"] = contrast(colors.onPrimary, colors.primary)
                ratios["reply"] = contrast(colors.onPrimaryContainer, colors.primaryContainer)
                ratios["fact"] = contrast(extended.factInk, extended.factInk.copy(alpha = .12f).compositeOver(colors.surface))
                ratios["guess"] = contrast(extended.guessInk, colors.surface)
                ratios["unknown"] = contrast(extended.unknownInk, colors.surface)
                ratios["safety"] = contrast(extended.onSafetyContainer, extended.safetyContainer)
                ratios["demo"] = contrast(extended.demoBadge, colors.surface)
            }
        }
        compose.runOnIdle {
            assertEquals(calm, actualCalm)
            assertEquals(10, ratios.size)
            ratios.forEach { (pair, ratio) ->
                println("WCAG dark=$dark calm=$calm $pair = %.3f:1".format(java.util.Locale.ROOT, ratio))
                assertTrue("$pair dark=$dark calm=$calm ratio=$ratio must be >=4.5", ratio >= 4.5)
            }
        }
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0},calm={1}")
        fun cases() = listOf(arrayOf(false, false), arrayOf(true, false), arrayOf(false, true), arrayOf(true, true))

        private fun contrast(foreground: Color, background: Color): Double {
            fun luminance(color: Color): Double {
                fun channel(value: Float): Double = if (value <= .04045f) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
                return .2126 * channel(color.red) + .7152 * channel(color.green) + .0722 * channel(color.blue)
            }
            val a = luminance(foreground)
            val b = luminance(background)
            return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
        }
    }
}
