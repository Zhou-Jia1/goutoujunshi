package com.shengjidaguai.goutoujunshi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import java.util.UUID
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.foundation.text.input.TextFieldState
import com.shengjidaguai.goutoujunshi.data.network.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.domain.OutboundPurpose
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.Goal
import com.shengjidaguai.goutoujunshi.domain.RelationType
import com.shengjidaguai.goutoujunshi.domain.SectionId
import com.shengjidaguai.goutoujunshi.domain.MessageRole
import com.shengjidaguai.goutoujunshi.domain.RedactionAlias
import com.shengjidaguai.goutoujunshi.domain.RedactionProfile
import com.shengjidaguai.goutoujunshi.domain.SafeAliasRestorer
import com.shengjidaguai.goutoujunshi.domain.SafetyLevel
import com.shengjidaguai.goutoujunshi.domain.SafetyPolicy
import com.shengjidaguai.goutoujunshi.domain.group
import com.shengjidaguai.goutoujunshi.domain.Tone as DomainTone

/** Event boundary for rendering and exercising the home screen without opening storage. */
data class CounselUiActions(
    val enterPractice:(String?)->Unit={},
    val editPracticeInput:(String?,String,String)->Unit={_,_,_->},
    val practiceDifficulty:(String?,String,com.shengjidaguai.goutoujunshi.domain.PracticeDifficulty)->Unit={_,_,_->},
    val previewPractice:(String?,String,Boolean)->Unit={_,_,_->},
    val sendPractice:(String?,String,String)->Unit={_,_,_->},
    val exitPractice:(String?,String)->Unit={_,_->},
    val savePracticeSummary:(String?,String,String)->Unit={_,_,_->},
    val toggleTip: (String?,String)->Unit = {_,_->},
    val saveTipScene: (String?,String)->Unit = {_,_->},
    val dismissHabit: (String?,String,Boolean)->Unit = {_,_,_->},
    val rewrite: (String?, String, DomainTone, Float) -> Unit = { _, _, _, _ -> },
    val changeScene: (String?, String, RelationType, Goal, String) -> Unit = { _, _, _, _, _ -> },
    val feedback: (String?, String, String?) -> Unit = { _, _, _ -> },
    val initialize: () -> Unit = {},
    val editDraft: (String, DraftEditStamp) -> Unit = { _, _ -> },
    val pasteText: (String, DraftEditStamp) -> Unit = { _, _ -> },
    val selectContact: (String) -> Unit = {},
    val createContact: (String, RelationType) -> Unit = { _, _ -> },
    val deleteContact: (String) -> Unit = {},
    val setRelation: (RelationType) -> Unit = {},
    val setGoal: (Goal) -> Unit = {},
    val setEmotion: (Int?) -> Unit = {},
    val setStyleBias: (Float) -> Unit = {},
    val setTone: (DomainTone) -> Unit = {},
    val preparePreview: (String) -> Unit = {},
    val analyze: (String) -> Unit = {},
    val retry: () -> Unit = {},
    val stop: () -> Unit = {},
    val newTemporary: () -> Unit = {},
    val clearAll: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val importFile: () -> Unit = {},
    val importImages: () -> Unit = {},
    val saveRedactionProfile: (String, List<String>, List<RedactionAlias>) -> Unit = { _, _, _ -> },
)

/** UI-only clipboard seam. Reading is invoked exclusively by an explicit paste click. */
class CounselClipboard(val read: () -> String?, val write: (String) -> Unit)
val LocalCounselClipboard = staticCompositionLocalOf<CounselClipboard?> { null }

/** Immediate IME owner. Actor echoes acknowledge saves, but may lag several keystrokes. */
@Stable internal class CounselDraftEditor(initial: String, private val origin: String?) {
    var text by mutableStateOf(initial)
        private set
    private val editorId = UUID.randomUUID().toString()
    private var sequence = 0L
    private var pending: DraftEditStamp? = null
    fun edit(value: String): DraftEditStamp? {
        text = value
        return origin?.let { DraftEditStamp(editorId, ++sequence, it) }?.also { pending = it }
    }
    fun acknowledge(value: String, stamp: DraftEditStamp?) {
        if (pending == null) text = value
        else if (pending == stamp) pending = null
    }
}

