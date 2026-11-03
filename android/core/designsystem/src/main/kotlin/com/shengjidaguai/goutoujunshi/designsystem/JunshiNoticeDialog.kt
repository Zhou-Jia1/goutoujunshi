package com.shengjidaguai.goutoujunshi.designsystem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.AlertDialog
/** Dialog windows supply their own platform density; carry the caller's effective font scale inside. */
@Composable fun JunshiNoticeDialog(body:String,closeText:String,onClose:()->Unit) {
    val density=LocalDensity.current
    AlertDialog(onDismissRequest=onClose,
        text={CompositionLocalProvider(LocalDensity provides density) {BodyText(body)}},
        confirmButton={CompositionLocalProvider(LocalDensity provides density) {SecondaryButton(closeText,onClose)}})
}
