package com.shengjidaguai.goutoujunshi.data

import androidx.room.*

@Dao
interface JunshiDao {
    @Query("SELECT contactId FROM conversations WHERE id=:id") suspend fun ownerOfConversation(id: String): String?
    @Query("SELECT contactId FROM messages WHERE id=:id") suspend fun ownerOfMessage(id: String): String?
    @Query("SELECT contactId FROM imports WHERE id=:id") suspend fun ownerOfImport(id: String): String?
    @Query("SELECT contactId FROM chat_lines WHERE id=:id") suspend fun ownerOfLine(id: String): String?
    @Query("SELECT contactId FROM memory_operations WHERE id=:id") suspend fun ownerOfMemoryOperation(id: String): String?
    @Query("SELECT contactId FROM memories WHERE id=:id") suspend fun ownerOfMemory(id: String): String?
    @Query("SELECT contactId FROM owned_records WHERE id=:id") suspend fun ownerOfRecord(id: String): String?
    @Query("SELECT * FROM contacts ORDER BY id") suspend fun contacts(): List<ContactRow>
    @Query("SELECT * FROM contacts WHERE id=:contactId") suspend fun contact(contactId: String): ContactRow?
    @Upsert suspend fun put(row: ContactRow)
    @Query("DELETE FROM contacts WHERE id=:contactId") suspend fun deleteContact(contactId: String)
    @Query("SELECT * FROM conversations WHERE contactId=:contactId ORDER BY id") suspend fun conversations(contactId: String): List<ConversationRow>
    @Query("SELECT * FROM conversations WHERE contactId=:contactId AND id=:id") suspend fun conversation(contactId: String, id: String): ConversationRow?
    @Upsert suspend fun put(row: ConversationRow)
    @Query("DELETE FROM conversations WHERE contactId=:contactId AND id=:id") suspend fun deleteConversation(contactId: String, id: String)
    @Query("UPDATE conversations SET draft=:draft WHERE contactId=:contactId AND id=:id") suspend fun draft(contactId: String, id: String, draft: String): Int
    @Query("SELECT * FROM messages WHERE contactId=:contactId AND conversationId=:conversationId ORDER BY rowid") suspend fun messages(contactId: String, conversationId: String): List<MessageRow>
    @Query("SELECT * FROM messages WHERE contactId=:contactId AND id=:id") suspend fun message(contactId: String, id: String): MessageRow?
    @Upsert suspend fun put(row: MessageRow)
    @Query("DELETE FROM messages WHERE contactId=:contactId AND id=:id") suspend fun deleteMessage(contactId: String, id: String)
    @Query("SELECT * FROM imports WHERE contactId=:contactId ORDER BY id") suspend fun imports(contactId: String): List<ImportRow>
    @Query("SELECT * FROM imports WHERE contactId=:contactId AND id=:id") suspend fun imported(contactId: String, id: String): ImportRow?
    @Upsert suspend fun put(row: ImportRow)
    @Query("DELETE FROM imports WHERE contactId=:contactId AND id=:id") suspend fun deleteImport(contactId: String, id: String)
    @Query("SELECT * FROM chat_lines WHERE contactId=:contactId AND importId=:importId ORDER BY rowid") suspend fun lines(contactId: String, importId: String): List<ChatLineRow>
    @Query("SELECT * FROM chat_lines WHERE contactId=:contactId AND id=:id") suspend fun line(contactId: String, id: String): ChatLineRow?
    @Upsert suspend fun put(row: ChatLineRow)
    @Query("SELECT * FROM style_samples WHERE groupName=:groupName ORDER BY rowid") suspend fun samples(groupName: String): List<StyleSampleRow>
    @Query("SELECT * FROM style_samples WHERE contactId=:contactId ORDER BY rowid") suspend fun samplesForContact(contactId:String):List<StyleSampleRow>
    @Query("SELECT * FROM style_samples WHERE id=:id") suspend fun sample(id: String): StyleSampleRow?
    @Upsert suspend fun put(row: StyleSampleRow)
    @Query("DELETE FROM style_samples WHERE id=:id") suspend fun deleteSample(id: String)
    @Query("DELETE FROM style_samples WHERE answerId=:id") suspend fun deleteAnswerSamples(id: String)
    @Query("SELECT * FROM style_profiles WHERE groupName=:groupName") suspend fun profile(groupName: String): StyleProfileRow?
    @Upsert suspend fun put(row: StyleProfileRow)
    @Query("DELETE FROM style_profiles") suspend fun clearProfiles()
    @Query("DELETE FROM style_profiles WHERE groupName=:groupName") suspend fun deleteProfile(groupName:String)
    @Query("DELETE FROM style_samples") suspend fun clearSamples()
    @Query("SELECT * FROM memories WHERE contactId=:contactId ORDER BY rowid") suspend fun memories(contactId: String): List<MemoryRow>
    @Upsert suspend fun put(row: MemoryRow)
    @Query("DELETE FROM memories WHERE contactId=:contactId") suspend fun clearMemories(contactId: String)
    @Query("DELETE FROM memories") suspend fun clearAllMemories()
    @Query("SELECT * FROM memory_operations WHERE contactId=:contactId ORDER BY commitOrder") suspend fun operations(contactId: String): List<MemoryOperationRow>
    @Upsert suspend fun put(row: MemoryOperationRow)
    @Query("DELETE FROM memory_operations WHERE contactId=:contactId") suspend fun clearOperations(contactId: String)
    @Query("DELETE FROM memory_operations") suspend fun clearAllOperations()
    @Query("SELECT * FROM owned_records WHERE contactId=:contactId AND kind=:kind ORDER BY id") suspend fun records(contactId: String, kind: String): List<OwnedRecordRow>
    @Query("SELECT * FROM owned_records WHERE contactId=:contactId AND kind=:kind AND id=(:kind || ':' || :id)") suspend fun record(contactId: String, kind: String, id: String): OwnedRecordRow?
    @Query("DELETE FROM owned_records WHERE kind='MEMORY_ANCHOR'") suspend fun clearMemoryAnchors()
    @Query("DELETE FROM owned_records WHERE kind='MEMORY_SOURCE'") suspend fun clearMemorySources()
    @Query("DELETE FROM owned_records WHERE kind='SUMMARY'") suspend fun clearSummaries()
    @Query("DELETE FROM owned_records WHERE contactId=:contactId AND kind='SUMMARY'") suspend fun clearSummaries(contactId: String)
    @Upsert suspend fun put(row: OwnedRecordRow)
    @Query("DELETE FROM owned_records WHERE contactId=:contactId AND kind=:kind AND id=(:kind || ':' || :id)") suspend fun deleteRecord(contactId: String, kind: String, id: String)
    @Query("SELECT * FROM tips ORDER BY name") suspend fun tips(): List<TipRow>
    @Query("SELECT * FROM tips WHERE name=:name") suspend fun tipNamed(name: String): TipRow?
    @Upsert suspend fun put(row: TipRow)
    @Query("DELETE FROM tips WHERE id=:id") suspend fun deleteTip(id: String)
    @Query("SELECT * FROM consent WHERE id=1") suspend fun consent(): ConsentRow?
    @Upsert suspend fun put(row: ConsentRow)
    @Query("SELECT * FROM metadata WHERE id=1") suspend fun metadata(): MetadataRow?
    @Upsert suspend fun put(row: MetadataRow)
    @Query("SELECT * FROM source_files WHERE contactId=:contactId") suspend fun files(contactId: String): List<SourceFileRow>
    @Query("SELECT * FROM source_files WHERE path=:path") suspend fun sourceFile(path: String): SourceFileRow?
    @Upsert suspend fun put(row: SourceFileRow)
    @Query("DELETE FROM source_files WHERE path=:path") suspend fun removeFile(path: String)
    @Upsert suspend fun put(row: PendingFileCleanupRow)
    @Query("SELECT * FROM pending_file_cleanup") suspend fun pendingFiles(): List<PendingFileCleanupRow>
    @Query("SELECT * FROM pending_file_cleanup WHERE path=:path") suspend fun pendingFile(path: String): PendingFileCleanupRow?
    @Query("DELETE FROM pending_file_cleanup WHERE path=:path") suspend fun cleaned(path: String)
}

@Database(entities = [ContactRow::class, ConversationRow::class, MessageRow::class, ImportRow::class,
    ChatLineRow::class, StyleSampleRow::class, StyleProfileRow::class, MemoryRow::class,
    MemoryOperationRow::class, OwnedRecordRow::class, TipRow::class, ConsentRow::class,
    MetadataRow::class, SourceFileRow::class, PendingFileCleanupRow::class], version = 1, exportSchema = true)
abstract class JunshiDatabase : RoomDatabase() {
    abstract fun dao(): JunshiDao
    companion object {
        /** No historical migrations exist at v1. Never add destructive fallback. */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }
}
