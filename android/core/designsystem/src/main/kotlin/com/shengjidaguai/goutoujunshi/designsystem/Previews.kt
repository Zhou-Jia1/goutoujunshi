package com.shengjidaguai.goutoujunshi.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewContactSwitcher(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ContactSwitcher(ContactChipModel(null,t),listOf(ContactChipModel(null,t)),{}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewModelStatusPill(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ModelStatusPill(ModelStatus.DEMO,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewUploadHeroButton(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { UploadHeroButton({}, {}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewQuickSceneChip(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { QuickSceneChip(t,RelationGroup.WORK,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewImportPreviewBubble(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ImportPreviewBubble(ChatLineModel("preview",t,t,true),{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewSpeakerConfirmBar(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { SpeakerConfirmBar(Side.RIGHT,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewRedactionNotice(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { RedactionNotice(3,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewUserMessageBubble(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { UserMessageBubble(t,emptyList()) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewImportCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ImportCard(ImportSummaryModel(t,3),{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewCounselAnswerCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { CounselAnswerCard(AnswerUiState(verdict=t,reply=listOf(t),demo=true,calm=calm,empathy=t,next=t),AnswerActions()) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewSceneChips(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { SceneChips(RelationGroup.WORK,t,t,Formality.HIGH,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewVerdictLine(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { VerdictLine(t) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewReplySuggestionCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ReplySuggestionCard(listOf(t),false,false,null,{}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewStyleBalanceSlider(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { StyleBalanceSlider(.5f,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewAnswerActionBar(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { AnswerActionBar({}, {}, {}, {}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewAlternativesRow(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { AlternativesRow(listOf(AltReplyModel(t,t)),{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewCollapsibleSection(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { CollapsibleSection(t,null,true) { Text(t) } }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewEvidenceChip(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { EvidenceChip(EvidenceKind.GUESS,t,t,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewTipCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { TipCard(t,t,t,false,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewHabitNotice(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { HabitNotice(t,{}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewFollowUpQuestionChips(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { FollowUpQuestionChips(listOf(t),{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewThinkingIndicator(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ThinkingIndicator(ThinkingStage.SORTING,false) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewMemoryChangeNotice(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { MemoryChangeNotice(t,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewSafetyCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { SafetyCard({}, {}, {}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewCalmBanner(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { CalmBanner({}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewBreathingPrompt(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { BreathingPrompt(onContinue={},onSaveDraft={}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewEmotionSlider(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { EmotionSlider(8,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewComposer(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { Composer(t,{},emptyList(),null,{},false,{}, {}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewContactCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ContactCard(ContactCardModel("preview",t,t),{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewTimelineItem(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { TimelineItem(TimelineItemModel("preview",t),{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewMemoryEntryRow(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { MemoryEntryRow(MemoryRowModel("preview",t,t),{}, {}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewStyleProfileCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { StyleProfileCard(RelationGroup.WORK,t,listOf(t),15,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewStyleQuizOption(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { StyleQuizOption(t,true,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewTipBookItem(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { TipBookItem(TipBookItemModel(t,t),{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewPracticeTurn(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { PracticeTurn(t,t,t,t) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewFloatingBall(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { FloatingBall(FloatingBallState.IDLE,Side.RIGHT) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewFloatingAnswerPanel(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { FloatingAnswerPanel(AnswerUiState(reply=listOf(t),calm=calm,empathy=t,next=t),t,{}, {}, {}, {}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewFloatingCaptureError(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { FloatingCaptureError(CaptureErrorKind.NO_TEXT,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewPermissionStepCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { PermissionStepCard(1,4,t,t,Illustration.SHIELD,t to {},null) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewJunshiTopBar(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { JunshiTopBar(t) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewPrimaryButton(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { PrimaryButton(t,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewSecondaryButton(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { SecondaryButton(t,{}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewConsentCard(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ConsentCard(t,t,t,t,{}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewEmptyState(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { EmptyState(Illustration.LISTENING,t,t) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewErrorState(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ErrorState(UiError(t),{}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewDangerConfirmDialog(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { DangerConfirmDialog(t,t,t,{}, {}) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewDemoBadge(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { DemoBadge() }
    }
}


@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewBodyText(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { BodyText(t) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewFormTextField(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { FormTextField(t, {}, t) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewChoiceField(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ChoiceField(t, t, listOf(t to {})) }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
internal fun PreviewContentDialog(dark: Boolean = isSystemInDarkTheme(), calm: Boolean = false, fontScaleLevel: FontScaleLevel = FontScaleLevel.Standard) {
    JunshiTheme(darkTheme = dark, calm = calm, fontScaleLevel = fontScaleLevel) {
        val t = stringResource(R.string.ds_empty_title)
        Surface { ContentDialog(t, t, {}) { BodyText(t) } }
    }
}
