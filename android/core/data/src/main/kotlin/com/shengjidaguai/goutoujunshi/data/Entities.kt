package com.shengjidaguai.goutoujunshi.data

import androidx.room.*

// Payloads are typed domain JSON. Relational ownership and source references remain SQL columns.
@Entity(tableName = "contacts")
data class ContactRow(@PrimaryKey val id: String, val revision: Long, val payload: String)

@Entity(tableName = "conversations", foreignKeys = [ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE)], indices = [Index("contactId"), Index(value = ["id", "contactId"], unique = true)])
data class ConversationRow(@PrimaryKey val id: String, val contactId: String, val payload: String, val draft: String = "")

@Entity(tableName = "messages", foreignKeys = [ForeignKey(ConversationRow::class, ["id", "contactId"], ["conversationId", "contactId"], onDelete = ForeignKey.CASCADE)], indices = [Index("conversationId", "contactId"), Index("contactId")])
data class MessageRow(@PrimaryKey val id: String, val contactId: String, val conversationId: String, val payload: String)

@Entity(tableName = "imports", foreignKeys = [ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE)], indices = [Index("contactId"), Index(value = ["contactId", "hash"], unique = true), Index(value = ["id", "contactId"], unique = true)])
data class ImportRow(@PrimaryKey val id: String, val contactId: String, val hash: String, val payload: String)

@Entity(tableName = "chat_lines", foreignKeys = [ForeignKey(ImportRow::class, ["id", "contactId"], ["importId", "contactId"], onDelete = ForeignKey.CASCADE)], indices = [Index("importId", "contactId"), Index("contactId")])
data class ChatLineRow(@PrimaryKey val id: String, val contactId: String, val importId: String, val payload: String)

@Entity(tableName = "style_samples", foreignKeys = [
    ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(ImportRow::class, ["id"], ["importId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(ChatLineRow::class, ["id"], ["lineId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(MessageRow::class, ["id"], ["answerId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("contactId"), Index("importId"), Index("lineId"), Index("answerId"), Index("groupName")])
data class StyleSampleRow(@PrimaryKey val id: String, val groupName: String, val contactId: String?, val importId: String?, val lineId: String?, val answerId: String?, val sourceRevision: Long, val consentVersion: Long, val payload: String)

@Entity(tableName = "style_profiles")
data class StyleProfileRow(@PrimaryKey val groupName: String, val sourceRevision: Long, val consentVersion: Long, val payload: String)

@Entity(tableName = "memories", foreignKeys = [ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE)], indices = [Index("contactId")])
data class MemoryRow(@PrimaryKey val id: String, val contactId: String, val payload: String)

@Entity(tableName = "memory_operations", foreignKeys = [ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE)], indices = [Index("contactId")])
data class MemoryOperationRow(@PrimaryKey val id: String, val contactId: String, val commitOrder: Long, val payload: String)

/** kind is validated by OwnedRecordKind; every row has required contact ownership. */
@Entity(tableName = "owned_records", foreignKeys = [ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE)], indices = [Index("contactId", "kind")])
data class OwnedRecordRow(@PrimaryKey val id: String, val contactId: String, val kind: OwnedRecordKind, val payload: String)
enum class OwnedRecordKind { PLAN, EVENT, ANALYSIS_RUN, NICKNAME, REDACTION, SUMMARY, MEMORY_STATE, MEMORY_SOURCE, MEMORY_ANCHOR, MEMORY_REJECTION, FLOATING_IDENTITY, CAPTURE_PROJECTION, TEACHING_HABIT, FOLLOWUP }

@Entity(tableName = "tips", indices = [Index(value = ["name"], unique = true)])
data class TipRow(@PrimaryKey val id: String, val name: String, val payload: String)

@Entity(tableName = "consent")
data class ConsentRow(@PrimaryKey val id: Int = 1, val payload: String)

@Entity(tableName = "metadata")
data class MetadataRow(@PrimaryKey val id: Int = 1, val styleRevision: Long = 0)

@Entity(tableName = "source_files", foreignKeys = [ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE)], indices = [Index("contactId"), Index("importId")])
data class SourceFileRow(@PrimaryKey val path: String, val contactId: String, val importId: String?)
@Entity(tableName = "pending_file_cleanup")
data class PendingFileCleanupRow(@PrimaryKey val path: String)