@Composable fun CounselScreen(viewModel: CounselViewModel, pickImages:()->Unit = {}, pickFile:()->Unit = {}, openSettings: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importing by viewModel.imports.state.collectAsStateWithLifecycle()
    val memory by viewModel.memory.collectAsStateWithLifecycle()
    val shownMemory=memory
    CounselContent(state, CounselUiActions(
        enterPractice=viewModel::enterPractice,editPracticeInput=viewModel::editPracticeInput,practiceDifficulty=viewModel::practiceDifficulty,previewPractice=viewModel::previewPractice,sendPractice=viewModel::sendPractice,exitPractice=viewModel::exitPractice,savePracticeSummary=viewModel::savePracticeSummary,
        toggleTip=viewModel::toggleTip, saveTipScene=viewModel::saveTipScene, dismissHabit=viewModel::dismissHabit,
        rewrite = viewModel::rewrite, changeScene = viewModel::changeScene, feedback = viewModel::feedback,
        initialize = viewModel::initialize, editDraft = viewModel::editDraft, pasteText = { text, _ -> viewModel.importPaste(text) },
        selectContact = viewModel::selectContact, createContact = viewModel::createContact, deleteContact = viewModel::deleteContact,
        setRelation = viewModel::setRelation, setGoal = viewModel::setGoal, setEmotion = viewModel::setEmotion,
        setStyleBias = viewModel::setStyleBias, setTone = viewModel::setTone, preparePreview = viewModel::preparePreview,
        analyze = viewModel::analyze, retry = viewModel::retry, stop = viewModel::stop,
        newTemporary = viewModel::newTemporary, clearAll = viewModel::clearAll,
        openSettings = openSettings,
        importImages = pickImages,
        importFile = pickFile,
        saveRedactionProfile = viewModel::saveRedactionProfile,
    ), importing != null, memoryPaused=memory.consent.paused, resumeMemory={ shownMemory.receipt?.let { viewModel.setMemoryEnabled(true,false,it) } }) {
        MemoryInline(shownMemory.copy(snapshot=shownMemory.snapshot.copy(consent=shownMemory.consent.copy(paused=false))),viewModel::acceptMemory,viewModel::declineMemory,{ receipt -> viewModel.setMemoryEnabled(true,false,receipt) }, {
            val owner=shownMemory.ownerId; val stamp=shownMemory.receipt; val id=shownMemory.notice?.operationId
            if(owner!=null && stamp!=null && id!=null) viewModel.undoMemory(owner,id,stamp)
        })
    }
}

