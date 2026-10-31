package com.shengjidaguai.goutoujunshi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.Goal
import com.shengjidaguai.goutoujunshi.domain.RelationType
import com.shengjidaguai.goutoujunshi.domain.SectionId
import com.shengjidaguai.goutoujunshi.domain.MessageRole
import com.shengjidaguai.goutoujunshi.domain.group
import com.shengjidaguai.goutoujunshi.domain.Tone as DomainTone

/** Event boundary for rendering and exercising the home screen without opening storage. */
data class CounselUiActions(
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
    val importFile: () -> Unit = {},
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

@Composable fun CounselScreen(viewModel: CounselViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importing by viewModel.imports.state.collectAsStateWithLifecycle()
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFile) }
    CounselContent(state, CounselUiActions(
        initialize = viewModel::initialize, editDraft = viewModel::editDraft, pasteText = { text, _ -> viewModel.importPaste(text) },
        selectContact = viewModel::selectContact, createContact = viewModel::createContact, deleteContact = viewModel::deleteContact,
        setRelation = viewModel::setRelation, setGoal = viewModel::setGoal, setEmotion = viewModel::setEmotion,
        setStyleBias = viewModel::setStyleBias, setTone = viewModel::setTone, preparePreview = viewModel::preparePreview,
        analyze = viewModel::analyze, retry = viewModel::retry, stop = viewModel::stop,
        newTemporary = viewModel::newTemporary, clearAll = viewModel::clearAll,
        importFile = { files.launch(arrayOf("text/plain", "application/json")) },
    ), importing != null)
}

