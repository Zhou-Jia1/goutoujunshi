package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactEditReceipt(val before: Contact, val after: Contact)

class ContactRepository(private val session: DataSession) {
    suspend fun list(): List<Contact> = session.transaction { contacts().map { decode(it.payload) } }
    suspend fun get(contactId: String): Contact? = session.transaction { contact(contactId)?.let { decode(it.payload) } }
    suspend fun save(contact: Contact) = session.transaction {
        require(contact.id.isNotBlank() && contact.alias.isNotBlank())
        val previous = contact(contact.id)
        val revision = previous?.revision?.plus(1) ?: 0
        put(ContactRow(contact.id, revision, encode(contact.copy(sourceRevision = revision))))
        if (previous != null) invalidateAndRecompute()
    }
    /** CAS edits cannot recreate a deleted owner. Undo also requires the receipt's current revision. */
    suspend fun update(next: Contact, expectedSource: Long): ContactEditReceipt = session.transaction {
        require(next.alias.isNotBlank() && next.alias.length <= 80 && (next.notes?.length ?: 0) <= 4000)
        val previous = requireContact(next.id)
        check(previous.revision == expectedSource) { "Contact source changed" }
        val before: Contact = decode(previous.payload)
        val after = next.copy(alias = next.alias.trim(), createdAt = before.createdAt, sourceRevision = expectedSource + 1)
        put(ContactRow(next.id, after.sourceRevision, encode(after)))
        invalidateAndRecompute()
        ContactEditReceipt(before, after)
    }
    suspend fun undo(receipt: ContactEditReceipt): Contact = update(receipt.before, receipt.after.sourceRevision).after

    suspend fun delete(contactId: String): List<String> {
        session.transaction {
            files(contactId).forEach { put(PendingFileCleanupRow(it.path)) }
            deleteContact(contactId)
            invalidateAndRecompute()
        }
        return FileCleanupRepository(session).retry()
    }
}

class ConversationRepository(private val session: DataSession) {
    suspend fun list(contactId: String): List<Conversation> = session.transaction { conversations(contactId).map { decode(it.payload) } }
    suspend fun save(conversation: Conversation) = session.transaction {
        require(conversation.canPersist && conversation.id.isNotBlank())
        val contactId = requireNotNull(conversation.contactId)
        requireContact(contactId)
        require(ownerOfConversation(conversation.id).let { it == null || it == contactId })
        val previous = conversation(contactId, conversation.id)
        put(ConversationRow(conversation.id, contactId, encode(conversation), previous?.draft.orEmpty()))
    }
    suspend fun saveMessage(message: Message) = session.transaction {
        val contactId = requireNotNull(message.contactId)
        require(message.id.isNotBlank())
        require(ownerOfMessage(message.id).let { it == null || it == contactId })
        requireNotNull(conversation(contactId, message.conversationId))
        val previous = message(contactId, message.id)
        // A source timestamp is immutable after first insertion. Never replace an older
        // stored time (including legacy unknown=0) with a retry or current wall-clock value.
        val retained = previous?.let { message.copy(createdAt = decode<Message>(it.payload).createdAt) } ?: message
        previous?.let {
            if (it.payload != encode(retained)) { deleteAnswerSamples(message.id); invalidateAndRecompute() }
        }
        put(MessageRow(message.id, contactId, message.conversationId, encode(retained)))
    }
    suspend fun messages(contactId: String, conversationId: String): List<Message> = session.transaction {
        messages(contactId, conversationId).map { decode(it.payload) }
    }
    suspend fun saveDraft(contactId: String, conversationId: String, text: String) = session.transaction {
        check(draft(contactId, conversationId, text) == 1) { "Conversation does not belong to contact" }
    }
    /** Atomically advance a formal conversation and its sensitive draft, rejecting stale edits. */
    suspend fun updateDraft(contactId: String, expectedRevision: Long, next: Conversation, text: String,
        expectedContactRevision: Long? = null) = session.transaction {
        require(next.canPersist && next.contactId == contactId && next.revision == expectedRevision + 1)
        val owner = requireContact(contactId)
        if (expectedContactRevision != null) check(owner.revision == expectedContactRevision) { "Contact source changed" }
        val previous = requireNotNull(conversation(contactId, next.id)) { "Conversation does not belong to contact" }
        check(decode<Conversation>(previous.payload).revision == expectedRevision) { "Conversation changed" }
        put(previous.copy(payload = encode(next), draft = text))
    }
    suspend fun draft(contactId: String, conversationId: String): String? = session.transaction { conversation(contactId, conversationId)?.draft }
    suspend fun delete(contactId: String, conversationId: String) = session.transaction {
        deleteConversation(contactId, conversationId)
        invalidateAndRecompute()
    }
    suspend fun deleteMessage(contactId: String, messageId: String) = session.transaction {
        deleteMessage(contactId, messageId)
        invalidateAndRecompute()
    }