@Composable fun CounselContent(state: CounselState, actions: CounselUiActions, importing: Boolean = false, memoryPaused: Boolean = false, resumeMemory: () -> Unit = {}, memoryContent: (@Composable () -> Unit)? = null) {
    if (!state.lockState.canDisplaySensitiveContent) {
        EmptyState(Illustration.SHIELD, stringResource(R.string.counsel_locked), null)
        return
    }
    val streamSafety = SafetyLevel.entries.filter { Regex("\\b${it.name}\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(state.partial[SectionId.SAFETY].orEmpty()) }.maxByOrNull { it.ordinal } ?: SafetyLevel.NONE
    val presentation = SafetyPolicy.evaluate(state.emotion, (state.answer?.safety ?: SafetyLevel.NONE).upgrade(state.safety), streamSafety, state.lockState)
    if(state.practice!=null && presentation.showOrdinaryStrategy) {PracticeContent(state,actions);return}
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var createContact by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    var previewOpen by remember { mutableStateOf(false) }
    val modalStamp = state.answerModalStamp()
    val actionableAnswer = state.hasCurrentAnswerSource()
    var sceneOpen by remember(modalStamp) { mutableStateOf(false) }
    var feedbackMode by remember(modalStamp) { mutableStateOf<Int?>(null) }
    val editedReply = remember(modalStamp) { TextFieldState(state.answer?.reply?.joinToString("\n").orEmpty()) }
    var rulesOpen by remember(state.conversation?.id) { mutableStateOf(false) }
    // Process memory only, keyed to the conversation so switching never exposes another draft.
    val origin = state.conversation?.id
    val editor = remember(origin) { CounselDraftEditor(state.draft, origin) }
    LaunchedEffect(editor, state.draft, state.draftEditAck) { editor.acknowledge(state.draft, state.draftEditAck) }
    fun editDraft(text: String) { editor.edit(text)?.let { actions.editDraft(text, it) } }
    fun flushDisplayedDraft() {
        if (editor.text != state.draft) editDraft(editor.text)
    }
    val appName = stringResource(R.string.app_name)
    val platformClipboard = remember(context, appName) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        CounselClipboard(
            read = { manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() },
            write = { text ->
                val clip = ClipData.newPlainText(appName, text)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                manager.setPrimaryClip(clip)
            },
        )
    }
    val clipboard = LocalCounselClipboard.current ?: platformClipboard
    val canAnalyze = presentation.showOrdinaryStrategy && !importing && !state.loading && state.conversation != null && !state.streaming
    fun paste() {
        val text = clipboard.read()
        if (text.isNullOrBlank()) note = R.string.clipboard_empty else {
            origin?.let { actions.pasteText(text, DraftEditStamp("import", 0, it)) }
        }
    }
    val latestState by rememberUpdatedState(state)
    fun copy(text: String) {
        val current = latestState
        if (text.isBlank() || current.answer == null || current.streaming || current.error != null ||
            (current.answerRequest != null && !current.hasCurrentAnswerSource()) ||
            !SafetyPolicy.evaluate(current.emotion, current.safety.upgrade(current.answer.safety), SafetyLevel.NONE, current.lockState).showOrdinaryStrategy) return
        clipboard.write(text)
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        note = R.string.copied
    }
    LazyColumn(Modifier.fillMaxSize().testTag("counsel-list"), contentPadding = PaddingValues(JunshiTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        if(memoryPaused) item { SecondaryButton(stringResource(R.string.memory_paused),resumeMemory) }
        item { JunshiTopBar(stringResource(R.string.nav_counsel)) }
        item {
            ContactSwitcher(ContactChipModel(state.currentContact?.id, state.currentContact?.alias ?: stringResource(R.string.temporary_title)),
                listOf(ContactChipModel(null, stringResource(R.string.temporary_title))) + state.contacts.filter { it.archivedAt == null }.map { ContactChipModel(it.id, it.alias) },
                { id -> if (id == null) actions.newTemporary() else actions.selectContact(id) }, { createContact = true })
        }
        item { Box(Modifier.testTag("model-status")) {
            ModelStatusPill(when {
                state.demo -> ModelStatus.DEMO
                state.modelSettings.maskedKey == null -> ModelStatus.NOT_CONFIGURED
                state.error?.code in setOf("NETWORK", "FIRST_PACKET_TIMEOUT", "STREAM_TIMEOUT") -> ModelStatus.OFFLINE
                else -> ModelStatus.LIVE
            }) { note = when {
                state.demo -> R.string.demo_explanation
                state.modelSettings.maskedKey == null -> R.string.model_not_configured_notice
                state.error?.code in setOf("NETWORK", "FIRST_PACKET_TIMEOUT", "STREAM_TIMEOUT") -> R.string.model_error_network
                else -> R.string.live_explanation
            } }
        } }
        if (state.currentContact == null) item { BodyText(stringResource(R.string.temporary_explanation), secondary = true) }
        item { BodyText(stringResource(if (state.demo) R.string.demo_explanation else R.string.live_explanation), secondary = true) }
        if (state.loading) item { ThinkingIndicator(ThinkingStage.LISTENING, false) }
        item {
            UploadHeroButton(actions.importImages, actions.importFile, ::paste)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("quick-scenes"),
                horizontalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.s)) {
                listOf(RelationType.BOSS, RelationType.PARTNER, RelationType.PARENT, RelationType.FRIEND, RelationType.MEDIATION).forEach { type ->
                    QuickSceneChip(stringResource(relationLabel(type)),
                        com.shengjidaguai.goutoujunshi.designsystem.RelationGroup.valueOf(type.group().name),
                        { actions.setRelation(type) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.s)) {
                ChoiceField(stringResource(R.string.relation_label), stringResource(relationLabel(state.relationType)),
                    RelationType.entries.map { type -> stringResource(relationLabel(type)) to { actions.setRelation(type) } })
            }
            ChoiceField(stringResource(R.string.goal_label), stringResource(goalLabel(state.goal)),
                Goal.entries.map { goal -> stringResource(goalLabel(goal)) to { actions.setGoal(goal) } })
        }
        if (state.messages.isEmpty() && state.evidence.isEmpty() && !state.streaming) item {
            Box(Modifier.testTag("counsel-empty")) {
                EmptyState(Illustration.LISTENING, stringResource(R.string.empty_counsel_title), stringResource(R.string.empty_counsel_body))
            }
        }
        if (state.importDuplicate) item { BodyText(stringResource(R.string.import_duplicate)) }
        state.messages.filter { it.role == MessageRole.USER }.takeLast(3).forEach { message ->
            item(key = message.id) { UserMessageBubble(message.text, emptyList()) }
        }
        if (presentation.showOrdinaryStrategy && state.currentRequest?.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) item {
            BodyText(stringResource(if (state.streaming) R.string.summary_running else R.string.summary_step))
            BodyText(stringResource(R.string.summary_scope), secondary = true)
        }
        if (presentation.showOrdinaryStrategy && state.summaryReady) item { BodyText(stringResource(R.string.summary_ready)) }
        if (state.streaming) item { ThinkingIndicator(
            when(state.waiting.stage) { 0 -> ThinkingStage.LISTENING; 1 -> ThinkingStage.SORTING; else -> ThinkingStage.PLANNING }, state.waiting.slow) }
        val answer = state.answer
        val hasAnswerSource = actionableAnswer
        val partialReply = (if (state.currentRequest?.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) null else state.partial[SectionId.REPLY])?.let { SafeAliasRestorer.displayPartial(it, state.preview) }?.split("+++")?.filter { it.isNotBlank() }.orEmpty()
        if (presentation.showSafetyCard) item {
            JunshiColumn(Modifier.testTag("counsel-safety")) {
                SafetyCard({ number -> context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))) },
                    { note = R.string.safety_contact_notice }, { note = R.string.safety_hotline_notice }, { note = R.string.safety_stays_active })
            }
        }
        if (presentation.showOrdinaryStrategy && (answer != null || partialReply.isNotEmpty())) item {
            JunshiColumn(Modifier.testTag("counsel-answer")) {
                if (state.answerDemo) DemoBadge()
                if (answer != null && hasAnswerSource) SceneChips(com.shengjidaguai.goutoujunshi.designsystem.RelationGroup.valueOf(state.relationType.group().name),
                    stringResource(relationLabel(state.relationType)), stringResource(goalLabel(state.goal)),
                    Formality.entries.firstOrNull { it.name.equals(state.formality, true) } ?: Formality.MEDIUM, { sceneOpen = true })
                val verdict = answer?.verdict ?: state.currentRequest?.takeIf { it.mode == com.shengjidaguai.goutoujunshi.domain.RequestMode.REWRITE_REPLY }?.originalAnswer?.verdict ?: SafeAliasRestorer.displayPartial(state.partial[SectionId.VERDICT].orEmpty(), state.preview)
                if (verdict.isNotBlank()) VerdictLine(verdict)
                val replies = answer?.reply ?: partialReply
                ReplySuggestionCard(replies, answer == null, (state.emotion ?: 0) >= 8,
                    if(com.shengjidaguai.goutoujunshi.domain.StyleDeviationPolicy.addedEmoji(state.answerRequest?.styleSamples.orEmpty(),replies)) stringResource(R.string.style_emoji_note) else null,
                    { index -> if (answer != null) replies.getOrNull(index)?.let(::copy) }, { if (answer != null) copy(replies.joinToString("\n")) }, highlight=state.teaching.tip?.example?.takeIf {state.teaching.answerId==answer?.id})
                if (answer != null && (answer.formatDisordered || answer.freeText.isNotBlank())) {
                    BodyText(stringResource(R.string.answer_format_disordered))
                    BodyText(answer.freeText.ifBlank { answer.rawText })
                }
                if (answer != null && hasAnswerSource) {
                    AnswerActionBar({ copy(replies.joinToString("\n")) }, { tone ->
                        actions.rewrite(origin, answer.id, DomainTone.valueOf(tone.name), state.styleBias); if (!state.demo) previewOpen = true
                    }, { feedbackMode = 0 }, { feedbackMode = 1 }, { note = R.string.answer_not_sent_notice }, enabled = !state.streaming && state.error == null)
                    var bias by remember(answer.id) { mutableStateOf(state.styleBias) }
                    StyleBalanceSlider(bias, { bias = it })
                    SecondaryButton(stringResource(R.string.answer_apply_style), {
                        actions.rewrite(origin, answer.id, state.tone, bias); if (!state.demo) previewOpen = true
                    }, enabled = !state.streaming && state.error == null)
                    if (state.feedbackSaved) BodyText(stringResource(R.string.answer_feedback_saved))
                    AnswerDetails(answer, state,{actions.enterPractice(origin)})
                    if(state.teaching.answerId==answer.id) TeachingContent(state.teaching,{actions.toggleTip(origin,state.teaching.receipt)},{actions.dismissHabit(origin,state.teaching.receipt,it)},saveScene={actions.saveTipScene(origin,state.teaching.receipt)})
                } else if (answer != null) CollapsibleSection(stringResource(R.string.answer_details), null) {
                    BodyText(listOf(answer.empathy, answer.why.joinToString("\n"), answer.cost, answer.next).filter { it.isNotBlank() }.joinToString("\n\n"))
                }
            }
        }
        if (presentation.showOrdinaryStrategy && state.currentRequest?.purpose != OutboundPurpose.LONG_RECORD_SUMMARY && state.currentRequest?.mode != com.shengjidaguai.goutoujunshi.domain.RequestMode.REWRITE_REPLY && state.answer == null && state.partial[SectionId.FREE_TEXT]?.isNotBlank() == true) item {
            BodyText(SafeAliasRestorer.displayPartial(state.partial[SectionId.FREE_TEXT].orEmpty(), state.preview))
        }
        if(memoryContent!=null && presentation.showOrdinaryStrategy) item { memoryContent() }
        state.error?.let { error -> item {
            if (error.code == "NETWORK") BodyText(stringResource(
                if (state.currentContact == null) R.string.error_network_temporary
                else if (state.draftSaved) R.string.error_network_saved else R.string.error_network_unsaved))
            ErrorState(UiError(stringResource(errorLabel(error.code))), if (error.retryable) ({
                if (error.code == "INTERRUPTED" && !state.demo && origin != null) {
                    actions.preparePreview(origin); previewOpen = true
                } else { actions.retry(); if (!state.demo && origin != null && error.code != "STORAGE_UNAVAILABLE") previewOpen = true }
            }) else null, if (error.code in setOf("KEY_MISSING", "KEY_INVALID", "HTTP_401", "HTTP_403", "INSUFFICIENT_BALANCE", "CONFIG_INVALID", "SETTINGS_UNAVAILABLE")) actions.openSettings else null)
        } }
        item { SecondaryButton(stringResource(R.string.preview_request), {
            if (canAnalyze && origin != null) { flushDisplayedDraft(); actions.preparePreview(origin); previewOpen = true }
        }) }
        item {
            Box(Modifier.testTag("counsel-composer")) {
                Composer(editor.text, ::editDraft, if (state.evidence.isEmpty()) emptyList() else listOf(AttachmentModel("imports", stringResource(R.string.import_ready, state.evidence.sumOf { it.lines.size }))), state.emotion, actions.setEmotion, state.streaming,
                    { source -> when(source) { AttachSource.PASTE -> paste(); AttachSource.SCREENSHOT -> actions.importImages(); AttachSource.FILE -> actions.importFile() } },
                    { if (canAnalyze && origin != null) { flushDisplayedDraft(); if (!state.demo) { actions.preparePreview(origin); previewOpen = true } else actions.analyze(origin) } }, actions.stop)
            }
        }
    }
    if (presentation.showOrdinaryStrategy && state.answer != null && actionableAnswer) {
        val answer = state.answer
        if (sceneOpen) SceneEditDialog(state, { relation, goal, formality ->
            actions.changeScene(origin, answer.id, relation, goal, formality); sceneOpen = false; if (!state.demo) previewOpen = true
        }, { sceneOpen = false })
        feedbackMode?.let { mode -> ContentDialog(stringResource(if (mode == 0) R.string.answer_confirm_sent else R.string.answer_edit_reply), stringResource(R.string.close), { feedbackMode = null },
            stringResource(R.string.answer_save_feedback) to { actions.feedback(origin, answer.id, if (mode == 1) editedReply.text.toString() else null); feedbackMode = null }) {
            if (mode == 1) ReplyEditField(editedReply, stringResource(R.string.answer_edit_reply))
            BodyText(stringResource(R.string.answer_feedback_notice))
        } }
    }
    if (createContact) CreateContactDialog(actions.createContact) { createContact = false }
    note?.let { message -> ContentDialog(stringResource(R.string.app_name), stringResource(R.string.close), { note = null }) { BodyText(stringResource(message)) } }
    if (previewOpen && presentation.showOrdinaryStrategy) ContentDialog(stringResource(R.string.preview_request), stringResource(R.string.close), { previewOpen = false },
        if (!state.demo && state.outboundBody != null && origin != null) stringResource(R.string.live_send) to { actions.analyze(origin); previewOpen = false } else null) {
        if (state.currentRequest?.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) {
            BodyText(stringResource(R.string.summary_step))
            BodyText(stringResource(R.string.summary_scope))
        }
        OutboundPreviewContent(state,{rulesOpen=true;previewOpen=false})
    }
    if (rulesOpen && origin != null) ContentDialog(stringResource(R.string.redaction_rules), stringResource(R.string.close), { rulesOpen = false }) {
        RedactionRulesContent(state.redactionProfile, state.currentContact != null, state.redactionError) { terms, aliases ->
            actions.saveRedactionProfile(origin, terms, aliases)
        }
    }
}

