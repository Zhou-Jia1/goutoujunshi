package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CounselControllerTest {
    internal class MemoryPersistence : CounselPersistence {
        val people = mutableListOf<Contact>()
        val saved = mutableMapOf<String, StoredCounsel>()
        var messageWrites = 0
        var draftWrites = 0
        var beforeMessage: CompletableDeferred<Unit>? = null
        var beforeUpdate: CompletableDeferred<Unit>? = null
        var initializeFailures = 0
        var initializeAttempts = 0
        var updateFailures = 0
        var failedLoadId: String? = null
        var failCreateAfterCommit = false
        var createAttempts = 0
        var assistantFailures = 0
        var contactsFailures = 0
        var deleteAttempts = 0
        var cleanupFailures = 0
        var failContactsAfterDelete = false
        var cleanupPending = false
        var beforeCleanup: CompletableDeferred<Unit>? = null
        override suspend fun initialize() {
            initializeAttempts++
            if (initializeFailures > 0) { initializeFailures--; error("synthetic init failure") }
        }
        override suspend fun contacts(): List<Contact> {
            if (contactsFailures > 0) { contactsFailures--; error("synthetic contacts read failure") }
            return people.toList()
        }
        override suspend fun createContact(contact: Contact) {
            createAttempts++
            people += contact
            if (failCreateAfterCommit) { failCreateAfterCommit = false; error("synthetic acknowledgement failure") }
        }
        override suspend fun deleteContact(id: String) {
            deleteAttempts++
            people.removeAll { it.id == id }; saved.remove(id)
            cleanupPending = true
            beforeCleanup?.await()
            if (cleanupFailures > 0) { cleanupFailures--; error("synthetic cleanup failure") }
            cleanupPending = false
            if (failContactsAfterDelete) { failContactsAfterDelete = false; contactsFailures = 1 }
        }
        override suspend fun loadOrCreate(contact: Contact): StoredCounsel {
            if (failedLoadId == contact.id) { failedLoadId = null; error("synthetic load failure") }
            return saved.getOrPut(contact.id) {
                StoredCounsel(Conversation("conversation:${contact.id}", contact.id, false), "", emptyList())
            }
        }
        override suspend fun update(contact: Contact, previous: Conversation, next: Conversation, draft: String) {
            check(saved[contact.id]?.conversation == previous)
            beforeUpdate?.await()
            if (updateFailures > 0) { updateFailures--; error("synthetic pre-commit failure") }
            draftWrites++
            saved[contact.id] = saved.getValue(contact.id).copy(conversation = next, draft = draft)
        }
        override suspend fun saveMessage(contact: Contact, conversation: Conversation, message: Message) {
            beforeMessage?.await()
            check(saved[contact.id]?.conversation == conversation)
            if (message.role == MessageRole.ASSISTANT && assistantFailures > 0) {
                assistantFailures--; error("synthetic assistant commit failure")
            }
            messageWrites++
            val prior = saved.getValue(contact.id)
            saved[contact.id] = prior.copy(messages = prior.messages.filterNot { it.id == message.id } + message)
        }
        override suspend fun discardMessage(contact: Contact, message: Message) {
            saved[contact.id]?.let { saved[contact.id] = it.copy(messages = it.messages.filterNot { entry -> entry.id == message.id }) }
        }
        override suspend fun clearAll(): Boolean { people.clear(); saved.clear(); return true }
    }
    internal class ControlledProvider : AnalysisProvider {
        override val kind = ProviderKind.DEMO
        val calls = mutableListOf<AnalysisRequest>()
        val outputs = mutableListOf<Channel<AnalysisChunk>>()
        override fun analyze(request: AnalysisRequest) = flow {
            calls += request
            val output = Channel<AnalysisChunk>(Channel.UNLIMITED)
            outputs += output
            for (chunk in output) emit(chunk)
        }
    }
    internal class Fixture(val persistence: MemoryPersistence = MemoryPersistence()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = ControlledProvider()
        var prepares = 0
        val controller = CounselController(persistence, TemporarySessionStore(), FrozenPromptAssembler(object : PromptAssembler {
            override fun prepare(request: AnalysisRequest): PreparedPrompt {
                prepares++
                return PreparedPrompt("synthetic system", "synthetic ${request.userText}", listOf(KnowledgeReference("synthetic.md", "a".repeat(64), 9)), "synthetic-v1")
            }
        }), provider, scope)
        fun close() { scope.cancel() }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(2_000) { while (!predicate()) yield() }
    private val completed get() = AnalysisChunk.Completed(StructuredAnswer(id = "synthetic-answer", reply = listOf("synthetic reply")))

    @Test fun staleConversationCallbacksDoNotMutateOrCancelCurrentConversation() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic A", RelationType.FRIEND)
            val a = f.controller.state.value.conversation!!.id
            f.controller.createContact("Synthetic B", RelationType.BOSS)
            f.controller.editDraft("synthetic B request"); f.controller.analyze()
            await { f.provider.calls.size == 1 }
            val before = f.controller.state.value
            val stale = DraftEditStamp("synthetic-A-editor", 1, a)
            f.controller.editDraft("stale A edit", stale)
            f.controller.pasteText("stale A paste", stale.copy(sequence = 2))
            f.controller.preparePreview(a); f.controller.analyze(a)
            assertEquals(before, f.controller.state.value)
            f.provider.outputs.single().send(completed)
            await { f.controller.state.value.answer != null }
            assertEquals(1, f.provider.calls.size)
            assertEquals("synthetic B request", f.persistence.saved.values.last().draft)
        } finally { f.close() }
    }

    @Test fun contextualEditAcceptedBeforeSwitchIsRejectedWhenExecutedAfterSwitch() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic queued A", RelationType.FRIEND)
            val aContact = f.controller.state.value.currentContact!!
            val aConversation = f.controller.state.value.conversation!!.id
            f.controller.createContact("Synthetic queued B", RelationType.BOSS)
            val b = f.controller.state.value.currentContact!!
            f.controller.editDraft("synthetic preserved B")
            f.controller.selectContact(aContact.id)
            val barrier = CompletableDeferred<Unit>()
            f.persistence.beforeUpdate = barrier
            f.controller.editDraft("synthetic first A")
            f.controller.selectContact(b.id)
            f.controller.editDraft("synthetic stale queued A", DraftEditStamp("queued-editor", 1, aConversation))
            barrier.complete(Unit)
            await { f.controller.state.value.currentContact?.id == b.id }
            assertEquals("synthetic preserved B", f.controller.state.value.draft)
            assertEquals("synthetic preserved B", f.persistence.saved.getValue(b.id).draft)
            assertNull(f.controller.state.value.draftEditAck)
        } finally { f.close() }
    }

    @Test fun initializeFailureRetryActuallyOpensStoreAndInitializesSession() = runBlocking {
        val storage = MemoryPersistence().apply { initializeFailures = 1 }
        val f = Fixture(storage)
        try {
            f.controller.initialize()
            await { f.controller.state.value.error?.code == "STORAGE_UNAVAILABLE" }
            assertNull(f.controller.state.value.conversation)
            f.controller.retry()
            await { f.controller.state.value.conversation != null }
            assertEquals(2, storage.initializeAttempts)
            assertNull(f.controller.state.value.error)
            assertTrue(f.provider.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun draftFailureKeepsLastSuccessfulVersionAndRetryPersistsDirtyDraft() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic draft retry", RelationType.FRIEND)
            val committed = f.controller.state.value.conversation!!
            f.persistence.updateFailures = 1
            f.controller.editDraft("synthetic dirty draft")
            await { f.controller.state.value.error != null }
            assertEquals("synthetic dirty draft", f.controller.state.value.draft)
            assertEquals(committed, f.controller.state.value.conversation)
            assertEquals(committed, f.persistence.saved.values.single().conversation)
            assertEquals("", f.persistence.saved.values.single().draft)
            f.controller.retry()
            await { f.controller.state.value.error == null }
            assertEquals(committed.revision + 1, f.controller.state.value.conversation!!.revision)
            assertEquals("synthetic dirty draft", f.persistence.saved.values.single().draft)
            assertTrue(f.provider.calls.isEmpty())
            f.controller.editDraft("synthetic later edit")
            assertEquals(committed.revision + 2, f.controller.state.value.conversation!!.revision)
            val restored = Fixture(f.persistence)
            try {
                restored.controller.initialize()
                assertEquals("synthetic later edit", restored.controller.state.value.draft)
                assertEquals(f.controller.state.value.conversation, restored.controller.state.value.conversation)
            } finally { restored.close() }
        } finally { f.close() }
    }

    @Test fun nextEditAfterDraftCommitFailureUsesLastCommittedVersion() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic next edit", RelationType.FRIEND)
            val revision = f.controller.state.value.conversation!!.revision
            f.persistence.updateFailures = 1
            f.controller.editDraft("synthetic failed edit")
            await { f.controller.state.value.error != null }
            f.controller.editDraft("synthetic replacement edit")
            await { f.controller.state.value.error == null }
            assertEquals(revision + 1, f.controller.state.value.conversation!!.revision)
            assertEquals("synthetic replacement edit", f.persistence.saved.values.single().draft)
            assertTrue(f.provider.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun failedLoadRetriesRequestedContactRatherThanInitialContact() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic first", RelationType.FRIEND)
            val first = f.controller.state.value.currentContact!!
            f.controller.createContact("Synthetic second", RelationType.BOSS)
            val second = f.controller.state.value.currentContact!!
            f.controller.selectContact(first.id)
            f.persistence.failedLoadId = second.id
            f.controller.selectContact(second.id)
            await { f.controller.state.value.error != null }
            assertEquals(first, f.controller.state.value.currentContact)
            f.controller.retry()
            await { f.controller.state.value.currentContact == second }
            assertNull(f.controller.state.value.error)
            assertTrue(f.provider.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun initializeWhoseInitialContactLoadFailedRetriesThatLoad() = runBlocking {
        val storage = MemoryPersistence()
        val contact = Contact(id = "synthetic-load", alias = "Synthetic load")
        storage.people += contact
        storage.failedLoadId = contact.id
        val f = Fixture(storage)
        try {
            f.controller.initialize()
            await { f.controller.state.value.error != null }
            f.controller.retry()
            await { f.controller.state.value.currentContact == contact }
            assertEquals(1, storage.initializeAttempts)
            assertNull(f.controller.state.value.error)
        } finally { f.close() }
    }

    @Test fun ambiguousCreateFailureRetryRefreshesWithoutCreatingDuplicateContact() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize()
            f.persistence.failCreateAfterCommit = true
            f.controller.createContact("Synthetic uncertain create", RelationType.FRIEND)
            await { f.controller.state.value.error != null }
            f.controller.retry()
            await { f.controller.state.value.contacts.size == 1 }
            assertEquals(1, f.persistence.createAttempts)
            assertEquals(1, f.persistence.people.size)
            assertTrue(f.provider.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun assistantCommitFailureRetrySavesReceivedAnswerWithoutRunningProviderAgain() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic answer retry", RelationType.FRIEND)
            f.controller.editDraft("synthetic request"); f.controller.analyze()
            await { f.provider.calls.size == 1 }
            f.persistence.assistantFailures = 1
            f.provider.outputs.single().send(completed)
            await { f.controller.state.value.error != null }
            assertNull(f.controller.state.value.answer)
            f.controller.retry()
            await { f.controller.state.value.answer != null }
            assertEquals(1, f.provider.calls.size)
            assertEquals(2, f.persistence.saved.values.single().messages.size)
            assertNull(f.controller.state.value.error)
        } finally { f.close() }
    }

    @Test fun committedDeleteWhoseContactRefreshFailedRetriesWholeDelete() = runBlocking {
        assertDeleteRecovery(cleanupFails = false)
    }

    @Test fun committedDeleteWhoseCleanupFailedRetriesCleanupBeforeClearingError() = runBlocking {
        assertDeleteRecovery(cleanupFails = true)
    }

    private suspend fun assertDeleteRecovery(cleanupFails: Boolean) {
        val f = Fixture()
        try {
            f.controller.initialize()
            f.controller.createContact("Synthetic retained contact", RelationType.FRIEND)
            val retained = f.controller.state.value.currentContact!!
            f.controller.editDraft("synthetic retained draft")
            val retainedSnapshot = f.persistence.saved.getValue(retained.id)
            f.controller.createContact("Synthetic deleted contact", RelationType.BOSS)
            val deleted = f.controller.state.value.currentContact!!
            f.controller.editDraft("synthetic content to delete")
            if (cleanupFails) f.persistence.cleanupFailures = 1 else f.persistence.failContactsAfterDelete = true
            f.controller.deleteContact(deleted.id)
            await { f.controller.state.value.error?.code == "STORAGE_UNAVAILABLE" }
            assertEquals(1, f.persistence.deleteAttempts)
            assertNull(f.controller.state.value.currentContact)
            assertTrue(f.controller.state.value.conversation!!.isTemporary)
            assertEquals("", f.controller.state.value.draft)
            assertTrue(f.controller.state.value.messages.isEmpty())
            assertTrue(f.controller.state.value.contacts.none { it.id == deleted.id })
            assertEquals(cleanupFails, f.persistence.cleanupPending)
            assertFalse(f.persistence.saved.containsKey(deleted.id))

            val cleanupBarrier = CompletableDeferred<Unit>()
            f.persistence.beforeCleanup = cleanupBarrier
            f.controller.retry()
            assertEquals("STORAGE_UNAVAILABLE", f.controller.state.value.error?.code)
            assertTrue(f.persistence.cleanupPending)
            cleanupBarrier.complete(Unit)
            await { f.controller.state.value.error == null }
            assertEquals(2, f.persistence.deleteAttempts)
            assertFalse(f.persistence.cleanupPending)
            assertEquals(listOf(retained), f.controller.state.value.contacts)
            assertEquals(retainedSnapshot, f.persistence.saved.getValue(retained.id))
            assertNull(f.controller.state.value.currentContact)
            f.controller.editDraft("synthetic temporary question after delete")
            f.controller.analyze()
            await { f.provider.calls.size == 1 }
            assertNull(f.provider.calls.single().contactId)
            f.provider.outputs.single().send(completed)
            await { f.controller.state.value.answer != null }
            assertFalse(f.persistence.saved.containsKey(deleted.id))
            f.controller.selectContact(retained.id)
            assertEquals("synthetic retained draft", f.controller.state.value.draft)
            assertEquals(retainedSnapshot.conversation, f.controller.state.value.conversation)
        } finally { f.close() }
    }

    @Test fun retryReusesFrozenRequestAndOneUserMessage() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic contact", RelationType.COLLEAGUE)
            f.controller.editDraft("synthetic input"); f.controller.analyze()
            await { f.provider.calls.size == 1 }
            f.provider.outputs[0].send(AnalysisChunk.Failed(AnalysisError("SYNTHETIC_FAILURE", "", true)))
            await { f.controller.state.value.error != null }
            f.controller.retry()
            await { f.provider.calls.size == 2 }
            assertEquals(f.provider.calls[0], f.provider.calls[1])
            f.provider.outputs[1].send(completed)
            await { f.controller.state.value.answer != null }
            assertEquals(2, f.controller.state.value.messages.size)
            assertEquals(2, f.persistence.messageWrites)
            assertEquals(1, f.prepares)
        } finally { f.close() }
    }

    @Test fun temporaryDraftAndAnswerNeverReachPersistence() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.pasteText("synthetic pasted claim"); f.controller.analyze()
            await { f.provider.calls.size == 1 }
            assertTrue(f.provider.calls.single().evidence.isEmpty())
            assertFalse(f.provider.calls.single().speakersConfirmed)
            f.provider.outputs.single().send(completed)
            await { f.controller.state.value.answer != null }
            assertEquals(0, f.persistence.draftWrites)
            assertEquals(0, f.persistence.messageWrites)
            f.controller.newTemporary()
            assertEquals("", f.controller.state.value.draft)
            assertTrue(f.controller.state.value.messages.isEmpty())
        } finally { f.close() }
    }

    @Test fun permanentDraftAndMessagesRestoreInNewController() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic contact", RelationType.FRIEND)
            f.controller.editDraft("synthetic saved input"); f.controller.analyze()
            await { f.provider.calls.size == 1 }
            f.provider.outputs.single().send(completed)
            await { f.controller.state.value.answer != null }
            val restored = Fixture(f.persistence)
            try {
                restored.controller.initialize()
                assertEquals("synthetic saved input", restored.controller.state.value.draft)
                assertEquals(2, restored.controller.state.value.messages.size)
                assertEquals(listOf("synthetic reply"), restored.controller.state.value.answer?.reply)
            } finally { restored.close() }
        } finally { f.close() }
    }

    @Test fun editedInputRevokesQueuedCompletionDuringSuspendedWrite() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic contact", RelationType.BOSS)
            f.controller.editDraft("first synthetic input"); f.controller.analyze()
            await { f.provider.calls.size == 1 }
            val barrier = CompletableDeferred<Unit>()
            f.persistence.beforeMessage = barrier
            f.provider.outputs.single().send(completed)
            yield()
            f.controller.editDraft("second synthetic input")
            barrier.complete(Unit)
            await { f.controller.state.value.draft == "second synthetic input" }
            assertNull(f.controller.state.value.answer)
            assertFalse(f.controller.state.value.streaming)
            // The serialized draft update runs after the in-flight write; no old callback
            // can write again after that revision has been committed.
            assertEquals("second synthetic input", f.persistence.saved.values.single().draft)
            assertTrue(f.persistence.saved.values.single().messages.none { it.role == MessageRole.ASSISTANT })
        } finally { f.close() }
    }

    @Test fun stopRejectsLateResultAndRetryKeepsOperationId() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.editDraft("synthetic input"); f.controller.analyze()
            await { f.provider.calls.size == 1 }
            val id = f.provider.calls.single().requestId
            f.controller.stop()
            f.provider.outputs[0].send(completed)
            assertNull(f.controller.state.value.answer)
            f.controller.retry()
            await { f.provider.calls.size == 2 }
            assertEquals(id, f.provider.calls[1].requestId)
            f.controller.clearAll()
            f.provider.outputs[1].send(completed)
            assertNull(f.controller.state.value.answer)
            assertNull(f.controller.state.value.conversation)
            assertTrue(f.persistence.saved.isEmpty())
        } finally { f.close() }
    }

    @Test fun previewIsFrozenForPlaybackAndEditInvalidatesIt() = runBlocking {
        val f = Fixture()
        try {
            f.controller.initialize(); f.controller.editDraft("synthetic input"); f.controller.preparePreview()
            await { f.controller.state.value.preview != null }
            val prepared = f.controller.state.value.preview
            f.controller.analyze()
            await { f.provider.calls.size == 1 }
            assertSame(prepared, f.controller.state.value.preview)
            assertEquals(1, f.prepares)
            f.controller.setTone(Tone.GENTLE)
            assertNull(f.controller.state.value.preview)
            f.controller.analyze()
            await { f.provider.calls.size == 2 }
            assertNotEquals(f.provider.calls[0].requestId, f.provider.calls[1].requestId)
        } finally { f.close() }
    }

    @Test fun consumedPreviewDoesNotReuseCompletedRequestOnSecondSend() = runBlocking {
        assertSecondSendIsNewOperation(previewAgain = false)
    }

    @Test fun previewAfterCompletionPreparesNewOperationForSecondSend() = runBlocking {
        assertSecondSendIsNewOperation(previewAgain = true)
    }

    private suspend fun assertSecondSendIsNewOperation(previewAgain: Boolean) {
        val f = Fixture()
        try {
            f.controller.initialize()
            f.controller.createContact("Synthetic repeated send", RelationType.COLLEAGUE)
            f.controller.editDraft("unchanged synthetic input")
            f.controller.preparePreview()
            await { f.controller.state.value.preview != null }
            f.controller.analyze()
            await { f.provider.calls.size == 1 }
            val firstAnswer = StructuredAnswer(id = "synthetic-first-answer", reply = listOf("synthetic first reply"))
            f.provider.outputs[0].send(AnalysisChunk.Completed(firstAnswer))
            await { f.controller.state.value.answer == firstAnswer }

            if (previewAgain) {
                f.controller.preparePreview()
                await { f.prepares == 2 }
            }
            f.controller.analyze()
            await { f.provider.calls.size == 2 }
            assertNotEquals(f.provider.calls[0].requestId, f.provider.calls[1].requestId)
            assertEquals(f.provider.calls[0].userText, f.provider.calls[1].userText)
            val secondAnswer = StructuredAnswer(id = "synthetic-second-answer", reply = listOf("synthetic second reply"))
            f.provider.outputs[1].send(AnalysisChunk.Completed(secondAnswer))
            await { f.controller.state.value.answer == secondAnswer }

            val messages = f.controller.state.value.messages
            assertEquals(2, messages.count { it.role == MessageRole.USER })
            assertEquals(2, messages.count { it.role == MessageRole.ASSISTANT })
            assertEquals(4, f.persistence.messageWrites)
            val stored = f.persistence.saved.values.single().messages
            assertEquals(messages, stored)
            val durableAnswer = Json.decodeFromString(StructuredAnswer.serializer(),
                stored.last { it.role == MessageRole.ASSISTANT }.answerJson!!)
            assertEquals(f.controller.state.value.answer, durableAnswer)

            val restored = Fixture(f.persistence)
            try {
                restored.controller.initialize()
                assertEquals(secondAnswer, restored.controller.state.value.answer)
                assertEquals(messages, restored.controller.state.value.messages)
            } finally { restored.close() }
        } finally { f.close() }
    }
}
