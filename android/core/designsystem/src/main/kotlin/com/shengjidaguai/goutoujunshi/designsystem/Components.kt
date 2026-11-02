@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.material3.AssistChip as MaterialAssistChip
import androidx.compose.material3.FilterChip as MaterialFilterChip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

object JunshiLayout {
    val touch = 48.dp
    val contentWidth = 640.dp
    val ball = 52.dp
    val smallRadius = 8.dp
    val mediumRadius = 14.dp
    val largeRadius = 20.dp
    val sheetRadius = 28.dp
    val illustration = 96.dp
}

// Enforce actual layout bounds as well as Material's expanded semantic touch target.
@Composable private fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    MaterialTextButton(onClick, modifier.sizeIn(minWidth = JunshiLayout.touch, minHeight = JunshiLayout.touch), enabled = enabled, content = content)
}
@Composable private fun AssistChip(onClick: () -> Unit, label: @Composable () -> Unit) {
    MaterialAssistChip(onClick, label, modifier = Modifier.sizeIn(minWidth = JunshiLayout.touch, minHeight = JunshiLayout.touch))
}
@Composable private fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    MaterialFilterChip(selected, onClick, label, modifier = Modifier.sizeIn(minWidth = JunshiLayout.touch, minHeight = JunshiLayout.touch))
}

@Composable
fun JunshiColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(JunshiTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m), content = content)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false, modifier: Modifier = Modifier) {
    Button(onClick, modifier.heightIn(min = JunshiLayout.touch), enabled = enabled && !loading, shape = RoundedCornerShape(JunshiLayout.mediumRadius)) {
        Text(if (loading) stringResource(R.string.ds_loading) else text)
    }
}

@Composable fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, modifier.heightIn(min = JunshiLayout.touch), enabled = enabled) { Text(text) }
}

@Composable
fun JunshiTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        val backLabel = stringResource(R.string.ds_back)
        onBack?.let { TextButton(it, Modifier.sizeIn(minWidth = JunshiLayout.touch, minHeight = JunshiLayout.touch).clearAndSetSemantics { contentDescription = backLabel; onClick { it(); true } }) { Text(stringResource(R.string.ds_symbol_back)) } }
    }, actions = actions)
}

@Composable
fun ContactSwitcher(current: ContactChipModel, options: List<ContactChipModel>, onSelect: (String?) -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton({ open = true }) { Text(current.label) }
        DropdownMenu(open, { open = false }) {
            options.forEach { contact -> DropdownMenuItem(text = { Text(contact.label) }, onClick = { open = false; onSelect(contact.id) }) }
            DropdownMenuItem(text = { Text(stringResource(R.string.ds_create)) }, onClick = { open = false; onCreate() })
        }
    }
}

@Composable fun ModelStatusPill(status: ModelStatus, onClick: () -> Unit) {
    val label = when (status) { ModelStatus.DEMO -> R.string.ds_demo; ModelStatus.LIVE -> R.string.ds_live; ModelStatus.NOT_CONFIGURED -> R.string.ds_not_configured; ModelStatus.OFFLINE -> R.string.ds_offline }
    AssistChip(onClick, label = { Text(stringResource(label)) })
}

@Composable
fun UploadHeroButton(onScreenshot: () -> Unit, onFile: () -> Unit, onPaste: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        JunshiColumn {
            Text(stringResource(R.string.ds_upload), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.s)) {
                PrimaryButton(stringResource(R.string.ds_screenshots), onScreenshot)
                SecondaryButton(stringResource(R.string.ds_file), onFile)
                SecondaryButton(stringResource(R.string.ds_paste), onPaste)
            }
        }
    }
}

@Composable fun QuickSceneChip(label: String, group: RelationGroup?, onClick: () -> Unit) { AssistChip(onClick, label = { Text(label) }) }

