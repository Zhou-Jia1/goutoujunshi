package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.importing.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

data class StoredCounsel(val conversation: Conversation, val draft: String, val messages: List<Message>, val evidence: List<EvidenceBlock> = emptyList(), val runs: List<AnalysisRun> = emptyList(), val summaries: List<TimelineSummary> = emptyList(), val plans: List<ActionPlan> = emptyList(),val sourceBlocked:Boolean=false,val capturePreferred:Boolean=false)

/** Share this assembler with the provider so preview and playback consume the same snapshot. */
class FrozenPromptAssembler(private val delegate: PromptAssembler) : PromptAssembler {
    private var request: AnalysisRequest? = null
    private var prepared: PreparedPrompt? = null
    @Synchronized override fun prepare(request: AnalysisRequest): PreparedPrompt {
        if (this.request == request) return checkNotNull(prepared)
        val result = delegate.prepare(request)
        this.request = request
        prepared = result
        return result
    }
    @Synchronized fun clear() { request = null; prepared = null }
}

/** Injectable persistence boundary; temporary content must never be supplied to this adapter. */
interface CounselPersistence {
    suspend fun styleContext(owner:String,group:RelationGroup):StyleOutboundContext = StyleOutboundContext()
    suspend fun styleSnapshot():StyleManagementSnapshot { error("Style unavailable") }
    suspend fun styleEdit(group:RelationGroup,text:String,stamp:WriteStamp,current:()->Boolean) { error("Style unavailable") }
    suspend fun styleGenerated(group:RelationGroup,text:String,stamp:WriteStamp,current:()->Boolean) = styleEdit(group,text,stamp,current)
    suspend fun styleEditSample(id:String,text:String,stamp:WriteStamp,current:()->Boolean) {error("Style unavailable")}
    suspend fun styleDelete(id:String,stamp:WriteStamp) { error("Style unavailable") }
    suspend fun styleReset(group:RelationGroup,stamp:WriteStamp) { error("Style unavailable") }
    suspend fun styleQuiz(answers:List<String>,stamp:WriteStamp,current:()->Boolean) { error("Style unavailable") }
    suspend fun memorySnapshot(owner: String?): MemorySnapshot = MemorySnapshot()
    suspend fun memoryContextSnapshot(owner:String?):MemorySnapshot = MemorySnapshot()
    suspend fun memoryConsent(enabled: Boolean, paused: Boolean, stamp: WriteStamp, clearAuthority: MemoryClearAuthority?, onAuthorized: () -> Unit) { error("Memory unavailable") }
    suspend fun memoryDecline(owner: String, stamp: WriteStamp, until: Long, onAuthorized: () -> Unit) { error("Memory unavailable") }
    suspend fun memoryApprove(owner: String, proposal: MemoryProposal, stamp: WriteStamp, operationId: String, onAuthorized: () -> Unit): MemoryOperation { error("Memory unavailable") }
    suspend fun memoryProposal(owner: String, proposal: MemoryProposal, stamp: WriteStamp): MemoryOperation { error("Memory unavailable") }
    suspend fun memoryEdit(owner: String, entry: MemoryEntry, stamp: WriteStamp, operationId: String): MemoryOperation { error("Memory unavailable") }
    suspend fun memoryDelete(owner: String, id: String, stamp: WriteStamp, wrong: Boolean) { error("Memory unavailable") }
    suspend fun memoryUndo(owner: String, id: String, stamp: WriteStamp) { error("Memory unavailable") }
    suspend fun clearMemories(authority: MemoryClearAuthority, onAuthorized: () -> Unit) { error("Memory unavailable") }
    suspend fun replaceCaptured(change:CapturedImportReplacement,current:()->Boolean):Contact { error("Capture replacement unavailable") }
    suspend fun saveCaptured(contact:Contact,imported:ChatImport,lines:List<ChatLine>,current:()->Boolean):LiveCaptureReceipt {error("Live capture unavailable")}
    suspend fun saveReviewedCaptured(contact:Contact,imported:ChatImport,lines:List<ChatLine>,frames:List<List<ChatLine>>,current:()->Boolean):LiveCaptureReceipt = saveCaptured(contact,imported,lines,current)
    suspend fun liveCaptureProvenance(owner:String,lineage:String):LiveCaptureProvenance? = null
    suspend fun saveFloatingIdentity(binding: FloatingIdentityBinding) { error("Floating identity unavailable") }
    suspend fun initialize()
    suspend fun contacts(): List<Contact>
    suspend fun createContact(contact: Contact)
    suspend fun editContact(next: Contact, expectedSource: Long): ContactEditReceipt { error("Contact editing unavailable") }
    suspend fun contactDetails(contact: Contact): ContactDetails { error("Contact details unavailable") }
    suspend fun deleteContact(id: String)
    suspend fun loadOrCreate(contact: Contact): StoredCounsel
    suspend fun update(contact: Contact, previous: Conversation, next: Conversation, draft: String)
    suspend fun saveMessage(contact: Contact, conversation: Conversation, message: Message)
    suspend fun discardMessage(contact: Contact, message: Message)
    suspend fun saveImport(contact: Contact, imported: ChatImport, lines: List<ChatLine>): String { error("Import persistence unavailable") }
    suspend fun discardSummary(summary: TimelineSummary) { error("Summary persistence unavailable") }
    suspend fun saveSummary(summary: TimelineSummary) { error("Summary persistence unavailable") }
    suspend fun saveRun(contact: Contact, run: AnalysisRun) {}
    suspend fun saveFeedback(contact: Contact, plan: ActionPlan, privacyRevision: Long) { error("Feedback persistence unavailable") }
    suspend fun clearAll(): Boolean
    suspend fun redactionProfile(contactId: String): RedactionProfile = RedactionProfile()
    suspend fun saveRedactionProfile(contactId: String, expected: Long, profile: RedactionProfile) { error("Privacy persistence unavailable") }
}

/** All calls are serialized by CounselController, including deletion and full wipe. */
class EncryptedCounselPersistence(
    private val store: EncryptedDataStore,
    private val temporary: TemporarySessionStore,
) : CounselPersistence {
    private var session: DataSession? = null
    private fun contactsRepo() = ContactRepository(checkNotNull(session))
    private fun conversationsRepo() = ConversationRepository(checkNotNull(session))
    private fun memoryRepo() = MemoryRepository(checkNotNull(session))
    private fun styleRepo()=StyleRepository(checkNotNull(session))
    override suspend fun styleContext(owner:String,group:RelationGroup)=styleRepo().context(owner,group)
    override suspend fun styleSnapshot()=styleRepo().snapshot()
    override suspend fun styleEdit(group:RelationGroup,text:String,stamp:WriteStamp,current:()->Boolean) {styleRepo().editDescription(group,text,stamp,current=current)}
    override suspend fun styleGenerated(group:RelationGroup,text:String,stamp:WriteStamp,current:()->Boolean) {styleRepo().editDescription(group,text,stamp,generated=true,current=current)}
    override suspend fun styleEditSample(id:String,text:String,stamp:WriteStamp,current:()->Boolean) {styleRepo().editSample(id,text,stamp,current)}
    override suspend fun styleDelete(id:String,stamp:WriteStamp) {styleRepo().removeSample(id,stamp)}
    override suspend fun styleReset(group:RelationGroup,stamp:WriteStamp) {styleRepo().reset(group,stamp)}
    override suspend fun styleQuiz(answers:List<String>,stamp:WriteStamp,current:()->Boolean) {styleRepo().saveQuiz(answers,stamp,current)}
    override suspend fun memorySnapshot(owner: String?) = memoryRepo().snapshot(owner)
    override suspend fun memoryContextSnapshot(owner:String?) = memoryRepo().contextSnapshot(owner)
    override suspend fun memoryConsent(enabled: Boolean, paused: Boolean, stamp: WriteStamp, clearAuthority: MemoryClearAuthority?, onAuthorized: () -> Unit) { ConsentRepository(checkNotNull(session)).update(enabled, paused, expected=stamp,expectedClear=if(!enabled) requireNotNull(clearAuthority) else null,onAuthorized=onAuthorized) }
    override suspend fun memoryDecline(owner: String, stamp: WriteStamp, until: Long, onAuthorized: () -> Unit) { ConsentRepository(checkNotNull(session)).recordDecline(owner,stamp,until,onAuthorized) }
    override suspend fun memoryApprove(owner: String, proposal: MemoryProposal, stamp: WriteStamp, operationId: String, onAuthorized: () -> Unit): MemoryOperation =
        memoryRepo().approveProposal(owner,proposal,stamp,operationId,onAuthorized)
    override suspend fun memoryProposal(owner: String, proposal: MemoryProposal, stamp: WriteStamp) = memoryRepo().applyProposal(owner, proposal, stamp)
    override suspend fun memoryEdit(owner: String, entry: MemoryEntry, stamp: WriteStamp, operationId: String) = memoryRepo().apply(owner, entry, stamp,operationId=operationId)
    override suspend fun memoryDelete(owner: String, id: String, stamp: WriteStamp, wrong: Boolean) { if(wrong) memoryRepo().markWrong(owner,id,stamp) else memoryRepo().delete(owner,id,stamp) }
    override suspend fun memoryUndo(owner: String, id: String, stamp: WriteStamp) { memoryRepo().undoLatest(owner,stamp,id) }
    override suspend fun clearMemories(authority: MemoryClearAuthority, onAuthorized: () -> Unit) { memoryRepo().clear(authority,onAuthorized) }
    override suspend fun replaceCaptured(change:CapturedImportReplacement,current:()->Boolean)=CapturedImportReplacementRepository(checkNotNull(session)).replace(change,current)
    override suspend fun saveCaptured(contact:Contact,imported:ChatImport,lines:List<ChatLine>,current:()->Boolean) = LiveCaptureRepository(checkNotNull(session)).save(imported,lines,contact.sourceRevision,current)
    override suspend fun saveReviewedCaptured(contact:Contact,imported:ChatImport,lines:List<ChatLine>,frames:List<List<ChatLine>>,current:()->Boolean) = LiveCaptureRepository(checkNotNull(session)).saveReviewed(imported,lines,contact.sourceRevision,frames,current)
    override suspend fun liveCaptureProvenance(owner:String,lineage:String) = LiveCaptureRepository(checkNotNull(session)).provenance(owner,lineage)
    override suspend fun saveFloatingIdentity(binding: FloatingIdentityBinding) { FloatingIdentityRepository(checkNotNull(session)).save(binding) }
    override suspend fun initialize() { session = store.open() }
    override suspend fun contacts() = contactsRepo().list()
    override suspend fun createContact(contact: Contact) { contactsRepo().save(contact) }
    override suspend fun editContact(next: Contact, expectedSource: Long) = contactsRepo().update(next, expectedSource)
    override suspend fun contactDetails(contact: Contact) = ContactDetailsRepository(checkNotNull(session)).load(contact.id, contact.sourceRevision)
    override suspend fun deleteContact(id: String) { check(contactsRepo().delete(id).isEmpty()) }
    private suspend fun requireCurrent(contact: Contact, conversation: Conversation) {
        check(contactsRepo().get(contact.id)?.sourceRevision == contact.sourceRevision)
        check(conversationsRepo().list(contact.id).any { it.id == conversation.id && it.revision == conversation.revision })
    }
    override suspend fun loadOrCreate(contact: Contact): StoredCounsel {
        check(contactsRepo().get(contact.id)?.sourceRevision == contact.sourceRevision)
        val repo = conversationsRepo()
        val conversation = repo.list(contact.id).maxByOrNull { it.updatedAt }
            ?: Conversation(UUID.randomUUID().toString(), contact.id, false, System.currentTimeMillis()).also { repo.save(it) }
        val selected=LiveCaptureRepository(checkNotNull(session)).evidence(contact.id)
        val evidence = selected.evidence
        return StoredCounsel(conversation, repo.draft(contact.id, conversation.id).orEmpty(), repo.messages(contact.id, conversation.id), evidence, TimelineRepository(checkNotNull(session)).runs(contact.id), SummaryRepository(checkNotNull(session)).list(contact.id), PlanRepository(checkNotNull(session)).list(contact.id),sourceBlocked=selected.blocked,capturePreferred=selected.preferred)
    }
    override suspend fun update(contact: Contact, previous: Conversation, next: Conversation, draft: String) {
        require(next.canPersist && next.contactId == contact.id && next.id == previous.id)
        conversationsRepo().updateDraft(contact.id, previous.revision, next, draft, contact.sourceRevision)
    }
    override suspend fun saveMessage(contact: Contact, conversation: Conversation, message: Message) {
        requireCurrent(contact, conversation)
        require(message.contactId == contact.id && message.conversationId == conversation.id)
        conversationsRepo().saveMessage(message)
    }
    override suspend fun discardMessage(contact: Contact, message: Message) {
        conversationsRepo().deleteMessage(contact.id, message.id)
    }
    override suspend fun saveImport(contact: Contact, imported: ChatImport, lines: List<ChatLine>): String {
        return ImportRepository(checkNotNull(session)).save(imported, lines, contact.sourceRevision)
    }
    override suspend fun discardSummary(summary: TimelineSummary) = SummaryRepository(checkNotNull(session)).discard(summary)
    override suspend fun saveSummary(summary: TimelineSummary) = SummaryRepository(checkNotNull(session)).save(summary)
    override suspend fun saveRun(contact: Contact, run: AnalysisRun) {
        TimelineRepository(checkNotNull(session)).saveCurrentRun(run, contact.sourceRevision)
    }
    override suspend fun saveFeedback(contact: Contact, plan: ActionPlan, privacyRevision: Long) = PlanRepository(checkNotNull(session)).saveFeedback(plan, privacyRevision)
    override suspend fun clearAll(): Boolean {
        try { return store.clearAll(temporary).isEmpty() } finally { session = null }
    }
    override suspend fun redactionProfile(contactId: String) = RedactionRepository(checkNotNull(session)).load(contactId)
    override suspend fun saveRedactionProfile(contactId: String, expected: Long, profile: RedactionProfile) = RedactionRepository(checkNotNull(session)).save(contactId, expected, profile)
}

/** A single mailbox serializes edits, database writes and model callbacks. Intent epochs also
 * revoke a callback immediately when a UI action is submitted, before a suspended write resumes.
 * This controller lives at application scope; none of its state is saved in a Bundle. */
