package com.shengjidaguai.goutoujunshi.designsystem

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos

data class JunshiMotion(val quick: Int, val base: Int, val slow: Int, val breathing: Int, val stream: Int) {
    companion object {
        fun resolve(calm: Boolean, removeAnimations: Boolean): JunshiMotion {
            val factor = if (removeAnimations) 0f else if (calm) 1.5f else 1f
            return JunshiMotion((150 * factor).toInt(), (250 * factor).toInt(), (400 * factor).toInt(),
                (4000 * factor).toInt(), (120 * factor).toInt())
        }
    }
}
object JunshiEasing {
    val standard = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val emphasizedDecelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
    val emphasized = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val sine = Easing { ((1 - cos(Math.PI * it)) / 2).toFloat() }
}
internal val LocalMotion = staticCompositionLocalOf { JunshiMotion.resolve(false, false) }

@Composable internal fun rememberJunshiMotion(calm: Boolean): JunshiMotion {
    val resolver = LocalContext.current.contentResolver
    fun removed() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var removeAnimations by remember(resolver) { mutableStateOf(removed()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { removeAnimations = removed() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return JunshiMotion.resolve(calm, removeAnimations)
}