/** Every preview displays its real destination and the same frozen payload used by the provider. */
@Composable internal fun OutboundPreviewContent(state:CounselState,openRules:()->Unit,scope:String?=null) {
    BodyText(scope ?: stringResource(if(state.demo) R.string.preview_demo_notice else R.string.live_scope))
    state.preview?.practiceSelection?.let {selection ->
        BodyText(stringResource(R.string.practice_selected_scope,selection.selectedTurns,selection.omittedTurns,selection.opponentMessageIds.size,selection.omittedOpponentMessages))
    }
    BodyText(stringResource(R.string.redaction_honesty))
    BodyText(stringResource(R.string.redaction_count,state.preview?.redactionCount ?: 0))
    SecondaryButton(stringResource(R.string.redaction_rules),openRules)
    if(!state.demo) BodyText(runCatching {state.modelSettings.saved.connection().safeDestination()}.getOrDefault(""))
    state.preview?.let {preview->
        val body=state.outboundBody ?: (preview.system+"\n\n"+preview.user)
        if(preview.practiceSelection!=null) CollapsibleSection(stringResource(R.string.practice_full_body),null) {
            Column(Modifier.testTag("practice-outbound-body")) {
                outboundBodyChunks(body).forEachIndexed {index,text -> BodyText(text,Modifier.testTag("practice-outbound-body-part-$index"))}
            }
        } else BodyText(body)
    }
        ?: BodyText(stringResource(if(state.error==null) R.string.preparing_preview else errorLabel(state.error!!.code)))
}