@Composable fun ImportPreviewBubble(line: ChatLineModel, onEdit: () -> Unit) {
    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
    val ink = MaterialTheme.colorScheme.onSurface
    val stroke = 1.dp
    val dash = JunshiTheme.spacing.xs
    Surface(onClick = onEdit, shape = RoundedCornerShape(JunshiLayout.largeRadius), color = if (line.me) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        JunshiColumn { Text(line.speaker, style = MaterialTheme.typography.labelMedium); Text(line.text,
            onTextLayout = { layout = it }, modifier = Modifier.drawBehind {
                if (line.lowConfidence) layout?.let { measured -> repeat(measured.lineCount) { index ->
                    val y = measured.getLineBottom(index) - stroke.toPx()
                    drawLine(ink, Offset(measured.getLineLeft(index), y), Offset(measured.getLineRight(index), y),
                        strokeWidth = stroke.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), dash.toPx())))
                } }
            }) }
    }
}

@Composable fun SpeakerConfirmBar(suggestedMeSide: Side, onConfirm: (Side) -> Unit) {
    JunshiColumn {
        Text(stringResource(if (suggestedMeSide == Side.RIGHT) R.string.ds_confirm_right else R.string.ds_confirm_left))
        FlowRow {
            PrimaryButton(stringResource(R.string.ds_yes), { onConfirm(suggestedMeSide) })
            SecondaryButton(stringResource(R.string.ds_other_side), { onConfirm(if (suggestedMeSide == Side.RIGHT) Side.LEFT else Side.RIGHT) })
        }
    }
}

@Composable fun RedactionNotice(count: Int, onPreview: () -> Unit) { TextButton(onPreview) { Text(stringResource(R.string.ds_redaction, count)) } }
@Composable fun UserMessageBubble(text: String, attachments: List<AttachmentModel>, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(JunshiLayout.largeRadius), color = MaterialTheme.colorScheme.secondaryContainer) {
        JunshiColumn { Text(text); attachments.forEach { Text(it.label, style = MaterialTheme.typography.labelMedium) } }
    }
}
@Composable fun ImportCard(summary: ImportSummaryModel, onOpen: () -> Unit) { OutlinedCard(onOpen) { JunshiColumn { Text(summary.label) } } }

@Composable
fun CounselAnswerCard(state: AnswerUiState, actions: AnswerActions, compact: Boolean = false, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(JunshiLayout.largeRadius),
        border = if (state.calm || JunshiTheme.calm) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        JunshiColumn {
            if (state.demo) DemoBadge()
            if (state.crisis) {
                SafetyCard(actions.onDial, actions.onTrustedContact, actions.onHotlines, actions.onDismissSafe)
            } else {
                if (state.calm) {
                    Text(state.empathy)
                    if (state.next.isNotBlank()) Text(state.next)
                    CollapsibleSection(stringResource(R.string.ds_later), null) {
                        CalmBanner(actions.onBreathe)
                        SceneChips(state.group, state.relationLabel, state.goalLabel, state.formality, actions.onEditScene)
                        OrdinaryAnswer(state, actions, compact)
                    }
                } else {
                    SceneChips(state.group, state.relationLabel, state.goalLabel, state.formality, actions.onEditScene)
                    OrdinaryAnswer(state, actions, compact)
                }
            }
        }
    }
}

@Composable
private fun OrdinaryAnswer(state: AnswerUiState, actions: AnswerActions, compact: Boolean) {
    if (state.verdict.isNotBlank()) VerdictLine(state.verdict)
    ReplySuggestionCard(state.reply, state.isStreaming, state.calm, state.styleNote, actions.onCopyOne, actions.onCopyAll)
    if (compact) ToneMenu(actions.onChangeTone)
    else AnswerActionBar(actions.onCopyAll, actions.onChangeTone, actions.onSent, actions.onEditThenSend, actions.onNotSent,
        enabled = !state.isStreaming && state.reply.isNotEmpty())
    if (!compact) {
        AlternativesRow(state.alternatives, actions.onAlternative)
        if (state.tipName.isNotBlank()) TipCard(state.tipName, state.tipExplanation, null, false, actions.onToggleTip)
        if (state.details.isNotBlank()) CollapsibleSection(stringResource(R.string.ds_details), null) { Text(state.details) }
    }
}

