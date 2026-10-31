package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

enum class FontScaleLevel(val multiplier: Float) { Standard(1f), Large(1.15f), ExtraLarge(1.3f) }
data class JunshiSpacing(val xs: Dp = 4.dp, val s: Dp = 8.dp, val m: Dp = 12.dp, val l: Dp = 16.dp, val xl: Dp = 24.dp, val xxl: Dp = 32.dp)
data class JunshiExtendedColors(
    val brandMark: Color, val warmAccent: Color, val factInk: Color, val guessInk: Color, val unknownInk: Color,
    val safetyContainer: Color, val onSafetyContainer: Color, val calmBackground: Color, val demoBadge: Color,
    val sceneWork: Color, val sceneIntimate: Color, val sceneFamily: Color, val sceneSocial: Color, val sceneMediation: Color,
)
private val LocalSpacing = staticCompositionLocalOf { JunshiSpacing() }
private val LocalColors = staticCompositionLocalOf { extended(false) }
private val LocalCalm = staticCompositionLocalOf { false }
object JunshiTheme {
    val spacing: JunshiSpacing @Composable get() = LocalSpacing.current
    val extendedColors: JunshiExtendedColors @Composable get() = LocalColors.current
    val calm: Boolean @Composable get() = LocalCalm.current
    val motion: JunshiMotion @Composable get() = LocalMotion.current
}
private fun extended(dark: Boolean) = if (dark) JunshiExtendedColors(
    Color(0xFF1DBD66), Color(0xFFF0A27E), Color(0xFFE7E4DC), Color(0xFFB8C4FF), Color(0xFFB5B1A7),
    Color(0xFF16304A), Color(0xFFD4E6F7), Color(0xFF141816), Color(0xFFE3C46E),
    Color(0xFFA9B8C8), Color(0xFFE8A58C), Color(0xFFD8BE8A), Color(0xFF8CC3A5), Color(0xFFB8BAF0),
) else JunshiExtendedColors(
    Color(0xFF1DBD66), Color(0xFFE8875B), Color(0xFF1C1B19), Color(0xFF4F5D95), Color(0xFF68645B),
    Color(0xFFE3EEF8), Color(0xFF12324F), Color(0xFFEEF2F0), Color(0xFF8A6D1F),
    Color(0xFF5A6B7D), Color(0xFFB5654A), Color(0xFF8A6D3B), Color(0xFF3F7A5C), Color(0xFF5B5E9A),
)
private fun scheme(dark: Boolean, calm: Boolean): ColorScheme {
    val colors = extended(dark)
    return if (dark) darkColorScheme(
        primary = if (calm) Color(0xFF8CC3A5) else Color(0xFF5FD08F), onPrimary = Color(0xFF00391C),
        primaryContainer = Color(0xFF0B5530), onPrimaryContainer = Color(0xFFC8F0D7),
        secondary = Color(0xFFD3C4B0), secondaryContainer = Color(0xFF4A3F32), onSecondaryContainer = Color(0xFFF1E8DB),
        tertiary = Color(0xFFB8C4FF), tertiaryContainer = Color(0xFF36447A),
        background = if (calm) colors.calmBackground else Color(0xFF121412), surface = Color(0xFF1A1D1A),
        surfaceVariant = Color(0xFF262A26), onSurface = Color(0xFFE7E4DC), onBackground = Color(0xFFE7E4DC),
        onSurfaceVariant = Color(0xFFB5B1A7), outline = Color(0xFF3B403B), error = Color(0xFFF2B8B5),
    ) else lightColorScheme(
        primary = if (calm) Color(0xFF3F7A5C) else Color(0xFF0F7A43), onPrimary = Color.White,
        primaryContainer = Color(0xFFD6F2E1), onPrimaryContainer = Color(0xFF04301A),
        secondary = Color(0xFF6B5E4E), secondaryContainer = Color(0xFFF1E8DB), onSecondaryContainer = Color(0xFF251A0E),
        tertiary = Color(0xFF4F5D95), tertiaryContainer = Color(0xFFE2E6FA),
        background = if (calm) colors.calmBackground else Color(0xFFF7F6F2), surface = Color.White,
        surfaceVariant = Color(0xFFEFEDE6), onSurface = Color(0xFF1C1B19), onBackground = Color(0xFF1C1B19),
        onSurfaceVariant = Color(0xFF5E5B54), outline = Color(0xFFD9D5CB), error = Color(0xFFB3261E),
    )
}
private val JunshiTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun JunshiTheme(darkTheme: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val motion = rememberJunshiMotion(calm)
    CompositionLocalProvider(LocalColors provides extended(darkTheme), LocalSpacing provides JunshiSpacing(), LocalCalm provides calm,
        LocalMotion provides motion,
        LocalDensity provides Density(density.density, density.fontScale * fontScaleLevel.multiplier)) {
        MaterialTheme(colorScheme = scheme(darkTheme, calm), typography = JunshiTypography, content = content)
    }
}