@Composable fun RedactionRulesContent(profile: RedactionProfile, formal: Boolean, failed: Boolean,
    save: (List<String>, List<RedactionAlias>) -> Unit) {
    var words by remember(profile) { mutableStateOf(profile.terms.joinToString("\n")) }
    var names by remember(profile) { mutableStateOf(profile.aliases.joinToString("\n") { "${it.original}=${it.alias}" }) }
    var invalid by remember { mutableStateOf(false) }
    BodyText(stringResource(if (formal) R.string.redaction_formal else R.string.redaction_temporary))
    BodyText(stringResource(R.string.redaction_honesty))
    FormTextField(words, { words = it }, stringResource(R.string.redaction_words))
    FormTextField(names, { names = it }, stringResource(R.string.redaction_aliases))
    if (failed || invalid) BodyText(stringResource(R.string.redaction_invalid))
    PrimaryButton(stringResource(R.string.redaction_save), {
        val terms = words.lines().filter { it.isNotBlank() }.distinct()
        val lines = names.lines().filter { it.isNotBlank() }
        val aliases = lines.map { RedactionAlias(it.substringBefore('='), it.substringAfter('=', "")) }
        invalid = runCatching { RedactionProfile(terms = terms, aliases = aliases) }.isFailure
        if (!invalid) save(terms, aliases)
    })
}

@Composable fun ContactsScreen(viewModel: CounselViewModel, onOpen: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.closeContactDetails() } }
    var creating by remember { mutableStateOf(false) }
    var memoryOwner by remember(details.ownerId) { mutableStateOf<String?>(null) }
    if(memoryOwner!=null) {
        androidx.activity.compose.BackHandler { memoryOwner=null }
        MemoryScreen(viewModel,memoryOwner,{ memoryOwner=null }); return
    }
    if (!state.lockState.canDisplaySensitiveContent) {
        EmptyState(Illustration.SHIELD, stringResource(R.string.counsel_locked), null)
        return
    }
    if (details.ownerId != null) {
        androidx.activity.compose.BackHandler { viewModel.closeContactDetails() }
        ContactDetailContent(details, viewModel::closeContactDetails, viewModel::openContactDetails,
            viewModel::editContact, viewModel::deleteContact, viewModel::undoContactEdit,
            { id -> viewModel.selectContact(id); viewModel.closeContactDetails(); onOpen() }, { id -> memoryOwner=id }, { id -> viewModel.practiceForContact(id);onOpen() })
    } else {
        ContactsContent(state.contacts, { creating = true }, viewModel::openContactDetails,
            { viewModel.newTemporary(); onOpen() })
    }
    if (creating) CreateContactDialog(viewModel) { creating = false }
}

@Composable private fun CreateContactDialog(viewModel: CounselViewModel, onClose: () -> Unit) {
    CreateContactDialog(viewModel::createContact, onClose)
}

