package com.shengjidaguai.goutoujunshi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.data.WriteStamp
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class MemoryModalGate {
    private var next=0L
    var current by mutableStateOf<Long?>(null)
        private set
    fun issue(): Long = (++next).also { current=it }
    fun revoke() { current=null }
    fun accepts(token: Long?)=token!=null && current==token
}
internal data class MemoryEditorSubmission(val value: String,val group: MemoryScope,val operation: String)
/** One modal owns one observable RAM buffer; input callbacks never read another modal's buffer. */
internal class MemoryEditorBuffer(val token: Long,val existing: MemoryEntry? = null) {
    val textState=TextFieldState(existing?.value.orEmpty())
    val value: String get()=textState.text.toString()
    var group by mutableStateOf(existing?.scope)
        private set
    var submitted by mutableStateOf<MemoryEditorSubmission?>(null)
        private set
    var invalidValue by mutableStateOf(false)
        private set
    private val operation=java.util.UUID.randomUUID().toString()
    fun replace(text: String) { if(submitted==null) textState.edit { replace(0,length,text) } }
    fun select(scope: MemoryScope) { if(submitted==null) group=scope }
    fun freeze(): MemoryEditorSubmission? {
        submitted?.let { return it }
        val scope=group ?: return null
        val validated=try { MemoryPolicy.validateValue(value) } catch(_: MemoryWriteRejected) {
            invalidValue=true
            return null
        }
        invalidValue=false
        return MemoryEditorSubmission(validated,scope,operation).also { submitted=it }
    }
}
private data class MemoryDanger(val token: Long,val receipt: MemoryUiReceipt,val withdraw: Boolean)

@Composable fun MemoryScreen(vm: CounselViewModel, owner: String?, back: () -> Unit) {
    val state by vm.memoryPage.collectAsStateWithLifecycle()
    val counsel by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(owner) { vm.openMemory(owner); onDispose { vm.closeMemory() } }
    if(!counsel.lockState.canDisplaySensitiveContent) return
    MemoryContent(if(state.ownerId==owner) state else MemoryUiState(ownerId=owner,loading=true), back, vm::setMemoryEnabled, vm::editMemory, vm::deleteMemory, vm::markMemoryWrong, vm::undoMemory, vm::clearMemories,add=vm::addMemory)
}