@Composable fun SceneChips(group: RelationGroup, relationLabel: String, goalLabel: String, formality: Formality, onEdit: () -> Unit) {
    val colors = JunshiTheme.extendedColors
    val (icon, dot) = when (group) {
        RelationGroup.WORK -> R.drawable.ic_scene_work to colors.sceneWork
        RelationGroup.INTIMATE -> R.drawable.ic_scene_favorite to colors.sceneIntimate
        RelationGroup.FAMILY -> R.drawable.ic_scene_home to colors.sceneFamily
        RelationGroup.MEDIATION -> R.drawable.ic_scene_handshake to colors.sceneMediation
        else -> R.drawable.ic_scene_group to colors.sceneSocial
    }
    val groupLabel = stringResource(when (group) {
        RelationGroup.WORK -> R.string.ds_scene_work; RelationGroup.INTIMATE -> R.string.ds_scene_intimate
        RelationGroup.FAMILY -> R.string.ds_scene_family; RelationGroup.SOCIAL -> R.string.ds_scene_social
        RelationGroup.MEDIATION -> R.string.ds_scene_mediation; RelationGroup.DEFAULT -> R.string.ds_scene_default
    })
    val formalityLabel = stringResource(when(formality) { Formality.LOW -> R.string.ds_formality_low; Formality.MEDIUM -> R.string.ds_formality_medium; Formality.HIGH -> R.string.ds_formality_high })
    TextButton(onEdit) {
        Icon(painterResource(icon), null, Modifier.size(JunshiTheme.spacing.xl))
        Spacer(Modifier.width(JunshiTheme.spacing.s))
        Box(Modifier.size(JunshiTheme.spacing.s).background(dot, CircleShape))
        Spacer(Modifier.width(JunshiTheme.spacing.s))
        Text(listOf(groupLabel, relationLabel, goalLabel, formalityLabel).filter { it.isNotBlank() }.joinToString(stringResource(R.string.ds_separator)))
    }
}
@Composable fun VerdictLine(text: String) { Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }

@Composable
fun ReplySuggestionCard(messages: List<String>, isStreaming: Boolean, calm: Boolean, styleNote: String?, onCopyOne: (Int) -> Unit, onCopyAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.s)) {
        Text(stringResource(R.string.ds_reply), Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        messages.forEachIndexed { index, text ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(JunshiLayout.mediumRadius)) {
                JunshiColumn { Text(stringResource(R.string.ds_reply_number, index + 1), style = MaterialTheme.typography.labelMedium); Text(text, style = MaterialTheme.typography.bodyLarge); TextButton({ onCopyOne(index) }, enabled = !isStreaming) { Text(stringResource(R.string.ds_copy)) } }
            }
        }
        if (!styleNote.isNullOrBlank()) Text(styleNote, style = MaterialTheme.typography.bodySmall)
        TextButton(onCopyAll, enabled = !isStreaming && messages.isNotEmpty()) { Text(stringResource(R.string.ds_copy_all)) }
    }
}

@Composable fun StyleBalanceSlider(value: Float, onValueChange: (Float) -> Unit) {
    Column {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.ds_appropriate)); Text(stringResource(R.string.ds_like_me)) }
        Slider(value.coerceIn(0f, 1f), onValueChange)
    }
}

@Composable
fun AnswerActionBar(onCopyAll: () -> Unit, onChangeTone: (Tone) -> Unit, onSent: () -> Unit, onEditThenSend: () -> Unit, onNotSent: () -> Unit, enabled: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.s)) {
        SecondaryButton(stringResource(R.string.ds_copy_all), onCopyAll, enabled = enabled)
        ToneMenu(onChangeTone, enabled)
        SecondaryButton(stringResource(R.string.ds_sent), onSent, enabled = enabled)
        SecondaryButton(stringResource(R.string.ds_edited), onEditThenSend, enabled = enabled)
        TextButton(onNotSent, enabled = enabled) { Text(stringResource(R.string.ds_not_sent)) }
    }
}

