package com.shengjidaguai.goutoujunshi.ui
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.res.stringResource
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.FollowupOffer
import com.shengjidaguai.goutoujunshi.designsystem.*
import java.time.*
import java.time.format.DateTimeFormatter
@Composable fun FollowupPrompt(offer:FollowupOffer,onSchedule:(Long)->Unit) {
    val optinLabel=stringResource(R.string.t54_optin)
    var checked by remember(offer) {mutableStateOf(false)}
    var due by remember(offer) {mutableStateOf<Long?>(null)}
    JunshiColumn {
        BodyText(stringResource(R.string.t54_offer))
        Row {Checkbox(checked,{checked=it},Modifier.semantics {contentDescription=optinLabel});BodyText(stringResource(R.string.t54_optin))}
        SecondaryButton(stringResource(R.string.t54_hour),{due=System.currentTimeMillis()+3_600_000})
        SecondaryButton(stringResource(R.string.t54_tomorrow),{due=LocalDate.now().plusDays(1).atTime(8,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()})
        due?.let { selected ->
            val actual=com.shengjidaguai.goutoujunshi.followup.FollowupTime.eligible(selected,System.currentTimeMillis(),ZoneId.systemDefault())
            BodyText(stringResource(R.string.t54_time,Instant.ofEpochMilli(actual).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm z"))))
            PrimaryButton(stringResource(R.string.t54_schedule),{onSchedule(selected)},enabled=checked)
        }
        BodyText(stringResource(R.string.t54_delay),secondary=true)
    }
}
@Composable fun FollowupQuestion(onUpload:()->Unit,onNoReply:()->Unit,onChanged:()->Unit) {
    JunshiColumn {
        BodyText(stringResource(R.string.t54_question))
        SecondaryButton(stringResource(R.string.t54_upload),onUpload)
        SecondaryButton(stringResource(R.string.t54_no_reply),onNoReply)
        SecondaryButton(stringResource(R.string.t54_changed),onChanged)
    }
}

@Composable fun FollowupPrivacySetting() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val preferences=remember(context) {context.getSharedPreferences("followup-settings",0)}
    val aliasLabel=stringResource(R.string.t54_alias_setting)
    var enabled by remember {mutableStateOf(preferences.getBoolean("alias",false))}
    Row {Checkbox(enabled,{enabled=it;com.shengjidaguai.goutoujunshi.followup.FollowupScheduler.setAliasVisibility(context,it)},Modifier.semantics {contentDescription=aliasLabel});BodyText(stringResource(R.string.t54_alias_setting))}
}

/** The same explicit-consent permission launcher is used by the App and device tests. */
@Composable fun rememberFollowupPermission(onResult:(FollowupOffer,Long,Boolean)->Unit):(FollowupOffer,Long)->Unit {
    var pending by remember {mutableStateOf<Pair<FollowupOffer,Long>?>(null)}
    var denied by remember {mutableStateOf(false)}
    val result by rememberUpdatedState(onResult)
    val context=androidx.compose.ui.platform.LocalContext.current
    val launcher=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {granted ->
        val selected=pending;pending=null;denied=!granted
        selected?.let {result(it.first,it.second,granted)}
    }
    if(denied) FollowupDenied {denied=false}
    return {offer,due ->
        if(android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pending=offer to due;launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else result(offer,due,true)
    }
}

@Composable fun FollowupDenied(onDismiss:()->Unit) {
    JunshiNoticeDialog(stringResource(R.string.t54_denied),stringResource(R.string.close),onDismiss)
}