@Composable fun MemoryContent(state: MemoryUiState, back: () -> Unit,
    consent: (Boolean,Boolean,MemoryUiReceipt) -> Unit, edit: (String,MemoryEntry,MemoryUiReceipt,String) -> Unit,
    delete: (String,String,MemoryUiReceipt) -> Unit, wrong: (String,String,MemoryUiReceipt) -> Unit,
    undo: (String,String,MemoryUiReceipt) -> Unit, clear: (MemoryUiReceipt) -> Unit, add: (String,MemoryScope,String,MemoryUiReceipt,String) -> Unit = { _,_,_,_,_-> }) {
    val receipt=state.receipt
    val gate=remember(state.ownerId,receipt?.identity) { MemoryModalGate() }
    DisposableEffect(gate) { onDispose { gate.revoke() } }
    var editor by remember(state.ownerId,receipt?.identity) { mutableStateOf<MemoryEditorBuffer?>(null) }
    var danger by remember(state.ownerId,receipt?.identity) { mutableStateOf<MemoryDanger?>(null) }
    val owner=state.ownerId
    if(state.loading) { Column { JunshiTopBar(stringResource(R.string.memory_title));BodyText(stringResource(R.string.memory_loading));SecondaryButton(stringResource(R.string.close),back) };return }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(JunshiTheme.spacing.l),verticalArrangement=Arrangement.spacedBy(JunshiTheme.spacing.m)) {
        item { JunshiTopBar(stringResource(R.string.memory_title));SecondaryButton(stringResource(R.string.close),back) }
        item { BodyText(stringResource(R.string.memory_disclosure)) }
        item { SecondaryButton(stringResource(if(!state.consent.enabled) R.string.memory_enable else if(state.consent.paused) R.string.memory_resume else R.string.memory_pause),{ receipt?.let { consent(true,state.consent.enabled && !state.consent.paused,it) } }) }
        if(state.consent.enabled) item { SecondaryButton(stringResource(R.string.memory_withdraw),{ if(receipt!=null && gate.current==null) danger=MemoryDanger(gate.issue(),receipt,true) }) }
        if(state.consent.paused) item { BodyText(stringResource(R.string.memory_edit_paused)) }
        if(state.failed) item { BodyText(stringResource(R.string.memory_failed)) }
        if(owner!=null && receipt!=null) {
            if(state.consent.enabled && !state.consent.paused) item { SecondaryButton(stringResource(R.string.memory_add),{
                if(!state.pendingOperation && gate.current==null) editor=MemoryEditorBuffer(gate.issue())
            }) }
            state.notice?.let { notice -> item { MemoryChangeNotice(stringResource(if(notice.inference) R.string.memory_saved else R.string.memory_saved_user,notice.summary),{ undo(owner,notice.operationId,receipt) }) } }
            state.undoOperationId?.let { id -> item { SecondaryButton(stringResource(R.string.memory_undo_latest),{ undo(owner,id,receipt) }) } }
            MemoryScope.entries.forEach { group ->
                item { BodyText(stringResource(memoryGroupLabel(group))) }
                val entries=state.entries.filter { it.scope==group }
                if(entries.isEmpty()) item { BodyText(stringResource(R.string.contact_no_memory)) }
                entries.forEach { entry -> item(key=entry.id) {
                    val source=stringResource(when(entry.sourceType) { MemorySource.USER_EXPLICIT->R.string.contact_memory_user;MemorySource.USER_REPORT->R.string.contact_memory_report;MemorySource.ASSISTANT_INFERENCE->R.string.contact_memory_model;else->R.string.contact_memory_tool })
                    val verified=entry.sourceType!=MemorySource.ASSISTANT_INFERENCE || entry.id in state.snapshot.verifiedInferenceIds
                    val provenance=if(verified) source else source+stringResource(R.string.memory_unverified)
                    val confidence=stringResource(when(entry.confidence) { Confidence.HIGH->R.string.answer_confidence_high;Confidence.MEDIUM->R.string.answer_confidence_medium;Confidence.LOW->R.string.answer_confidence_low })
                    val date=entry.updatedAt.takeIf { it>0 }?.let { DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: stringResource(R.string.contact_time_unknown)
                    MemoryEntryRow(MemoryRowModel(entry.id,entry.value,stringResource(R.string.memory_source_date,provenance,confidence,date)),{
                        if(state.consent.enabled && !state.consent.paused && verified && !state.pendingOperation && gate.current==null) editor=MemoryEditorBuffer(gate.issue(),entry)
                    },{ delete(owner,entry.id,receipt) },{ wrong(owner,entry.id,receipt) })
                } }
            }
        }
        item { SecondaryButton(stringResource(R.string.memory_clear),{ if(receipt!=null && gate.current==null) danger=MemoryDanger(gate.issue(),receipt,false) }) }
    }
    editor?.let { buffer ->
        ContentDialog(stringResource(if(buffer.existing==null) R.string.memory_add else R.string.memory_edit),stringResource(R.string.close),{
            if(gate.accepts(buffer.token)) { gate.revoke();editor=null }
        },stringResource(if(buffer.existing==null) R.string.memory_confirm_user else R.string.memory_save) to confirm@{
            if(!gate.accepts(buffer.token) || state.pendingOperation || owner==null || receipt==null) return@confirm
            val submission=buffer.freeze() ?: return@confirm
            val existing=buffer.existing
            if(existing==null) add(owner,submission.group,submission.value,receipt,submission.operation)
            else edit(owner,existing.copy(value=submission.value),receipt,submission.operation)
        }) {
            if(state.failed) BodyText(stringResource(R.string.memory_editor_retry))
            if(buffer.invalidValue) BodyText(stringResource(R.string.memory_editor_invalid,MemoryPolicy.MAX_VALUE_CODEPOINTS))
            if(buffer.existing==null) {
                BodyText(stringResource(R.string.memory_add_disclosure))
                ChoiceField(stringResource(R.string.memory_group_choose),buffer.group?.let { stringResource(memoryGroupLabel(it)) } ?: stringResource(R.string.memory_group_choose),MemoryScope.entries.map { group ->
                    stringResource(memoryGroupLabel(group)) to { if(gate.accepts(buffer.token)) buffer.select(group) }
                })
            }
            ReplyEditField(buffer.textState,stringResource(R.string.memory_edit),readOnly=buffer.submitted!=null)
        }
    }
    danger?.let { shown ->
        val count=shown.receipt.clearAuthority?.memoryCount ?: state.totalMemories
        DangerConfirmDialog(stringResource(if(shown.withdraw) R.string.memory_withdraw else R.string.memory_clear),
            stringResource(if(shown.withdraw) R.string.memory_withdraw_consequence else R.string.memory_clear_consequence,count),
            if(shown.withdraw) stringResource(R.string.memory_withdraw) else stringResource(R.string.memory_clear_confirm,count),{
                if(gate.accepts(shown.token)) { gate.revoke();danger=null;if(shown.withdraw) consent(false,false,shown.receipt) else clear(shown.receipt) }
            },{ if(gate.accepts(shown.token)) { gate.revoke();danger=null } })
    }
}

@Composable fun MemoryInline(state: MemoryUiState, accept: (MemoryUiReceipt) -> Unit, decline: (MemoryUiReceipt) -> Unit, resume: (MemoryUiReceipt) -> Unit, undo: () -> Unit) {
    if(state.consent.paused) SecondaryButton(stringResource(R.string.memory_paused),{ state.receipt?.let(resume) })
    if(state.approvalFinishing) {
        BodyText(stringResource(if(state.failed) R.string.memory_saved_read_failed else R.string.memory_saved_read_pending))
        if(!state.pendingOperation) SecondaryButton(stringResource(R.string.memory_retry_read),{ state.receipt?.let(accept) })
    } else if(state.failed) BodyText(stringResource(if(state.capacityFull) R.string.memory_capacity_full else R.string.memory_failed))
    state.consentProposal?.takeUnless { state.approvalFinishing }?.let { proposal -> ConsentCard(stringResource(R.string.memory_title),stringResource(R.string.memory_proposal_disclosure,proposal.value),stringResource(R.string.memory_accept),stringResource(R.string.memory_decline),{ state.receipt?.let(accept) },{ state.receipt?.let(decline) }) }
    state.notice?.let { MemoryChangeNotice(stringResource(if(it.inference) R.string.memory_saved else R.string.memory_saved_user,it.summary),undo) }
}

private fun memoryGroupLabel(group: MemoryScope): Int = when(group) {
    MemoryScope.USER->R.string.memory_group_user
    MemoryScope.OBJECT->R.string.memory_group_other
    MemoryScope.RELATIONSHIP->R.string.memory_group_relationship
    MemoryScope.EVENT->R.string.memory_group_event
    MemoryScope.HYPOTHESIS->R.string.memory_group_guess
}
