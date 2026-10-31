package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

class StyleRepository(private val session: DataSession) {
    suspend fun samples(group: RelationGroup): List<StyleSample> = session.transaction {
        samples(group.name).map { decode(it.payload) }
    }
    suspend fun profile(group: RelationGroup): StyleProfile? = session.transaction {
        val consent = currentConsent()
        if (!consent.enabled || consent.paused) return@transaction null
        profile(group.name)?.takeIf {
            it.sourceRevision == (metadata()?.styleRevision ?: 0) && it.consentVersion == consent.consentVersion
        }?.let { decode(it.payload) }
    }
    suspend fun saveSample(sample: StyleSample, stamp: WriteStamp) = session.transaction {
        checkStamp(session, stamp, sample.sourceContactId)
        require(sample.consentVersion == stamp.consentVersion && sample.sourceRevision == stamp.sourceRevision)
        StylePersistencePolicy.validate(sample, currentConsent(), stamp.sourceRevision, false)
        require(sample.id.isNotBlank())
        sample.sourceContactId?.let { contactId ->
            val owner: Contact = decode(requireContact(contactId).payload)
            require(sample.relationGroup == owner.relationType.group())
        }
        // A source reference is verified, not accepted on the model's assertion.
        when (sample.origin) {
            StyleOrigin.IMPORT -> {
                val contact = requireNotNull(sample.sourceContactId)
                val source = requireNotNull(line(contact, requireNotNull(sample.sourceMessageId)))
                val domain: ChatLine = decode(source.payload)
                require(source.importId == sample.sourceImportId && domain.speaker == Speaker.ME && domain.speakerConfirmed)
                require(domain.text == sample.text) { "Import sample must be an actual confirmed user line" }
                requireNotNull(imported(contact, source.importId))
                require(sample.sourceAnswerId == null)
            }
            StyleOrigin.EDITED_REPLY -> {
                val source = requireNotNull(message(requireNotNull(sample.sourceContactId), requireNotNull(sample.sourceAnswerId)))
                require(decode<Message>(source.payload).role == MessageRole.ASSISTANT)
                require(sample.text.isNotBlank())
                require(sample.sourceImportId == null && sample.sourceMessageId == null)
            }
            StyleOrigin.QUIZ -> {
                require(sample.sourceContactId == null && sample.sourceImportId == null && sample.sourceMessageId == null && sample.sourceAnswerId == null)
                require(sample.relationGroup == RelationGroup.DEFAULT && sample.text.isNotBlank())
            }
        }
        // Never allow replacing an unrelated source row under the same ID.
        sample(sample.id)?.let { require(it.contactId == sample.sourceContactId && it.importId == sample.sourceImportId && it.lineId == sample.sourceMessageId && it.answerId == sample.sourceAnswerId) }
        put(StyleSampleRow(sample.id, sample.relationGroup.name, sample.sourceContactId, sample.sourceImportId,
            sample.sourceMessageId, sample.sourceAnswerId, sample.sourceRevision, sample.consentVersion, encode(sample)))
        val rows = samples(sample.relationGroup.name).map { decode<StyleSample>(it.payload) }
        val retained = StylePersistencePolicy.retainSamples(rows).map { it.id }.toSet()
        rows.filterNot { it.id in retained }.forEach { deleteSample(it.id) }
        invalidateAndRecompute()
    }
    suspend fun saveProfile(profile: StyleProfile, stamp: WriteStamp) = session.transaction {
        checkStamp(session, stamp)
        require(profile.sourceRevision == stamp.sourceRevision && profile.consentVersion == stamp.consentVersion)
        StylePersistencePolicy.validateProfile(profile, currentConsent(), stamp.sourceRevision, false)
        require(profile.description.codePointCount(0, profile.description.length) <= 200)
        val sources = samples(profile.relationGroup.name)
        require(sources.isNotEmpty()) { "No surviving sources" }
        // Features always come from surviving local samples; only the description may be asynchronous.
        val features = StyleFeatureExtractor.extract(sources.map { decode<StyleSample>(it.payload).text })
        put(StyleProfileRow(profile.relationGroup.name, stamp.sourceRevision, stamp.consentVersion, encode(profile.copy(features = features))))
    }
    suspend fun deleteSample(id: String) = session.transaction { deleteSample(id); invalidateAndRecompute() }
}

/** Called only inside the mutation transaction. Clear generated descriptions before recomputing. */
internal suspend fun JunshiDao.invalidateAndRecompute() {
    val revision = (metadata()?.styleRevision ?: 0) + 1
    put(MetadataRow(styleRevision = revision))
    clearProfiles()
    val consent = currentConsent()
    if (!consent.enabled || consent.paused) return
    RelationGroup.entries.forEach { group ->
        val sources = samples(group.name).map { decode<StyleSample>(it.payload) }
        if (sources.isNotEmpty()) {
            val profile = StyleProfile(id = group.name, relationGroup = group,
                features = StyleFeatureExtractor.extract(sources.map { it.text }), description = "",
                sourceRevision = revision, consentVersion = consent.consentVersion, updatedAt = System.currentTimeMillis())
            put(StyleProfileRow(group.name, revision, consent.consentVersion, encode(profile)))
        }
    }
}
