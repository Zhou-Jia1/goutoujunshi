package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier

@Composable fun JunshiConfirmationDialog(title:String,body:String,confirm:String,dismiss:String,onConfirm:()->Unit,onDismiss:()->Unit) {
    val density=LocalDensity.current
    AlertDialog(onDismissRequest=onDismiss,
        title={CompositionLocalProvider(LocalDensity provides density) {BodyText(title)}},
        text={CompositionLocalProvider(LocalDensity provides density) {BodyText(body,Modifier.verticalScroll(rememberScrollState()))}},
        confirmButton={CompositionLocalProvider(LocalDensity provides density) {SecondaryButton(confirm,onConfirm)}},
        dismissButton={CompositionLocalProvider(LocalDensity provides density) {SecondaryButton(dismiss,onDismiss)}})
}