@Composable private fun CreateContactDialog(onCreate: (String, RelationType) -> Unit, onClose: () -> Unit) {
    var alias by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf(RelationType.ACQUAINTANCE) }
    ContentDialog(stringResource(R.string.create_contact), stringResource(R.string.close), onClose,
        stringResource(R.string.create_contact) to { if (alias.isNotBlank()) { onCreate(alias, relation); onClose() } }) {
        FormTextField(alias, { alias = it }, stringResource(R.string.contact_alias_hint))
        ChoiceField(stringResource(R.string.relation_label), stringResource(relationLabel(relation)),
            RelationType.entries.map { type -> stringResource(relationLabel(type)) to { relation = type } })
    }
}

@Composable fun SettingsScreen(viewModel: CounselViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var memories by remember { mutableStateOf(false) }
    var styles by remember { mutableStateOf(false) }
    var tips by remember {mutableStateOf(false)}
    if(tips) {androidx.activity.compose.BackHandler {tips=false};TipsScreen(viewModel) {tips=false};return}
    if(styles) {androidx.activity.compose.BackHandler {styles=false};StyleScreen(viewModel) {styles=false};return}
    if(memories) {
        androidx.activity.compose.BackHandler { memories=false }
        MemoryScreen(viewModel,null,{ memories=false }); return
    }
    val memory by viewModel.memoryPage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.openMemory(null) }
    ModelSettingsContent(state, viewModel::saveModelSettings, viewModel::testModelConnection,
        viewModel::cancelModelTest, viewModel::clearAll, viewModel::initialize,
        { memories=true }, if(memory.ownerId==null && !memory.loading && !memory.failed && memory.snapshot.stamp!=null) Triple(state.contacts.size,memory.totalMemories,memory.totalImports) else null,openStyle={styles=true},openTips={tips=true})
}

