package com.shengjidaguai.goutoujunshi.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.RelationGroup
import kotlinx.serialization.json.*

@Composable fun StyleScreen(vm:CounselViewModel,back:()->Unit) {
    val page by vm.stylePage.collectAsStateWithLifecycle()
    val counsel by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {vm.openStyle();onDispose {vm.closeStyle()}}
    StyleContent(page,counsel,back,vm::editStyle,vm::deleteStyle,vm::resetStyle,vm::saveStyleQuiz,vm::previewStyle,{if(counsel.error?.retryable==true) vm.retry() else vm.analyze()},vm::editStyleSample,{terms,aliases->vm.saveRedactionProfile(counsel.conversation?.id,terms,aliases)})
}

@Composable fun StyleContent(page:StyleUiState,counsel:CounselState,back:()->Unit,
    edit:(RelationGroup,String,String)->Unit,delete:(String,String)->Unit,reset:(RelationGroup,String)->Unit,
    saveQuiz:(List<String>,String)->Unit,preview:(RelationGroup,String)->Unit,send:()->Unit,editSample:(String,String,String)->Unit={_,_,_->},saveRules:(List<String>,List<RedactionAlias>)->Unit={_,_->}) {
    val context=LocalContext.current
    val quiz=remember {Json.parseToJsonElement(context.assets.open("style-quiz.json").bufferedReader().use {it.readText()}).jsonObject.getValue("groups").jsonArray}
    var rulesOpen by remember(counsel.conversation?.id,page.receipt) {mutableStateOf(false)}
    var showQuiz by remember {mutableStateOf(false)}
    var choices by remember {mutableStateOf<Map<Int,String>>(emptyMap())}
    var editing by remember(page.receipt) {mutableStateOf<RelationGroup?>(null)}
    var description by remember(page.receipt) {mutableStateOf("")}
    var sampleEditor by remember(page.receipt) {mutableStateOf<String?>(null)}
    var sampleText by remember(page.receipt) {mutableStateOf("")}
    if(!counsel.lockState.canDisplaySensitiveContent) return
    if(rulesOpen) {
        androidx.activity.compose.BackHandler {rulesOpen=false}
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JunshiTheme.spacing.l),verticalArrangement=Arrangement.spacedBy(JunshiTheme.spacing.m)) {
            JunshiTopBar(stringResource(R.string.redaction_rules))
            SecondaryButton(stringResource(R.string.close),{rulesOpen=false})
            RedactionRulesContent(counsel.redactionProfile,counsel.currentContact!=null,counsel.redactionError,saveRules)
        }
        return
    }
    val snapshot=page.snapshot
    val receipt=page.receipt
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(JunshiTheme.spacing.l),verticalArrangement=Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item {JunshiTopBar(stringResource(R.string.style_title));SecondaryButton(stringResource(R.string.close),back)}
        item {BodyText(stringResource(R.string.style_disclosure))}
        if(page.loading) item {BodyText(stringResource(R.string.memory_loading))}
        if(page.modelRequired) item {BodyText(stringResource(R.string.style_requires_model));SecondaryButton(stringResource(R.string.style_configure_model),back)}
        if(page.failed || counsel.error!=null) item {BodyText(stringResource(R.string.memory_failed))}
        if(counsel.streaming && counsel.currentRequest?.purpose==OutboundPurpose.STYLE_DESCRIPTION) item {BodyText(stringResource(R.string.style_generating))}
        if(snapshot!=null && receipt!=null) {
            item {SecondaryButton(stringResource(R.string.style_quiz),{showQuiz=!showQuiz})}
            if(showQuiz) {
                quiz.forEachIndexed {index,element ->
                    val group=element.jsonObject
                    item {BodyText(group.getValue("prompt").jsonPrimitive.content)}
                    group.getValue("options").jsonArray.forEach {option->
                        val text=option.jsonPrimitive.content
                        item {StyleQuizOption(text,choices[index]==text,{choices=choices+(index to text)})}
                    }
                }
                if(choices.size==5) item {PrimaryButton(stringResource(R.string.style_quiz_finish),{saveQuiz((0..4).map {choices.getValue(it)},receipt);showQuiz=false;choices=emptyMap()})}
            }
            if(page.temporaryQuiz.isNotEmpty()) item {BodyText(stringResource(R.string.style_quiz_ram));BodyText(stringResource(R.string.style_features,page.temporaryQuiz.size,StyleFeatureExtractor.extract(page.temporaryQuiz).particles.keys.joinToString("、")));page.temporaryQuiz.forEach {BodyText(it)}}
            if(snapshot.consent.paused) item {BodyText(stringResource(R.string.style_paused_readonly))}
            val editable=snapshot.consent.enabled && !snapshot.consent.paused
            snapshot.groups.values.forEach {group->
                item {BodyText(stringResource(when(group.group) {RelationGroup.WORK->R.string.style_work;RelationGroup.INTIMATE->R.string.style_intimate;RelationGroup.FAMILY->R.string.style_family;RelationGroup.SOCIAL->R.string.style_social;RelationGroup.MEDIATION->R.string.style_mediation;RelationGroup.DEFAULT->R.string.style_default}))}
                item {
                    BodyText(group.profile?.description?.takeIf {it.isNotBlank()} ?: stringResource(R.string.style_no_description))
                    BodyText(stringResource(R.string.style_features,group.samples.size,group.profile?.features?.particles?.keys?.joinToString("、").orEmpty()))
                }
                if(group.samples.isNotEmpty()) {
                    if(editable) item {SecondaryButton(stringResource(R.string.style_edit),{editing=group.group;description=group.profile?.description.orEmpty()})}
                    if(editable && editing==group.group) item {
                        BodyText(stringResource(R.string.style_description_label))
                        FormTextField(description,{description=it},stringResource(R.string.style_description_field))
                        if(description.codePointCount(0,description.length)<=200) PrimaryButton(stringResource(R.string.memory_save),{edit(group.group,description,receipt);editing=null})
                    }
                    if(editable && group.samples.size>=15 && group.profile?.description.isNullOrBlank() && group.profile?.let {it.generatedFingerprint.isNotBlank() && it.generatedFingerprint==it.sourceFingerprint}!=true)
                        item {SecondaryButton(stringResource(R.string.style_generate_preview),{preview(group.group,receipt)})}
                    item {SecondaryButton(stringResource(R.string.style_reset),{reset(group.group,receipt)})}
                    group.samples.forEach {sample -> item {
                        BodyText(sample.effectiveText)
                        if(sample.userEdit!=null) {
                            BodyText(stringResource(R.string.style_user_edited))
                            CollapsibleSection(stringResource(R.string.style_original_sample),null) {BodyText(sample.text)}
                        }
                        if(editable) SecondaryButton(stringResource(R.string.style_edit_sample),{sampleEditor=sample.id;sampleText=sample.effectiveText})
                        if(editable && sampleEditor==sample.id) {
                            BodyText(stringResource(R.string.style_sample_edit_label))
                            FormTextField(sampleText,{sampleText=it},stringResource(R.string.style_sample_field))
                            if(sampleText.isNotBlank() && sampleText.codePointCount(0,sampleText.length)<=4000)
                                PrimaryButton(stringResource(R.string.style_save_sample),{editSample(sample.id,sampleText,receipt);sampleEditor=null})
                        }
                        BodyText(stringResource(R.string.style_source,stringResource(when(sample.origin) {StyleOrigin.IMPORT->R.string.style_source_import;StyleOrigin.QUIZ->R.string.style_source_quiz;StyleOrigin.EDITED_REPLY->R.string.style_source_edited}),snapshot.aliases[sample.sourceContactId] ?: stringResource(R.string.style_source_self)))
                        SecondaryButton(stringResource(R.string.style_delete_sample),{delete(sample.id,receipt)})
                    }}
                }
            }
            if(counsel.preview!=null && counsel.currentRequest?.purpose==OutboundPurpose.STYLE_DESCRIPTION && !counsel.streaming) item {
                OutboundPreviewContent(counsel,{rulesOpen=true},stringResource(R.string.style_outbound_scope))
                PrimaryButton(stringResource(R.string.style_send),send)
            }
            if(page.previewGroup!=null && counsel.preview==null && !counsel.streaming && editable) item {
                SecondaryButton(stringResource(R.string.style_repreview),{preview(page.previewGroup,receipt)})
            }
        }
    }
}
