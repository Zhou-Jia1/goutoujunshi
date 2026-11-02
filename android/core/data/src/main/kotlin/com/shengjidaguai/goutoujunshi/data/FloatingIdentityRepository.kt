package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.FloatingIdentityBinding

/** SQLCipher owned records cascade with the contact, and clear with the database generation. */
class FloatingIdentityRepository(private val session: DataSession) {
    suspend fun save(binding: FloatingIdentityBinding, expected: Long = 0) = session.transaction {
        check(requireContact(binding.contactId).revision == binding.contactRevision)
        val key="FLOATING_IDENTITY:${binding.id}"
        val previous=records(binding.contactId,OwnedRecordKind.FLOATING_IDENTITY.name)
            .firstOrNull { it.id==key }?.let { decode<FloatingIdentityBinding>(it.payload) }
        if(previous==binding) return@transaction
        check((previous?.revision ?: 0)==expected && binding.revision==expected+1)
        put(OwnedRecordRow(key,binding.contactId,OwnedRecordKind.FLOATING_IDENTITY,encode(binding)))
    }
    suspend fun list(contactId: String): List<FloatingIdentityBinding> = session.transaction {
        val current=requireContact(contactId)
        records(contactId,OwnedRecordKind.FLOATING_IDENTITY.name).map { decode<FloatingIdentityBinding>(it.payload) }
            .filter { it.contactRevision==current.revision }
    }
}
