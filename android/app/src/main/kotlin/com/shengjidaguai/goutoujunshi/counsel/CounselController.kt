package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

data class StoredCounsel(val conversation: Conversation, val draft: String, val messages: List<Message>)

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
    suspend fun deleteContact(id: String)
    suspend fun loadOrCreate(contact: Contact): StoredCounsel
    suspend fun update(contact: Contact, previous: Conversation, next: Conversation, draft: String)
    suspend fun saveMessage(contact: Contact, conversation: Conversation, message: Message)
    suspend fun discardMessage(contact: Contact, message: Message)
    suspend fun clearAll(): Boolean
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
        return StoredCounsel(conversation, repo.draft(contact.id, conversation.id).orEmpty(), repo.messages(contact.id, conversation.id))
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
    override suspend fun clearAll(): Boolean {
        try { return store.clearAll(temporary).isEmpty() } finally { session = null }
    }
}

/** A single mailbox serializes edits, database writes and model callbacks. Intent epochs also
 * revoke a callback immediately when a UI action is submitted, before a suspended write resumes.
 * This controller lives at application scope; none of its state is saved in a Bundle. */
class CounselController(
    private val persistence: CounselPersistence,
    private val temporary: TemporarySessionStore,
    private val assembler: PromptAssembler,
    private val provider: AnalysisProvider,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(CounselState(modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO))
    val state: StateFlow<CounselState> = mutableState.asStateFlow()
    private val mailbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val epoch = AtomicLong()
    private val intentLock = Any()
    private var inputRevision = 0L
    private var credential: TemporarySessionCredential? = null
    private var ledger = AnalysisLedger()
    private var stream: Job? = null
    private var initialized = false
    private var frozen: AnalysisRequest? = null
    private var previewRequest: AnalysisRequest? = null
    private var dirtyDraft = false
    private sealed interface Recovery {
        data object Initialize : Recovery
        data class Load(val contact: Contact) : Recovery
        data object SaveDraft : Recovery
        data class Analysis(val request: AnalysisRequest) : Recovery
        data class Complete(val request: AnalysisRequest, val token: Long, val answer: StructuredAnswer) : Recovery
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
    private fun enqueueFrom(origin: String?, invalidate: Boolean = false, block: suspend () -> Unit) {
        synchronized(intentLock) {
            if (origin == null || state.value.conversation?.id != origin) return
            if (invalidate) { epoch.incrementAndGet(); stream?.cancel() }
            mailbox.trySend {
                if (synchronized(intentLock) { state.value.conversation?.id == origin }) block()
            }
        }
    }
    private fun set(next: CounselState) = synchronized(intentLock) { mutableState.value = next }
    private fun invalidateRun() {
        stream?.cancel(); stream = null; frozen = null
        previewRequest = null
        recovery = null
        (assembler as? FrozenPromptAssembler)?.clear()
        set(state.value.copy(streaming = false, partial = emptyMap(), currentRequest = null, preview = null, error = null))
    }

    fun initialize() = enqueue {
        if (!initialized) {
            recovery = Recovery.Initialize
            persistence.initialize()
            val contacts = persistence.contacts()
            initialized = true
            set(state.value.copy(contacts = contacts, loading = false))
            if (contacts.isEmpty()) beginTemporary() else loadContact(contacts.first())
        }
    }
    private suspend fun beginTemporary() {
        invalidateRun()
        credential?.let { temporary.remove(it) }
        credential = temporary.begin()
        inputRevision++
        ledger = AnalysisLedger()
        dirtyDraft = false
        set(CounselState(loading = false, contacts = state.value.contacts,
            conversation = Conversation(checkNotNull(credential).sessionId), modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO))
    }
    fun newTemporary() = enqueue(true) { flushDraft(); beginTemporary() }
    private suspend fun loadContact(contact: Contact) {
        invalidateRun()
        recovery = Recovery.Load(contact)
        val saved = persistence.loadOrCreate(contact)
        credential?.let { temporary.remove(it) }
        credential = null
        inputRevision++
        dirtyDraft = false
        ledger = AnalysisLedger(messages = saved.messages)
        val latestUser = saved.messages.lastOrNull { it.role == MessageRole.USER }
        val answer = saved.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.answerJson != null &&
                it.requestId == latestUser?.requestId && latestUser?.text == saved.draft
        }
            ?.answerJson?.let { runCatching { json.decodeFromString(StructuredAnswer.serializer(), it) }.getOrNull() }
        set(CounselState(loading = false, contacts = state.value.contacts, currentContact = contact,
            conversation = saved.conversation, draft = saved.draft, relationType = contact.relationType,
            messages = saved.messages, answer = answer, modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO))
        recovery = null
    }
    fun selectContact(id: String) = enqueue(true) {
        state.value.contacts.firstOrNull { it.id == id }?.let { flushDraft(); loadContact(it) }
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
    fun deleteContact(id: String) = enqueue(true) { performDelete(id) }
    private suspend fun performDelete(id: String) {
        val pendingError = if (recovery is Recovery.Delete) state.value.error else null
        invalidateRun()
        // The durable delete can commit before cleanup or refresh fails. Stop exposing the
        // deleted contact immediately, and retain the complete idempotent operation to retry.
        if (state.value.currentContact?.id == id) beginTemporary()
        set(state.value.copy(contacts = state.value.contacts.filterNot { it.id == id }, error = pendingError))
        recovery = Recovery.Delete(id)
        persistence.deleteContact(id)
        set(state.value.copy(contacts = persistence.contacts(), error = null))
        recovery = null
    }
    private suspend fun edit(transform: (CounselState) -> CounselState) {
        invalidateRun()
        if (state.value.conversation == null) return
        inputRevision++
        val next = transform(state.value).copy(answer = null)
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
        set(state.value.copy(conversation = next, error = null))
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
    fun setRelation(value: RelationType) = enqueue(true) { edit { it.copy(relationType = value) } }
    fun setGoal(value: Goal) = enqueue(true) { edit { it.copy(goal = value) } }
    fun setEmotion(value: Int?) = enqueue(true) { edit { it.copy(emotion = value?.coerceIn(0, 10)) } }
    fun setStyleBias(value: Float) = enqueue(true) { edit { it.copy(styleBias = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f) } }
    fun setTone(value: Tone) = enqueue(true) { edit { it.copy(tone = value) } }
    private fun request(): AnalysisRequest {
        val current = state.value
        val conversation = checkNotNull(current.conversation)
        return AnalysisRequest(requestId = UUID.randomUUID().toString(), inputRevision = inputRevision,
            contactId = current.currentContact?.id, relationType = current.relationType, goal = current.goal,
            userText = current.draft, emotionLevel = current.emotion, styleBias = current.styleBias, tone = current.tone,
            conversationId = conversation.id, conversationRevision = conversation.revision,
            sourceRevision = current.currentContact?.sourceRevision ?: 0)
    }
    fun preparePreview() = preparePreview(state.value.conversation?.id)
    fun preparePreview(originConversationId: String?) = enqueueFrom(originConversationId) preview@{
        if (state.value.loading || state.value.conversation == null) return@preview
        if (state.value.streaming) return@preview
        if (state.value.draft.isBlank()) { set(state.value.copy(error = CounselError("INPUT_REQUIRED"))); return@preview }
        flushDraft()
        recovery = Recovery.Preview
        try {
            // Preview belongs to the next send. Only retry may reuse a failed/cancelled run.
            val snapshot = previewRequest ?: request()
            val prepared = withContext(Dispatchers.Default) { assembler.prepare(snapshot) }
            previewRequest = snapshot
            set(state.value.copy(preview = prepared, error = null))
            recovery = null
        }
        catch (_: Exception) { set(state.value.copy(error = CounselError("PROMPT_UNAVAILABLE", true))) }
    }
    fun analyze() = analyze(state.value.conversation?.id)
    fun analyze(originConversationId: String?) = enqueueFrom(originConversationId) analyze@{
        if (state.value.loading || state.value.conversation == null) return@analyze
        if (state.value.streaming) return@analyze
        if (state.value.draft.isBlank()) { set(state.value.copy(error = CounselError("INPUT_REQUIRED"))); return@analyze }
        flushDraft()
        val snapshot = previewRequest ?: request()
        previewRequest = null // A preview can authorize one new operation, never two sends.
        start(snapshot)
    }
    fun retry() = enqueue {
        if (state.value.streaming || state.value.error?.retryable != true) return@enqueue
        when (val pending = recovery) {
            Recovery.Initialize -> initialize()
            is Recovery.Load -> loadContact(pending.contact)
            Recovery.SaveDraft -> flushDraft()
            is Recovery.Analysis -> start(pending.request)
            is Recovery.Complete -> {
                if (isCurrent(pending.request, pending.token)) {
                    set(state.value.copy(streaming = true, error = null))
                    receive(pending.request, pending.token, AnalysisChunk.Completed(pending.answer))
                }
            }
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
        val token = epoch.incrementAndGet()
        frozen = request
        recovery = Recovery.Analysis(request)
        set(state.value.copy(streaming = true, partial = emptyMap(), answer = null, error = null, currentRequest = request))
        try {
            val prepared = withContext(Dispatchers.Default) { assembler.prepare(request) }
            if (!isCurrent(request, token)) return
            set(state.value.copy(preview = prepared))
        } catch (_: Exception) { fail("PROMPT_UNAVAILABLE", true); return }
        val begun = ledger.begin(request, "user:${request.requestId}")
        val user = begun.messages.first { it.requestId == request.requestId && it.role == MessageRole.USER }
        if (ledger.messages.none { it.id == user.id }) persistMessage(user)
        ledger = begun
        if (!isCurrent(request, token)) return
        set(state.value.copy(messages = ledger.messages))
        stream = scope.launch {
            try {
                provider.analyze(request).collect { chunk -> mailbox.send { receive(request, token, chunk) } }
                mailbox.send {
                    if (isCurrent(request, token) && state.value.streaming) fail("STREAM_INCOMPLETE", true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mailbox.send { if (isCurrent(request, token)) fail("ANALYSIS_UNAVAILABLE", true) } }
        }
    }
    private fun fail(code: String, retryable: Boolean) {
        set(state.value.copy(streaming = false, error = CounselError(code, retryable)))
    }
    private suspend fun receive(request: AnalysisRequest, token: Long, chunk: AnalysisChunk) {
        if (!isCurrent(request, token) || !state.value.streaming) return
        when (chunk) {
            is AnalysisChunk.Section -> set(state.value.copy(partial = state.value.partial + (chunk.id to (state.value.partial[chunk.id].orEmpty() + chunk.textDelta))))
            is AnalysisChunk.Failed -> fail(chunk.error.code, chunk.error.retryable)
            is AnalysisChunk.Completed -> {
                val conversationRevision = checkNotNull(state.value.conversation).revision
                val answer = AnswerPolicy.apply(request, chunk.answer, inputRevision, conversationRevision) ?: return
                val next = ledger.complete(request, answer, "assistant:${request.requestId}", inputRevision, conversationRevision)
                val message = next.messages.firstOrNull { it.requestId == request.requestId && it.role == MessageRole.ASSISTANT } ?: return
                if (!isCurrent(request, token)) return
                recovery = Recovery.Complete(request, token, chunk.answer)
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
                ledger = next
                frozen = null
                recovery = null
                set(state.value.copy(answer = answer, messages = ledger.messages, streaming = false, error = null))
            }
        }
    }
    fun stop() = enqueue(true) {
        if (!state.value.streaming) return@enqueue
        stream?.cancel(); stream = null
        recovery = frozen?.let { Recovery.Analysis(it) }
        set(state.value.copy(streaming = false, error = CounselError("CANCELLED", true)))
    }
    fun clearAll() = enqueue(true) {
        invalidateRun()
        temporary.clear(); credential = null; ledger = AnalysisLedger(); initialized = false; inputRevision++
        dirtyDraft = false
        recovery = Recovery.Clear
        // Clear UI before suspending so a failed physical cleanup cannot expose old content.
        set(CounselState(loading = true, modelStatus = provider.kind, demo = provider.kind == ProviderKind.DEMO))
        val cleared = persistence.clearAll()
        set(state.value.copy(loading = false, error = if (cleared) null else CounselError("CLEANUP_INCOMPLETE", true)))
        if (cleared) recovery = null
    }
}
