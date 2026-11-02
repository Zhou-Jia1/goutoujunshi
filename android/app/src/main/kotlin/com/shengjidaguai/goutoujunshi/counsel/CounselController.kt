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

data class StoredCounsel(val conversation: Conversation, val draft: String, val messages: List<Message>, val evidence: List<EvidenceBlock> = emptyList(), val runs: List<AnalysisRun> = emptyList(), val summaries: List<TimelineSummary> = emptyList(), val plans: List<ActionPlan> = emptyList())

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
        val imports = ImportRepository(checkNotNull(session))
        val evidence = imports.list(contact.id).map { EvidenceBlock(it.id, it.sourceType.name, imports.lines(contact.id, it.id)) }
        return StoredCounsel(conversation, repo.draft(contact.id, conversation.id).orEmpty(), repo.messages(contact.id, conversation.id), evidence, TimelineRepository(checkNotNull(session)).runs(contact.id), SummaryRepository(checkNotNull(session)).list(contact.id), PlanRepository(checkNotNull(session)).list(contact.id))
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
) {
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
        if (mutableDetails.value.ownerId == id) {
            detailEpoch.incrementAndGet(); mutableDetails.value = ContactDetailState(id, loading = true)
        }
        if (state.value.currentContact?.id == id) {
            epoch.incrementAndGet(); stream?.cancel()
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
    private val intentLock = Any()
    @Volatile private var importPending = false
    fun setImportPending(value: Boolean) { synchronized(intentLock) {
        importPending = value; epoch.incrementAndGet(); stream?.cancel()
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
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { set(state.value.copy(loading = false, streaming = false, error = CounselError("STORAGE_UNAVAILABLE", recovery != null))) }
            }
        }
    }
    private fun enqueue(invalidate: Boolean = false, block: suspend () -> Unit) {
        synchronized(intentLock) {
            if (invalidate) { epoch.incrementAndGet(); stream?.cancel() }
            mailbox.trySend(block)
        }
    }
    private fun enqueueFrom(origin: String?, invalidate: Boolean = false, onDropped: () -> Unit = {}, block: suspend (Long) -> Unit) {
        synchronized(intentLock) {
            if (origin == null || state.value.conversation?.id != origin) { onDropped(); return }
            if (invalidate) { epoch.incrementAndGet(); stream?.cancel() }
            val submissionEpoch = epoch.get()
            mailbox.trySend {
                if (synchronized(intentLock) { state.value.conversation?.id == origin }) block(submissionEpoch)
                else onDropped()
            }
        }
    }
    private fun set(next: CounselState) = synchronized(intentLock) {
        mutableState.value = if (pendingSummaryCleanup.isEmpty()) next else next.copy(error = CounselError("STORAGE_UNAVAILABLE", true))
    }
    private fun invalidateRun() {
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
    fun testModelConnection() = enqueue {
        if (!initialized || state.value.streaming || state.value.testingConnection || !settings.saved.live) return@enqueue
        val token = testGeneration
        val operationProvider = provider
        set(state.value.copy(testingConnection = true, connectionResult = null))
        testJob = scope.launch {
            try {
                val terminal = operationProvider.analyze(AnalysisRequest(requestId = "connection-test",
                    userText = "连接测试：请只回复‘连接成功’。")).last()
                val result = when (terminal) {
                    is AnalysisChunk.Completed -> "SUCCESS"
                    is AnalysisChunk.Failed -> terminal.error.code
                    else -> "STREAM_INCOMPLETE"
                }
                mailbox.send { if (testGeneration == token) set(state.value.copy(testingConnection = false, connectionResult = result)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mailbox.send { if (testGeneration == token) set(state.value.copy(testingConnection = false, connectionResult = "NETWORK")) } }
        }
    }
    fun cancelModelTest() = enqueue {
        testGeneration++; testJob?.cancel(); testJob = null
        set(state.value.copy(testingConnection = false, connectionResult = "CANCELLED"))
    }

    fun initialize() = enqueue { initializeNow() }
    private suspend fun initializeNow() {
        if (!initialized) {
            recovery = Recovery.Initialize
            set(state.value.copy(loading = true, error = null))
            persistence.initialize()
            modelSettings?.let { settings = it.load(); applyModelSettings() }
            val contacts = persistence.contacts()
            initialized = true
            set(state.value.copy(contacts = contacts, loading = false))
            contacts.firstOrNull { it.archivedAt == null }?.let { loadContact(it) } ?: beginTemporary()
        }
    }
    /** Explicit completion avoids relying on conflated loading/error transitions for a retry. */
    fun retryStartup(completion: (String?) -> Unit) = enqueue {
        try {
            if (!initialized) initializeNow()
            else (recovery as? Recovery.Load)?.let { loadContact(it.contact) }
            completion(state.value.conversation?.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            set(state.value.copy(loading = false, streaming = false, error = CounselError("STORAGE_UNAVAILABLE", recovery != null)))
            completion(null)
        }
    }
    private suspend fun beginTemporary() {
        invalidateRun()
        credential?.let { temporary.remove(it) }
        credential = temporary.begin()
        summaries = emptyList(); plans = emptyList()
        inputRevision++
        ledger = AnalysisLedger()
        dirtyDraft = false
        set(CounselState(loading = false, contacts = state.value.contacts,
            conversation = Conversation(checkNotNull(credential).sessionId), modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO, modelSettings = settings))
    }
    fun newTemporary() = enqueue(true) { flushDraft(); beginTemporary() }
    private suspend fun loadContact(contact: Contact, expectedEpoch: Long? = null, selectionToken: Long? = null): Boolean {
        invalidateRun()
        recovery = Recovery.Load(contact)
        val saved = persistence.loadOrCreate(contact)
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
        val completedCurrentAnswer = AnswerSourcePolicy.matches(answerRequest,
            saved.runs.firstOrNull { it.requestId == answerMessage?.requestId }?.configRevision,
            saved.conversation, contact.id, contact.sourceRevision, saved.draft, saved.evidence, profile.revision, settings.saved.revision)
        val resumable = saved.runs.sortedBy { if (it.requestId == latestUser?.requestId) 1 else 0 }.lastOrNull { run ->
            val r = run.requestSnapshot
            r != null && (run.requestId == latestUser?.requestId || (r.purpose == OutboundPurpose.LONG_RECORD_SUMMARY && !completedCurrentAnswer)) && run.status in setOf(RunStatus.RUNNING, RunStatus.INTERRUPTED, RunStatus.FAILED, RunStatus.CANCELLED) &&
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
            set(CounselState(loading = false, contacts = state.value.contacts, currentContact = contact,
                conversation = saved.conversation, draft = saved.draft,
                relationType = answerRequest?.manualRelation ?: answer?.scene?.relationType ?: contact.relationType,
                goal = answerRequest?.manualGoal ?: answer?.scene?.goal ?: Goal.REPLY,
                manualRelation = answerRequest?.manualRelation, manualGoal = answerRequest?.manualGoal, manualFormality = answerRequest?.manualFormality,
                formality = answerRequest?.manualFormality ?: answer?.scene?.formality?.takeIf { it in setOf("low", "medium", "high") } ?: "medium",
                messages = saved.messages, evidence = saved.evidence, answer = answer, answerSourceRevision = answerRequest?.sourceRevision, answerRequest = answerRequest, answerConfigRevision = saved.runs.lastOrNull { it.requestId == answerMessage?.requestId }?.configRevision, draftSaved = true, answerDemo = answerMessage?.providerKind != ProviderKind.BYOK,
                modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO, modelSettings = settings, redactionProfile = profile, safety = summarySafety))
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
            epoch.incrementAndGet(); stream?.cancel()
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
    fun setRelation(value: RelationType) = enqueue(true) { edit { it.copy(relationType = value, manualRelation = value) } }
    fun setGoal(value: Goal) = enqueue(true) { edit { it.copy(goal = value, manualGoal = value) } }
    fun setEmotion(value: Int?) = enqueue(true) { edit { it.copy(emotion = value?.coerceIn(0, 10)) } }
    fun setStyleBias(value: Float) = enqueue(true) { edit { it.copy(styleBias = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f) } }
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
            if (original.purpose != OutboundPurpose.ANALYSIS || !ContinuousAnalysis.sourcesRetained(original, current.currentContact?.id, current.evidence) || original.redactionProfile.revision != current.redactionProfile.revision || run.configRevision != settings.saved.revision) return@mapNotNull null
            val answer = runCatching { json.decodeFromString(StructuredAnswer.serializer(), message.answerJson ?: "") }.getOrNull() ?: return@mapNotNull null
            val choice = plans.lastOrNull { it.sourceRequestId == message.requestId && it.sourceAnswerId == message.id }?.let { "${it.status}:${ContinuousAnalysis.historyExcerpt(it.chosenReply, 80)}" } ?: "未记录选择"
            ContinuousAnalysis.scopeHistoricalTokens("历史建议（${if (message.providerKind == ProviderKind.DEMO) "虚构演示" else "模型分析，非聊天事实"}） VERDICT=${ContinuousAnalysis.historyExcerpt(answer.verdict, 100)} NEXT=${ContinuousAnalysis.historyExcerpt(answer.next, 100)} 用户选择=$choice", "历史${message.requestId.hashCode().toUInt().toString(16)}")
        }
        return ContinuousAnalysis.historyExcerpt(pieces.takeLast(2).joinToString("\n"), 900)
    }
    private suspend fun nextRequest(source: AnalysisRequest): AnalysisRequest {
        if (source.networkSelection != null || source.mode != RequestMode.FULL_ANALYSIS || source.purpose != OutboundPurpose.ANALYSIS) return source
        val plan = ContinuousAnalysis.plan(source.evidence)
        if (!plan.needsSummary || !settings.saved.live) return source.copy(networkSelection = plan.recent.map { it.id })
        val candidate = source.copy(purpose = OutboundPurpose.LONG_RECORD_SUMMARY, recentSummary = "", networkSelection = plan.summarySelection.map { it.id })
        val key = cacheKey(candidate)
        summaryKey = key
        val cached = summaries.firstOrNull { it.request.requestId !in blockedSummaryRequests && ledger.runs.any { run -> run.requestId == it.request.requestId && run.status == RunStatus.COMPLETED } && it.key == key && it.request.sourceRevision == source.sourceRevision && ContinuousAnalysis.sourcesRetained(it.request, source.contactId, source.evidence) && ContinuousAnalysis.validSummary(it.text, plan.summarySelection) }
        if (cached != null) set(state.value.copy(summaryReady = true))
        return if (cached != null) source.copy(networkSelection = plan.recent.map { it.id }, timelineSummary = cached.text)
        else candidate
    }
    private fun cacheKey(candidate: AnalysisRequest): String {
        val prepared = assembler.prepare(candidate)
        val version = prepared.knowledgeVersion + prepared.system
        val identity = json.encodeToString(AnalysisRequest.serializer(), candidate.copy(requestId = "", inputRevision = 0)) + settings.saved.revision + version
        return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun request(): AnalysisRequest {
        val current = state.value
        val conversation = checkNotNull(current.conversation)
        return AnalysisRequest(requestId = UUID.randomUUID().toString(), inputRevision = inputRevision,
            contactId = current.currentContact?.id,
            relationType = ScenePredictor.predict(current.currentContact?.relationType, current.draft, current.evidence, current.manualRelation, current.manualGoal).relationType,
            goal = ScenePredictor.predict(current.currentContact?.relationType, current.draft, current.evidence, current.manualRelation, current.manualGoal).goal, formality = current.formality,
            manualRelation = current.manualRelation, manualGoal = current.manualGoal, manualFormality = current.manualFormality,
            userText = current.draft, emotionLevel = current.emotion, styleBias = current.styleBias, tone = current.tone,
            conversationId = conversation.id, conversationRevision = conversation.revision,
            sourceRevision = current.currentContact?.sourceRevision ?: 0,
            redactionProfile = current.redactionProfile,
            recentSummary = history(current), evidence = current.evidence, speakersConfirmed = current.evidence.isNotEmpty() && current.evidence.flatMap { it.lines }.all { it.speakerConfirmed })
    }
    private fun ordinaryAllowed() = pendingSummaryCleanup.isEmpty() && SafetyPolicy.evaluate(state.value.emotion, state.value.safety.upgrade(state.value.answer?.safety ?: SafetyLevel.NONE), SafetyLevel.NONE, state.value.lockState).showOrdinaryStrategy
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
        return stored.messages.any { it.id == source.id && it.answerJson == source.answerJson } &&
            stored.conversation == current.conversation && stored.draft == current.draft && stored.evidence == current.evidence &&
            persistence.redactionProfile(contact!!.id).revision == original.redactionProfile.revision
    }
    fun changeScene(origin: String?, answerId: String, relation: RelationType, goal: Goal, formality: String) = enqueueFrom(origin) { submissionEpoch ->
        if (!ordinaryAllowed() || state.value.answer?.id != answerId || state.value.streaming || !answerSourceCurrent()) return@enqueueFrom
        if (epoch.get() != submissionEpoch) return@enqueueFrom
        val original = checkNotNull(state.value.answer)
        epoch.incrementAndGet()
        edit { it.copy(relationType = relation, manualRelation = relation, goal = goal, manualGoal = goal, formality = formality.takeIf { f -> f in setOf("low", "medium", "high") } ?: "medium", manualFormality = formality.takeIf { f -> f in setOf("low", "medium", "high") } ?: "medium") }
        previewRequest = request().copy(parentAnswerId = answerId, originalAnswer = original)
        if (settings.saved.live) preparePreview(origin) else analyze(origin)
    }
    fun rewrite(origin: String?, answerId: String, tone: Tone, bias: Float) = enqueueFrom(origin) { submissionEpoch ->
        val original = state.value.answer ?: return@enqueueFrom
        if (original.id != answerId || !ordinaryAllowed() || state.value.streaming || state.value.error != null || !answerSourceCurrent()) return@enqueueFrom
        if (epoch.get() != submissionEpoch) return@enqueueFrom
        val operationEpoch = epoch.incrementAndGet()
        inputRevision++
        invalidateRun()
        val snapshot = request().copy(mode = RequestMode.REWRITE_REPLY, purpose = OutboundPurpose.REPLY_REWRITE,
            parentAnswerId = original.id, originalAnswer = original, tone = tone, styleBias = bias.coerceIn(0f, 1f))
        if (settings.saved.live) {
            val prepared = withContext(Dispatchers.Default) { assembler.prepare(snapshot) }
            if (epoch.get() != operationEpoch || state.value.conversation?.id != origin) return@enqueueFrom
            previewRequest = snapshot
            set(state.value.copy(preview = prepared, outboundBody = outboundBody(prepared)))
        } else start(snapshot)
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
    fun preparePreview(originConversationId: String?) = enqueueFrom(originConversationId) preview@{ operationEpoch ->
        if (!ordinaryAllowed()) return@preview
        if (state.value.redactionError) return@preview
        if (importPending || state.value.loading || state.value.conversation == null) return@preview
        if (state.value.streaming) return@preview
        if (state.value.draft.isBlank() && state.value.evidence.isEmpty()) { set(state.value.copy(error = CounselError("INPUT_REQUIRED"))); return@preview }
        flushDraft()
        val interrupted = (recovery as? Recovery.Analysis)?.request?.takeIf { state.value.error?.code == "INTERRUPTED" }
        recovery = Recovery.Preview
        try {
            // Preview belongs to the next send. Only retry may reuse a failed/cancelled run.
            var snapshot = nextRequest(previewRequest ?: interrupted ?: request())
            if (snapshot.purpose == OutboundPurpose.LONG_RECORD_SUMMARY && finishSavedSummary(snapshot)) {
                if (epoch.get() != operationEpoch) return@preview
                snapshot = nextRequest(request())
            }
            val prepared = withContext(Dispatchers.Default) { assembler.prepare(snapshot) }
            if (epoch.get() != operationEpoch) return@preview
            previewRequest = snapshot
            set(state.value.copy(currentRequest = if (snapshot.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) snapshot else state.value.currentRequest, preview = prepared, outboundBody = outboundBody(prepared), error = null))
            recovery = null
        }
        catch (_: Exception) { set(state.value.copy(error = CounselError(if (recovery is Recovery.FinishSummary) "STORAGE_UNAVAILABLE" else "PROMPT_UNAVAILABLE", true))) }
    }
    private suspend fun finishSavedSummary(request: AnalysisRequest): Boolean {
        val key = cacheKey(request)
        val selected = request.evidence.flatMap { it.lines }.filter { it.id in request.networkSelection.orEmpty() }
        val completed = summaries.any { cache -> cache.key == key && cache.request.requestId !in blockedSummaryRequests &&
            cache.request.sourceRevision == request.sourceRevision && ContinuousAnalysis.sourcesRetained(cache.request, request.contactId, request.evidence) &&
            ContinuousAnalysis.validSummary(cache.text, selected) && ledger.runs.any { it.requestId == cache.request.requestId && it.status == RunStatus.COMPLETED } }
        if (completed) {
            recovery = null
            set(state.value.copy(summaryReady = true, currentRequest = null, error = null))
            return true
        }
        val run = ledger.runs.firstOrNull { it.requestId == request.requestId } ?: return false
        if (run.status !in setOf(RunStatus.RUNNING, RunStatus.INTERRUPTED) || request.requestId in blockedSummaryRequests) return false
        if (summaries.none { it.key == key && it.request.requestId == request.requestId && ContinuousAnalysis.sourcesRetained(it.request, request.contactId, request.evidence) && ContinuousAnalysis.validSummary(it.text, selected) }) return false
        recovery = Recovery.FinishSummary(request)
        val token = epoch.get()
        checkpoint(request, RunStatus.COMPLETED)
        if (epoch.get() != token) return true
        recovery = null
        set(state.value.copy(summaryReady = true, currentRequest = null, error = null))
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
    fun analyze() = analyze(state.value.conversation?.id)
    fun analyze(originConversationId: String?) = enqueueFrom(originConversationId) analyze@{
        if (!ordinaryAllowed()) return@analyze
        if (state.value.redactionError) return@analyze
        if (state.value.testingConnection) return@analyze
        if (importPending || state.value.loading || state.value.conversation == null) return@analyze
        if (state.value.streaming) return@analyze
        if (state.value.draft.isBlank() && state.value.evidence.isEmpty()) { set(state.value.copy(error = CounselError("INPUT_REQUIRED"))); return@analyze }
        flushDraft()
        if (settings.saved.live && previewRequest == null) {
            set(state.value.copy(error = CounselError("PREVIEW_REQUIRED"))); return@analyze
        }
        val snapshot = previewRequest ?: (recovery as? Recovery.Analysis)?.request?.takeIf { state.value.error?.code == "INTERRUPTED" } ?: nextRequest(request())
        previewRequest = null // A preview can authorize one new operation, never two sends.
        start(snapshot)
    }
    fun retry() = enqueue {
        if (pendingSummaryCleanup.isNotEmpty()) { retrySummaryCleanup(); return@enqueue }
        if (state.value.streaming || state.value.error?.retryable != true) return@enqueue
        when (val pending = recovery) {
            is Recovery.Feedback -> feedback(pending.origin, pending.answerId, pending.edited)
            Recovery.Initialize -> initialize()
            is Recovery.Load -> loadContact(pending.contact)
            Recovery.SaveDraft -> flushDraft()
            is Recovery.Analysis -> {
                if (settings.saved.live) {
                    val prepared = assembler.prepare(pending.request)
                    previewRequest = pending.request
                    set(state.value.copy(preview = prepared, outboundBody = outboundBody(prepared), error = null))
                } else start(pending.request)
            }
            is Recovery.Checkpoint -> fail(pending.code, pending.retryable)
            is Recovery.Complete -> {
                if (isCurrent(pending.request, pending.token)) {
                    set(state.value.copy(streaming = true, error = null))
                    receive(pending.request, pending.token, AnalysisChunk.Completed(pending.answer))
                }
            }
            is Recovery.FinishSummary -> { if (finishSavedSummary(pending.request)) preparePreview() }
            Recovery.Preview -> preparePreview()
            Recovery.RefreshContacts -> {
                set(state.value.copy(contacts = persistence.contacts(), error = null))
                recovery = null
            }
            is Recovery.Delete -> performDelete(pending.contactId)
            Recovery.Clear -> clearAll()
            null -> Unit
        }
    }
    private fun isCurrent(request: AnalysisRequest, token: Long): Boolean = epoch.get() == token && frozen == request &&
        request.inputRevision == inputRevision && request.conversationId == state.value.conversation?.id &&
        request.conversationRevision == state.value.conversation?.revision && request.contactId == state.value.currentContact?.id

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
    private suspend fun start(request: AnalysisRequest) {
        if (request.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) {
            summaryKey = cacheKey(request)
            val previous = ledger.runs.firstOrNull { it.requestId == request.requestId }
            if (previous?.status in setOf(RunStatus.CANCELLED, RunStatus.FAILED)) {
                for (cache in summaries.filter { it.request.requestId == request.requestId }) {
                    rejectCancelledSummary(cache)
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
        val token = epoch.incrementAndGet()
        frozen = request
        recovery = Recovery.Analysis(request)
        set(state.value.copy(streaming = true, partial = emptyMap(), answer = null, safety = priorSafety,
            answerDemo = operationProvider.kind == ProviderKind.DEMO, error = null, currentRequest = request))
        try {
            val prepared = withContext(Dispatchers.Default) { assembler.prepare(request) }
            if (!isCurrent(request, token)) return
            set(state.value.copy(preview = prepared))
        } catch (_: Exception) { fail("PROMPT_UNAVAILABLE", true); return }
        val begun = ledger.begin(request, "user:${request.requestId}")
        val user = begun.messages.firstOrNull { it.requestId == request.requestId && it.role == MessageRole.USER }
        if (user != null && ledger.messages.none { it.id == user.id }) persistMessage(user)
        ledger = begun
        checkpoint(request, RunStatus.RUNNING)
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
        stream = scope.launch {
            try {
                (if (request.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) OutboundEngine(operationProvider).summarize(request) else operationProvider.analyze(request)).collect { chunk -> mailbox.send { receive(request, token, chunk) } }
                mailbox.send {
                    if (isCurrent(request, token) && state.value.streaming) fail("STREAM_INCOMPLETE", true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mailbox.send { if (isCurrent(request, token)) fail("ANALYSIS_UNAVAILABLE", true) } }
        }
    }
    private suspend fun checkpoint(request: AnalysisRequest, status: RunStatus) {
        val run = ledger.runs.firstOrNull { it.requestId == request.requestId } ?: return
        val next = run.copy(status = status, requestSnapshot = request.copy(redactionProfile = RedactionProfile(revision = request.redactionProfile.revision)), configRevision = settings.saved.revision, highestSafety = run.highestSafety.upgrade(state.value.safety))
        state.value.currentContact?.let { persistence.saveRun(it, next) }
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
                set(state.value.copy(partial = partial, safety = highest))
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
                        set(state.value.copy(streaming = false, preview = null, outboundBody = null,
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
                set(state.value.copy(safety = state.value.safety.upgrade(answer.safety)))
                if (request.purpose == OutboundPurpose.LONG_RECORD_SUMMARY) {
                    recovery = Recovery.Complete(request, token, highestAnswer)
                    checkpoint(request, RunStatus.RUNNING)
                    if (answer.safety == SafetyLevel.CRISIS) {
                        checkpoint(request, RunStatus.COMPLETED)
                        waitingJob?.cancel(); frozen = null; recovery = null
                        set(state.value.copy(streaming = false, preview = null, outboundBody = null))
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
                ledger = next
                waitingJob?.cancel()
                frozen = null
                recovery = null
                set(state.value.copy(answer = answer, answerSourceRevision = request.sourceRevision, answerRequest = request, answerConfigRevision = settings.saved.revision, messages = ledger.messages, streaming = false, error = null, feedbackSaved = false,
                    relationType = state.value.manualRelation ?: answer.scene?.relationType ?: state.value.relationType,
                    goal = state.value.manualGoal ?: answer.scene?.goal ?: state.value.goal,
                    formality = state.value.manualFormality ?: answer.scene?.formality?.takeIf { it in setOf("low", "medium", "high") } ?: state.value.formality, styleBias = request.styleBias, tone = request.tone ?: state.value.tone))
            }
        }
    }
    /** The same mailbox owns target selection, source CAS, dedup and evidence refresh. */
    fun commitImport(preview: ImportPreview, completion: (Boolean) -> Unit) {
        val origin = state.value.conversation?.id
        enqueueFrom(origin, true, onDropped = { completion(false) }) { intentEpoch ->
            try {
                if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                check(preview.canConfirm)
                flushDraft()
                if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                invalidateRun()
                val target = checkNotNull(preview.target)
                var contact = target.contact
                if (target.newAlias != null) {
                    val id = "import-contact:${preview.id}"
                    contact = persistence.contacts().firstOrNull { it.id == id && it.archivedAt == null }
                    if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                    if (contact == null) {
                        val created = Contact(id, target.newAlias.trim(), RelationType.ACQUAINTANCE, createdAt = System.currentTimeMillis())
                        persistence.createContact(created)
                        if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                        contact = persistence.contacts().first { it.id == id }
                        if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                    }
                }
                val id = "import:${preview.id}"
                val hash = ChatImportParser.hash(preview.rows)
                val imported = ChatImport(id, contact?.id, preview.source, hash, System.currentTimeMillis())
                val lines = preview.rows.mapIndexed { i, row -> ChatLine("$id:$i", id, i,
                    checkNotNull(row.speaker), row.text, row.sentAt, speakerConfirmed = true,
                    contactId = contact?.id, speakerName = row.name.ifBlank { null }) }
                if (contact != null) {
                    if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                    val savedId = persistence.saveImport(contact, imported, lines)
                    val contacts = persistence.contacts()
                    set(state.value.copy(contacts = contacts,
                        currentContact = state.value.currentContact?.let { current -> contacts.firstOrNull { it.id == current.id } }))
                    if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                    if (!loadContact(contacts.first { it.id == contact.id }, intentEpoch)) { completion(false); return@enqueueFrom }
                    if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                    set(state.value.copy(importDuplicate = savedId != imported.id))
                } else {
                    if (epoch.get() != intentEpoch) { completion(false); return@enqueueFrom }
                    if (state.value.currentContact != null) beginTemporary()
                    val lease = checkNotNull(credential)
                    val session = checkNotNull(temporary.get(lease))
                    val duplicate = session.imports.any { it.contentHash == hash }
                    set(state.value.copy(importDuplicate = duplicate))
                    if (!duplicate) {
                        check(temporary.put(lease, session.copy(imports = session.imports + imported, lines = session.lines + lines)))
                        inputRevision++
                        set(state.value.copy(evidence = state.value.evidence + EvidenceBlock(id, preview.source.name, lines), answer = null))
                    }
                }
                recovery = null
                completion(true)
            } catch (_: Exception) { completion(false) }
        }
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
        if (cleared) { pendingSummaryCleanup.clear(); blockedSummaryRequests.clear(); summaries = emptyList() }
        // The application store records durable wipe intent before destroying the shared
        // vault. Do not let a separate settings remove failure prevent that privacy boundary.
        modelSettings?.clear()
        set(state.value.copy(loading = false, error = if (cleared) null else CounselError("CLEANUP_INCOMPLETE", true)))
        if (cleared) recovery = null
    }
    }
}