@Composable fun ModelSettingsContent(state: CounselState,
    onSave: (SavedModelSettings, String?, Boolean) -> Unit, onTest: () -> Unit,
    onCancel: () -> Unit, onClear: () -> Unit, onInitialize: () -> Unit = {}, openMemory: (() -> Unit)? = null, dataCounts: Triple<Int,Int,Int>? = null,openStyle:(()->Unit)?=null,openTips:(()->Unit)?=null) {
    var confirmClear by remember { mutableStateOf(false) }
    val snapshot = state.modelSettings
    val defaults = snapshot.saved.takeIf { it.baseUrl.isNotBlank() } ?: ModelPresets.openAi.let { SavedModelSettings(false, it.baseUrl, it.model, it.providerLabel) }
    var live by remember(snapshot) { mutableStateOf(defaults.live) }
    var base by remember(snapshot) { mutableStateOf(defaults.baseUrl) }
    var model by remember(snapshot) { mutableStateOf(defaults.model) }
    var label by remember(snapshot) { mutableStateOf(defaults.label) }
    // Never use rememberTextFieldState/rememberSaveable: secret must not enter SavedState.
    val key = remember(snapshot) { TextFieldState() }
    DisposableEffect(key) { onDispose { key.edit { replace(0, length, "") } } }
    val dirty = live != snapshot.saved.live || base != defaults.baseUrl || model != defaults.model || label != defaults.label || key.text.isNotEmpty()
    LazyColumn(Modifier.fillMaxSize().testTag("model-settings"), contentPadding = PaddingValues(JunshiTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { JunshiTopBar(stringResource(R.string.nav_me)) }
        if(openMemory!=null) item { SecondaryButton(stringResource(R.string.memory_title),openMemory) }
        if(openTips!=null) item {SecondaryButton(stringResource(R.string.tip_book),openTips)}
        if(openStyle!=null) item {SecondaryButton(stringResource(R.string.style_title),openStyle)}
        item { BodyText(stringResource(R.string.live_explanation)) }
        item { ChoiceField(stringResource(R.string.model_mode), stringResource(if (live) R.string.model_live else R.string.model_demo), listOf(
            stringResource(R.string.model_demo) to { live = false }, stringResource(R.string.model_live) to { live = true })) }
        item { ChoiceField(stringResource(R.string.model_preset), label, listOf(
            stringResource(R.string.preset_openai) to { base = ModelPresets.openAi.baseUrl; model = ModelPresets.openAi.model; label = ModelPresets.openAi.providerLabel },
            stringResource(R.string.preset_deepseek) to { base = ModelPresets.deepSeek.baseUrl; model = ModelPresets.deepSeek.model; label = ModelPresets.deepSeek.providerLabel },
            stringResource(R.string.preset_qwen) to { base = ModelPresets.qwen.baseUrl; model = ModelPresets.qwen.model; label = ModelPresets.qwen.providerLabel },
            stringResource(R.string.preset_custom) to { label = "" })) }
        item { BodyText(stringResource(R.string.model_workspace_hint), secondary = true) }
        item { FormTextField(base, { base = it }, stringResource(R.string.model_base)) }
        item { FormTextField(model, { model = it }, stringResource(R.string.model_name)) }
        item { SensitiveTextField(key, stringResource(R.string.model_key)) }
        item { BodyText(snapshot.maskedKey?.let { stringResource(R.string.model_key_saved, it) } ?: stringResource(R.string.model_key_missing)) }
        item { BodyText(stringResource(R.string.model_key_origin), secondary = true) }
        item { PrimaryButton(stringResource(R.string.model_save), {
            onSave(SavedModelSettings(live, base.trim(), model.trim(), label), key.text.toString().takeIf { it.isNotBlank() }, false)
            key.edit { replace(0, length, "") }
        }) }
        item { SecondaryButton(stringResource(R.string.model_remove_key), {
            key.edit { replace(0, length, "") }; onSave(SavedModelSettings(live, base.trim(), model.trim(), label), null, true)
        }) }
        item { BodyText(stringResource(R.string.model_test_scope), secondary = true) }
        item { SecondaryButton(stringResource(if (state.testingConnection) R.string.model_test_cancel else R.string.model_test), {
            if (state.testingConnection) onCancel() else if (!dirty && snapshot.saved.live) onTest()
        }) }
        if (dirty) item { BodyText(stringResource(R.string.model_save_first)) }
        state.connectionResult?.let { code -> item { BodyText(stringResource(if (code == "SUCCESS") R.string.model_test_success else errorLabel(code))) } }
        item { SecondaryButton(stringResource(R.string.clear_all), { if(openMemory==null || dataCounts!=null) confirmClear = true }) }
        if(openMemory!=null && dataCounts==null) item { BodyText(stringResource(R.string.memory_loading)) }
        item { SecondaryButton(stringResource(R.string.initialize_store), onInitialize) }
    }
    val clearText=dataCounts?.let { stringResource(R.string.memory_full_clear,it.first,it.second,it.third) }
    if (confirmClear) DangerConfirmDialog(stringResource(R.string.clear_all), clearText ?: stringResource(R.string.clear_all_consequence),
        clearText ?: stringResource(R.string.confirm_clear), {
            key.edit { replace(0, length, "") }
            live = false; base = ModelPresets.openAi.baseUrl; model = ModelPresets.openAi.model; label = ModelPresets.openAi.providerLabel
            onClear(); confirmClear = false
        }, { confirmClear = false })
}

fun errorLabel(code: String): Int = when (code) {
    "PRACTICE_NEEDS_MESSAGES" -> R.string.practice_needs_messages
    "PRACTICE_SOURCE_CHANGED" -> R.string.practice_source_changed
    "PRACTICE_SAFETY_UNAVAILABLE" -> R.string.practice_safety_unavailable
    "PRACTICE_RANGE" -> R.string.practice_range
    "PRACTICE_INVALID" -> R.string.practice_invalid
    "SETTINGS_UNAVAILABLE" -> R.string.model_error_settings
    "CONFIG_INVALID" -> R.string.model_error_config
    "KEY_MISSING", "KEY_INVALID" -> R.string.model_error_key
    "HTTP_401", "HTTP_403" -> R.string.model_error_auth
    "INSUFFICIENT_BALANCE" -> R.string.model_error_balance
    "CONTENT_FILTERED" -> R.string.model_error_filtered
    "INTERRUPTED" -> R.string.error_interrupted
    "HTTP_429" -> R.string.model_error_limit
    in (500..599).map { "HTTP_$it" } -> R.string.model_error_server
    "FIRST_PACKET_TIMEOUT", "STREAM_TIMEOUT" -> R.string.model_error_timeout
    "NETWORK", "STREAM_INTERRUPTED" -> R.string.model_error_network
    "NOT_SSE", "INVALID_JSON", "EMPTY_RESPONSE", "PROVIDER_REJECTED", "PROVIDER_ERROR" -> R.string.model_error_protocol
    "SUMMARY_INVALID" -> R.string.summary_invalid
    "PREVIEW_REQUIRED" -> R.string.model_error_preview
    "REDACTION_INVALID" -> R.string.redaction_invalid
    "CONTACT_ALIAS_REQUIRED" -> R.string.error_alias
    "INPUT_REQUIRED" -> R.string.error_input
    "CAPTURE_SOURCE_RECONFIRM" -> R.string.t33_capture_reconfirm
    "PROMPT_UNAVAILABLE" -> R.string.error_prompt
    "CANCELLED" -> R.string.error_cancelled
    "CLEANUP_INCOMPLETE" -> R.string.error_cleanup
    "STORAGE_UNAVAILABLE" -> R.string.error_storage
    else -> R.string.error_analysis
}

fun relationLabel(type: RelationType): Int = when (type) {
    RelationType.BOSS -> R.string.relation_boss; RelationType.COLLEAGUE -> R.string.relation_colleague
    RelationType.SUBORDINATE -> R.string.relation_subordinate; RelationType.CLIENT -> R.string.relation_client
    RelationType.CRUSH -> R.string.relation_crush; RelationType.PARTNER -> R.string.relation_partner
    RelationType.EX -> R.string.relation_ex; RelationType.PARENT -> R.string.relation_parent
    RelationType.IN_LAW -> R.string.relation_in_law; RelationType.RELATIVE -> R.string.relation_relative
    RelationType.CHILD -> R.string.relation_child; RelationType.SIBLING -> R.string.relation_sibling
    RelationType.FRIEND -> R.string.relation_friend; RelationType.ACQUAINTANCE -> R.string.relation_acquaintance
    RelationType.GROUP -> R.string.relation_group; RelationType.MEDIATION -> R.string.relation_mediation
}
fun goalLabel(goal: Goal): Int = when (goal) {
    Goal.REPLY -> R.string.goal_reply; Goal.REPORT -> R.string.goal_report; Goal.ASK_HELP -> R.string.goal_ask_help
    Goal.DECLINE -> R.string.goal_decline; Goal.APOLOGIZE -> R.string.goal_apologize; Goal.CLARIFY -> R.string.goal_clarify
    Goal.COMFORT -> R.string.goal_comfort; Goal.ADVANCE -> R.string.goal_advance; Goal.INVITE -> R.string.goal_invite
    Goal.BOUNDARY -> R.string.goal_boundary; Goal.ICEBREAK -> R.string.goal_icebreak; Goal.REPAIR -> R.string.goal_repair
    Goal.MEDIATE -> R.string.goal_mediate; Goal.CLOSE -> R.string.goal_close
}

@Composable private fun AnswerDetails(answer: com.shengjidaguai.goutoujunshi.domain.StructuredAnswer, state: CounselState,practice:()->Unit) {
    var selectedRefs by remember(state.answerModalStamp()) { mutableStateOf<List<String>?>(null) }
    fun resolve(refs: List<String>) = com.shengjidaguai.goutoujunshi.domain.FactSourceResolver.resolve(refs,
        state.currentContact?.id, state.currentContact?.sourceRevision ?: 0,
        state.answerSourceRevision ?: state.currentRequest?.sourceRevision, state.evidence)
    selectedRefs?.let { refs ->
        val source = resolve(refs)
        ContentDialog(stringResource(R.string.answer_source), stringResource(R.string.close), { selectedRefs = null }) {
            if (source.userStatement) {
                BodyText(stringResource(R.string.answer_user_statement))
                BodyText(state.draft.ifBlank { stringResource(R.string.answer_source_unavailable) })
            } else if (source.available) source.lines.forEach { line ->
                BodyText(stringResource(when(line.speaker) {
                    com.shengjidaguai.goutoujunshi.domain.Speaker.ME -> R.string.answer_speaker_me
                    com.shengjidaguai.goutoujunshi.domain.Speaker.THEM -> R.string.answer_speaker_them
                    else -> R.string.answer_speaker_other
                }, line.text))
            } else BodyText(stringResource(R.string.answer_source_unavailable))
        }
    }
    listOf(R.string.answer_alt to answer.alternatives.joinToString("\n") { "${it.label}：${it.reply}" },
        R.string.answer_why to answer.why.joinToString("\n"), R.string.answer_cost to answer.cost,
        R.string.answer_situation to answer.situation).forEach { (title, body) ->
        CollapsibleSection(stringResource(title), null) { BodyText(body.ifBlank { stringResource(R.string.answer_no_detail) }) }
    }
    CollapsibleSection(stringResource(R.string.answer_evidence), null) {
        answer.facts.forEach { fact ->
            val source = resolve(fact.messageRefs)
            EvidenceChip(EvidenceKind.FACT, fact.text, stringResource(when {
                source.userStatement -> R.string.answer_user_statement
                source.available -> R.string.answer_source
                else -> R.string.answer_source_unavailable
            }), if (source.available) ({ selectedRefs = fact.messageRefs }) else null)
        }
        answer.guesses.forEach { guess -> EvidenceChip(EvidenceKind.GUESS, guess.text,
            stringResource(R.string.answer_confidence, stringResource(when(guess.confidence) {
                com.shengjidaguai.goutoujunshi.domain.Confidence.LOW -> R.string.answer_confidence_low
                com.shengjidaguai.goutoujunshi.domain.Confidence.MEDIUM -> R.string.answer_confidence_medium
                com.shengjidaguai.goutoujunshi.domain.Confidence.HIGH -> R.string.answer_confidence_high
            })), null) }
        answer.unknowns.forEach { EvidenceChip(EvidenceKind.UNKNOWN, it, null, null) }
    }
    listOf(R.string.answer_risk to answer.risk, R.string.answer_next to answer.next).forEach { (title, body) ->
        CollapsibleSection(stringResource(title), null) { BodyText(body.ifBlank { stringResource(R.string.answer_no_detail) });if(title==R.string.answer_next) SecondaryButton(stringResource(R.string.contact_practice),practice) }
    }
}
@Composable private fun SceneEditDialog(state: CounselState, save: (RelationType, Goal, String) -> Unit, close: () -> Unit) {
    var relation by remember { mutableStateOf(state.relationType) }
    var goal by remember { mutableStateOf(state.goal) }
    var formality by remember { mutableStateOf(state.formality) }
    ContentDialog(stringResource(R.string.answer_scene_edit), stringResource(R.string.close), close,
        stringResource(R.string.answer_regenerate) to { save(relation, goal, formality) }) {
        ChoiceField(stringResource(R.string.relation_label), stringResource(relationLabel(relation)), RelationType.entries.map { type -> stringResource(relationLabel(type)) to { relation = type } })
        ChoiceField(stringResource(R.string.goal_label), stringResource(goalLabel(goal)), Goal.entries.map { value -> stringResource(goalLabel(value)) to { goal = value } })
        ChoiceField(stringResource(R.string.answer_formality), stringResource(when(formality) { "low" -> R.string.answer_formality_low; "high" -> R.string.answer_formality_high; else -> R.string.answer_formality_medium }),
            listOf("low" to R.string.answer_formality_low, "medium" to R.string.answer_formality_medium, "high" to R.string.answer_formality_high).map { (value, label) -> stringResource(label) to { formality = value } })
    }
}

/** Keep every byte of visible content in order while bounding each Text layout. */
internal fun outboundBodyChunks(value:String):List<String> {
    val chunks=mutableListOf<String>();var offset=0
    while(offset<value.length) {
        val count=minOf(2000,value.codePointCount(offset,value.length))
        val end=value.offsetByCodePoints(offset,count)
        chunks+=value.substring(offset,end);offset=end
    }
    return chunks.ifEmpty {listOf("")}
}
