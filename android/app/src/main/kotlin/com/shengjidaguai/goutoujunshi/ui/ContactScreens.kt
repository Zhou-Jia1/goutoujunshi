package com.shengjidaguai.goutoujunshi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.ContactDetailState
import com.shengjidaguai.goutoujunshi.data.ContactDetails
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.RelationGroup
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** List grouping is presentation only: MEDIATION remains its own domain scene. */
fun contactListGroup(type: RelationType): RelationGroup = when (val group = type.group()) {
    RelationGroup.MEDIATION -> RelationGroup.SOCIAL
    else -> group
}

@Composable fun ContactsContent(contacts: List<Contact>, create: () -> Unit, open: (String) -> Unit, temporary: () -> Unit) {
    var archived by remember { mutableStateOf(false) }
    val visible = contacts.filter { (it.archivedAt != null) == archived }
    LazyColumn(Modifier.fillMaxSize().testTag("contacts-list"), contentPadding = PaddingValues(JunshiTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { JunshiTopBar(stringResource(R.string.nav_contacts)) }
        item { PrimaryButton(stringResource(R.string.create_contact), create) }
        item { ChoiceField(stringResource(R.string.nav_contacts), stringResource(if (archived) R.string.contacts_archived else R.string.contacts_active), listOf(
            stringResource(R.string.contacts_active) to { archived = false }, stringResource(R.string.contacts_archived) to { archived = true })) }
        if (visible.isEmpty()) item { BodyText(stringResource(R.string.no_contacts)) }
        listOf(RelationGroup.WORK to R.string.contacts_work, RelationGroup.INTIMATE to R.string.contacts_intimate,
            RelationGroup.FAMILY to R.string.contacts_family, RelationGroup.SOCIAL to R.string.contacts_social).forEach { (group, title) ->
            val members = visible.filter { contactListGroup(it.relationType) == group }
            if (members.isNotEmpty()) item { BodyText(stringResource(title)) }
            members.forEach { contact -> item(key = contact.id) {
                ContactCard(ContactCardModel(contact.id, contact.alias, stringResource(relationLabel(contact.relationType))), { open(contact.id) })
            } }
        }
        item { SecondaryButton(stringResource(R.string.temporary_title), temporary) }
    }
}

private fun ContactDetails.currentAnswers(): List<Pair<Message, StructuredAnswer>> = messages.filter {
    it.contactId == contact.id && it.role == MessageRole.ASSISTANT && it.status == MessageStatus.COMPLETED
}.mapNotNull { message ->
    val run = runs.firstOrNull { it.contactId == contact.id && it.requestId == message.requestId }
    val request = run?.requestSnapshot
    if (request == null || request.contactId != contact.id || request.sourceRevision != contact.sourceRevision ||
        !ContinuousAnalysis.sourcesRetained(request, contact.id, evidence)) null
    else message.answerJson?.let { raw -> runCatching { Json.decodeFromString(StructuredAnswer.serializer(), raw) }.getOrNull() }?.let { answer ->
        if (answer.safety == SafetyLevel.CRISIS || run.highestSafety == SafetyLevel.CRISIS) null else message to answer
    }
}.sortedBy { it.first.createdAt }

/** Unknown timestamps are retained after dated entries; no inferred action time is stored. */
sealed interface ContactTimelineItem {
    val time: Long?
    data class Imported(val value: ChatImport) : ContactTimelineItem { override val time = value.importedAt.takeIf { it > 0 } }
    data class Analysis(val value: Message) : ContactTimelineItem { override val time = value.createdAt.takeIf { it > 0 } }
    data class Action(val value: ActionPlan) : ContactTimelineItem { override val time: Long? = null }
    data class KeyEvent(val value: Event) : ContactTimelineItem {
        override val time: Long? = if (value.timePrecision == TimePrecision.UNKNOWN) null else value.occurredAt?.let { raw ->
            runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { java.time.LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}
fun contactTimeline(data: ContactDetails): List<ContactTimelineItem> = (
    data.imports.filter { it.contactId == data.contact.id }.map { ContactTimelineItem.Imported(it) } +
    data.messages.filter { it.contactId == data.contact.id && it.role == MessageRole.ASSISTANT }.map { ContactTimelineItem.Analysis(it) } +
    data.events.filter { it.contactId == data.contact.id }.map { ContactTimelineItem.KeyEvent(it) } +
    data.plans.filter { it.contactId == data.contact.id }.map { ContactTimelineItem.Action(it) }
).sortedWith(compareBy<ContactTimelineItem> { it.time == null }.thenByDescending { it.time })

@Composable private fun recordedTime(millis: Long): String = if (millis <= 0) stringResource(R.string.contact_time_unknown)
    else DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

@Composable fun ContactDetailContent(state: ContactDetailState, back: () -> Unit, reload: (String) -> Unit,
    edit: (Contact, Long) -> Unit, delete: (String) -> Unit, undo: () -> Unit, talk: (String) -> Unit, memory: ((String) -> Unit)? = null, practice: ((String)->Unit)?=null) {
    val data = state.data?.takeIf { it.contact.id == state.ownerId }
    val contact = data?.contact
    // A new owner/revision discards every private editor, source selection and confirmation.
    var tab by remember(state.ownerId) { mutableStateOf(0) }
    var modal by remember(state.ownerId, contact?.sourceRevision, data) { mutableStateOf<String?>(null) }
    var source by remember(state.ownerId, contact?.sourceRevision, data) { mutableStateOf<List<ChatLine>?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("contact-detail-list"), contentPadding = PaddingValues(JunshiTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { JunshiTopBar(contact?.alias ?: stringResource(R.string.nav_contacts), back) }
        if (state.loading) item { BodyText(stringResource(R.string.contact_loading)) }
        if (state.failed) item {
            BodyText(stringResource(R.string.contact_load_failed))
            SecondaryButton(stringResource(R.string.contact_reload), { state.ownerId?.let { if (state.deletePending) delete(it) else reload(it) } })
        }
        if (data != null && contact != null) {
            item { BodyText(stringResource(relationLabel(contact.relationType))) }
            if (state.saved) item { BodyText(stringResource(R.string.contact_saved)) }
            if (state.undoAvailable) item {
                BodyText(stringResource(R.string.contact_undo_notice))
                SecondaryButton(stringResource(R.string.contact_undo), undo)
            }
            if (contact.archivedAt != null) item { BodyText(stringResource(R.string.contact_archived_notice)) }
            item { ChoiceField(stringResource(R.string.contact_profile), stringResource(listOf(R.string.contact_overview, R.string.contact_timeline, R.string.contact_profile, R.string.contact_memory)[tab]),
                listOf(R.string.contact_overview, R.string.contact_timeline, R.string.contact_profile, R.string.contact_memory).mapIndexed { index, label -> stringResource(label) to { tab = index } }) }
            when (tab) {
                0 -> {
                    item { BodyText(stringResource(R.string.contact_advice)); BodyText(data.currentAnswers().lastOrNull()?.second?.verdict?.takeIf { it.isNotBlank() } ?: stringResource(R.string.contact_no_advice)) }
                    item { BodyText(stringResource(R.string.contact_followup)) }
                    val plans = data.plans.filter { it.contactId == contact.id }
                    if (plans.isEmpty()) item { BodyText(stringResource(R.string.contact_no_followup)) }
                    plans.forEach { plan -> item { PlanRecord(plan) } }
                }
                1 -> {
                    val timeline = contactTimeline(data)
                    if (timeline.isEmpty()) item { BodyText(stringResource(R.string.contact_empty_timeline)) }
                    timeline.forEach { entry -> item { when (entry) {
                        is ContactTimelineItem.Imported -> {
                            val batch = entry.value
                            BodyText(stringResource(R.string.contact_import)); BodyText(recordedTime(batch.importedAt))
                            val lines = data.evidence.firstOrNull { it.id == batch.id }?.lines.orEmpty()
                            BodyText(stringResource(R.string.contact_import_count, lines.size))
                            SecondaryButton(stringResource(R.string.contact_source), {
                                source = FactSourceResolver.resolve(lines.map { it.id }, contact.id, contact.sourceRevision, contact.sourceRevision, data.evidence).lines
                            })
                        }
                        is ContactTimelineItem.Analysis -> {
                            val message = entry.value
                            BodyText(stringResource(R.string.contact_analysis)); BodyText(recordedTime(message.createdAt))
                            val answer = data.currentAnswers().firstOrNull { it.first.id == message.id }?.second
                            if (answer == null) BodyText(stringResource(R.string.contact_source_unavailable))
                            else { BodyText(stringResource(R.string.contact_advice)); BodyText(answer.verdict) }
                        }
                        is ContactTimelineItem.Action -> {
                            BodyText(stringResource(R.string.contact_action)); BodyText(stringResource(R.string.contact_time_unknown)); PlanRecord(entry.value)
                        }
                        is ContactTimelineItem.KeyEvent -> {
                            val event = entry.value
                            BodyText(stringResource(R.string.contact_event))
                            BodyText(event.occurredAt?.takeIf { event.timePrecision != TimePrecision.UNKNOWN } ?: stringResource(R.string.contact_time_unknown))
                            BodyText(event.summary)
                            BodyText(stringResource(R.string.contact_source_unavailable))
                        }
                    } } }
                }
                2 -> item {
                    BodyText(stringResource(R.string.contact_notes_local))
                    BodyText(contact.notes?.takeIf { it.isNotBlank() } ?: stringResource(R.string.contact_unknown_notes))
                }
                3 -> {
                    item { if(memory==null) BodyText(stringResource(R.string.contact_memory_pending)) else SecondaryButton(stringResource(R.string.memory_manage),{ memory(contact.id) }) }
                    val memories = data.memories.filter { it.contactId == contact.id }
                    if (memories.isEmpty()) item { BodyText(stringResource(R.string.contact_no_memory)) }
                    memories.forEach { memory -> item {
                        BodyText(stringResource(R.string.contact_memory_value, memory.field, memory.value))
                        BodyText(stringResource(when(memory.sourceType) {
                            MemorySource.USER_EXPLICIT -> R.string.contact_memory_user
                            MemorySource.USER_REPORT -> R.string.contact_memory_report
                            MemorySource.ASSISTANT_INFERENCE -> R.string.contact_memory_model
                            else -> R.string.contact_memory_tool
                        }))
                        BodyText(stringResource(R.string.answer_confidence, stringResource(when(memory.confidence) {
                            Confidence.HIGH -> R.string.answer_confidence_high
                            Confidence.MEDIUM -> R.string.answer_confidence_medium
                            Confidence.LOW -> R.string.answer_confidence_low
                        })))
                        if (!memory.active) BodyText(stringResource(R.string.contact_inactive))
                    } }
                }
            }
            item { PrimaryButton(stringResource(R.string.contact_talk), { talk(contact.id) }, enabled = contact.archivedAt == null) }
            item { SecondaryButton(stringResource(R.string.contact_practice), { practice?.invoke(contact.id) }) }
            item { SecondaryButton(stringResource(R.string.contact_edit), { modal = "edit" }) }
            item { SecondaryButton(stringResource(if (contact.archivedAt == null) R.string.contact_archive else R.string.contact_restore), { modal = "archive" }) }
            item { SecondaryButton(stringResource(R.string.delete_contact), { modal = "delete" }) }
        }
    }
    if (contact != null) when (modal) {
        "delete" -> DangerConfirmDialog(stringResource(R.string.delete_contact), stringResource(R.string.contact_delete_consequence), stringResource(R.string.confirm_delete), { modal = null; delete(contact.id) }, { modal = null })
        "archive" -> DangerConfirmDialog(stringResource(if (contact.archivedAt == null) R.string.contact_archive else R.string.contact_restore), stringResource(if (contact.archivedAt == null) R.string.contact_archive_consequence else R.string.contact_restore_consequence), stringResource(R.string.contact_confirm), {
            modal = null; edit(contact.copy(archivedAt = if (contact.archivedAt == null) System.currentTimeMillis() else null), contact.sourceRevision)
        }, { modal = null })
        "edit" -> ContactEditDialog(contact, { modal = null }, { next -> modal = null; edit(next, contact.sourceRevision) })
    }
    source?.let { lines -> ContentDialog(stringResource(R.string.contact_source), stringResource(R.string.close), { source = null }) {
        if (lines.isEmpty()) BodyText(stringResource(R.string.contact_source_unavailable))
        lines.forEach { BodyText(it.text) }
    } }
}

@Composable private fun PlanRecord(plan: ActionPlan) {
    BodyText(stringResource(when { plan.status == PlanStatus.SKIPPED -> R.string.contact_skipped
        plan.status == PlanStatus.PENDING -> R.string.contact_pending
        plan.wasEdited -> R.string.contact_edited
        else -> R.string.contact_sent }))
    BodyText(plan.chosenReply)
}

@Composable private fun ContactEditDialog(contact: Contact, close: () -> Unit, save: (Contact) -> Unit) {
    var alias by remember { mutableStateOf(contact.alias) }
    var notes by remember { mutableStateOf(contact.notes.orEmpty()) }
    var relation by remember { mutableStateOf(contact.relationType) }
    ContentDialog(stringResource(R.string.contact_edit), stringResource(R.string.close), close,
        stringResource(R.string.contact_save) to { if (alias.isNotBlank() && alias.length <= 80 && notes.length <= 4000) save(contact.copy(alias = alias, notes = notes, relationType = relation)) }) {
        FormTextField(alias, { alias = it.take(80) }, stringResource(R.string.contact_alias_hint))
        ChoiceField(stringResource(R.string.relation_label), stringResource(relationLabel(relation)), RelationType.entries.map { type -> stringResource(relationLabel(type)) to { relation = type } })
        BodyText(stringResource(R.string.contact_notes_local))
        FormTextField(notes, { notes = it.take(4000) }, stringResource(R.string.contact_notes))
    }
}
