package com.shengjidaguai.goutoujunshi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*

@Composable fun PracticeContent(state:CounselState,actions:CounselUiActions) {
    val view=state.practice ?: return
    val origin=state.conversation?.id
    var rules by remember(view.id,view.revision) {mutableStateOf(false)}
    androidx.activity.compose.BackHandler {actions.exitPractice(origin,view.id)}
    LazyColumn(Modifier.fillMaxSize().testTag("practice-screen"),contentPadding=PaddingValues(JunshiTheme.spacing.l),verticalArrangement=Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item {JunshiTopBar(stringResource(R.string.contact_practice),{actions.exitPractice(origin,view.id)})}
        item {BodyText(stringResource(R.string.practice_privacy));if(state.demo) BodyText(stringResource(R.string.preview_demo_notice))}
        item {ChoiceField(stringResource(R.string.practice_difficulty),stringResource(practiceDifficultyLabel(view.difficulty)),PracticeDifficulty.entries.map {difficulty -> stringResource(practiceDifficultyLabel(difficulty)) to {actions.practiceDifficulty(origin,view.id,difficulty)} })}
        view.turns.forEachIndexed {index,turn -> item(key=index) {PracticeTurn(turn.counterpartLine,turn.userLine,turn.feedback,turn.betterVersion)}}
        if(view.summary!=null) item {
            BodyText(stringResource(R.string.practice_summary));BodyText(view.summary.name);BodyText(view.summary.explanation);BodyText(view.summary.example)
            if(view.saved) BodyText(stringResource(R.string.practice_saved))
            else PrimaryButton(stringResource(R.string.practice_save),{view.summaryReceipt?.let {actions.savePracticeSummary(origin,view.id,it)}})
        } else {
            if(view.turns.isNotEmpty()) item {FormTextField(view.draft,{actions.editPracticeInput(origin,view.id,it)},stringResource(R.string.practice_input))}
            if(!state.streaming) {
                if(view.turns.size<20) item {PrimaryButton(stringResource(if(view.turns.isEmpty()) R.string.practice_open else R.string.practice_next),{actions.previewPractice(origin,view.id,false)})}
                item {SecondaryButton(stringResource(R.string.practice_end),{actions.previewPractice(origin,view.id,true)})}
            } else item {BodyText(stringResource(R.string.practice_wait));SecondaryButton(stringResource(R.string.practice_exit),actions.stop)}
        }
        state.error?.let {error -> item {ErrorState(UiError(stringResource(errorLabel(error.code))),if(error.retryable) actions.retry else null,null)}}
        item {SecondaryButton(stringResource(R.string.practice_exit),{actions.exitPractice(origin,view.id)})}
    }
    val shownReceipt=state.practicePreviewReceipt
    if(state.preview!=null && !state.streaming && shownReceipt!=null) ContentDialog(stringResource(R.string.preview_request),stringResource(R.string.close),actions.stop,
        stringResource(if(state.demo) R.string.practice_demo_play else R.string.live_send) to {actions.sendPractice(origin,view.id,shownReceipt)}) {
        OutboundPreviewContent(state,{rules=true},stringResource(R.string.practice_scope))
    }
    if(rules) ContentDialog(stringResource(R.string.redaction_rules),stringResource(R.string.close),{rules=false}) {
        RedactionRulesContent(state.redactionProfile,state.currentContact!=null,state.redactionError) {terms,aliases ->
            if(origin!=null) actions.saveRedactionProfile(origin,terms,aliases)
            rules=false
        }
    }
}
fun practiceDifficultyLabel(value:PracticeDifficulty):Int=when(value) {
    PracticeDifficulty.COOPERATIVE->R.string.practice_cooperative
    PracticeDifficulty.NORMAL->R.string.practice_normal
    PracticeDifficulty.DIFFICULT->R.string.practice_difficult
}