@Composable private fun ToneMenu(onChangeTone: (Tone) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(stringResource(R.string.ds_tone), { expanded = true }, enabled = enabled)
        DropdownMenu(expanded, { expanded = false }) { Tone.entries.forEach { tone ->
            DropdownMenuItem(text = { Text(toneLabel(tone)) }, onClick = { expanded = false; onChangeTone(tone) })
        } }
    }
}

@Composable private fun toneLabel(tone: Tone) = stringResource(when(tone) {
    Tone.NATURAL -> R.string.ds_natural; Tone.GENTLE -> R.string.ds_gentle; Tone.DIRECT -> R.string.ds_direct; Tone.FORMAL -> R.string.ds_formal
    Tone.WARM -> R.string.ds_warm; Tone.CALM -> R.string.ds_calm; Tone.HUMOROUS -> R.string.ds_humorous
})
@Composable fun AlternativesRow(alternatives: List<AltReplyModel>, onPick: (AltReplyModel) -> Unit) { FlowRow { alternatives.take(2).forEach { item -> TextButton({ onPick(item) }) { Text(item.label) } } } }
@Composable fun CollapsibleSection(title: String, summary: String?, initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(Modifier.animateContentSize(tween(JunshiTheme.motion.base, easing = JunshiEasing.emphasizedDecelerate))) {
        TextButton({ expanded = !expanded }) { Text(title); summary?.let { Text(it) } }; if (expanded) content()
    }
}
@Composable fun EvidenceChip(kind: EvidenceKind, text: String, sourceLabel: String?, onOpenSource: (() -> Unit)?) {
    val prefix = stringResource(when(kind) { EvidenceKind.FACT -> R.string.ds_fact; EvidenceKind.GUESS -> R.string.ds_guess; EvidenceKind.UNKNOWN -> R.string.ds_unknown })
    val ink = when(kind) { EvidenceKind.FACT -> JunshiTheme.extendedColors.factInk; EvidenceKind.GUESS -> JunshiTheme.extendedColors.guessInk; EvidenceKind.UNKNOWN -> JunshiTheme.extendedColors.unknownInk }
    val symbol = stringResource(when(kind) { EvidenceKind.FACT -> R.string.ds_evidence_fact_symbol; EvidenceKind.GUESS -> R.string.ds_evidence_guess_symbol; EvidenceKind.UNKNOWN -> R.string.ds_evidence_unknown_symbol })
    val modifier = Modifier.fillMaxWidth().heightIn(min = JunshiLayout.touch).drawBehind {
        if (kind != EvidenceKind.FACT) {
            val stroke = 1.dp.toPx()
            drawRoundRect(ink, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(JunshiLayout.mediumRadius.toPx()),
                style = Stroke(stroke, pathEffect = if (kind == EvidenceKind.GUESS) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null))
        }
    }.then(if (onOpenSource != null) Modifier.clickable(onClick = onOpenSource) else Modifier)
    Surface(modifier, shape = RoundedCornerShape(JunshiLayout.mediumRadius), color = if (kind == EvidenceKind.FACT) ink.copy(alpha = .12f) else Color.Transparent) {
        JunshiColumn { Row(horizontalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.s)) {
            Text(symbol, Modifier.clearAndSetSemantics {}, color = ink)
            Text(stringResource(R.string.ds_evidence_text, prefix, text), color = ink)
        }; sourceLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) } }
    }
}
@Composable fun TipCard(name: String, explanation: String, howUsed: String?, saved: Boolean, onToggleSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(JunshiLayout.mediumRadius)) {
        JunshiColumn { Text(name, style = MaterialTheme.typography.titleSmall); Text(explanation); howUsed?.let { Text(it) }; TextButton(onToggleSave) { Text(stringResource(if (saved) R.string.ds_unsave_tip else R.string.ds_save_tip)) } }
    }
}
@Composable fun HabitNotice(text: String, onDismiss: () -> Unit, onNeverForThis: () -> Unit) { JunshiColumn { Text(text); FlowRow { TextButton(onDismiss) { Text(stringResource(R.string.ds_dismiss)) }; TextButton(onNeverForThis) { Text(stringResource(R.string.ds_never_this)) } } } }
@Composable fun FollowUpQuestionChips(questions: List<String>, onPick: (String) -> Unit) { FlowRow { questions.take(3).forEach { question -> AssistChip({ onPick(question) }, label = { Text(question) }) } } }
@Composable fun ThinkingIndicator(stage: ThinkingStage, slow: Boolean) { Column {
    JunshiIllustration(Illustration.THINKING)
    Text(stringResource(when(stage) { ThinkingStage.LISTENING -> R.string.ds_listening; ThinkingStage.SORTING -> R.string.ds_sorting; ThinkingStage.PLANNING -> R.string.ds_planning }))
    if (slow) Text(stringResource(R.string.ds_slow))
} }
@Composable fun MemoryChangeNotice(summary: String, onUndo: () -> Unit) { JunshiColumn { Text(summary); TextButton(onUndo) { Text(stringResource(R.string.ds_undo)) } } }

