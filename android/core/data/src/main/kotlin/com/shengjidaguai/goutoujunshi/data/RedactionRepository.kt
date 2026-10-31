package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.RedactionProfile

/** SQLCipher-owned row, required contact FK cascades on deletion; no consent coupling. */
class RedactionRepository(private val session: DataSession) {
    private fun key(contactId: String) = "REDACTION:${contactId.length}:$contactId"
    suspend fun load(contactId: String): RedactionProfile = session.transaction {
        requireContact(contactId)
        records(contactId, OwnedRecordKind.REDACTION.name).singleOrNull()?.let { decode<RedactionProfile>(it.payload) } ?: RedactionProfile()
    }
    suspend fun save(contactId: String, expected: Long, profile: RedactionProfile) = session.transaction {
        requireContact(contactId)
        val previous = records(contactId, OwnedRecordKind.REDACTION.name).singleOrNull()?.let { decode<RedactionProfile>(it.payload) } ?: RedactionProfile()
        check(previous.revision == expected && profile.revision == expected + 1)
        put(OwnedRecordRow(key(contactId), contactId, OwnedRecordKind.REDACTION, encode(profile)))
    }
}