    /** Explicit save only. Deterministic IDs and one transaction make repeated taps idempotent. */
    suspend fun saveSelectedTemporary(contactId: String, store: TemporarySessionStore, credential: TemporarySessionCredential): String = store.withCurrent(credential) { temporary ->
        session.transaction {
        requireContact(contactId)
        val conversationId = "saved:${credential.instanceId}"
        require(ownerOfConversation(conversationId).let { it == null || it == contactId })
        if (conversation(contactId, conversationId) != null) return@transaction conversationId
        val conversation = Conversation(conversationId, contactId, false, System.currentTimeMillis())
        put(ConversationRow(conversationId, contactId, encode(conversation), temporary.draft))
        temporary.messages.forEach { message ->
            val saved = message.copy(id = "$conversationId:${message.id}", contactId = contactId, conversationId = conversationId)
            require(ownerOfMessage(saved.id) == null)
            put(MessageRow(saved.id, contactId, conversationId, encode(saved)))
        }
        temporary.imports.forEach { imported ->
            if (imports(contactId).none { it.hash == imported.contentHash }) {
                val saved = imported.copy(id = "$conversationId:${imported.id}", contactId = contactId)
                require(saved.contentHash.isNotBlank() && ownerOfImport(saved.id) == null)
                put(ImportRow(saved.id, contactId, saved.contentHash, encode(saved)))
                temporary.lines.filter { it.importId == imported.id }.forEach { line ->
                    val savedLine = line.copy(id = "$conversationId:${line.id}", contactId = contactId, importId = saved.id)
                    require(ownerOfLine(savedLine.id) == null)
                    put(ChatLineRow(savedLine.id, contactId, saved.id, encode(savedLine)))
                }
            }
        }
        val owner = requireContact(contactId)
        put(owner.copy(revision = owner.revision + 1, payload = encode(decode<Contact>(owner.payload).copy(sourceRevision = owner.revision + 1))))
        invalidateAndRecompute()
        conversationId
        }
    }
}

class ImportRepository(private val session: DataSession) {
    suspend fun list(contactId: String): List<ChatImport> = session.transaction { imports(contactId).map { decode(it.payload) } }
    suspend fun lines(contactId: String, importId: String): List<ChatLine> = session.transaction { lines(contactId, importId).map { decode(it.payload) } }
    /** Hash deduplication is scoped to a contact; caller supplies a deterministic content hash. */
    suspend fun save(imported: ChatImport, lines: List<ChatLine>, expectedSourceRevision: Long? = null): String = session.transaction {
        val contactId = requireNotNull(imported.contactId)
        val source = requireContact(contactId)
        require(imported.id.isNotBlank() && imported.contentHash.isNotBlank())
        require(ownerOfImport(imported.id).let { it == null || it == contactId })
        imports(contactId).firstOrNull { it.hash == imported.contentHash }?.let { return@transaction it.id }
        if (expectedSourceRevision != null) check(source.revision == expectedSourceRevision) { "Contact source changed" }
        require(imported(contactId, imported.id) == null) { "Imports are immutable; delete then import corrected content" }
        require(lines.all { it.contactId == contactId && it.importId == imported.id && it.id.isNotBlank() })
        require(lines.map { it.id }.distinct().size == lines.size)
        lines.forEach { require(ownerOfLine(it.id) == null) }
        put(ImportRow(imported.id, contactId, imported.contentHash, encode(imported)))
        lines.sortedBy { it.seq }.forEach { put(ChatLineRow(it.id, contactId, imported.id, encode(it))) }
        // New source material invalidates in-flight computations as well as edits/deletions.
        val owner = requireContact(contactId)
        put(owner.copy(revision = owner.revision + 1, payload = encode(decode<Contact>(owner.payload).copy(sourceRevision = owner.revision + 1))))
        invalidateAndRecompute()
        imported.id
    }
    suspend fun delete(contactId: String, importId: String): List<String> {
        session.transaction {
            val owner = requireContact(contactId)
            files(contactId).filter { it.importId == importId }.forEach {
                put(PendingFileCleanupRow(it.path)); removeFile(it.path)
            }
            deleteImport(contactId, importId)
            put(owner.copy(revision = owner.revision + 1, payload = encode(decode<Contact>(owner.payload).copy(sourceRevision = owner.revision + 1))))
            invalidateAndRecompute()
        }
        return FileCleanupRepository(session).retry()
    }
}

/** Register only app-owned permanent files; temporary screenshots must never call this. */
class FileCleanupRepository(private val session: DataSession) {
    suspend fun register(contactId: String, importId: String?, file: File, ownedRoot: File) = session.transaction {
        requireContact(contactId)
        if (importId != null) requireNotNull(imported(contactId, importId))
        val root = ownedRoot.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(file.isFile) { "Only permanent files may be registered" }
        session.ownedFileRoot?.let { require(root == it.canonicalFile.toPath()) { "Use EncryptedDataStore.permanentFileRoot" } }
        require(path.startsWith(root) && path != root)
        require(pendingFile(path.toString()) == null) { "A path pending cleanup cannot be registered" }
        sourceFile(path.toString())?.let { existing ->
            require(existing.contactId == contactId && existing.importId == importId) { "A registered path cannot change source ownership" }
        }
        put(SourceFileRow(path.toString(), contactId, importId))
    }
    suspend fun retry(): List<String> = retryWith { file -> !file.exists() || file.delete() }

    /** Internal deletion seam permits deterministic failure and concurrency regression tests. */
    internal suspend fun retryWith(deleteFile: suspend (File) -> Boolean): List<String> = session.transaction {
        // Keep registration, retries and full-clear serialized through the session gate until
        // the physical deletion and queue acknowledgement have both finished. Never delete a
        // path from a snapshot taken before another transaction could register a new owner.
        val pending = pendingFiles()
        val failures = mutableListOf<String>()
        pending.forEach { row ->
            val deleted = withContext(Dispatchers.IO) {
                runCatching { deleteFile(File(row.path)) }.getOrDefault(false)
            }
            if (deleted) cleaned(row.path) else failures += row.path
        }
        failures
    }
}