@Composable fun SafetyCard(onDial: (String) -> Unit, onTrustedContact: () -> Unit, onHotlines: () -> Unit, onDismissSafe: () -> Unit) {
    val police = stringResource(R.string.ds_police_number)
    val ambulance = stringResource(R.string.ds_ambulance_number)
    Surface(color = JunshiTheme.extendedColors.safetyContainer, contentColor = JunshiTheme.extendedColors.onSafetyContainer, shape = RoundedCornerShape(JunshiLayout.largeRadius)) {
        JunshiColumn {
            Text(stringResource(R.string.ds_safety), Modifier.semantics { heading() })
            FlowRow {
                SecondaryButton(stringResource(R.string.ds_police), { onDial(police) })
                SecondaryButton(stringResource(R.string.ds_ambulance), { onDial(ambulance) })
            }
            SecondaryButton(stringResource(R.string.ds_trusted), onTrustedContact)
            SecondaryButton(stringResource(R.string.ds_resources), onHotlines)
            TextButton(onDismissSafe) { Text(stringResource(R.string.ds_safe_now)) }
        }
    }
}
@Composable fun CalmBanner(onBreathe: () -> Unit) { Column { Text(stringResource(R.string.ds_breathe)); TextButton(onBreathe) { Text(stringResource(R.string.ds_breathe_button)) } } }
@Composable fun BreathingPrompt(seconds: Int = 10, onContinue: () -> Unit, onSaveDraft: () -> Unit) {
    var remaining by remember(seconds) { mutableIntStateOf(seconds) }
    LaunchedEffect(seconds) { while (remaining > 0) { delay(1000); remaining-- } }
    JunshiColumn { Text(stringResource(R.string.ds_breathing, remaining)); PrimaryButton(stringResource(R.string.ds_continue_copy), onContinue); SecondaryButton(stringResource(R.string.ds_save_draft), onSaveDraft) }
}
@Composable fun EmotionSlider(value: Int?, onValueChange: (Int?) -> Unit) {
    Column {
        Text(if (value == null) stringResource(R.string.ds_mood_optional) else stringResource(R.string.ds_mood, value))
        Slider((value ?: 0).toFloat(), { onValueChange(it.toInt()) }, valueRange = 0f..10f, steps = 9)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.l)) { Text(stringResource(R.string.ds_mood_low)); Text(stringResource(R.string.ds_mood_high)) }
        TextButton({ onValueChange(null) }) { Text(stringResource(R.string.ds_clear_mood)) }
    }
}