class CounselController(
    private val persistence: CounselPersistence,
    private val temporary: TemporarySessionStore,
    private val assembler: PromptAssembler,
    private var provider: AnalysisProvider,
    private val scope: CoroutineScope,
    private val modelSettings: ModelSettingsRepository? = null,
    private val liveProvider: ((SavedModelSettings) -> AnalysisProvider)? = null,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    private val memoryNoticeDelay: suspend (Long) -> Unit = { delay(it) },
    private val styleBiasPreferences:StyleBiasPreferences=MemoryStyleBiasPreferences(),
) {
    private val styleCoordinator=StyleCoordinator()
    val stylePage:StateFlow<StyleUiState> = styleCoordinator.state.asStateFlow()
    private var pendingStyle:StyleDescriptionLease?=null
    fun closeStyle()=synchronized(intentLock) {styleCoordinator.close()}
    fun openStyle()=enqueue {
        if(!state.value.lockState.canDisplaySensitiveContent) return@enqueue
        styleCoordinator.open=true
        styleCoordinator.state.value=StyleUiState(loading=true)
        try {val snapshot=persistence.styleSnapshot();if(styleCoordinator.open && state.value.lockState.canDisplaySensitiveContent) styleCoordinator.display(snapshot)}
        catch(_:Exception) {styleCoordinator.state.value=StyleUiState(failed=true)}
    }
    fun editStyle(group:RelationGroup,text:String,receipt:String)=enqueue(true) {
        if(!styleCoordinator.current(receipt) || !state.value.lockState.canDisplaySensitiveContent) return@enqueue
        val stamp=checkNotNull(stylePage.value.snapshot).stamp
        try {invalidateRun();persistence.styleEdit(group,text,stamp) {styleCoordinator.current(receipt) && state.value.lockState.canDisplaySensitiveContent};styleCoordinator.display(persistence.styleSnapshot())}
        catch(_:Exception) {styleCoordinator.state.value=stylePage.value.copy(failed=true)}
    }
    fun editStyleSample(id:String,text:String,receipt:String)=enqueue(true) {
        if(!styleCoordinator.current(receipt) || !state.value.lockState.canDisplaySensitiveContent) return@enqueue
        val stamp=checkNotNull(stylePage.value.snapshot).stamp
        try {invalidateRun();persistence.styleEditSample(id,text,stamp) {styleCoordinator.current(receipt) && state.value.lockState.canDisplaySensitiveContent};styleCoordinator.display(persistence.styleSnapshot())}
        catch(_:Exception) {styleCoordinator.state.value=stylePage.value.copy(failed=true)}
    }
    fun deleteStyle(id:String,receipt:String)=enqueue(true) {
        if(!styleCoordinator.current(receipt) || !state.value.lockState.canDisplaySensitiveContent) return@enqueue
        try {invalidateRun();persistence.styleDelete(id,checkNotNull(stylePage.value.snapshot).stamp);styleCoordinator.display(persistence.styleSnapshot())}
        catch(_:Exception) {styleCoordinator.state.value=stylePage.value.copy(failed=true)}
    }
    fun resetStyle(group:RelationGroup,receipt:String)=enqueue(true) {
        if(!styleCoordinator.current(receipt) || !state.value.lockState.canDisplaySensitiveContent) return@enqueue
        try {invalidateRun();persistence.styleReset(group,checkNotNull(stylePage.value.snapshot).stamp);styleCoordinator.display(persistence.styleSnapshot())}
        catch(_:Exception) {styleCoordinator.state.value=stylePage.value.copy(failed=true)}
    }
    fun saveStyleQuiz(answers:List<String>,receipt:String)=enqueue(true) {
        if(!styleCoordinator.current(receipt) || !state.value.lockState.canDisplaySensitiveContent || answers.size!=5) return@enqueue
        val snapshot=checkNotNull(stylePage.value.snapshot)
        try {
            invalidateRun()
            val owner=state.value.currentContact?.id
            val formal=owner!=null && state.value.conversation?.let {it.canPersist && it.contactId==owner}==true
            if(formal && snapshot.consent.enabled && !snapshot.consent.paused) {persistence.styleQuiz(answers,snapshot.stamp) {styleCoordinator.current(receipt) && state.value.lockState.canDisplaySensitiveContent};styleCoordinator.display(persistence.styleSnapshot())}
            else {
                state.value.conversation?.id?.let {styleCoordinator.rememberQuiz(it,answers)}
                styleCoordinator.state.value=stylePage.value.copy(temporaryQuiz=answers.toList())
            }
        } catch(_:Exception) {styleCoordinator.state.value=stylePage.value.copy(failed=true)}
    }
    fun previewStyle(group:RelationGroup,receipt:String)=enqueue {
        if(!styleCoordinator.current(receipt) || state.value.streaming || !ordinaryAllowed() || state.value.conversation==null) return@enqueue
        val snapshot=persistence.styleSnapshot()
        val selected=snapshot.groups[group] ?: return@enqueue
        if(snapshot.stamp!=stylePage.value.snapshot?.stamp || !snapshot.consent.enabled || snapshot.consent.paused || selected.samples.size<15 || selected.profile==null || !selected.profile?.description.isNullOrBlank() || selected.profile?.let {it.generatedFingerprint.isNotBlank() && it.generatedFingerprint==it.sourceFingerprint}==true) return@enqueue
        if(provider.kind==ProviderKind.DEMO) {styleCoordinator.state.value=stylePage.value.copy(modelRequired=true);return@enqueue}
        val authority=authoritySnapshot()
        val base=request(authority)
        val projected=StylePersistencePolicy.retainSamples(selected.samples).joinToString("\n") {it.effectiveText}
        val selectedRelation=when(group) {RelationGroup.WORK->RelationType.BOSS;RelationGroup.INTIMATE->RelationType.PARTNER;RelationGroup.FAMILY->RelationType.PARENT;RelationGroup.SOCIAL->RelationType.FRIEND;RelationGroup.MEDIATION->RelationType.MEDIATION;RelationGroup.DEFAULT->RelationType.ACQUAINTANCE}
        val next=base.copy(requestId=UUID.randomUUID().toString(),purpose=OutboundPurpose.STYLE_DESCRIPTION,relationType=selectedRelation,
            userText="请用不超过200字描述这些本人样本的表达风格，保留个性并指出影响效果的习惯。",evidence=emptyList(),networkSelection=null,
            styleContext=projected,styleSource=null,styleSamples=emptyList(),actualDataContext=OutboundDataContext(),recentSummary="",contactContext="",timelineSummary="",
            redactionTerms=(base.redactionTerms+snapshot.sourceTerms[group].orEmpty()).distinct())
        val prepared=withContext(Dispatchers.Default) {assembler.prepare(next)}
        authorized(authority) {styleCoordinator.state.value=stylePage.value.copy(previewGroup=group);pendingStyle=StyleDescriptionLease(group,snapshot);previewRequest=next;previewAuthority=authority;set(state.value.copy(currentRequest=next,preview=prepared,outboundBody=outboundBody(prepared),error=null))}
    }
    private val memoryDisplay=MemoryDisplayCoordinator(scope,{ task -> enqueue { task() } },memoryNoticeDelay)
    private val mutableMemory get()=memoryDisplay.inline
    val memory: StateFlow<MemoryUiState> = memoryDisplay.inline.asStateFlow()
    private val mutableMemoryPage get()=memoryDisplay.page
    val memoryPage: StateFlow<MemoryUiState> = memoryDisplay.page.asStateFlow()
    private val memoryEpoch get()=memoryDisplay.routeEpoch
    private var pendingMemory: Pair<AnalysisRequest, MemoryProposal>? = null
    private var proposalStamp: WriteStamp? = null
    private var pendingMemoryStamp: WriteStamp? = null
    private data class ApprovalFinish(val owner: String,val request: AnalysisRequest,val token: Long,
        val operation: String,val summary: String,val receipt: MemoryUiReceipt)
    private var approvalFinish: ApprovalFinish?=null
    private fun displayed(view: MemoryUiState, channel: String, requestId: String? = null): MemoryUiState {
        val stamp=view.snapshot.stamp ?: return view.copy(receipt=null)
        if(view.loading) return view.copy(receipt=null)
        return view.copy(receipt=MemoryUiReceipt(UUID.randomUUID().toString(),channel,view.ownerId,stamp,requestId,view.consentProposal,view.undoOperationId,view.snapshot.clearAuthority))
    }
    private fun currentDisplay(receipt: MemoryUiReceipt, allowPending: Boolean = false): Boolean {
        val view=if(receipt.channel=="inline") memory.value else if(receipt.channel=="page") memoryPage.value else return false
        return view.receipt==receipt && view.ownerId==receipt.owner && view.snapshot.stamp==receipt.stamp && !view.loading && (allowPending || !view.pendingOperation) && state.value.lockState.canDisplaySensitiveContent
    }
    private fun operationCurrent(owner: String?, id: String): Boolean =
        (memory.value.ownerId==owner && memory.value.activeOperation==id) || (memoryPage.value.ownerId==owner && memoryPage.value.activeOperation==id)
    @Volatile private var memoryPageOpen=false
    fun closeMemory() = synchronized(intentLock) {
        memoryPageOpen=false
        memoryEpoch.incrementAndGet()
        mutableMemoryPage.value=MemoryUiState()
    }
    private fun clearInlineMemory() {
        styleCoordinator.revoke()
        closeMemory();pendingMemory=null;pendingMemoryStamp=null;approvalFinish=null;memoryDisplay.cancelNotice()
        mutableMemory.value=MemoryUiState(snapshot=MemorySnapshot(consent=memory.value.consent))
    }
    fun openMemory(owner: String?) {
        memoryPageOpen=true
        val token=memoryEpoch.incrementAndGet()
        mutableMemoryPage.value=MemoryUiState(owner,MemorySnapshot(consent=memory.value.consent),loading=true)
        enqueue {
            try {
                val snapshot=persistence.memorySnapshot(owner)
                val contacts=persistence.contacts()
                var count=0; var imports=0
                contacts.forEach { contact ->
                    val detail=persistence.contactDetails(contact)
                    count+=detail.memories.size; imports+=detail.imports.size
                }
                synchronized(intentLock) { if(memoryPageOpen && memoryEpoch.get()==token && state.value.lockState.canDisplaySensitiveContent)
                    mutableMemoryPage.value=displayed(MemoryUiState(owner,snapshot,totalMemories=snapshot.clearAuthority?.memoryCount ?: count,totalImports=snapshot.clearAuthority?.importCount ?: imports),"page") }
            } catch (_: Exception) { if(memoryEpoch.get()==token) mutableMemoryPage.value=memoryPage.value.copy(failed=true,loading=false,pendingOperation=false) }
        }
    }
    private fun revokeMemoryAuthority(owner: String? = null, preserveAnswer: Boolean = false, preserveEditor: Boolean = false): String = synchronized(intentLock) {
        val operation=UUID.randomUUID().toString()
        val affectsCurrent=owner==null || state.value.currentContact?.id==owner
        if(affectsCurrent) { epoch.incrementAndGet(); stream?.cancel() }; memoryEpoch.incrementAndGet()
        if(affectsCurrent) {
            pendingMemory=null; pendingMemoryStamp=null; approvalFinish=null; memoryDisplay.cancelNotice(); proposalStamp=null
            mutableMemory.value=memory.value.copy(consentProposal=null,notice=null,receipt=null,activeOperation=operation,approvalFinishing=false,pendingOperation=false)
        }
        if(owner==null || memoryPage.value.ownerId==owner) mutableMemoryPage.value=if(preserveEditor) memoryPage.value.copy(activeOperation=operation,pendingOperation=true,failed=false) else MemoryUiState(ownerId=memoryPage.value.ownerId,loading=true,activeOperation=operation)
        if(affectsCurrent) set(if(preserveAnswer) state.value.copy(preview=null,currentRequest=null,outboundBody=null) else state.value.copy(answer=null,answerRequest=null,preview=null,currentRequest=null,outboundBody=null))
        operation
    }
    private suspend fun refreshMemory(owner: String?) {
        val routeToken=memoryEpoch.get()
        val authorityToken=epoch.get()
        val snapshot=persistence.memoryContextSnapshot(owner)
        if(memoryPageOpen && memoryPage.value.ownerId==owner) {
            val management=persistence.memorySnapshot(owner)
            var count=0;var imports=0
            persistence.contacts().forEach { contact -> val detail=persistence.contactDetails(contact);count+=detail.memories.size;imports+=detail.imports.size }
            if(memoryPageOpen && memoryEpoch.get()==routeToken && memoryPage.value.ownerId==owner)
                mutableMemoryPage.value=displayed(memoryPage.value.copy(snapshot=management,failed=false,loading=false,pendingOperation=false,totalMemories=management.clearAuthority?.memoryCount ?: count,totalImports=management.clearAuthority?.importCount ?: imports),"page")
        }
        if(epoch.get()==authorityToken && state.value.currentContact?.id==owner) {
            mutableMemory.value=displayed(memory.value.copy(ownerId=owner,snapshot=snapshot,failed=false,loading=false,pendingOperation=false),"inline")
            set(state.value.copy(memoryContext=MemoryPresentation.context(owner,snapshot),memoryRetention=MemoryPresentation.retention(owner,snapshot)))
        }
    }
    private fun memoryFailed(owner: String?, operation: String) {
        if(memoryPage.value.ownerId==owner && memoryPage.value.activeOperation==operation) mutableMemoryPage.value=memoryPage.value.copy(failed=true,loading=false,pendingOperation=false)
        if(memory.value.ownerId==owner && memory.value.activeOperation==operation) mutableMemory.value=memory.value.copy(failed=true,pendingOperation=false)
    }
    fun setMemoryEnabled(enabled: Boolean, paused: Boolean, receipt: MemoryUiReceipt) {
        val submitted=synchronized(intentLock) { if(!currentDisplay(receipt)) return;epoch.get() }
        enqueue {
            var operation: String?=null
            try {
                persistence.memoryConsent(enabled,paused,receipt.stamp,receipt.clearAuthority) {
                    synchronized(intentLock) {
                        check(epoch.get()==submitted && currentDisplay(receipt)) { "Stale consent UI" }
                        operation=revokeMemoryAuthority()
                    }
                }
                invalidateRun();summaries=emptyList()
                refreshMemory(state.value.currentContact?.id); if(memoryPage.value.ownerId!=state.value.currentContact?.id) refreshMemory(memoryPage.value.ownerId)
            } catch (_: Exception) { operation?.let { memoryFailed(receipt.owner,it) } }
        }
    }
    fun declineMemory(receipt: MemoryUiReceipt) {
        val owner=receipt.owner ?: return
        val (pending,submitted)=synchronized(intentLock) {
            if(!currentDisplay(receipt,allowPending=true) || receipt.channel!="inline" || receipt.requestId!=pendingMemory?.first?.requestId || receipt.proposal!=pendingMemory?.second || receipt.stamp!=pendingMemoryStamp) return
            if(memory.value.pendingOperation) {
                epoch.incrementAndGet()
                mutableMemory.value=memory.value.copy(pendingOperation=false,activeOperation=null)
            }
            (pendingMemory ?: return) to epoch.get()
        }
        enqueue {
            var operation: String?=null
            try {
                persistence.memoryDecline(owner,receipt.stamp,wallClockMillis()+30L*24*60*60*1000) {
                    synchronized(intentLock) {
                        check(epoch.get()==submitted && currentDisplay(receipt) && state.value.answerRequest==pending.first && state.value.currentContact?.id==owner) { "Stale decline UI" }
                        operation=revokeMemoryAuthority(preserveAnswer=true)
                    }
                }
                invalidateRun(); summaries=emptyList();refreshMemory(owner)
            } catch (_: Exception) {
                operation?.let { memoryFailed(owner,it) }
                // The approval may already have committed before this decline linearized.
                // Reveal its existing receipt; never turn an old decline into withdrawal.
                if(operation==null && synchronized(intentLock) { epoch.get()==submitted && currentDisplay(receipt) }) {
                    val live=runCatching { persistence.memoryContextSnapshot(owner) }.getOrNull()
                    synchronized(intentLock) {
                        if(live!=null && live.consent.enabled && epoch.get()==submitted && currentDisplay(receipt)) {
                            pendingMemory=null;pendingMemoryStamp=null
                            mutableMemory.value=displayed(MemoryUiState(owner,live),"inline")
                            set(state.value.copy(memoryContext=MemoryPresentation.context(owner,live),memoryRetention=MemoryPresentation.retention(owner,live)))
                        }
                    }
                }
            }
        }
    }
    private fun finishCurrent(ticket: ApprovalFinish): Boolean = approvalFinish===ticket && epoch.get()==ticket.token &&
        state.value.currentContact?.id==ticket.owner && state.value.answerRequest==ticket.request &&
        state.value.lockState.canDisplaySensitiveContent && memory.value.ownerId==ticket.owner && memory.value.activeOperation==ticket.operation
    private suspend fun finishApproval(ticket: ApprovalFinish) {
        if(!synchronized(intentLock) { finishCurrent(ticket) }) return
        try {
            refreshMemory(ticket.owner)
            synchronized(intentLock) {
                if(!finishCurrent(ticket)) return@synchronized
                approvalFinish=null
                mutableMemory.value=memory.value.copy(approvalFinishing=false,pendingOperation=false,failed=false,consentProposal=null)
                if(memory.value.snapshot.operationId==ticket.operation) showMemoryNotice(ticket.summary,ticket.operation,ticket.owner)
            }
        } catch (_: Exception) {
            synchronized(intentLock) {
                if(finishCurrent(ticket)) mutableMemory.value=memory.value.copy(receipt=ticket.receipt,approvalFinishing=true,pendingOperation=false,failed=true,consentProposal=null)
            }
        }
    }
    fun acceptMemory(receipt: MemoryUiReceipt) {
        val finishing=synchronized(intentLock) {
            approvalFinish?.takeIf { ticket -> ticket.receipt==receipt && memory.value.receipt==receipt && memory.value.approvalFinishing && !memory.value.pendingOperation && finishCurrent(ticket) }?.also {
                mutableMemory.value=memory.value.copy(pendingOperation=true,failed=false)
            }
        }
        if(finishing!=null) { enqueue { finishApproval(finishing) };return }
        val owner=receipt.owner ?: return
        val (pending,operation,token)=synchronized(intentLock) {
            if(!currentDisplay(receipt) || receipt.channel!="inline" || receipt.requestId!=pendingMemory?.first?.requestId || receipt.proposal!=pendingMemory?.second || receipt.stamp!=pendingMemoryStamp) return
            val pending=pendingMemory ?: return
            val operation="approve:$owner:${receipt.identity}"
            mutableMemory.value=memory.value.copy(pendingOperation=true,activeOperation=operation,failed=false)
            Triple(pending,operation,epoch.get())
        }
        fun stillCurrent(): Boolean = epoch.get()==token && currentDisplay(receipt,allowPending=true) && memory.value.activeOperation==operation && memory.value.pendingOperation &&
            state.value.answerRequest==pending.first && state.value.currentContact?.id==owner && pendingMemory==pending && pendingMemoryStamp==receipt.stamp
        enqueue {
            try {
                if(!synchronized(intentLock) { stillCurrent() }) return@enqueue
                val sourceCurrent=answerSourceCurrent()
                if(!synchronized(intentLock) { stillCurrent() }) return@enqueue
                if(!sourceCurrent) {
                    synchronized(intentLock) { if(stillCurrent()) {
                        pendingMemory=null;pendingMemoryStamp=null
                        mutableMemory.value=memory.value.copy(consentProposal=null,receipt=null,pendingOperation=false,failed=true)
                    } }
                    return@enqueue
                }
                val op=persistence.memoryApprove(owner,pending.second,receipt.stamp,operation) {
                    synchronized(intentLock) { check(stillCurrent()) { "Approval display or source authority changed" } }
                }
                if(!synchronized(intentLock) { stillCurrent() }) return@enqueue
                val finishing=ApprovalFinish(owner,pending.first,token,op.id,pending.second.value,receipt)
                invalidateRun();summaries=emptyList()
                synchronized(intentLock) {
                    if(epoch.get()!=token || state.value.currentContact?.id!=owner || state.value.answerRequest!=pending.first || !state.value.lockState.canDisplaySensitiveContent) return@synchronized
                    approvalFinish=finishing
                    mutableMemory.value=memory.value.copy(receipt=receipt,activeOperation=op.id,consentProposal=null,approvalFinishing=true,pendingOperation=true,failed=false)
                }
                finishApproval(finishing)
            } catch (_: Exception) {
                synchronized(intentLock) {
                    if(stillCurrent()) mutableMemory.value=memory.value.copy(pendingOperation=false,failed=true)
                }
            }
        }
    }
    private fun showMemoryNotice(summary: String, id: String, owner: String? = state.value.currentContact?.id, inference: Boolean = true) = synchronized(intentLock) {
        if(owner!=null && (state.value.currentContact?.id==owner || memoryPage.value.ownerId==owner)) memoryDisplay.showNotice(summary,id,owner,inference)
    }
    private fun mutateMemory(owner: String, receipt: MemoryUiReceipt, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        val operation=synchronized(intentLock) { if(receipt.owner!=owner || !currentDisplay(receipt)) return;revokeMemoryAuthority(owner,preserveEditor=true) }
        enqueue {
            if(!operationCurrent(owner,operation)) return@enqueue
            if(state.value.currentContact?.id==owner) { invalidateRun(); summaries=emptyList() }
            try { action(); refreshMemory(owner);onSuccess() }
            catch (failure: Exception) {
                if(failure is MemoryWriteRejected && failure.code in setOf("STALE_SOURCE","STALE_MEMORY","STALE_CONSENT","CONSENT_REQUIRED","MEMORY_PAUSED","POLICY_VERSION")) {
                    if(memoryPage.value.ownerId==owner && memoryPage.value.activeOperation==operation) openMemory(owner)
                } else memoryFailed(owner,operation)
            }
        }
    }
    fun editMemory(owner: String, entry: MemoryEntry, receipt: MemoryUiReceipt, editorOperation: String) {
        var result: MemoryOperation?=null
        mutateMemory(owner,receipt,{ result?.let { op -> op.after.firstOrNull { it.id==entry.id }?.let { saved -> showMemoryNotice(saved.value,op.id,owner,saved.sourceType==MemorySource.ASSISTANT_INFERENCE) } } }) {
            val snapshot=persistence.memoryContextSnapshot(owner)
            val current=snapshot.entries.first { it.id==entry.id }
            require(current.contactId==owner)
            require(current.sourceType!=MemorySource.ASSISTANT_INFERENCE || current.id in snapshot.verifiedInferenceIds) { "Unverified legacy inference is read-only" }
            result=persistence.memoryEdit(owner,current.copy(value=entry.value,updatedAt=wallClockMillis()),receipt.stamp,"user:$owner:$editorOperation")
        }
    }
    fun addMemory(owner: String, group: MemoryScope, value: String, receipt: MemoryUiReceipt, editorOperation: String) {
        var result: MemoryOperation?=null
        mutateMemory(owner,receipt,{ result?.let { showMemoryNotice(value,it.id,owner,false) } }) {
            val entry=MemoryEntry("manual:$editorOperation",owner,scope=group,subjectId=owner,field=group.name,value=value,
                sourceType=MemorySource.USER_EXPLICIT,confidence=if(group==MemoryScope.HYPOTHESIS) Confidence.LOW else Confidence.HIGH,createdAt=wallClockMillis(),updatedAt=wallClockMillis())
            result=persistence.memoryEdit(owner,entry,receipt.stamp,"user:$owner:$editorOperation")
        }
    }
    fun deleteMemory(owner: String,id: String,receipt: MemoryUiReceipt) = mutateMemory(owner,receipt) { persistence.memoryDelete(owner,id,receipt.stamp,false) }
    fun markMemoryWrong(owner: String,id: String,receipt: MemoryUiReceipt) = mutateMemory(owner,receipt) { persistence.memoryDelete(owner,id,receipt.stamp,true) }
    fun undoMemory(owner: String,id: String,receipt: MemoryUiReceipt) = mutateMemory(owner,receipt) { if(receipt.operationId==id) persistence.memoryUndo(owner,id,receipt.stamp) }
    fun clearMemories(receipt: MemoryUiReceipt) {
        val authority=receipt.clearAuthority ?: return
        val submitted=synchronized(intentLock) { if(!currentDisplay(receipt) || receipt.channel!="page") return;epoch.get() }
        enqueue {
            var operation: String?=null
            try {
                persistence.clearMemories(authority) {
                    synchronized(intentLock) {
                        check(epoch.get()==submitted && currentDisplay(receipt)) { "Stale clear UI" }
                        operation=revokeMemoryAuthority()
                    }
                }
                invalidateRun();summaries=emptyList()
                refreshMemory(state.value.currentContact?.id);openMemory(memoryPage.value.ownerId)
            } catch (_: Exception) { operation?.let { memoryFailed(receipt.owner,it) } }
        }
    }
    private suspend fun rememberCompleted(request: AnalysisRequest, answer: StructuredAnswer, token: Long) {
        val owner=request.contactId ?: return
        if(request.mode!=RequestMode.FULL_ANALYSIS || request.purpose!=OutboundPurpose.ANALYSIS || state.value.answerDemo || !isCurrentMemoryResult(request,token)) return
        val snapshot=persistence.memoryContextSnapshot(owner)
        if(snapshot.stamp!=proposalStamp) return
        val candidate=MemoryPresentation.candidate(answer,snapshot,owner)?.copy(subjectId=owner,sourceRef=request.requestId) ?: return
        if(snapshot.consent.paused) return
        if(!snapshot.consent.enabled) {
            if(snapshot.consent.declinedUntil>wallClockMillis()) return
            pendingMemory=request to candidate
            pendingMemoryStamp=snapshot.stamp
            mutableMemory.value=displayed(MemoryUiState(owner,snapshot,consentProposal=candidate),"inline",request.requestId)
        } else {
            val stamp=proposalStamp ?: return
            try {
                val op=persistence.memoryProposal(owner,candidate,stamp)
                if(!isCurrentMemoryResult(request,token)) {
                    persistence.memoryUndo(owner,op.id,checkNotNull(persistence.memoryContextSnapshot(owner).stamp)); return
                }
                refreshMemory(owner)
                if(!isCurrentMemoryResult(request,token)) {
                    persistence.memoryUndo(owner,op.id,checkNotNull(persistence.memoryContextSnapshot(owner).stamp));return
                }
                showMemoryNotice(candidate.value,op.id)
                summaries=emptyList(); previewRequest=null; (assembler as? FrozenPromptAssembler)?.clear()
            } catch (rejected: MemoryWriteRejected) {
                if(rejected.code=="MEMORY_METADATA_LIMIT") mutableMemory.value=memory.value.copy(failed=true,capacityFull=true)
            } catch (_: Exception) { /* No receipt means no saved notice. */ }
        }
    }
    private fun isCurrentMemoryResult(request: AnalysisRequest, token: Long) = epoch.get()==token && state.value.answerRequest==request && state.value.currentContact?.id==request.contactId

    private val demoProvider = provider
    private var settings = ModelSettingsSnapshot()
    private var testJob: Job? = null
    private var testGeneration = 0L
    private val mutableState = MutableStateFlow(CounselState(modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO, modelSettings = settings))
    val state: StateFlow<CounselState> = mutableState.asStateFlow()
    private val detailEpoch = AtomicLong()
    private val selectionEpoch = AtomicLong()
    private val mutableDetails = MutableStateFlow(ContactDetailState())
    val details: StateFlow<ContactDetailState> = mutableDetails.asStateFlow()
    private var contactUndo: ContactEditReceipt? = null

    fun closeContactDetails() { synchronized(intentLock) {
        detailEpoch.incrementAndGet()
        mutableDetails.value = ContactDetailState()
    } }
    fun openContactDetails(id: String) {
        val token = synchronized(intentLock) {
            detailEpoch.incrementAndGet().also { mutableDetails.value = ContactDetailState(ownerId = id, loading = true) }
        }
        enqueue {
            try {
                val owner = persistence.contacts().firstOrNull { it.id == id } ?: error("Missing owner")
                val loaded = persistence.contactDetails(owner)
                synchronized(intentLock) {
                    if (detailEpoch.get() == token && state.value.lockState.canDisplaySensitiveContent)
                        mutableDetails.value = ContactDetailState(id, data = loaded, saved = contactUndo?.after == owner, undoAvailable = contactUndo?.after == owner)
                }
            } catch (_: Exception) {
                synchronized(intentLock) { if (detailEpoch.get() == token) mutableDetails.value = ContactDetailState(id, failed = true) }
            }
        }
    }
    /** Only this owner's operations revoke its request. An old A form cannot cancel B. */
    private fun revokeContact(id: String) = synchronized(intentLock) {
        if(memory.value.ownerId==id) clearInlineMemory()
        if (mutableDetails.value.ownerId == id) {
            detailEpoch.incrementAndGet(); mutableDetails.value = ContactDetailState(id, loading = true)
        }
        if (state.value.currentContact?.id == id) {
            revokeFloatingCaptureLocked();epoch.incrementAndGet(); stream?.cancel()
            set(state.value.copy(answer = null, answerRequest = null, preview = null, currentRequest = null, outboundBody = null))
        }
    }
    fun editContact(next: Contact, expectedSource: Long) {
        revokeContact(next.id)
        enqueue {
            val currentOwner = state.value.currentContact?.id == next.id
            if (currentOwner) {
                try { flushDraft() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // No contact edit has been attempted. Preserve the only dirty draft,
                    // its owner/CAS version and Recovery.SaveDraft; retry saves that draft
                    // only and never silently submits the unexecuted contact operation.
                    set(state.value.copy(loading = false, streaming = false,
                        error = CounselError("STORAGE_UNAVAILABLE", true)))
                    synchronized(intentLock) { if (mutableDetails.value.ownerId == next.id)
                        mutableDetails.value = ContactDetailState(next.id, failed = true) }
                    return@enqueue
                }
                invalidateRun()
            }
            try {
                val receipt = persistence.editContact(next, expectedSource)
                contactUndo = receipt
                set(state.value.copy(contacts = persistence.contacts()))
                if (currentOwner) {
                    if (receipt.after.archivedAt != null) beginTemporary() else loadContact(receipt.after, epoch.get())
                }
                synchronized(intentLock) { if (mutableDetails.value.ownerId == next.id) openContactDetails(next.id) }
            } catch (_: Exception) {
                // Result can be unknown: refetch before another edit, never retry an upsert.
                runCatching { persistence.contacts() }.getOrNull()?.let { set(state.value.copy(contacts = it)) }
                if (currentOwner) beginTemporary()
                if (mutableDetails.value.ownerId == next.id) mutableDetails.value = ContactDetailState(next.id, failed = true)
            }
        }
    }
    fun undoContactEdit() {
        val receipt = contactUndo ?: return
        editContact(receipt.before, receipt.after.sourceRevision)
    }

    private val mailbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val epoch = AtomicLong()
    val importAuthority: Long get() = epoch.get()
    internal fun importSelectionState() = synchronized(intentLock) { epoch.get() to state.value }
    private val intentLock = Any()
    @Volatile private var importPending = false
    fun setImportPending(value: Boolean, change:()->Unit = {}) = changeImportPending(value,null,change)
    internal fun continueImportPending(id:String,change:()->Unit) = changeImportPending(true,id,change)
    private fun changeImportPending(value:Boolean,moreId:String?,change:()->Unit) { synchronized(intentLock) {
        change()
        val previousCapture=floatingCapture
        val previousReceiver=captureReceiver
        val continuing=floatingMore?.takeIf { it.id==moreId && moreCurrent(it) }
        revokeFloatingMoreLocked()
        if(value) revokeFloatingCaptureLocked()
        importPending = value; val next=epoch.incrementAndGet(); stream?.cancel()
        if(continuing!=null) floatingMore=continuing.copy(epoch=next)
        if(!value && previousCapture!=null && previousCapture.epoch==next-1) {
            floatingCapture=previousCapture.copy(epoch=next)
            captureConfirmation=captureConfirmation?.takeIf {it.captureId==previousCapture.id && it.epoch==next-1}?.copy(epoch=next)
            mutableState.value=state.value.copy(floatingCapture=floatingCaptureView(state.value))
        }
        if(!value && previousReceiver!=null && previousReceiver.epoch==next-1 && captureReceiver?.token==previousReceiver.token) captureReceiver=previousReceiver.copy(epoch=next)
        mailbox.trySend {
            // A share queued during startup must not erase the failure needed by its retry UI.
            if (state.value.conversation != null || state.value.error == null) invalidateRun()
        }
    } }
    private var inputRevision = 0L
    private var credential: TemporarySessionCredential? = null
    private var ledger = AnalysisLedger()
    private var stream: Job? = null
    private var waitingJob: Job? = null
    private var initialized = false
    private var frozen: AnalysisRequest? = null
    private var previewRequest: AnalysisRequest? = null
    private var dirtyDraft = false
    private var summaries = emptyList<TimelineSummary>()
    private var plans = emptyList<ActionPlan>()
    private var summaryKey: String? = null
    private val blockedSummaryRequests = mutableSetOf<String>()
    private val pendingSummaryCleanup = mutableMapOf<String, Pair<TimelineSummary, AnalysisRun?>>()
    private sealed interface Recovery {
        data object Initialize : Recovery
        data class Load(val contact: Contact) : Recovery
        data object SaveDraft : Recovery
        data class Feedback(val origin: String?, val answerId: String, val edited: String?) : Recovery
        data class Analysis(val request: AnalysisRequest) : Recovery
        data class Checkpoint(val request: AnalysisRequest, val code: String, val retryable: Boolean) : Recovery
        data class Complete(val request: AnalysisRequest, val token: Long, val answer: StructuredAnswer, val completedAtMillis: Long? = null) : Recovery
        data class FinishSummary(val request: AnalysisRequest) : Recovery
        data object Preview : Recovery
        data object RefreshContacts : Recovery
        data class Delete(val contactId: String) : Recovery
        data object Clear : Recovery
    }
    private var recovery: Recovery? = null
    private val json = Json { ignoreUnknownKeys = true }

    init {
        scope.launch {
            for (action in mailbox) {
                try { action() }
                catch (_: RevokedOperation) { /* Revocation is final, not a recoverable model error. */ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { set(state.value.copy(loading = false, streaming = false, error = CounselError("STORAGE_UNAVAILABLE", recovery != null))) }
            }
        }
    }
    private fun enqueue(invalidate: Boolean = false, block: suspend () -> Unit) {
        synchronized(intentLock) {
            if (invalidate) { revokeFloatingCaptureLocked();epoch.incrementAndGet(); stream?.cancel() }
            mailbox.trySend(block)
        }
    }
    private fun enqueueFrom(origin: String?, invalidate: Boolean = false, onDropped: () -> Unit = {}, preserveReceiver:CaptureReceiver?=null,block: suspend (Long) -> Unit) {
        synchronized(intentLock) {
            if (origin == null || state.value.conversation?.id != origin) { onDropped(); return }
            if (invalidate) {
                val receiver=captureReceiver?.takeIf {it==preserveReceiver && it.epoch==epoch.get() && it.lock==lockGeneration}
                revokeFloatingCaptureLocked();val next=epoch.incrementAndGet();stream?.cancel()
                if(receiver!=null) captureReceiver=receiver.copy(epoch=next)
            }
            val submissionEpoch = epoch.get()
            mailbox.trySend {
                if (synchronized(intentLock) { state.value.conversation?.id == origin }) block(submissionEpoch)
                else onDropped()
            }
        }
    }
    private var lockGeneration = 0L
    private var authorityLock = LockState()
    private var runLockGeneration = -1L
    private var activeRunAuthority:RunAuthority?=null
    private var previewAuthority:RunAuthority?=null
    private var floatingStartedAt:Long?=null
    internal fun reuseFloatingRequest():Boolean = synchronized(intentLock) {
        val started=floatingStartedAt ?: return@synchronized false
        state.value.streaming && frozen?.floating==true && state.value.currentRequest==frozen && activeRunAuthority?.let(::authorityCurrent)==true && authorityLock.canDisplaySensitiveContent &&
            runLockGeneration==lockGeneration && monotonicMillis()-started in 0..10_000
    }
    private data class RunAuthority(val epoch: Long, val lockGeneration: Long)
    private class RevokedOperation : RuntimeException()
    private class FloatingOpenFull : RuntimeException()
    /** Floating requests retain every confirmed line or explicitly return to the full flow. */
    private fun requireFloatingBudget(request:AnalysisRequest) {
        if(!request.floating) return
        if(request.purpose==OutboundPurpose.LONG_RECORD_SUMMARY) throw FloatingOpenFull()
        val plan=try { ContinuousAnalysis.plan(request.evidence) } catch(_:IllegalArgumentException) { throw FloatingOpenFull() }
        if(plan.needsSummary) throw FloatingOpenFull()
        if(request.networkSelection?.toSet()?.let { it!=plan.recent.map { line -> line.id }.toSet() }==true) throw FloatingOpenFull()
    }
    private fun floatingOpenFull(authority:RunAuthority) = authorized(authority) {
        invalidateRun()
        recovery=null
        set(state.value.copy(preview=null,outboundBody=null,currentRequest=null,summaryReady=false,answer=null,answerRequest=null,
            error=CounselError("FLOATING_OPEN_FULL")))
    }
    private fun authoritySnapshot(expectedEpoch: Long? = null) = synchronized(intentLock) {
        RunAuthority(expectedEpoch ?: epoch.get(), lockGeneration)
    }
    private fun authorityCurrent(authority: RunAuthority) = synchronized(intentLock) {
        authority.epoch == epoch.get() && authority.lockGeneration == lockGeneration && authorityLock.canDisplaySensitiveContent
    }
    private inline fun <T> authorized(authority: RunAuthority, block: () -> T): T = synchronized(intentLock) {
        if (!authorityCurrent(authority)) throw RevokedOperation()
        block()
    }
    val lockRevision get() = synchronized(intentLock) { lockGeneration }
    fun updateLockState(lock: LockState): Unit = synchronized(intentLock) {
        revokeFloatingCaptureLocked()
        authorityLock = lock
        styleCoordinator.revoke()
        lockGeneration++
        epoch.incrementAndGet()
        // Immediate display/network revocation; actor-owned fields are cleaned in mailbox order.
        stream?.cancel(); waitingJob?.cancel(); testJob?.cancel()
        mutableState.value = state.value.copy(lockState=lock, streaming=false, testingConnection=false,floatingPreviewRequest=null,floatingPreviewReady=false,
            partial=emptyMap(), currentRequest=null, preview=null, outboundBody=null, summaryReady=false)
        mailbox.trySend { clearInlineMemory(); invalidateRun() }
    }
    private fun set(next: CounselState) = synchronized(intentLock) {
        // Ordinary state snapshots never own the lock, including freshly constructed sessions.
        val floatingPreview=previewRequest?.takeIf { it.floating && previewAuthority?.let(::authorityCurrent)==true }
        val visible = next.copy(lockState=authorityLock,floatingCapture=floatingCaptureView(next),floatingPreviewReady=floatingPreview!=null,floatingPreviewRequest=floatingPreview).let {
            if (authorityLock.canDisplaySensitiveContent) it else it.copy(streaming=false, testingConnection=false,
                partial=emptyMap(), currentRequest=null, preview=null, outboundBody=null, summaryReady=false)
        }
        mutableState.value = if (pendingSummaryCleanup.isEmpty()) visible else visible.copy(error = CounselError("STORAGE_UNAVAILABLE", true))
    }
    private fun invalidateRun() {
        pendingStyle=null
        activeRunAuthority=null;previewAuthority=null
        pendingMemory=null;pendingMemoryStamp=null;approvalFinish=null
        mutableMemory.value=memory.value.copy(consentProposal=null,approvalFinishing=false,pendingOperation=false)
        testGeneration++; testJob?.cancel(); testJob = null
        waitingJob?.cancel(); waitingJob = null
        stream?.cancel(); stream = null; frozen = null
        previewRequest = null
        summaryKey = null
        recovery = null
        (assembler as? FrozenPromptAssembler)?.clear()
        set(state.value.copy(streaming = false, testingConnection = false, partial = emptyMap(), currentRequest = null, preview = null, outboundBody = null, summaryReady = false, error = null))
    }

    private fun applyModelSettings() {
        provider = if (settings.saved.live) checkNotNull(liveProvider).invoke(settings.saved) else demoProvider
        set(state.value.copy(modelSettings = settings, modelStatus = provider.kind,
            demo = !settings.saved.live, testingConnection = false, connectionResult = null, answer = null))
    }
    private fun outboundBody(prompt: PreparedPrompt): String? = if (settings.saved.live)
        CompletionBody.encode(settings.saved.connection(), prompt) else null

    fun saveModelSettings(saved: SavedModelSettings, newKey: String?, removeKey: Boolean = false) = enqueue(true) {
        testGeneration++; testJob?.cancel(); testJob = null
        invalidateRun()
        if (!initialized || modelSettings == null) return@enqueue
        try {
            saved.connection().completionUrl()
            settings = modelSettings.save(saved, newKey, removeKey)
            applyModelSettings()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Save may have committed before acknowledgement failed. Revoke the previous
            // provider rather than letting a retained endpoint read a newer credential.
            settings = ModelSettingsSnapshot(saved.copy(revision = ""))
            provider = if (saved.live) object : AnalysisProvider {
                override val kind = ProviderKind.BYOK
                override fun analyze(request: AnalysisRequest) = flowOf(AnalysisChunk.Failed(
                    AnalysisError("SETTINGS_UNAVAILABLE", "模型设置保存未确认，请重新保存后再发送", false)))
            } else demoProvider
            val code = if (failure is IllegalArgumentException) "CONFIG_INVALID" else "SETTINGS_UNAVAILABLE"
            set(state.value.copy(modelSettings = settings, modelStatus = provider.kind, demo = !saved.live,
                answer = null, connectionResult = code, testingConnection = false, error = CounselError(code)))
        }
    }
    fun testModelConnection() {
        val authority=authoritySnapshot()
        enqueue {
        authorized(authority) {
        if (!initialized || state.value.streaming || state.value.testingConnection || !settings.saved.live) return@enqueue
        val token = testGeneration
        val operationProvider = provider
        set(state.value.copy(testingConnection = true, connectionResult = null))
        testJob = scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                val terminal = operationProvider.analyze(AnalysisRequest(requestId = "connection-test",
                    userText = "连接测试：请只回复‘连接成功’。")).last()
                val result = when (terminal) {
                    is AnalysisChunk.Completed -> "SUCCESS"
                    is AnalysisChunk.Failed -> terminal.error.code
                    else -> "STREAM_INCOMPLETE"
                }
                mailbox.send { synchronized(intentLock) { if (testGeneration == token && authorityCurrent(authority)) set(state.value.copy(testingConnection = false, connectionResult = result)) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mailbox.send { synchronized(intentLock) { if (testGeneration == token && authorityCurrent(authority)) set(state.value.copy(testingConnection = false, connectionResult = "NETWORK")) } } }
        }
        }
        }
    }
    fun cancelModelTest() = enqueue {
        testGeneration++; testJob?.cancel(); testJob = null
        set(state.value.copy(testingConnection = false, connectionResult = "CANCELLED"))
    }

    fun initialize() = enqueue { initializeNow() }
    private suspend fun initializeNow(forCapture:Boolean=false) {
        if (!initialized) {
            recovery = Recovery.Initialize
            set(state.value.copy(loading = true, error = null))
            persistence.initialize()
            modelSettings?.let { settings = it.load(); applyModelSettings() }
            val contacts = persistence.contacts()
            refreshMemory(null)
            initialized = true
            set(state.value.copy(contacts = contacts, loading = false))
        }
        if(!forCapture && state.value.conversation==null) {
            state.value.contacts.firstOrNull { it.archivedAt==null }?.let { loadContact(it) } ?: beginTemporary()
        }
    }
    /** Explicit completion avoids relying on conflated loading/error transitions for a retry. */
    fun retryStartup(completion: (String?) -> Unit) = enqueue {
        try {
            if (!initialized || state.value.conversation==null) initializeNow()
            else (recovery as? Recovery.Load)?.let { loadContact(it.contact) }
            completion(state.value.conversation?.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            set(state.value.copy(loading = false, streaming = false, error = CounselError("STORAGE_UNAVAILABLE", recovery != null)))
            completion(null)
        }
    }
    private fun beginTemporaryState(next:TemporarySessionCredential) {
        clearInlineMemory()
        invalidateRun()
        credential = next
        summaries = emptyList(); plans = emptyList()
        inputRevision++
        ledger = AnalysisLedger()
        dirtyDraft = false
        set(CounselState(loading = false, contacts = state.value.contacts,
            conversation = Conversation(checkNotNull(credential).sessionId), modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO, modelSettings = settings))
    }
    private suspend fun beginTemporary() {
        val previous=credential
        beginTemporaryState(temporary.begin())
        previous?.let { temporary.remove(it) }
        refreshMemory(null)
    }
    /** Capture may only replace the context that was current at the user's click. */
    internal fun captureTemporary(expected:Long,current:()->Boolean,commit:((()->Unit)->Boolean),completion:(Long?)->Unit) = captureTemporary(expected,null,current,commit,completion)
    internal fun captureTemporary(expected:Long,moreId:String?,current:()->Boolean,commit:((()->Unit)->Boolean),completion:(Long?)->Unit) = enqueue {
        if(importAuthority!=expected || !current()) { completion(null);return@enqueue }
        if(!initialized) initializeNow(forCapture=true)
        if(importAuthority!=expected || !current()) { completion(null);return@enqueue }
        flushDraft()
        val next=temporary.begin()
        var claimed:Long?=null
        var previous:TemporarySessionCredential?=null
        val switched=synchronized(intentLock) {
            if(importAuthority!=expected || !current()) false
            else commit {
                val continuing=floatingMore?.takeIf { it.id==moreId && moreCurrent(it) }
                revokeFloatingMoreLocked()
                claimed=epoch.incrementAndGet();previous=credential;beginTemporaryState(next)
                if(continuing!=null) floatingMore=continuing.copy(epoch=checkNotNull(claimed))
            }
        }
        if(switched) { previous?.let { temporary.remove(it) };refreshMemory(null) }
        else temporary.remove(next)
        completion(claimed)
    }
    fun newTemporary() = enqueue(true) { flushDraft(); beginTemporary() }
    private suspend fun loadContact(contact: Contact, expectedEpoch: Long? = null, selectionToken: Long? = null): Boolean {
        clearInlineMemory()
        invalidateRun()
        recovery = Recovery.Load(contact)
        val saved = persistence.loadOrCreate(contact)
        val memorySnapshot=if(saved.sourceBlocked) MemorySnapshot() else persistence.memoryContextSnapshot(contact.id)
        val memoryContext=MemoryPresentation.context(contact.id,memorySnapshot)
        if (expectedEpoch != null && epoch.get() != expectedEpoch) return false
        if (selectionToken != null && selectionEpoch.get() != selectionToken) return false
        summaries = saved.summaries; plans = saved.plans
        val latestUser = saved.messages.lastOrNull { it.role == MessageRole.USER }
        val answerMessage = saved.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.answerJson != null &&
                it.requestId == latestUser?.requestId && latestUser?.text == saved.draft
        }
        val answerRequest = saved.runs.lastOrNull { it.requestId == answerMessage?.requestId }?.requestSnapshot
        val answer = answerMessage?.answerJson?.let { runCatching { json.decodeFromString(StructuredAnswer.serializer(), it) }.getOrNull() }
        val profile = persistence.redactionProfile(contact.id)
        val completedCurrentAnswer = !saved.sourceBlocked && (answerRequest==null || MemoryPresentation.sourcesRetained(answerRequest.actualDataContext,MemoryPresentation.retention(contact.id,memorySnapshot))) && AnswerSourcePolicy.matches(answerRequest,
            saved.runs.firstOrNull { it.requestId == answerMessage?.requestId }?.configRevision,
            saved.conversation, contact.id, contact.sourceRevision, saved.draft, saved.evidence, profile.revision, settings.saved.revision)
        val resumable = saved.runs.sortedBy { if (it.requestId == latestUser?.requestId) 1 else 0 }.lastOrNull { run ->
            val r = run.requestSnapshot
            !saved.sourceBlocked && r != null && r.actualDataContext==memoryContext && (run.requestId == latestUser?.requestId || (r.purpose == OutboundPurpose.LONG_RECORD_SUMMARY && !completedCurrentAnswer)) && run.status in setOf(RunStatus.RUNNING, RunStatus.INTERRUPTED, RunStatus.FAILED, RunStatus.CANCELLED) &&
                r.conversationId == saved.conversation.id && r.conversationRevision == saved.conversation.revision &&
                r.contactId == contact.id && r.sourceRevision == contact.sourceRevision && r.userText == saved.draft &&
                r.evidence == saved.evidence && r.redactionProfile.revision == profile.revision && run.configRevision == settings.saved.revision &&
                saved.messages.none { it.requestId == run.requestId && it.role == MessageRole.ASSISTANT }
        }?.let { it.copy(status = if (it.status == RunStatus.CANCELLED && it.requestSnapshot?.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) RunStatus.CANCELLED else RunStatus.INTERRUPTED, requestSnapshot = it.requestSnapshot?.copy(redactionProfile = profile)) }
        if (resumable != null) persistence.saveRun(contact, resumable.copy(requestSnapshot = resumable.requestSnapshot?.copy(redactionProfile = RedactionProfile(revision = profile.revision))))
        val summarySafety = saved.runs.filter { run ->
            val r = run.requestSnapshot
            r != null && r.purpose == OutboundPurpose.LONG_RECORD_SUMMARY && r.contactId == contact.id &&
                r.conversationId == saved.conversation.id && r.conversationRevision == saved.conversation.revision &&
                r.sourceRevision == contact.sourceRevision && r.userText == saved.draft && r.evidence == saved.evidence &&
                r.redactionProfile.revision == profile.revision && run.configRevision == settings.saved.revision
        }.maxByOrNull { it.highestSafety.ordinal }?.highestSafety ?: SafetyLevel.NONE
        val oldCredential = synchronized(intentLock) {
            // Target publication and its private actor state form one atomic token decision.
            if (expectedEpoch != null && epoch.get() != expectedEpoch) return false
            if (selectionToken != null && selectionEpoch.get() != selectionToken) return false
            val old = credential
            credential = null
            inputRevision++
            dirtyDraft = false
            ledger = AnalysisLedger(runs = saved.runs.map { if (it.requestId == resumable?.requestId) resumable else it }, messages = saved.messages)
            mutableMemory.value=displayed(MemoryUiState(contact.id,memorySnapshot),"inline")
            set(CounselState(loading = false, contacts = state.value.contacts, currentContact = contact,
                conversation = saved.conversation, draft = saved.draft, memoryContext=memoryContext,memoryRetention=MemoryPresentation.retention(contact.id,memorySnapshot),
                relationType = answerRequest?.manualRelation ?: answer?.scene?.relationType ?: contact.relationType,
                styleBias=styleBiasPreferences.get((answerRequest?.manualRelation ?: answer?.scene?.relationType ?: contact.relationType).group()),
                goal = answerRequest?.manualGoal ?: answer?.scene?.goal ?: Goal.REPLY,
                manualRelation = answerRequest?.manualRelation, manualGoal = answerRequest?.manualGoal, manualFormality = answerRequest?.manualFormality,
                formality = answerRequest?.manualFormality ?: answer?.scene?.formality?.takeIf { it in setOf("low", "medium", "high") } ?: "medium",
                messages = saved.messages, evidence = saved.evidence, answer = answer, answerSourceRevision = answerRequest?.sourceRevision, answerRequest = answerRequest, answerConfigRevision = saved.runs.lastOrNull { it.requestId == answerMessage?.requestId }?.configRevision, draftSaved = true, answerDemo = answerMessage?.providerKind != ProviderKind.BYOK,
                modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO, modelSettings = settings, redactionProfile = profile, safety = summarySafety,sourceSelectionBlocked=saved.sourceBlocked,captureSourcePreferred=saved.capturePreferred,
                error=if(saved.sourceBlocked) CounselError("CAPTURE_SOURCE_RECONFIRM") else null))
            recovery = resumable?.requestSnapshot?.let { r ->
                inputRevision = r.inputRevision
                set(state.value.copy(relationType = r.relationType ?: contact.relationType, goal = r.goal ?: Goal.REPLY,
                    tone = r.tone ?: Tone.NATURAL, emotion = r.emotionLevel, styleBias = r.styleBias,
                    manualRelation = r.manualRelation, manualGoal = r.manualGoal, manualFormality = r.manualFormality, formality = r.formality,
                    error = CounselError("INTERRUPTED", true), currentRequest = r, draftSaved = true, safety = resumable.highestSafety.upgrade(summarySafety)))
                Recovery.Analysis(r)
            }
            old
        }
        // A rejected publication must leave the previous temporary lease usable.
        oldCredential?.let { temporary.remove(it) }
        return true
    }
    fun selectContact(id: String) {
        synchronized(intentLock) {
            if (state.value.contacts.none { it.id == id && it.archivedAt == null }) return
            revokeFloatingCaptureLocked();epoch.incrementAndGet(); stream?.cancel()
            val token = selectionEpoch.incrementAndGet()
            mailbox.trySend {
                state.value.contacts.firstOrNull { it.id == id && it.archivedAt == null }?.let { flushDraft(); loadContact(it, selectionToken = token) }
            }
        }
    }
    fun createContact(alias: String, relation: RelationType) = enqueue(true) {
        if (alias.isBlank()) { set(state.value.copy(error = CounselError("CONTACT_ALIAS_REQUIRED"))); return@enqueue }
        flushDraft()
        val contact = Contact(UUID.randomUUID().toString(), alias.trim(), relation, createdAt = System.currentTimeMillis())
        invalidateRun()
        // A thrown create may already have committed. Retry only refreshes, never creates twice.
        recovery = Recovery.RefreshContacts
        persistence.createContact(contact)
        set(state.value.copy(contacts = persistence.contacts()))
        loadContact(contact)
    }
    fun deleteContact(id: String) {
        revokeContact(id)
        enqueue {
            if (state.value.currentContact?.id == id || recovery is Recovery.Delete) {
                val detailToken = detailEpoch.get()
                val requestedFromDetails = mutableDetails.value.ownerId == id
                try { performDelete(id) }
                catch (_: Exception) {
                    // The contact row may already be gone while its files still need cleanup.
                    // Preserve the original idempotent delete recovery and show a concrete retry.
                    set(state.value.copy(loading = false, streaming = false, error = CounselError("STORAGE_UNAVAILABLE", true)))
                    synchronized(intentLock) {
                        if (requestedFromDetails && detailEpoch.get() == detailToken)
                            mutableDetails.value = ContactDetailState(id, failed = true, deletePending = true)
                    }
                }
            } else {
                try {
                    persistence.deleteContact(id)
                    set(state.value.copy(contacts = persistence.contacts()))
                    if (mutableDetails.value.ownerId == id) closeContactDetails()
                    if (contactUndo?.after?.id == id) contactUndo = null
                } catch (_: Exception) {
                    // Keep B's request/recovery intact; A retains its own cleanup retry.
                    synchronized(intentLock) { if (mutableDetails.value.ownerId == id)
                        mutableDetails.value = ContactDetailState(id, failed = true, deletePending = true) }
                }
            }
        }
    }
    private suspend fun performDelete(id: String) {
        if (contactUndo?.after?.id == id) contactUndo = null
        val pendingError = if (recovery is Recovery.Delete) state.value.error else null
        if (state.value.currentContact?.id == id) invalidateRun()
        // The durable delete can commit before cleanup or refresh fails. Stop exposing the
        // deleted contact immediately, and retain the complete idempotent operation to retry.
        if (state.value.currentContact?.id == id) beginTemporary()
        set(state.value.copy(contacts = state.value.contacts.filterNot { it.id == id }, error = pendingError))
        recovery = Recovery.Delete(id)
        persistence.deleteContact(id)
        set(state.value.copy(contacts = persistence.contacts(), error = null))
        if (mutableDetails.value.ownerId == id) closeContactDetails()
        recovery = null
    }
    private suspend fun edit(transform: (CounselState) -> CounselState) {
        invalidateRun()
        if (state.value.conversation == null) return
        inputRevision++
        val next = transform(state.value).copy(answer = null, draftSaved = false)
        set(next)
        dirtyDraft = true
        flushDraft()
    }
    private suspend fun flushDraft() {
        if (!dirtyDraft) return
        recovery = Recovery.SaveDraft
        val current = state.value
        val previous = checkNotNull(current.conversation)
        val next = previous.copy(revision = previous.revision + 1, updatedAt = System.currentTimeMillis())
        val contact = current.currentContact
        if (contact != null) persistence.update(contact, previous, next, current.draft)
        else {
            val token = checkNotNull(credential)
            val session = checkNotNull(temporary.get(token))
            check(temporary.put(token, session.copy(draft = current.draft)))
        }
        // Keep the last successful CAS version on failure, while preserving the dirty text.
        set(state.value.copy(conversation = next, error = null, draftSaved = contact != null))
        dirtyDraft = false
        recovery = null
    }
    fun editDraft(text: String) = editDraftFrom(text, state.value.conversation?.id, null)
    fun editDraft(text: String, stamp: DraftEditStamp) = editDraftFrom(text, stamp.originConversationId, stamp)
    private fun editDraftFrom(text: String, origin: String?, stamp: DraftEditStamp?) = enqueueFrom(origin, true) {
        edit { it.copy(draft = text, draftEditAck = stamp) }
    }
    /** Pasted text is a user statement, never confirmed dialogue evidence. */
    fun pasteText(text: String) = editDraft(text)
    fun pasteText(text: String, stamp: DraftEditStamp) = editDraft(text, stamp)
    fun setRelation(value: RelationType) = enqueue(true) { edit { it.copy(relationType = value, manualRelation = value,styleBias=styleBiasPreferences.get(value.group())) } }
    fun setGoal(value: Goal) = enqueue(true) { edit { it.copy(goal = value, manualGoal = value) } }
    fun setEmotion(value: Int?) = enqueue(true) { edit { it.copy(emotion = value?.coerceIn(0, 10)) } }
    fun setStyleBias(value: Float) = enqueue(true) {
        val selected=if(value.isFinite()) value.coerceIn(0f,1f) else 0.5f
        styleBiasPreferences.set(state.value.relationType.group(),selected)
        edit {it.copy(styleBias=selected)}
    }
    fun setTone(value: Tone) = enqueue(true) { edit { it.copy(tone = value) } }
    fun saveRedactionProfile(origin: String?, terms: List<String>, aliases: List<RedactionAlias>) = enqueueFrom(origin, true) {
        invalidateRun()
        try {
            val previous = state.value.currentContact?.let { persistence.redactionProfile(it.id) } ?: state.value.redactionProfile
            val next = RedactionProfile(previous.revision + 1, terms, aliases)
            com.shengjidaguai.goutoujunshi.knowledge.Redactor(next.terms, next.aliases)
            // Publish before IO so uncertain saves cannot authorize a previous frozen request.
            inputRevision++
            set(state.value.copy(redactionProfile = next, redactionError = false, answer = null))
            state.value.currentContact?.let { persistence.saveRedactionProfile(it.id, previous.revision, next) }
        } catch (_: Exception) { set(state.value.copy(redactionError = true, error = CounselError("REDACTION_INVALID"))) }
    }
    private fun history(current: CounselState): String {
        val pieces = ledger.messages.filter { it.role == MessageRole.ASSISTANT && it.status == MessageStatus.COMPLETED && it.contactId == current.currentContact?.id }.mapNotNull { message ->
            val run = ledger.runs.firstOrNull { it.requestId == message.requestId } ?: return@mapNotNull null
            val original = run.requestSnapshot ?: return@mapNotNull null
            if (!MemoryPresentation.sourcesRetained(original.actualDataContext,current.memoryRetention) || original.purpose != OutboundPurpose.ANALYSIS || !ContinuousAnalysis.sourcesRetained(original, current.currentContact?.id, current.evidence) || original.redactionProfile.revision != current.redactionProfile.revision || run.configRevision != settings.saved.revision) return@mapNotNull null
            val answer = runCatching { json.decodeFromString(StructuredAnswer.serializer(), message.answerJson ?: "") }.getOrNull() ?: return@mapNotNull null
            val choice = plans.lastOrNull { it.sourceRequestId == message.requestId && it.sourceAnswerId == message.id }?.let { "${it.status}:${ContinuousAnalysis.historyExcerpt(it.chosenReply, 80)}" } ?: "未记录选择"
            ContinuousAnalysis.scopeHistoricalTokens("历史建议（${if (message.providerKind == ProviderKind.DEMO) "虚构演示" else "模型分析，非聊天事实"}） VERDICT=${ContinuousAnalysis.historyExcerpt(answer.verdict, 100)} NEXT=${ContinuousAnalysis.historyExcerpt(answer.next, 100)} 用户选择=$choice", "历史${message.requestId.hashCode().toUInt().toString(16)}")
        }
        return ContinuousAnalysis.historyExcerpt(pieces.takeLast(2).joinToString("\n"), 900)
    }
    private fun nextRequest(candidateSource: AnalysisRequest, authority: RunAuthority): AnalysisRequest = authorized(authority) {
        val source=if(candidateSource.floating) candidateSource.copy(redactionTerms=(candidateSource.redactionTerms+candidateSource.evidence.flatMap { it.lines }.mapNotNull { it.speakerName }).filter { it.isNotBlank() }.distinct()) else candidateSource
        requireFloatingBudget(source)
        if (source.networkSelection != null || source.mode != RequestMode.FULL_ANALYSIS || source.purpose != OutboundPurpose.ANALYSIS) return@authorized source
        val plan = ContinuousAnalysis.plan(source.evidence)
        if (!plan.needsSummary || !settings.saved.live) return@authorized source.copy(networkSelection = plan.recent.map { it.id })
        val candidate = source.copy(purpose = OutboundPurpose.LONG_RECORD_SUMMARY, recentSummary = "", networkSelection = plan.summarySelection.map { it.id })
        val key = cacheKey(candidate)
        summaryKey = key
        val cached = summaries.firstOrNull { it.request.requestId !in blockedSummaryRequests && ledger.runs.any { run -> run.requestId == it.request.requestId && run.status == RunStatus.COMPLETED } && it.key == key && it.request.sourceRevision == source.sourceRevision && ContinuousAnalysis.sourcesRetained(it.request, source.contactId, source.evidence) && ContinuousAnalysis.validSummary(it.text, plan.summarySelection) }
        if (cached != null) set(state.value.copy(summaryReady = true))
        if (cached != null) source.copy(networkSelection = plan.recent.map { it.id }, timelineSummary = cached.text)
        else candidate
    }
    private fun cacheKey(candidate: AnalysisRequest): String {
        val prepared = assembler.prepare(candidate)
        val version = prepared.knowledgeVersion + prepared.system
        val identity = json.encodeToString(AnalysisRequest.serializer(), candidate.copy(requestId = "", inputRevision = 0)) + settings.saved.revision + version
        return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private suspend fun request(authority: RunAuthority): AnalysisRequest {
        val current = authorized(authority) { state.value }
        val conversation = checkNotNull(current.conversation)
        val memorySnapshot=persistence.memoryContextSnapshot(current.currentContact?.id)
        val context=MemoryPresentation.context(current.currentContact?.id,memorySnapshot)
        val styleGroup=ScenePredictor.predict(current.currentContact?.relationType,current.draft,current.evidence,current.manualRelation,current.manualGoal).relationType?.group() ?: RelationGroup.DEFAULT
        val persistentStyle=current.currentContact?.let {persistence.styleContext(it.id,styleGroup)} ?: StyleOutboundContext()
        val localStyle=StylePersistencePolicy.retainSamples(current.evidence.flatMap {it.lines}.filter {it.speaker==Speaker.ME && it.speakerConfirmed}.map {
            StyleSample(id="ram:${it.id}",relationGroup=styleGroup,text=it.text,origin=StyleOrigin.IMPORT,sourceContactId=it.contactId,sourceImportId=it.importId,sourceMessageId=it.id)
        }+plans.filter {it.wasEdited && it.sourceRevision==(current.currentContact?.sourceRevision ?: 0) && current.messages.any {message->message.id==it.sourceAnswerId && message.role==MessageRole.ASSISTANT}}.map {StyleSample(id="ram:${it.id}",relationGroup=styleGroup,text=it.chosenReply,origin=StyleOrigin.EDITED_REPLY,sourceContactId=it.contactId,sourceAnswerId=it.sourceAnswerId)})
        val persistentAllowed=current.currentContact!=null && memorySnapshot.consent.enabled && !memorySnapshot.consent.paused
        val ramQuiz=styleCoordinator.quizFor(conversation.id).mapIndexed {index,text->StyleSample("ram:quiz:$index",RelationGroup.DEFAULT,text,StyleOrigin.QUIZ)}
        val style=if(persistentAllowed) persistentStyle else StyleOutboundContext(samples=if(localStyle.size>=3) localStyle.take(5) else ramQuiz)
        return authorized(authority) {
        mutableMemory.value=displayed(memory.value.copy(ownerId=current.currentContact?.id,snapshot=memorySnapshot),"inline")
        set(state.value.copy(memoryContext=context,memoryRetention=MemoryPresentation.retention(current.currentContact?.id,memorySnapshot)))
        AnalysisRequest(requestId = UUID.randomUUID().toString(), inputRevision = inputRevision,
            contactId = current.currentContact?.id,
            relationType = ScenePredictor.predict(current.currentContact?.relationType, current.draft, current.evidence, current.manualRelation, current.manualGoal).relationType,
            goal = ScenePredictor.predict(current.currentContact?.relationType, current.draft, current.evidence, current.manualRelation, current.manualGoal).goal, formality = current.formality,
            manualRelation = current.manualRelation, manualGoal = current.manualGoal, manualFormality = current.manualFormality,
            userText = current.draft, emotionLevel = current.emotion, styleBias = styleBiasPreferences.get(styleGroup), tone = current.tone,
            conversationId = conversation.id, conversationRevision = conversation.revision,
            sourceRevision = current.currentContact?.sourceRevision ?: 0,
            redactionProfile = current.redactionProfile,
            styleSource=style.takeIf {persistentAllowed},styleContext=style.description,styleSamples=style.samples,redactionTerms=(style.sourceAliases+current.evidence.flatMap {it.lines}.mapNotNull {it.speakerName}+listOfNotNull(current.currentContact?.alias)).distinct(),
            actualDataContext=context, recentSummary = history(current.copy(memoryContext=context,memoryRetention=MemoryPresentation.retention(current.currentContact?.id,memorySnapshot))), evidence = current.evidence, speakersConfirmed = current.evidence.isNotEmpty() && current.evidence.flatMap { it.lines }.all { it.speakerConfirmed })
        }
    }
    private fun ordinaryAllowed() = !state.value.sourceSelectionBlocked && (floatingSideOperation?.let { it.epoch==epoch.get() }!=true) && pendingSummaryCleanup.isEmpty() && SafetyPolicy.evaluate(state.value.emotion, state.value.safety.upgrade(state.value.answer?.safety ?: SafetyLevel.NONE), SafetyLevel.NONE, state.value.lockState).showOrdinaryStrategy
    private suspend fun answerSourceCurrent(): Boolean {
        val current = state.value
        if (!current.hasCurrentAnswerSource()) return false
        val source = current.messages.last { it.role == MessageRole.ASSISTANT && it.requestId == current.answerRequest?.requestId }
        val contact = current.currentContact
        val stored = if (contact == null) null else {
            val owner = persistence.contacts().firstOrNull { it.id == contact.id } ?: return false
            if (owner.sourceRevision != contact.sourceRevision) return false
            persistence.loadOrCreate(contact)
        }
        val run = (stored?.runs ?: ledger.runs).lastOrNull { it.requestId == source.requestId } ?: return false
        val original = run.requestSnapshot ?: return false
        if (run.inputRevision != original.inputRevision || run.conversationId != original.conversationId ||
            run.conversationRevision != original.conversationRevision || run.contactId != original.contactId ||
            run.mode != original.mode || run.parentAnswerId != original.parentAnswerId) return false
        if (!AnswerSourcePolicy.matches(original, run.configRevision, current.conversation, contact?.id,
            contact?.sourceRevision ?: 0, current.draft, current.evidence, current.redactionProfile.revision, settings.saved.revision)) return false
        if (stored == null) return true
        val memorySnapshot=persistence.memoryContextSnapshot(contact!!.id)
        if(!MemoryPresentation.sourcesRetained(original.actualDataContext,MemoryPresentation.retention(contact.id,memorySnapshot))) return false
        return stored.messages.any { it.id == source.id && it.answerJson == source.answerJson } &&
            stored.conversation == current.conversation && stored.draft == current.draft && stored.evidence == current.evidence &&
            persistence.redactionProfile(contact!!.id).revision == original.redactionProfile.revision
    }
    fun changeScene(origin: String?, answerId: String, relation: RelationType, goal: Goal, formality: String) = enqueueFrom(origin) { submissionEpoch ->
        val submitted=authoritySnapshot(submissionEpoch)
        if (!ordinaryAllowed() || state.value.answer?.id != answerId || state.value.streaming || !answerSourceCurrent()) return@enqueueFrom
        authorized(submitted) { Unit }
        val original = checkNotNull(state.value.answer)
        val authority=authorized(submitted) { authoritySnapshot(epoch.incrementAndGet()) }
        edit { it.copy(relationType = relation, manualRelation = relation, goal = goal, manualGoal = goal, formality = formality.takeIf { f -> f in setOf("low", "medium", "high") } ?: "medium", manualFormality = formality.takeIf { f -> f in setOf("low", "medium", "high") } ?: "medium") }
        val request=request(authority).copy(parentAnswerId = answerId, originalAnswer = original)
        authorized(authority) {
            previewRequest = request
            if (settings.saved.live) preparePreview(origin) else analyze(origin)
        }
    }
    fun rewrite(origin: String?, answerId: String, tone: Tone, bias: Float, requirePreview: Boolean = false, onPrepared: (AnalysisRequest)->Unit = {}) = enqueueFrom(origin) { submissionEpoch ->
        val submitted=authoritySnapshot(submissionEpoch)
        val original = state.value.answer ?: return@enqueueFrom
        if (original.id != answerId || !ordinaryAllowed() || state.value.streaming || state.value.error != null || !answerSourceCurrent()) return@enqueueFrom
        val captureBefore=floatingCapture?.takeIf { it.epoch==submitted.epoch && requirePreview }
        val authority=authorized(submitted) { authoritySnapshot(epoch.incrementAndGet()) }
        inputRevision++
        if(captureBefore!=null) floatingCapture=captureBefore.copy(epoch=authority.epoch,inputRevision=inputRevision)
        invalidateRun()
        val base=request(authority)
        val selectedBias=if(bias.isFinite()) bias.coerceIn(0f,1f) else 0.5f
        styleBiasPreferences.set(state.value.relationType.group(),selectedBias)
        val snapshot = base.copy(mode = RequestMode.REWRITE_REPLY, purpose = OutboundPurpose.REPLY_REWRITE,
            parentAnswerId = original.id, originalAnswer = original, tone = tone, styleBias = selectedBias, floating=requirePreview,
            redactionTerms=(base.redactionTerms+if(requirePreview) state.value.evidence.flatMap { it.lines }.mapNotNull { it.speakerName } else emptyList()).distinct())
        try { requireFloatingBudget(snapshot) } catch(_:FloatingOpenFull) { floatingOpenFull(authority);return@enqueueFrom }
        if (settings.saved.live || requirePreview) {
            if(snapshot.actualDataContext!=MemoryPresentation.context(snapshot.contactId,persistence.memoryContextSnapshot(snapshot.contactId))) {
                authorized(authority) { invalidateRun() }; return@enqueueFrom
            }
            val prepared = withContext(Dispatchers.Default) { assembler.prepare(snapshot) }
            authorized(authority) {
                if(state.value.conversation?.id != origin) throw RevokedOperation()
                previewAuthority=authoritySnapshot()
                previewRequest = snapshot
                set(state.value.copy(preview = prepared, outboundBody = outboundBody(prepared)))
                onPrepared(snapshot)
            }
        } else start(snapshot,authority)
    }
    fun feedback(origin: String?, answerId: String, edited: String? = null) = enqueueFrom(origin) { operationEpoch ->
        val current = state.value
        val answer = current.answer ?: return@enqueueFrom
        if (!ordinaryAllowed() || answer.id != answerId || current.streaming || (current.error != null && recovery !is Recovery.Feedback) || !answerSourceCurrent()) return@enqueueFrom
        if (epoch.get() != operationEpoch) return@enqueueFrom
        val source = current.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.answerJson?.let { raw -> runCatching { json.decodeFromString(StructuredAnswer.serializer(), raw).id }.getOrNull() } == answerId } ?: return@enqueueFrom
        val plan = ActionPlan(id = "feedback:${source.id}:${if (edited == null) "sent" else java.security.MessageDigest.getInstance("SHA-256").digest(edited.toByteArray()).joinToString("") { "%02x".format(it) }}", contactId = current.currentContact?.id,
            chosenReply = edited ?: answer.reply.joinToString("\n"), wasEdited = edited != null, status = if (edited == null) PlanStatus.SENT else PlanStatus.PENDING,
            sourceAnswerId = source.id, sourceRequestId = source.requestId, conversationId = current.conversation!!.id,
            inputRevision = ledger.runs.firstOrNull { it.requestId == source.requestId }?.inputRevision ?: inputRevision, conversationRevision = current.conversation.revision, sourceRevision = current.currentContact?.sourceRevision ?: 0)
        if (plan.chosenReply.isBlank()) return@enqueueFrom
        try {
            if (current.currentContact != null) persistence.saveFeedback(current.currentContact, plan, current.redactionProfile.revision)
            else { val lease = checkNotNull(credential); val session = checkNotNull(temporary.get(lease)); check(temporary.put(lease, session.copy(plans = session.plans.filterNot { it.id == plan.id } + plan))) }
            if (epoch.get() == operationEpoch) { recovery = null; plans = plans.filterNot { it.id == plan.id } + plan
            set(state.value.copy(feedbackSaved = true, error = null)) }
        } catch (_: Exception) {
            recovery = Recovery.Feedback(origin, answerId, edited)
            set(state.value.copy(feedbackSaved = false, error = CounselError("STORAGE_UNAVAILABLE", true)))
        }
    }
    fun preparePreview() = preparePreview(state.value.conversation?.id)
    fun preparePreview(originConversationId: String?, floating: Boolean = false, onPrepared: (AnalysisRequest)->Unit = {}) = enqueueFrom(originConversationId) preview@{ operationEpoch ->
        val authority=authoritySnapshot(operationEpoch)
        authorized(authority) { Unit }
        if (!ordinaryAllowed()) return@preview
        if (state.value.redactionError) return@preview
        if (importPending || state.value.loading || state.value.conversation == null) return@preview
        if (state.value.streaming) return@preview
        if (state.value.draft.isBlank() && state.value.evidence.isEmpty()) { set(state.value.copy(error = CounselError("INPUT_REQUIRED"))); return@preview }
        flushDraft()
        authorized(authority) { Unit }
        val interrupted = (recovery as? Recovery.Analysis)?.request?.takeIf { state.value.error?.code == "INTERRUPTED" }
        recovery = Recovery.Preview
        try {
            // Preview belongs to the next send. Only retry may reuse a failed/cancelled run.
            var snapshot = nextRequest((previewRequest ?: interrupted ?: request(authority)).copy(floating=floating),authority)
            if (snapshot.purpose == OutboundPurpose.LONG_RECORD_SUMMARY && finishSavedSummary(snapshot,authority)) {
                authorized(authority) { Unit }
                snapshot = nextRequest(request(authority),authority)
            }
            val prepared = withContext(Dispatchers.Default) { assembler.prepare(snapshot) }
            authorized(authority) {
                previewAuthority=authoritySnapshot()
                previewRequest = snapshot
                set(state.value.copy(currentRequest = if (snapshot.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) snapshot else state.value.currentRequest, preview = prepared, outboundBody = outboundBody(prepared), error = null))
                recovery = null
                onPrepared(snapshot)
            }
        }
        catch (_: RevokedOperation) { return@preview }
        catch (_: FloatingOpenFull) { floatingOpenFull(authority) }
        catch (_: Exception) { authorized(authority) { set(state.value.copy(error = CounselError(if (recovery is Recovery.FinishSummary) "STORAGE_UNAVAILABLE" else "PROMPT_UNAVAILABLE", true))) } }
    }
    private suspend fun finishSavedSummary(request: AnalysisRequest, authority: RunAuthority): Boolean {
        authorized(authority) { Unit }
        val key = cacheKey(request)
        val selected = request.evidence.flatMap { it.lines }.filter { it.id in request.networkSelection.orEmpty() }
        val completed = summaries.any { cache -> cache.key == key && cache.request.requestId !in blockedSummaryRequests &&
            cache.request.sourceRevision == request.sourceRevision && ContinuousAnalysis.sourcesRetained(cache.request, request.contactId, request.evidence) &&
            ContinuousAnalysis.validSummary(cache.text, selected) && ledger.runs.any { it.requestId == cache.request.requestId && it.status == RunStatus.COMPLETED } }
        if (completed) {
            authorized(authority) {
                recovery = null
                set(state.value.copy(summaryReady = true, currentRequest = null, error = null))
            }
            return true
        }
        val run = ledger.runs.firstOrNull { it.requestId == request.requestId } ?: return false
        if (run.status !in setOf(RunStatus.RUNNING, RunStatus.INTERRUPTED) || request.requestId in blockedSummaryRequests) return false
        if (summaries.none { it.key == key && it.request.requestId == request.requestId && ContinuousAnalysis.sourcesRetained(it.request, request.contactId, request.evidence) && ContinuousAnalysis.validSummary(it.text, selected) }) return false
        authorized(authority) { recovery = Recovery.FinishSummary(request) }
        checkpoint(request, RunStatus.COMPLETED)
        authorized(authority) {
            recovery = null
            set(state.value.copy(summaryReady = true, currentRequest = null, error = null))
        }
        return true
    }
    private suspend fun retrySummaryCleanup() {
        for ((key, pending) in pendingSummaryCleanup.toMap()) {
            val (cache, _) = pending
            try { if (cache.request.contactId != null) persistence.discardSummary(cache); pendingSummaryCleanup.remove(key) }
            finally { summaries = summaries.filterNot { it.key == cache.key && it.request.requestId == cache.request.requestId } }
        }
        recovery = null
        set(state.value.copy(error = null))
    }
    private suspend fun rejectCancelledSummary(cache: TimelineSummary) {
        blockedSummaryRequests += cache.request.requestId
        try { if (cache.request.contactId != null) persistence.discardSummary(cache) }
        catch (_: Exception) {
            val run = ledger.runs.firstOrNull { it.requestId == cache.request.requestId }
            pendingSummaryCleanup[cache.key + ":" + cache.request.requestId] = cache to run
            // A failed deletion is visible and retryable. A durable cancellation also prevents
            // another controller from treating the retained row as a completed cache receipt.
            if (run != null && cache.request.contactId != null) runCatching {
                val cancelled = run.copy(status = RunStatus.CANCELLED, highestSafety = run.highestSafety.upgrade(state.value.safety))
                persistence.saveRun(Contact(id = checkNotNull(cache.request.contactId), sourceRevision = cache.request.sourceRevision), cancelled)
                ledger = ledger.copy(runs = ledger.runs.map { if (it.requestId == run.requestId) cancelled else it })
            }
            set(state.value.copy(streaming = false, error = CounselError("STORAGE_UNAVAILABLE", true)))
        } finally {
            summaries = summaries.filterNot { it.key == cache.key && it.request.requestId == cache.request.requestId }
        }
    }
    internal fun analyzeFloating(origin:String?,expected:AnalysisRequest?,onStarted:(AnalysisRequest)->Unit={}) = analyzeScoped(origin,true,expected,onStarted)
    fun analyze() = analyze(state.value.conversation?.id)
    fun analyze(originConversationId: String?, onStarted: (AnalysisRequest)->Unit = {}) = analyzeScoped(originConversationId,false,null,onStarted)
    private fun analyzeScoped(originConversationId:String?,floating:Boolean,expected:AnalysisRequest?,onStarted:(AnalysisRequest)->Unit) = enqueueFrom(originConversationId) analyze@{ submissionEpoch ->
        val authority=authoritySnapshot(submissionEpoch)
        authorized(authority) { Unit }
        if (!ordinaryAllowed()) return@analyze
        if (state.value.redactionError) return@analyze
        if (state.value.testingConnection) return@analyze
        if(floating && (expected==null || expected!=previewRequest || !expected.floating || previewAuthority?.let(::authorityCurrent)!=true)) return@analyze
        if (importPending || state.value.loading || state.value.conversation == null) return@analyze
        if (state.value.streaming) return@analyze
        if (state.value.draft.isBlank() && state.value.evidence.isEmpty() && pendingStyle==null) { set(state.value.copy(error = CounselError("INPUT_REQUIRED"))); return@analyze }
        flushDraft()
        authorized(authority) { Unit }
        if (settings.saved.live && previewRequest == null) {
            set(state.value.copy(error = CounselError("PREVIEW_REQUIRED"))); return@analyze
        }
        val snapshot = previewRequest ?: (recovery as? Recovery.Analysis)?.request?.takeIf { state.value.error?.code == "INTERRUPTED" } ?: nextRequest(request(authority),authority)
        authorized(authority) { previewRequest = null } // One preview authorizes one operation.
        start(snapshot,authority,onStarted)
    }
    fun retry() {
        val authority=authoritySnapshot()
        enqueue {
        if (pendingSummaryCleanup.isNotEmpty()) { retrySummaryCleanup(); return@enqueue }
        if (state.value.streaming || state.value.error?.retryable != true) return@enqueue
        when (val pending = recovery) {
            is Recovery.Feedback -> feedback(pending.origin, pending.answerId, pending.edited)
            Recovery.Initialize -> initialize()
            is Recovery.Load -> loadContact(pending.contact)
            Recovery.SaveDraft -> flushDraft()
            is Recovery.Analysis -> {
                authorized(authority) { Unit }
                if (settings.saved.live) {
                    val prepared = assembler.prepare(pending.request)
                    authorized(authority) {
                        previewRequest = pending.request
                        set(state.value.copy(preview = prepared, outboundBody = outboundBody(prepared), error = null))
                    }
                } else start(pending.request,authority)
            }
            is Recovery.Checkpoint -> fail(pending.code, pending.retryable)
            is Recovery.Complete -> {
                val current=synchronized(intentLock) {
                if (isCurrent(pending.request, pending.token)) {
                    set(state.value.copy(streaming = true, error = null))
                    true
                } else false
                }
                if(current) receive(pending.request, pending.token, AnalysisChunk.Completed(pending.answer))
            }
            is Recovery.FinishSummary -> { if (finishSavedSummary(pending.request,authority)) authorized(authority) { preparePreview() } }
            Recovery.Preview -> authorized(authority) { preparePreview() }
            Recovery.RefreshContacts -> {
                set(state.value.copy(contacts = persistence.contacts(), error = null))
                recovery = null
            }
            is Recovery.Delete -> performDelete(pending.contactId)
            Recovery.Clear -> clearAll()
            null -> Unit
        }
        }
    }
    private fun isCurrent(request: AnalysisRequest, token: Long): Boolean = synchronized(intentLock) { authorityLock.canDisplaySensitiveContent && runLockGeneration == lockGeneration && epoch.get() == token && frozen == request &&
        request.inputRevision == inputRevision && request.conversationId == state.value.conversation?.id &&
        request.conversationRevision == state.value.conversation?.revision && request.contactId == state.value.currentContact?.id
    }
    private fun publishRun(request: AnalysisRequest, token: Long, next: CounselState): Boolean = synchronized(intentLock) {
        if(!isCurrent(request,token)) return false
        set(next)
        true
    }

    private suspend fun persistMessage(message: Message) {
        val current = state.value
        val contact = current.currentContact
        if (contact != null) persistence.saveMessage(contact, checkNotNull(current.conversation), message)
        else {
            val token = checkNotNull(credential)
            val session = checkNotNull(temporary.get(token))
            check(temporary.put(token, session.copy(messages = session.messages.filterNot { it.id == message.id } + message)))
        }
    }
    private suspend fun start(request: AnalysisRequest, authority: RunAuthority, onStarted:(AnalysisRequest)->Unit = {}) {
        authorized(authority) { Unit }
        try { requireFloatingBudget(request) } catch(_:FloatingOpenFull) { floatingOpenFull(authority);return }
        val snapshot=persistence.memoryContextSnapshot(request.contactId)
        authorized(authority) { Unit }
        if(request.purpose!=OutboundPurpose.STYLE_DESCRIPTION && request.actualDataContext!=MemoryPresentation.context(request.contactId,snapshot)) { authorized(authority) { invalidateRun() }; return }
        if(request.styleSource!=null && request.contactId!=null && request.styleSource!=persistence.styleContext(requireNotNull(request.contactId),(request.relationType ?: RelationType.ACQUAINTANCE).group())) { authorized(authority) {invalidateRun()};return }
        if(request.purpose==OutboundPurpose.STYLE_DESCRIPTION && pendingStyle?.snapshot?.stamp!=persistence.styleSnapshot().stamp) {authorized(authority) {invalidateRun()};return}
        authorized(authority) { proposalStamp=snapshot.stamp }
        if (request.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) {
            summaryKey = cacheKey(request)
            val previous = ledger.runs.firstOrNull { it.requestId == request.requestId }
            if (previous?.status in setOf(RunStatus.CANCELLED, RunStatus.FAILED)) {
                for (cache in summaries.filter { it.request.requestId == request.requestId }) {
                    rejectCancelledSummary(cache)
                    authorized(authority) { Unit }
                    if (pendingSummaryCleanup.isNotEmpty()) return
                }
                blockedSummaryRequests.remove(request.requestId)
            }
        }
        val operationProvider = provider
        val priorSafety = (if (state.value.currentRequest?.requestId == request.requestId) state.value.safety else SafetyLevel.NONE)
            .upgrade(ledger.runs.firstOrNull { it.requestId == request.requestId }?.highestSafety ?: SafetyLevel.NONE)
            .upgrade(request.originalAnswer?.safety ?: SafetyLevel.NONE)
            .upgrade(if (state.value.summaryReady) state.value.safety else SafetyLevel.NONE)
        val token = authorized(authority) {
            // Advancing a run is permitted only by the original, still-current submission.
            val next=epoch.incrementAndGet()
            runLockGeneration=authority.lockGeneration
            floatingCapture?.takeIf { it.epoch==authority.epoch && request.floating }?.let { floatingCapture=it.copy(epoch=next,inputRevision=inputRevision) }
            activeRunAuthority=RunAuthority(next,authority.lockGeneration)
            frozen = request
            floatingStartedAt=if(request.floating) monotonicMillis() else null
            recovery = Recovery.Analysis(request)
            set(state.value.copy(streaming = true, partial = emptyMap(), answer = null, safety = priorSafety,
                answerDemo = operationProvider.kind == ProviderKind.DEMO, error = null, currentRequest = request))
            onStarted(request)
            next
        }
        try {
            val prepared = withContext(Dispatchers.Default) { assembler.prepare(request) }
            synchronized(intentLock) {
                if (!isCurrent(request, token)) return
                set(state.value.copy(preview = prepared))
            }
        } catch (_: Exception) { if(isCurrent(request,token)) fail("PROMPT_UNAVAILABLE", true); return }
        val begun = synchronized(intentLock) {
            if(!isCurrent(request,token)) return
            ledger.begin(request, "user:${request.requestId}")
        }
        val user = begun.messages.firstOrNull { it.requestId == request.requestId && it.role == MessageRole.USER }
        if (request.purpose!=OutboundPurpose.STYLE_DESCRIPTION && user != null && ledger.messages.none { it.id == user.id }) persistMessage(user)
        synchronized(intentLock) {
            if(!isCurrent(request,token)) return
            ledger = begun
        }
        checkpoint(request, RunStatus.RUNNING)
        synchronized(intentLock) {
        if (!isCurrent(request, token)) return
        set(state.value.copy(messages = ledger.messages))
        waitingJob?.cancel()
        set(state.value.copy(waiting = WaitingPhase.at(0)))
        waitingJob = scope.launch {
            val started = monotonicMillis()
            while (isActive) {
                delay(250)
                mailbox.send {
                    if (isCurrent(request, token) && state.value.streaming)
                        set(state.value.copy(waiting = WaitingPhase.at(monotonicMillis() - started)))
                }
            }
        }
        // Enter the provider while this run is authorized. On its first suspension the Job
        // is assigned before the monitor is released, so a concurrent lock can cancel it.
        stream = scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                if(!isCurrent(request,token)) return@launch
                ensureActive()
                (when(request.purpose) {OutboundPurpose.LONG_RECORD_SUMMARY->OutboundEngine(operationProvider).summarize(request);OutboundPurpose.STYLE_DESCRIPTION->OutboundEngine(operationProvider).describeStyle(request);else->operationProvider.analyze(request)}).collect { chunk -> mailbox.send { receive(request, token, chunk) } }
                mailbox.send {
                    if (isCurrent(request, token) && state.value.streaming) fail("STREAM_INCOMPLETE", true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mailbox.send { if (isCurrent(request, token)) fail("ANALYSIS_UNAVAILABLE", true) } }
        }
        }
    }
    private suspend fun checkpoint(request: AnalysisRequest, status: RunStatus) {
        val run = ledger.runs.firstOrNull { it.requestId == request.requestId } ?: return
        val next = run.copy(status = status, requestSnapshot = request.copy(redactionProfile = RedactionProfile(revision = request.redactionProfile.revision)), configRevision = settings.saved.revision, highestSafety = run.highestSafety.upgrade(state.value.safety))
        if(request.purpose!=OutboundPurpose.STYLE_DESCRIPTION) state.value.currentContact?.let { persistence.saveRun(it, next) }
        ledger = ledger.copy(runs = ledger.runs.map { if (it.requestId == next.requestId) next else it })
    }
    private suspend fun fail(code: String, retryable: Boolean) {
        waitingJob?.cancel()
        // Checkpoint failure preserves the request but never offers a blind network resend.
        frozen?.let {
            recovery = Recovery.Checkpoint(it, code, retryable)
            checkpoint(it, RunStatus.FAILED)
            recovery = Recovery.Analysis(it)
        }
        set(state.value.copy(streaming = false, error = CounselError(code, retryable)))
    }
    private suspend fun receive(request: AnalysisRequest, token: Long, chunk: AnalysisChunk) {
        if (!isCurrent(request, token) || !state.value.streaming) return
        when (chunk) {
            is AnalysisChunk.Section -> {
                val partial = state.value.partial + (chunk.id to (state.value.partial[chunk.id].orEmpty() + chunk.textDelta))
                val received = SafetyLevel.entries.filter { Regex("\\b${it.name}\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(partial[SectionId.SAFETY].orEmpty()) }.maxByOrNull { it.ordinal } ?: SafetyLevel.NONE
                val previousSafety = state.value.safety
                val highest = previousSafety.upgrade(received)
                if(!publishRun(request,token,state.value.copy(partial = partial, safety = highest))) return
                if (highest != previousSafety) {
                    recovery = Recovery.Checkpoint(request, "INTERRUPTED", true)
                    try {
                        checkpoint(request, RunStatus.RUNNING)
                        recovery = Recovery.Analysis(request)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        // Stop the billable stream; retry repairs storage first and only then
                        // offers explicit continuation, including a fresh LIVE preview.
                        stream?.cancel(); waitingJob?.cancel(); previewRequest = null
                        publishRun(request,token,state.value.copy(streaming = false, preview = null, outboundBody = null,
                            error = CounselError("STORAGE_UNAVAILABLE", true)))
                    }
                }
            }
            is AnalysisChunk.Failed -> fail(chunk.error.code, chunk.error.retryable)
            is AnalysisChunk.Completed -> {
                val conversationRevision = checkNotNull(state.value.conversation).revision
                val highestAnswer = chunk.answer.copy(safety = state.value.safety.upgrade(chunk.answer.safety))
                val displayAnswer = state.value.preview?.let { SafeAliasRestorer.restore(highestAnswer, it) } ?: highestAnswer
                val answer = (AnswerPolicy.apply(request, displayAnswer, inputRevision, conversationRevision) ?: return).let { it.copy(id = if (request.mode == RequestMode.REWRITE_REPLY || it.id.isBlank()) "answer:${request.requestId}" else it.id) }
                if(!publishRun(request,token,state.value.copy(safety = state.value.safety.upgrade(answer.safety)))) return
                if(request.purpose==OutboundPurpose.STYLE_DESCRIPTION) {
                    if(state.value.answerDemo) {fail("STYLE_REQUIRES_MODEL",false);return}
                    val target=pendingStyle ?: return
                    val text=highestAnswer.sections[SectionId.FREE_TEXT].orEmpty().ifBlank {highestAnswer.freeText}.trim()
                    if(text.isBlank() || text.codePointCount(0,text.length)>200 || answer.safety==SafetyLevel.CRISIS) {fail("STYLE_INVALID",true);return}
                    persistence.styleGenerated(target.group,text,target.snapshot.stamp) {isCurrent(request,token)}
                    if(!isCurrent(request,token)) return
                    waitingJob?.cancel();frozen=null;recovery=null;pendingStyle=null;previewRequest=null
                    ledger=ledger.copy(messages=ledger.messages.filterNot {it.requestId==request.requestId})
                    set(state.value.copy(streaming=false,partial=emptyMap(),currentRequest=null,preview=null,outboundBody=null,messages=ledger.messages))
                    if(styleCoordinator.open) styleCoordinator.display(persistence.styleSnapshot())
                    return
                }
                if (request.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) {
                    recovery = Recovery.Complete(request, token, highestAnswer)
                    checkpoint(request, RunStatus.RUNNING)
                    if (answer.safety == SafetyLevel.CRISIS) {
                        checkpoint(request, RunStatus.COMPLETED)
                        synchronized(intentLock) {
                        if(!isCurrent(request,token)) return
                        waitingJob?.cancel(); frozen = null; recovery = null
                        set(state.value.copy(streaming = false, preview = null, outboundBody = null))
                        }
                        return
                    }
                    val text = ContinuousAnalysis.scopeHistoricalTokens(answer.freeText.ifBlank { answer.sections[SectionId.FREE_TEXT].orEmpty() }.trim(), "早期节选#${checkNotNull(summaryKey)}")
                    val selected = request.evidence.flatMap { it.lines }.filter { it.id in request.networkSelection.orEmpty() }
                    if (!ContinuousAnalysis.validSummary(text, selected) || text.codePointCount(0, text.length) > 1000) { fail("SUMMARY_INVALID", true); return }
                    val cache = TimelineSummary(checkNotNull(summaryKey), request, text)
                    try {
                        if (request.contactId != null) persistence.saveSummary(cache)
                        if (!isCurrent(request, token)) return
                        checkpoint(request, RunStatus.COMPLETED)
                        synchronized(intentLock) {
                            if (!isCurrent(request, token)) return@synchronized
                            summaries = summaries.filterNot { it.key == cache.key } + cache
                            waitingJob?.cancel(); frozen = null; recovery = null; previewRequest = null
                            set(state.value.copy(streaming = false, partial = emptyMap(), currentRequest = null, preview = null, outboundBody = null, summaryReady = true, error = null))
                        }
                    } finally {
                        // A submitted stop/edit revokes the token before this mailbox resumes.
                        // Remove only this request's cache, never another operation with the same key.
                        if (epoch.get() != token) {
                            rejectCancelledSummary(cache)
                        }
                    }
                    return
                }
                // This is the local receipt time of a newly completed analysis, not a chat
                // timestamp. Retain it across storage-only retries, including committed writes
                // whose acknowledgement/checkpoint failed. Existing (even unknown=0) times win.
                val completedAt = ledger.messages.firstOrNull { it.requestId == request.requestId && it.role == MessageRole.ASSISTANT }?.createdAt
                    ?: (recovery as? Recovery.Complete)?.takeIf { it.request == request && it.token == token }?.completedAtMillis
                    ?: wallClockMillis().coerceAtLeast(0)
                val completedLedger = ledger.complete(request, answer, "assistant:${request.requestId}", inputRevision, conversationRevision)
                val next = completedLedger.copy(messages = completedLedger.messages.map { if (it.requestId == request.requestId && it.role == MessageRole.ASSISTANT) it.copy(createdAt = completedAt, answerJson = json.encodeToString(StructuredAnswer.serializer(), answer), providerKind = if (state.value.answerDemo) ProviderKind.DEMO else ProviderKind.BYOK) else it })
                val message = next.messages.firstOrNull { it.requestId == request.requestId && it.role == MessageRole.ASSISTANT } ?: return
                if (!isCurrent(request, token)) return
                recovery = Recovery.Complete(request, token, highestAnswer, completedAt)
                if (answer.safety.ordinal > (ledger.runs.firstOrNull { it.requestId == request.requestId }?.highestSafety ?: SafetyLevel.NONE).ordinal)
                    checkpoint(request, RunStatus.RUNNING)
                persistMessage(message)
                if (!isCurrent(request, token)) {
                    // An edit/stop may be submitted while the database write suspends. The
                    // mailbox keeps its next edit/delete behind this compensating removal.
                    val contact = state.value.currentContact
                    if (contact != null) persistence.discardMessage(contact, message)
                    else credential?.let { lease -> temporary.get(lease)?.let { session ->
                        temporary.put(lease, session.copy(messages = session.messages.filterNot { it.id == message.id }))
                    } }
                    return
                }
                // A saved assistant is authoritative on restart even if this acknowledgement fails.
                checkpoint(request, RunStatus.COMPLETED)
                synchronized(intentLock) {
                if(!isCurrent(request,token)) return
                ledger = next
                waitingJob?.cancel()
                frozen = null
                recovery = null
                set(state.value.copy(answer = answer, answerSourceRevision = request.sourceRevision, answerRequest = request, answerConfigRevision = settings.saved.revision, messages = ledger.messages, streaming = false, error = null, feedbackSaved = false,
                    relationType = state.value.manualRelation ?: answer.scene?.relationType ?: state.value.relationType,
                    goal = state.value.manualGoal ?: answer.scene?.goal ?: state.value.goal,
                    formality = state.value.manualFormality ?: answer.scene?.formality?.takeIf { it in setOf("low", "medium", "high") } ?: state.value.formality, styleBias = request.styleBias, tone = request.tone ?: state.value.tone))
                }
                rememberCompleted(request,answer,token)
            }
        }
    }
    /** The same mailbox owns target selection, source CAS, dedup and evidence refresh. */
    fun commitImport(preview: ImportPreview, completion: (Boolean) -> Unit) {
        commitImportResult(preview) {completion(it.success)}
    }
    internal fun commitImportResult(preview:ImportPreview,completion:(ImportCommitResult)->Unit) {
        fun finish(success:Boolean,error:String?=null) = completion(ImportCommitResult(success,error))
        val moreOrigin=synchronized(intentLock) { floatingMore?.takeIf { it.before.ticket==preview.captureSession && moreCurrent(it) } }
        val continuing=moreOrigin?.takeIf { it.id==preview.captureContinuationId }
        val origin = state.value.conversation?.id
        val receiver=synchronized(intentLock) {captureReceiver?.takeIf {it.previewId==preview.id && it.ticket==preview.captureSession && it.epoch==epoch.get() && it.lock==lockGeneration}}
        enqueueFrom(origin, true, onDropped = { finish(false) },preserveReceiver=receiver) { intentEpoch ->
            try {
                if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                check(preview.canConfirm)
                if(moreOrigin?.before?.contactId!=null && preview.target?.contact?.id==moreOrigin.before.contactId) check(continuing!=null) { "Explicit capture continuation required" }
                if(preview.captureContinuationId!=null) check(continuing!=null && preview.target?.newAlias==null && preview.target?.contact?.id==continuing.before.contactId && preview.floatingSource==continuing.before.binding?.source)
                flushDraft()
                if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                invalidateRun()
                val target = checkNotNull(preview.target)
                var contact = target.contact
                if (target.newAlias != null) {
                    val id = "import-contact:${preview.id}"
                    contact = persistence.contacts().firstOrNull { it.id == id && it.archivedAt == null }
                    if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                    if (contact == null) {
                        val created = Contact(id, target.newAlias.trim(), RelationType.ACQUAINTANCE, createdAt = System.currentTimeMillis())
                        persistence.createContact(created)
                        if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                        contact = persistence.contacts().first { it.id == id }
                        if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                    }
                }
                val id = "import:${preview.id}"
                val hash = ChatImportParser.hash(preview.rows)
                val imported = ChatImport(id, contact?.id, preview.source, hash, preview.createdAt)
                var capturedImport=imported
                var capturedBinding:FloatingIdentityBinding?=null
                var capturedProvenance:LiveCaptureProvenance?=null
                var duplicateCapture=false
                val lines = preview.rows.mapIndexed { i, row -> ChatLine("$id:$i", id, i,
                    checkNotNull(row.speaker), row.text, row.sentAt, ocrConfidence = row.ocrConfidence, speakerConfirmed = true,
                    contactId = contact?.id, speakerName = row.name.ifBlank { null }) }
                val retained=preview.rows.mapIndexed {index,row->row.id to lines[index]}.toMap()
                val frames=preview.captureFrames.map {frame->frame.mapIndexed {index,row->
                    val kept=retained[row.id] ?: throw CaptureHistoryReviewRequired()
                    kept.copy(seq=index,text=row.text,sentAt=row.sentAt,speaker=checkNotNull(row.speaker),speakerName=row.name.ifBlank {null})
                }}.ifEmpty {listOf(lines)}
                if (contact != null) {
                    if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                    val savedId = if(continuing!=null && preview.captureContinuationId==continuing.id) {
                        val before=continuing.before
                        check(contact.id==before.contactId)
                        val proof=requireNotNull(before.provenance)
                        val binding=before.binding?.copy(contactRevision=before.contactRevision+1,revision=before.binding.revision+1,rightIsMe=preview.mySide==com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT)
                        val nextId=LiveCaptureFingerprint.id(imported.id)
                        val nextImport=imported.copy(id=nextId,contentHash=LiveCaptureFingerprint.stored(proof.lineageId,LiveCaptureFingerprint.normalized(lines)))
                        val nextLines=lines.mapIndexed {i,line->line.copy(id="$nextId:$i",importId=nextId)}
                        val nextByOriginalId=lines.zip(nextLines).associate {it.first.id to it.second}
                        val mappedFrames=frames.map {frame->frame.map {line->
                            val kept=checkNotNull(nextByOriginalId[line.id]);line.copy(id=kept.id,importId=nextId)
                        }}
                        val change=CapturedImportReplacement(before.imported.id,before.imported.contentHash,before.contactRevision,nextImport,nextLines,binding,before.binding?.revision ?: 0,proof,mappedFrames)
                        contact=persistence.replaceCaptured(change) { epoch.get()==intentEpoch && lockGeneration==continuing.lock && authorityLock.canDisplaySensitiveContent }
                        capturedBinding=binding;capturedImport=nextImport
                        capturedProvenance=requireNotNull(persistence.liveCaptureProvenance(contact.id,proof.lineageId))
                        nextId
                    } else if(preview.captureSession!=null) {
                        val saved=persistence.saveReviewedCaptured(contact,imported,lines,frames) {epoch.get()==intentEpoch && authorityLock.canDisplaySensitiveContent}
                        capturedImport=saved.imported;capturedProvenance=saved.provenance;duplicateCapture=saved.duplicate
                        saved.imported.id
                    } else persistence.saveImport(contact, imported, lines)
                    val contacts = persistence.contacts()
                    set(state.value.copy(contacts = contacts,
                        currentContact = state.value.currentContact?.let { current -> contacts.firstOrNull { it.id == current.id } }))
                    if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                    if (!loadContact(contacts.first { it.id == contact.id }, intentEpoch)) { finish(false); return@enqueueFrom }
                    if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                    set(state.value.copy(importDuplicate = if(preview.captureSession!=null) duplicateCapture else savedId != imported.id))
                    preview.floatingSource?.takeIf { preview.captureContinuationId==null }?.let { source ->
                        val confirmed=contacts.first { it.id==contact.id }
                        val binding=FloatingIdentityBinding(preview.id,confirmed.id,source,
                            preview.rows.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                            preview.mySide==com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT,confirmed.sourceRevision)
                        persistence.saveFloatingIdentity(binding);capturedBinding=binding
                    }
                } else {
                    if (epoch.get() != intentEpoch) { finish(false); return@enqueueFrom }
                    if (state.value.currentContact != null) beginTemporary()
                    val lease = checkNotNull(credential)
                    val session = checkNotNull(temporary.get(lease))
                    val duplicate = session.imports.any { it.contentHash == hash }
                    session.imports.firstOrNull { it.contentHash==hash }?.let { capturedImport=it }
                    set(state.value.copy(importDuplicate = duplicate))
                    if (!duplicate) {
                        val draft=continuing?.takeIf { it.id==preview.captureContinuationId }?.before?.draft ?: state.value.draft
                        check(temporary.put(lease, session.copy(imports = session.imports + imported, lines = session.lines + lines,draft=draft)))
                        inputRevision++
                        set(state.value.copy(evidence = state.value.evidence + EvidenceBlock(id, preview.source.name, lines),draft=draft, answer = null))
                    }
                }
                recovery = null
                if(preview.captureSession!=null && preview.mySide!=null) synchronized(intentLock) {
                    if(epoch.get()==intentEpoch) installFloatingCapture(preview,capturedImport,capturedBinding,capturedProvenance)
                }
                finish(true)
            } catch (failure: Exception) {
                synchronized(intentLock) { if(epoch.get()==intentEpoch && continuing!=null && continuing.lock==lockGeneration && authorityLock.canDisplaySensitiveContent) floatingMore=continuing.copy(epoch=intentEpoch) }
                finish(false,if(failure is CaptureHistoryReviewRequired && epoch.get()==intentEpoch && authorityLock.canDisplaySensitiveContent) "CAPTURE_HISTORY_REVIEW" else null)
            }
        }
    }
    private var captureConfirmation:CaptureConfirmation?=null
    private var captureReceiver:CaptureReceiver?=null
    internal fun registerCaptureReceiver(previewId:String,ticket:Long):String? = synchronized(intentLock) {
        if(!importPending || !authorityLock.canDisplaySensitiveContent) return@synchronized null
        CaptureReceiver(UUID.randomUUID().toString(),previewId,ticket,epoch.get(),lockGeneration).also {captureReceiver=it}.token
    }
    internal fun unregisterCaptureReceiver(token:String) = synchronized(intentLock) {
        if(captureReceiver?.token==token) {captureReceiver=null;if(captureConfirmation?.receiver==token) captureConfirmation=null}
    }
    internal fun consumeCaptureConfirmation(previewId:String,ticket:Long,receiver:String?=null):Boolean = synchronized(intentLock) {
        val ack=captureConfirmation ?: return@synchronized false
        val capture=floatingCapture ?: return@synchronized false
        if(receiver==null || captureReceiver?.token!=receiver || captureReceiver?.epoch!=epoch.get() || captureReceiver?.lock!=lockGeneration || ack.receiver!=receiver || importPending || ack.previewId!=previewId || ack.ticket!=ticket || ack.captureId!=capture.id ||
            ack.actualSavedId!=capture.imported.id || ack.origin!=capture.origin || ack.epoch!=capture.epoch ||
            ack.lock!=capture.lock || ack.contactId!=capture.contactId || ack.contactRevision!=capture.contactRevision ||
            ack.conversationRevision!=capture.conversationRevision || ack.inputRevision!=capture.inputRevision ||
            ack.config!=capture.config || !captureMatches(capture) || state.value.evidence.none {it.id==ack.actualSavedId}) return@synchronized false
        captureConfirmation=null;true
    }
    private var floatingCapture:ConfirmedFloatingCapture?=null
    private var floatingMore:FloatingCaptureMore?=null
    private fun revokeFloatingMoreLocked() { floatingMore=null }
    private fun moreCurrent(m:FloatingCaptureMore) = m.epoch==epoch.get() && m.lock==lockGeneration && authorityLock.canDisplaySensitiveContent && settings.saved.revision==m.before.config
    internal fun dropFloatingMore(id:String) = synchronized(intentLock) { if(floatingMore?.id==id) revokeFloatingMoreLocked() }
    internal fun captureMore(id:String):FloatingCaptureMore? = synchronized(intentLock) { floatingMore?.takeIf { it.id==id && moreCurrent(it) } }
    internal fun prepareFloatingMore(origin:String?,receiptId:String,ticket:Long):FloatingCaptureMore? = synchronized(intentLock) {
        val before=floatingCapture?.takeIf { it.id==receiptId && it.origin==origin && it.ticket==ticket && captureMatches(it) } ?: return@synchronized null
        val next=epoch.incrementAndGet()
        invalidateRun();floatingCapture=null
        FloatingCaptureMore(UUID.randomUUID().toString(),before,next,lockGeneration).also { floatingMore=it
            set(state.value.copy(answer=null,answerRequest=null,preview=null,outboundBody=null,currentRequest=null,streaming=false,partial=emptyMap())) }
    }
    private var floatingSideOperation:FloatingSideOperation?=null
    private fun revokeFloatingCaptureLocked() {
        captureConfirmation=null;captureReceiver=null
        revokeFloatingMoreLocked()
        floatingCapture=null;floatingSideOperation=null
        mutableState.value=state.value.copy(floatingCapture=null,floatingPreviewReady=false,floatingPreviewRequest=null)
    }
    private fun captureMatches(c:ConfirmedFloatingCapture,current:CounselState=state.value):Boolean =
        c.epoch==epoch.get() && c.lock==lockGeneration && authorityLock.canDisplaySensitiveContent &&
        current.conversation?.id==c.origin && current.conversation.revision==c.conversationRevision &&
        current.currentContact?.id==c.contactId && (current.currentContact?.sourceRevision ?: 0)==c.contactRevision &&
        current.modelSettings.saved.revision==c.config && current.draft==c.draft && current.evidence==c.evidence && inputRevision==c.inputRevision
    private fun floatingCaptureView(current:CounselState):FloatingCaptureView? {
        val operation=floatingSideOperation
        if(operation!=null && operation.epoch==epoch.get() && operation.lock==lockGeneration && current.conversation?.id==operation.before.origin)
            return FloatingCaptureView(operation.before.id,operation.mySide,operation.busy,operation.failed)
        return floatingCapture?.takeIf { captureMatches(it,current) }?.let { FloatingCaptureView(it.id,it.mySide,canSwapPhysicalSide=FloatingCaptureRows.canSwap(it.rows)) }
    }
    private fun installFloatingCapture(preview:ImportPreview,imported:ChatImport,binding:FloatingIdentityBinding?,provenance:LiveCaptureProvenance?) {
        val current=state.value;val conversation=checkNotNull(current.conversation)
        floatingCapture=ConfirmedFloatingCapture(UUID.randomUUID().toString(),checkNotNull(preview.captureSession),conversation.id,current.currentContact?.id,
            current.currentContact?.sourceRevision ?: 0,conversation.revision,epoch.get(),lockGeneration,current.modelSettings.saved.revision,inputRevision,
            current.draft,current.evidence,imported,preview.rows,checkNotNull(preview.mySide),binding,provenance)
        val captured=checkNotNull(floatingCapture)
        val receiver=captureReceiver?.takeIf {it.previewId==preview.id && it.ticket==captured.ticket && it.epoch==captured.epoch && it.lock==captured.lock}
        captureConfirmation=receiver?.let {CaptureConfirmation(preview.id,captured.ticket,captured.origin,captured.id,imported.id,captured.epoch,captured.lock,
            captured.contactId,captured.contactRevision,captured.conversationRevision,captured.inputRevision,captured.config,it.token)}
        set(current)
    }
    internal fun editFloatingDraft(origin:String?,receiptId:String,text:String) {
        val before=synchronized(intentLock) { floatingCapture?.takeIf { it.id==receiptId && it.origin==origin && captureMatches(it) } } ?: return
        enqueueFrom(origin,true) { submitted ->
            edit { it.copy(draft=text) }
            synchronized(intentLock) { if(epoch.get()==submitted && state.value.conversation?.id==origin && state.value.evidence==before.evidence) {
                floatingCapture=before.copy(id=UUID.randomUUID().toString(),epoch=submitted,inputRevision=inputRevision,draft=state.value.draft,conversationRevision=checkNotNull(state.value.conversation).revision)
                set(state.value)
            } }
        }
    }
    internal fun closeFloatingCapture(ticket:Long) = synchronized(intentLock) {
        if(captureConfirmation?.ticket==ticket) captureConfirmation=null
        if(captureReceiver?.ticket==ticket) captureReceiver=null
        if(floatingMore?.before?.ticket==ticket) revokeFloatingMoreLocked()
        val pending=floatingSideOperation
        if(pending?.before?.ticket==ticket && pending.epoch==epoch.get()) { epoch.incrementAndGet();invalidateRun() }
        if(floatingCapture?.ticket==ticket || pending?.before?.ticket==ticket) { revokeFloatingCaptureLocked();set(state.value) }
    }
    internal fun switchFloatingSide(origin:String?,receiptId:String,onPrepared:(AnalysisRequest)->Unit={}) {
        synchronized(intentLock) {
            val retry=floatingSideOperation?.takeIf { it.before.id==receiptId && it.epoch==epoch.get() && it.lock==lockGeneration && it.before.origin==origin }
            if(retry?.busy==true) return
            val operation=if(retry!=null) retry.also { it.busy=true;it.failed=false } else {
                val before=floatingCapture?.takeIf { it.id==receiptId && it.origin==origin && captureMatches(it) } ?: return
                if(!FloatingCaptureRows.canSwap(before.rows)) return
                val side=if(before.mySide==com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT) com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.LEFT else com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT
                val rows=FloatingCaptureRows.swap(before.rows,side)
                val operationId="capture-correction:${UUID.randomUUID()}"
                val id=if(before.contactId!=null) LiveCaptureFingerprint.id(operationId) else operationId
                val normalized=ChatImportParser.hash(rows)
                val hash=if(before.contactId!=null) LiveCaptureFingerprint.stored(requireNotNull(before.provenance).lineageId,normalized) else normalized
                val imported=before.imported.copy(id=id,contentHash=hash,importedAt=wallClockMillis())
                val lines=rows.mapIndexed { index,row -> ChatLine("$id:$index",id,index,checkNotNull(row.speaker),row.text,row.sentAt,row.ocrConfidence,true,before.contactId,row.name.ifBlank { null }) }
                val binding=before.binding?.copy(rightIsMe=side==com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT,contactRevision=before.contactRevision+1,revision=before.binding.revision+1)
                val change=CapturedImportReplacement(before.imported.id,before.imported.contentHash,before.contactRevision,imported,lines,binding,before.binding?.revision ?: 0,before.provenance)
                val next=epoch.incrementAndGet();inputRevision++;invalidateRun()
                FloatingSideOperation(before,change,side,rows,next,lockGeneration).also { floatingSideOperation=it }
            }
            set(state.value.copy(answer=null,answerRequest=null,answerConfigRevision=null,partial=emptyMap(),preview=null,outboundBody=null,currentRequest=null,streaming=false,error=null))
            mailbox.trySend {
                val authority=RunAuthority(operation.epoch,operation.lock)
                fun current()=synchronized(intentLock) { floatingSideOperation===operation && authorityCurrent(authority) && state.value.conversation?.id==origin }
                try {
                    if(!current()) return@trySend
                    if(operation.before.contactId!=null) {
                        val contact=persistence.replaceCaptured(operation.change,::current)
                        if(!current()) return@trySend
                        val contacts=persistence.contacts()
                        authorized(authority) { set(state.value.copy(contacts=contacts)) }
                        if(!loadContact(contact,authority.epoch)) return@trySend
                    } else {
                        val lease=checkNotNull(credential)
                        val session=checkNotNull(temporary.get(lease))
                        check(current() && session.imports.any { it.id==operation.change.oldImportId && it.contentHash==operation.change.oldHash })
                        val removedLines=session.lines.filter { it.importId==operation.change.oldImportId }.map { it.id }.toSet()
                        val affectedRequests=ledger.runs.filter { it.requestSnapshot?.evidence?.any { b -> b.lines.any { it.id in removedLines } }==true }.map { it.requestId }.toSet()
                        val affectedMessages=session.messages.filter { it.requestId in affectedRequests }.map { it.id }.toSet()
                        val next=session.copy(imports=session.imports.filterNot { it.id==operation.change.oldImportId }+operation.change.replacement,
                            lines=session.lines.filterNot { it.importId==operation.change.oldImportId }+operation.change.lines,
                            samples=session.samples.filterNot { it.sourceImportId==operation.change.oldImportId || it.sourceMessageId in removedLines || it.sourceAnswerId in affectedMessages },
                            messages=session.messages.filterNot { it.requestId in affectedRequests },summary="",plans=session.plans.filterNot { it.sourceRequestId in affectedRequests })
                        check(current() && temporary.put(lease,next))
                        authorized(authority) {
                            ledger=AnalysisLedger(messages=next.messages,runs=ledger.runs.filterNot { it.requestId in affectedRequests });summaries=emptyList();plans=next.plans
                            set(state.value.copy(evidence=state.value.evidence.filterNot { it.id==operation.change.oldImportId }+EvidenceBlock(operation.change.replacement.id,ImportSource.SCREENSHOT.name,operation.change.lines),messages=next.messages))
                        }
                    }
                    val updatedProvenance=operation.before.provenance?.let {requireNotNull(persistence.liveCaptureProvenance(requireNotNull(operation.before.contactId),it.lineageId))}
                    authorized(authority) {
                        floatingSideOperation=null
                        val now=state.value;val conversation=checkNotNull(now.conversation)
                        floatingCapture=operation.before.copy(id=UUID.randomUUID().toString(),epoch=epoch.get(),inputRevision=inputRevision,
                            contactRevision=now.currentContact?.sourceRevision ?: 0,conversationRevision=conversation.revision,
                            evidence=now.evidence,draft=now.draft,imported=operation.change.replacement,rows=operation.rows,mySide=operation.mySide,binding=operation.change.binding,provenance=updatedProvenance)
                        set(now.copy(answer=null,answerRequest=null,answerConfigRevision=null,error=null,feedbackSaved=false))
                    }
                    preparePreview(origin,true,onPrepared)
                } catch(_:Exception) {
                    synchronized(intentLock) { if(current()) { operation.busy=false;operation.failed=true;set(state.value.copy(answer=null,answerRequest=null,error=CounselError("CAPTURE_CORRECTION_FAILED",true))) } }
                }
            }
        }
    }
    internal fun stopFloating(origin: String?, expected: AnalysisRequest?) = synchronized(intentLock) {
        if(state.value.conversation?.id!=origin || expected==null) return@synchronized
        val submitted=if(previewRequest==expected) previewAuthority else if(frozen==expected) activeRunAuthority else null
        if(submitted==null || !authorityCurrent(submitted)) return@synchronized
        // Revoke before the actor resumes a database suspension. A queued newer input has
        // already advanced epoch, even while the displayed snapshot still belongs to A.
        epoch.incrementAndGet()
        invalidateRun()
        set(state.value.copy(streaming=false,partial=emptyMap(),currentRequest=null,preview=null,outboundBody=null))
    }
    fun stop() = enqueue(true) {
        if (pendingSummaryCleanup.isNotEmpty()) { set(state.value.copy(streaming = false, error = CounselError("STORAGE_UNAVAILABLE", true))); return@enqueue }
        if (!state.value.streaming) return@enqueue
        waitingJob?.cancel()
        stream?.cancel(); stream = null
        frozen?.let { recovery = Recovery.Checkpoint(it, "CANCELLED", true); checkpoint(it, RunStatus.CANCELLED) }
        recovery = frozen?.let { Recovery.Analysis(it) }
        set(state.value.copy(streaming = false, error = CounselError("CANCELLED", true)))
    }
    fun clearAll() {
        clearInlineMemory()
        closeContactDetails()
        selectionEpoch.incrementAndGet()
        enqueue(true) {
        contactUndo = null
        testGeneration++; testJob?.cancel(); testJob = null
        settings = ModelSettingsSnapshot(); provider = demoProvider
        invalidateRun()
        temporary.clear(); credential = null; ledger = AnalysisLedger(); initialized = false; inputRevision++
        dirtyDraft = false
        recovery = Recovery.Clear
        // Clear UI before suspending so a failed physical cleanup cannot expose old content.
        set(CounselState(loading = true, modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO, modelSettings = settings))
        val cleared = persistence.clearAll()
        styleBiasPreferences.clear()
        if (cleared) { pendingSummaryCleanup.clear(); blockedSummaryRequests.clear(); summaries = emptyList() }
        // The application store records durable wipe intent before destroying the shared
        // vault. Do not let a separate settings remove failure prevent that privacy boundary.
        modelSettings?.clear()
        set(state.value.copy(loading = false, error = if (cleared) null else CounselError("CLEANUP_INCOMPLETE", true)))
        if (cleared) recovery = null
    }
    }
}