@Composable fun CounselContent(state: CounselState, actions: CounselUiActions, importing: Boolean = false) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var createContact by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    var previewOpen by remember { mutableStateOf(false) }
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
    val canAnalyze = !importing && !state.loading && state.conversation != null && !state.streaming
    fun paste() {
        val text = clipboard.read()
        if (text.isNullOrBlank()) note = R.string.clipboard_empty else {
            origin?.let { actions.pasteText(text, DraftEditStamp("import", 0, it)) }
        }
    }
    fun copy(text: String) {
        if (text.isBlank()) return
        clipboard.write(text)
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        note = R.string.copied
    }
    LazyColumn(Modifier.fillMaxSize().testTag("counsel-list"), contentPadding = PaddingValues(JunshiTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { JunshiTopBar(stringResource(R.string.nav_counsel)) }
        item {
            ContactSwitcher(ContactChipModel(state.currentContact?.id, state.currentContact?.alias ?: stringResource(R.string.temporary_title)),
                listOf(ContactChipModel(null, stringResource(R.string.temporary_title))) + state.contacts.map { ContactChipModel(it.id, it.alias) },
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
            UploadHeroButton({ note = R.string.import_ocr_pending }, actions.importFile, ::paste)
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
        if (state.streaming) item { ThinkingIndicator(ThinkingStage.PLANNING, false) }
        val answer = state.answer
        val partialReply = state.partial[SectionId.REPLY]?.split("+++")?.filter { it.isNotBlank() }.orEmpty()
        if (answer != null || partialReply.isNotEmpty()) item {
            JunshiColumn(Modifier.testTag("counsel-answer")) {
                if (state.answerDemo) DemoBadge()
                val verdict = answer?.verdict ?: state.partial[SectionId.VERDICT].orEmpty()
                if (verdict.isNotBlank()) VerdictLine(verdict)
                val replies = answer?.reply ?: partialReply
                ReplySuggestionCard(replies, state.streaming, (state.emotion ?: 0) >= 8, null,
                    { index -> replies.getOrNull(index)?.let(::copy) }, { copy(replies.joinToString("\n")) })
                if (!answer?.freeText.isNullOrBlank()) BodyText(answer!!.freeText)
                if (answer != null) CollapsibleSection(stringResource(R.string.answer_details), null) {
                    BodyText(listOf(answer.empathy, answer.why.joinToString("\n"), answer.cost, answer.next).filter { it.isNotBlank() }.joinToString("\n\n"))
                }
            }
        }
        state.error?.let { error -> item {
            ErrorState(UiError(stringResource(errorLabel(error.code))), if (error.retryable) actions.retry else null, null)
        } }
        item { SecondaryButton(stringResource(R.string.preview_request), {
            if (canAnalyze && origin != null) { flushDisplayedDraft(); actions.preparePreview(origin); previewOpen = true }
        }) }
        item {
            Box(Modifier.testTag("counsel-composer")) {
                Composer(editor.text, ::editDraft, if (state.evidence.isEmpty()) emptyList() else listOf(AttachmentModel("imports", stringResource(R.string.import_ready, state.evidence.sumOf { it.lines.size }))), state.emotion, actions.setEmotion, state.streaming,
                    { source -> if (source == AttachSource.PASTE) paste() else actions.importFile() },
                    { if (canAnalyze && origin != null) { flushDisplayedDraft(); if (!state.demo) { actions.preparePreview(origin); previewOpen = true } else actions.analyze(origin) } }, actions.stop)
            }
        }
    }
    if (createContact) CreateContactDialog(actions.createContact) { createContact = false }
    note?.let { message -> ContentDialog(stringResource(R.string.app_name), stringResource(R.string.close), { note = null }) { BodyText(stringResource(message)) } }
    if (previewOpen) ContentDialog(stringResource(R.string.preview_request), stringResource(R.string.close), { previewOpen = false },
        if (!state.demo && state.outboundBody != null && origin != null) stringResource(R.string.live_send) to { actions.analyze(origin); previewOpen = false } else null) {
        BodyText(stringResource(if (state.demo) R.string.preview_demo_notice else R.string.live_scope))
        if (!state.demo) BodyText(runCatching { state.modelSettings.saved.connection().safeDestination() }.getOrDefault(""))
        state.preview?.let { preview -> BodyText(state.outboundBody ?: (preview.system + "\n\n" + preview.user)) }
            ?: BodyText(stringResource(if (state.error == null) R.string.preparing_preview else errorLabel(state.error!!.code)))
    }
}

@Composable fun ContactsScreen(viewModel: CounselViewModel, onOpen: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(JunshiTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { JunshiTopBar(stringResource(R.string.nav_contacts)) }
        item { PrimaryButton(stringResource(R.string.create_contact), { creating = true }) }
        if (state.contacts.isEmpty()) item { EmptyState(Illustration.SLEEPING, stringResource(R.string.no_contacts), stringResource(R.string.contact_alias_hint)) }
        state.contacts.forEach { contact -> item(key = contact.id) {
            ContactCard(ContactCardModel(contact.id, contact.alias, stringResource(relationLabel(contact.relationType))), { viewModel.selectContact(contact.id); onOpen() })
            SecondaryButton(stringResource(R.string.delete_contact), { deleting = contact.id })
        } }
        item { SecondaryButton(stringResource(R.string.temporary_title), { viewModel.newTemporary(); onOpen() }) }
    }
    if (creating) CreateContactDialog(viewModel) { creating = false }
    deleting?.let { id -> DangerConfirmDialog(stringResource(R.string.delete_contact), stringResource(R.string.delete_contact_consequence),
        stringResource(R.string.confirm_delete), { viewModel.deleteContact(id); deleting = null }, { deleting = null }) }
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
    ModelSettingsContent(state, viewModel::saveModelSettings, viewModel::testModelConnection,
        viewModel::cancelModelTest, viewModel::clearAll, viewModel::initialize)
}

@Composable fun ModelSettingsContent(state: CounselState,
    onSave: (SavedModelSettings, String?, Boolean) -> Unit, onTest: () -> Unit,
    onCancel: () -> Unit, onClear: () -> Unit, onInitialize: () -> Unit = {}) {
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
        item { SecondaryButton(stringResource(R.string.clear_all), { confirmClear = true }) }
        item { SecondaryButton(stringResource(R.string.initialize_store), onInitialize) }
    }
    if (confirmClear) DangerConfirmDialog(stringResource(R.string.clear_all), stringResource(R.string.clear_all_consequence),
        stringResource(R.string.confirm_clear), {
            key.edit { replace(0, length, "") }
            live = false; base = ModelPresets.openAi.baseUrl; model = ModelPresets.openAi.model; label = ModelPresets.openAi.providerLabel
            onClear(); confirmClear = false
        }, { confirmClear = false })
}

fun errorLabel(code: String): Int = when (code) {
    "SETTINGS_UNAVAILABLE" -> R.string.model_error_settings
    "CONFIG_INVALID" -> R.string.model_error_config
    "KEY_MISSING", "KEY_INVALID" -> R.string.model_error_key
    "HTTP_401", "HTTP_403" -> R.string.model_error_auth
    "HTTP_429" -> R.string.model_error_limit
    "HTTP_500", "HTTP_502", "HTTP_503", "HTTP_504" -> R.string.model_error_server
    "FIRST_PACKET_TIMEOUT", "STREAM_TIMEOUT" -> R.string.model_error_timeout
    "NETWORK", "STREAM_INTERRUPTED" -> R.string.model_error_network
    "NOT_SSE", "INVALID_JSON", "EMPTY_RESPONSE", "PROVIDER_REJECTED", "PROVIDER_ERROR" -> R.string.model_error_protocol
    "PREVIEW_REQUIRED" -> R.string.model_error_preview
    "CONTACT_ALIAS_REQUIRED" -> R.string.error_alias
    "INPUT_REQUIRED" -> R.string.error_input
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