@Composable
fun Composer(text: String, onTextChange: (String) -> Unit, attachments: List<AttachmentModel>, emotionLevel: Int?, onEmotionChange: (Int?) -> Unit,
    isGenerating: Boolean, onAttach: (AttachSource) -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    var sources by remember { mutableStateOf(false) }
    JunshiColumn {
        attachments.forEach { Text(it.label) }
        CollapsibleSection(stringResource(R.string.ds_mood_optional), null) { EmotionSlider(emotionLevel, onEmotionChange) }
        OutlinedTextField(text, onTextChange, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.ds_input)) })
        FlowRow {
            Box {
                SecondaryButton(stringResource(R.string.ds_attach), { sources = true })
                DropdownMenu(sources, { sources = false }) { AttachSource.entries.forEach { source -> DropdownMenuItem(text = { Text(stringResource(when(source) { AttachSource.SCREENSHOT -> R.string.ds_screenshots; AttachSource.FILE -> R.string.ds_file; AttachSource.PASTE -> R.string.ds_paste })) }, onClick = { sources = false; onAttach(source) }) } }
            }
            PrimaryButton(stringResource(if (isGenerating) R.string.ds_stop else R.string.ds_send), if (isGenerating) onStop else onSend, enabled = isGenerating || text.isNotBlank() || attachments.isNotEmpty())
        }
    }
}

@Composable fun ContactCard(model: ContactCardModel, onClick: () -> Unit) { OutlinedCard(onClick) { JunshiColumn { Text(model.alias, style = MaterialTheme.typography.titleMedium); Text(model.relationLabel) } } }
@Composable fun TimelineItem(model: TimelineItemModel, onOpenEvidence: (String) -> Unit) { JunshiColumn { Text(model.text); model.sourceId?.let { id -> TextButton({ onOpenEvidence(id) }) { Text(id) } } } }
@Composable fun MemoryEntryRow(model: MemoryRowModel, onEdit: () -> Unit, onDelete: () -> Unit, onMarkWrong: () -> Unit) { JunshiColumn {
    Text(model.value); Text(model.source, style = MaterialTheme.typography.labelMedium)
    FlowRow { TextButton(onEdit) { Text(stringResource(R.string.ds_edit)) }; TextButton(onDelete) { Text(stringResource(R.string.ds_delete)) }; TextButton(onMarkWrong) { Text(stringResource(R.string.ds_wrong)) } }
} }
@Composable fun StyleProfileCard(group: RelationGroup, description: String, traits: List<String>, sampleCount: Int, onEdit: () -> Unit) { OutlinedCard(onEdit) { JunshiColumn { Text(description); traits.forEach { Text(it) }; Text(stringResource(R.string.ds_samples, sampleCount)) } } }
@Composable fun StyleQuizOption(text: String, selected: Boolean, onClick: () -> Unit) { FilterChip(selected, onClick, label = { Text(text) }) }
@Composable fun TipBookItem(model: TipBookItemModel, onClick: () -> Unit) { OutlinedCard(onClick) { JunshiColumn { Text(model.name); Text(model.explanation) } } }
@Composable fun PracticeTurn(counterpartLine: String, userLine: String?, feedback: String?, betterVersion: String?) { JunshiColumn { Text(counterpartLine); userLine?.let { Text(it) }; feedback?.let { Text(it) }; betterVersion?.let { Text(it) } } }

