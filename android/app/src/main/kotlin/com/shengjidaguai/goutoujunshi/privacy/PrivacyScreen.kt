package com.shengjidaguai.goutoujunshi.privacy

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.designsystem.*

@Composable internal fun PrivacyScreen(back:()->Unit,onClear:()->Unit) {
    val context=LocalContext.current
    var enabled by remember {mutableStateOf(PrivacyRuntime.enabled)}
    var delay by remember {mutableLongStateOf(PrivacyRuntime.delayMillis)}
    var alias by remember {mutableStateOf<Int?>(null)}
    var clear by remember {mutableStateOf(false)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) {enabled=PrivacyRuntime.enabled;delay=PrivacyRuntime.delayMillis}
        }
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    PrivacyContent(enabled,delay,back,{
        if(enabled) {if(PrivacyRuntime.setEnabled(false)) enabled=false}
        else context.startActivity(Intent(context,com.shengjidaguai.goutoujunshi.floating.FloatingCounselActivity::class.java).setAction("privacy-enable"))
    },{PrivacyRuntime.setDelay(it);delay=it},{alias=it},{clear=true})
    alias?.let {index -> AliasNotice({LauncherAliases.select(context,index);alias=null},{alias=null})}
    if(clear) ClearNotice({clear=false;onClear()},{clear=false})
}

@Composable internal fun AliasNotice(confirm:()->Unit,dismiss:()->Unit) {
    JunshiConfirmationDialog(stringResource(R.string.t55_icon_title),stringResource(R.string.t55_icon_notice),stringResource(R.string.t55_switch),stringResource(R.string.close),confirm,dismiss)
}

@Composable internal fun ClearNotice(confirm:()->Unit,dismiss:()->Unit) {
    JunshiConfirmationDialog(stringResource(R.string.clear_all),stringResource(R.string.clear_all_consequence),stringResource(R.string.confirm_clear),stringResource(R.string.close),confirm,dismiss)
}

@Composable internal fun PrivacyContent(enabled:Boolean,delay:Long,back:()->Unit,toggle:()->Unit,onDelay:(Long)->Unit,onAlias:(Int)->Unit,onClear:()->Unit={}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JunshiTheme.spacing.l),verticalArrangement=Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        JunshiTopBar(stringResource(R.string.t55_title))
        BodyText(stringResource(R.string.t55_secure_notice))
        BodyText(stringResource(R.string.t55_system_trip_notice))
        SecondaryButton(stringResource(if(enabled) R.string.t55_disable else R.string.t55_enable),toggle)
        BodyText(stringResource(R.string.t55_delay))
        listOf(0L to R.string.t55_immediate,60_000L to R.string.t55_one_minute,300_000L to R.string.t55_five_minutes).forEach {(value,label) ->
            SecondaryButton(stringResource(if(delay==value) R.string.t55_selected else R.string.t55_choice,stringResource(label)),{onDelay(value)})
        }
        BodyText(stringResource(R.string.t55_icon_notice))
        listOf(R.string.app_name,R.string.t55_notes,R.string.t55_calculator).forEachIndexed {index,label ->SecondaryButton(stringResource(label),{onAlias(index)})}
        SecondaryButton(stringResource(R.string.clear_all),onClear)
        SecondaryButton(stringResource(R.string.close),back)
    }
}