@Composable fun FloatingBall(state: FloatingBallState, dockedSide: Side, modifier: Modifier = Modifier) { Surface(modifier.size(JunshiLayout.ball).alpha(if (state == FloatingBallState.IDLE) .7f else 1f), shape = CircleShape, color = JunshiTheme.extendedColors.brandMark) {
    val description = stringResource(when(state) { FloatingBallState.NEED_AUTH -> R.string.ds_need_auth; FloatingBallState.ANALYZING -> R.string.ds_planning; FloatingBallState.IDLE -> R.string.ds_floating_idle; FloatingBallState.HINT -> R.string.ds_floating_hint })
    Box(contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.ic_junshi_foreground), description, Modifier.fillMaxSize()) }
} }
@Composable fun FloatingAnswerPanel(state: AnswerUiState, contactLabel: String, onCopy: () -> Unit, onChangeTone: (Tone) -> Unit, onCaptureMore: () -> Unit, onOpenInApp: () -> Unit, onClose: () -> Unit, safetyActions: AnswerActions? = null, footer: (@Composable ColumnScope.() -> Unit)? = null) {
    val context = LocalContext.current
    val defaultSafety = AnswerActions(
        onDial = { number -> context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
        onTrustedContact = onOpenInApp, onHotlines = onOpenInApp, onDismissSafe = onOpenInApp,
    )
    Surface(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape = RoundedCornerShape(topStart = JunshiLayout.sheetRadius, topEnd = JunshiLayout.sheetRadius)) {
        JunshiColumn(Modifier.verticalScroll(rememberScrollState())) {
            Text(contactLabel); CounselAnswerCard(state, (safetyActions ?: defaultSafety).copy(onCopyOne = { onCopy() }, onCopyAll = onCopy, onChangeTone = onChangeTone), compact = true)
            FlowRow { if (!state.crisis) SecondaryButton(stringResource(R.string.ds_capture_more), onCaptureMore); SecondaryButton(stringResource(R.string.ds_open_full), onOpenInApp); TextButton(onClose) { Text(stringResource(R.string.ds_close)) } }
            footer?.invoke(this)
        }
    }
}
@Composable fun FloatingCaptureError(kind: CaptureErrorKind, onFallback: () -> Unit) { JunshiColumn { Text(stringResource(if (kind == CaptureErrorKind.SECURE_SCREEN) R.string.ds_secure else R.string.ds_capture_failed)); SecondaryButton(stringResource(R.string.ds_fallback), onFallback) } }
@Composable fun PermissionStepCard(step: Int, total: Int, title: String, body: String, illustration: Illustration, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>?) { JunshiColumn { Text(stringResource(R.string.ds_step, step, total)); JunshiIllustration(illustration); VerdictLine(title); Text(body); PrimaryButton(primary.first, primary.second); secondary?.let { SecondaryButton(it.first, it.second) } } }

@Composable fun ConsentCard(title: String, body: String, acceptText: String, declineText: String, onAccept: () -> Unit, onDecline: () -> Unit) { OutlinedCard { JunshiColumn { VerdictLine(title); Text(body); FlowRow { SecondaryButton(acceptText, onAccept); SecondaryButton(declineText, onDecline) } } } }
@Composable fun EmptyState(illustration: Illustration, title: String, body: String?, action: Pair<String, () -> Unit>? = null) { JunshiColumn { JunshiIllustration(illustration); VerdictLine(title); body?.let { Text(it) }; action?.let { PrimaryButton(it.first, it.second) } } }
@Composable fun ErrorState(error: UiError, onRetry: (() -> Unit)?, onOpenSettings: (() -> Unit)?) { JunshiColumn { Text(error.message); onRetry?.let { SecondaryButton(stringResource(R.string.ds_retry), it) }; onOpenSettings?.let { SecondaryButton(stringResource(R.string.ds_settings), it) } } }
@Composable fun DangerConfirmDialog(title: String, consequence: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ContentDialog(title, stringResource(R.string.ds_cancel), onDismiss, confirmText to onConfirm) { BodyText(consequence) }
}
@Composable fun DemoBadge() { Text(stringResource(R.string.ds_demo), color = JunshiTheme.extendedColors.demoBadge, style = MaterialTheme.typography.labelMedium) }

@Composable fun JunshiIllustration(illustration: Illustration, modifier: Modifier = Modifier) {
    val motion = JunshiTheme.motion
    val scale = if (illustration == Illustration.THINKING && motion.breathing > 0) {
        val transition = rememberInfiniteTransition(label = "breathing")
        transition.animateFloat(.96f, 1f,
            infiniteRepeatable(tween(motion.breathing / 2, easing = JunshiEasing.sine), RepeatMode.Reverse), label = "dogScale").value
    } else 1f
    val resource = when(illustration) {
        Illustration.LISTENING -> R.drawable.ill_dog_listening; Illustration.THINKING -> R.drawable.ill_dog_thinking
        Illustration.CALM -> R.drawable.ill_dog_calm; Illustration.SLEEPING -> R.drawable.ill_dog_sleeping
        Illustration.SHIELD -> R.drawable.ill_dog_shield; Illustration.CONFUSED -> R.drawable.ill_dog_confused
    }
    Image(painterResource(resource), contentDescription = null,
        modifier = modifier.size(JunshiLayout.illustration).graphicsLayer { scaleX = scale; scaleY = scale })
}
