package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

class StyleRepository(private val session: DataSession) {

    /** Explicit style management receipt; never obtained through an unidentified memory read. */
    suspend fun snapshot():StyleManagementSnapshot = session.transaction {
        val consent=currentConsent()
        val stamp=WriteStamp(consent.consentVersion,metadata()?.styleRevision ?: 0,session.generation)
        StyleManagementSnapshot(consent,stamp,RelationGroup.entries.associateWith { group ->
            val qualified=qualifiedStyleSamples(group)
            StyleGroupSnapshot(group,qualified,profile(group.name)?.takeIf {qualified.size==samples(group.name).size}?.let {decode(it.payload)})
        },contacts().associate {row->row.id to decode<Contact>(row.payload).alias},RelationGroup.entries.associateWith {styleSourceTerms(it)})
    }
    suspend fun editDescription(group:RelationGroup,text:String,stamp:WriteStamp,generated:Boolean=false,current:()->Boolean={true}) = session.transaction {
        check(current())
        checkStamp(session,stamp)
        require(text.codePointCount(0,text.length)<=200)
        val qualified=qualifiedStyleSamples(group)
        require(qualified.isNotEmpty() && qualified.size==samples(group.name).size) {"Style sources need review"}
        val existing=requireNotNull(profile(group.name)).let {decode<StyleProfile>(it.payload)}
        invalidateAndRecompute()
        val revision=metadata()!!.styleRevision
        put(StyleProfileRow(group.name,revision,stamp.consentVersion,encode(existing.copy(description=text,sourceRevision=revision,sourceFingerprint=styleFingerprint(group),generatedFingerprint=if(generated) styleFingerprint(group) else existing.generatedFingerprint))))
        check(current())
    }
    suspend fun editSample(id:String,text:String,stamp:WriteStamp,current:()->Boolean={true}) = session.transaction {
        check(current());checkStamp(session,stamp)
        require(text.isNotBlank() && text.codePointCount(0,text.length)<=4000)
        val row=requireNotNull(sample(id))
        val original=decode<StyleSample>(row.payload)
        require(qualifiedStyleSamples(original.relationGroup).any {it.id==id}) {"Sample source is no longer eligible"}
        val edit=UserStyleSampleEdit(text,styleSampleProvenance(original),stamp.consentVersion,stamp.sourceRevision,System.currentTimeMillis())
        put(row.copy(payload=encode(original.copy(userEdit=edit))))
        invalidateAndRecompute();check(current())
    }
    suspend fun removeSample(id:String,stamp:WriteStamp) = session.transaction {
        checkStyleManagementStamp(session,stamp);requireNotNull(sample(id));deleteSample(id);invalidateAndRecompute()
    }
    suspend fun reset(group:RelationGroup,stamp:WriteStamp) = session.transaction {
        checkStyleManagementStamp(session,stamp);samples(group.name).forEach {deleteSample(it.id)};invalidateAndRecompute()
    }
    suspend fun saveQuiz(answers:List<String>,stamp:WriteStamp,current:()->Boolean={true}) = session.transaction {
        check(current());checkStamp(session,stamp)
        require(answers.size==5 && answers.all {it.isNotBlank() && it.length<=200})
        answers.forEachIndexed { index,text ->
            val sample=StyleSample("style:quiz:$index",RelationGroup.DEFAULT,text,StyleOrigin.QUIZ,consentVersion=stamp.consentVersion,sourceRevision=stamp.sourceRevision,createdAt=System.currentTimeMillis())
            put(StyleSampleRow(sample.id,RelationGroup.DEFAULT.name,null,null,null,null,stamp.sourceRevision,stamp.consentVersion,encode(sample)))
        }
        invalidateAndRecompute();check(current())
    }
    /** Owner is confirmed before this method is called; foreign raw samples never become facts. */
    suspend fun context(owner:String,group:RelationGroup):StyleOutboundContext = session.transaction {
        requireContact(owner)
        check(!effectiveCaptureSelection(owner).blocked)
        val consent=currentConsent()
        if(!consent.enabled || consent.paused) return@transaction StyleOutboundContext()
        val preferred=qualifiedStyleSamples(group)
        val preferredProfile=profile(group.name)?.takeIf {preferred.size==samples(group.name).size && it.sourceRevision==(metadata()?.styleRevision ?: 0) && it.consentVersion==consent.consentVersion}?.let {decode<StyleProfile>(it.payload)}
        val preferredOwned=preferred.filter {it.sourceContactId==owner || it.origin==StyleOrigin.QUIZ}
        // Availability means examples usable by this owner, or a verified group description.
        // Other owners' raw examples never satisfy the three-example threshold on their behalf.
        val selected=if(preferredOwned.size>=3 || preferredProfile?.description?.isNotBlank()==true) group else RelationGroup.DEFAULT
        val selectedSources=if(selected==group) preferred else qualifiedStyleSamples(selected)
        val profile=if(selected==group) preferredProfile else profile(selected.name)?.takeIf {selectedSources.size==samples(selected.name).size && it.sourceRevision==(metadata()?.styleRevision ?: 0) && it.consentVersion==consent.consentVersion}?.let {decode<StyleProfile>(it.payload)}
        val owned=StylePersistencePolicy.retainSamples(selectedSources.filter {it.sourceContactId==owner || it.origin==StyleOrigin.QUIZ})
        StyleOutboundContext(profile?.description.orEmpty(),if(owned.size>=3) owned.take(5) else emptyList(),metadata()?.styleRevision ?: 0,consent.consentVersion,
            styleSourceTerms(selected))
    }
    suspend fun samples(group: RelationGroup): List<StyleSample> = session.transaction {
        samples(group.name).map { decode(it.payload) }
    }
    suspend fun profile(group: RelationGroup): StyleProfile? = session.transaction {
        val consent = currentConsent()
        if (!consent.enabled || consent.paused) return@transaction null
        if(qualifiedStyleSamples(group).size!=samples(group.name).size) return@transaction null
        profile(group.name)?.takeIf {
            it.sourceRevision == (metadata()?.styleRevision ?: 0) && it.consentVersion == consent.consentVersion
        }?.let { decode(it.payload) }
    }
    suspend fun saveSample(sample: StyleSample, stamp: WriteStamp) = session.transaction {
        checkStamp(session, stamp, sample.sourceContactId)
        require(sample.consentVersion == stamp.consentVersion && sample.sourceRevision == stamp.sourceRevision)
        StylePersistencePolicy.validate(sample, currentConsent(), stamp.sourceRevision, false)
        require(sample.id.isNotBlank() && sample.userEdit==null) {"Use the explicit sample editor for user-authored overrides"}
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
                require(captureImportEligible(contact,source.importId)) {"Source is not eligible for analysis"}
                require(sample.sourceAnswerId == null)
            }
            StyleOrigin.EDITED_REPLY -> {
                val source = requireNotNull(message(requireNotNull(sample.sourceContactId), requireNotNull(sample.sourceAnswerId)))
                val message=decode<Message>(source.payload)
                require(message.role == MessageRole.ASSISTANT)
                val owner=requireNotNull(sample.sourceContactId)
                records(owner,OwnedRecordKind.ANALYSIS_RUN.name).map {decode<AnalysisRun>(it.payload)}.firstOrNull {it.requestId==message.requestId}?.requestSnapshot?.evidence?.forEach {b->require(captureImportEligible(owner,b.id))}
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
        if(sample.origin==StyleOrigin.IMPORT) samples(sample.relationGroup.name).filter { it.id!=sample.id && it.contactId==sample.sourceContactId && it.lineId==sample.sourceMessageId }.forEach { deleteSample(it.id) }
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
        val features = StyleFeatureExtractor.extract(sources.map { decode<StyleSample>(it.payload).effectiveText })
        put(StyleProfileRow(profile.relationGroup.name, stamp.sourceRevision, stamp.consentVersion, encode(profile.copy(features = features, sourceFingerprint=styleFingerprint(profile.relationGroup)))))
    }
    suspend fun deleteSample(id: String) = session.transaction { deleteSample(id); invalidateAndRecompute() }
}

/** Called only inside the mutation transaction. Clear generated descriptions before recomputing. */
internal suspend fun JunshiDao.invalidateAndRecompute(ownerId:String?=null) {
    contacts().forEach {owner->conversations(owner.id).forEach {row->
        val conversation=decode<Conversation>(row.payload)
        if(conversation.coolingDraft?.let {it.sourceRevision!=owner.revision}==true) put(row.copy(payload=encode(conversation.copy(coolingDraft=null))))
    }}
    pruneDerivedMemories(ownerId=ownerId)
    RelationGroup.entries.forEach {group ->
        val qualified=qualifiedStyleSamples(group).map {it.id}.toSet()
        samples(group.name).filterNot {it.id in qualified}.forEach {deleteSample(it.id)}
    }
    val revision = (metadata()?.styleRevision ?: 0) + 1
    put(MetadataRow(styleRevision = revision))
    val previous=RelationGroup.entries.associateWith { profile(it.name)?.let { row -> decode<StyleProfile>(row.payload) } }
    clearSummaries()
    val consent = currentConsent()
    if (!consent.enabled) {clearProfiles();return}
    if(consent.paused) {
        // Retain byte-identical, previously authorized content only. No new feature extraction,
        // no refreshed source fingerprint, no adoption of a model result during pause.
        RelationGroup.entries.forEach {group ->
            val old=previous[group]
            if(old!=null && (old.sourceFingerprint.isEmpty() || old.sourceFingerprint!=styleFingerprint(group))) deleteProfile(group.name)
        }
        return
    }
    clearProfiles()
    RelationGroup.entries.forEach { group ->
        val sources = samples(group.name).map { decode<StyleSample>(it.payload) }
        if (sources.isNotEmpty()) {
            val profile = StyleProfile(id = group.name, relationGroup = group,
                features = StyleFeatureExtractor.extract(sources.map { it.effectiveText }),
                description = previous[group]?.takeIf { it.sourceFingerprint.isNotEmpty() && it.sourceFingerprint==styleFingerprint(group) }?.description.orEmpty(),
                generatedFingerprint=previous[group]?.takeIf {it.sourceFingerprint==styleFingerprint(group)}?.generatedFingerprint.orEmpty(),
                sourceFingerprint=styleFingerprint(group), sourceRevision = revision, consentVersion = consent.consentVersion, updatedAt = System.currentTimeMillis())
            put(StyleProfileRow(group.name, revision, consent.consentVersion, encode(profile)))
        }
    }
}

/** A group fingerprint carries real sources and their aliases, independent of unrelated group writes. */
internal suspend fun JunshiDao.styleFingerprint(group:RelationGroup):String {
    val rows=samples(group.name).map { decode<StyleSample>(it.payload) }.sortedBy {it.id}
    val owners=rows.mapNotNull {it.sourceContactId}.distinct().sorted().map { id ->
        val owner=contact(id)?.let {decode<Contact>(it.payload)}
        listOf(id,owner?.alias,owner?.relationType?.name).joinToString("|")
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest((encode(rows)+encode(owners)).toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Invoked in the same source transaction, only after the new effective source is established.
 * A retry never re-learns a deliberately deleted sample. No consent means zero persistent writes. */
internal suspend fun JunshiDao.learnConfirmedStyle(owner:String,importId:String) {
    val consent=currentConsent()
    if(!consent.enabled || consent.paused || !captureImportEligible(owner,importId)) return
    val contact=decode<Contact>(requireContact(owner).payload)
    val group=contact.relationType.group()
    lines(owner,importId).map {decode<ChatLine>(it.payload)}.filter {it.speaker==Speaker.ME && it.speakerConfirmed && it.text.isNotBlank()}.forEach { line ->
        val id="style:import:${line.id}"
        require(sample(id)==null)
        val sample=StyleSample(id,group,line.text,StyleOrigin.IMPORT,owner,importId,line.id,
            consentVersion=consent.consentVersion,sourceRevision=metadata()?.styleRevision ?: 0,createdAt=System.currentTimeMillis())
        put(StyleSampleRow(id,group.name,owner,importId,line.id,null,sample.sourceRevision,consent.consentVersion,encode(sample)))
    }
    val rows=samples(group.name).map {decode<StyleSample>(it.payload)}
    val retained=StylePersistencePolicy.retainSamples(rows).map {it.id}.toSet()
    rows.filterNot {it.id in retained}.forEach {deleteSample(it.id)}
}

data class StyleGroupSnapshot(val group:RelationGroup,val samples:List<StyleSample>,val profile:StyleProfile?)
data class StyleManagementSnapshot(val consent:ConsentState,val stamp:WriteStamp,val groups:Map<RelationGroup,StyleGroupSnapshot>,val aliases:Map<String,String> = emptyMap(),val sourceTerms:Map<RelationGroup,List<String>> = emptyMap())

/** Include every selected source's real names and privacy rules, not just the current owner. */
internal suspend fun JunshiDao.styleSourceTerms(group:RelationGroup):List<String> {
    val samples=samples(group.name)
    val terms=mutableListOf<String>()
    samples.mapNotNull {it.contactId}.distinct().forEach {owner ->
        contact(owner)?.let {terms+=decode<Contact>(it.payload).alias}
        terms+=records(owner,OwnedRecordKind.NICKNAME.name).map {decode<NicknameMapping>(it.payload).original}
        records(owner,OwnedRecordKind.FLOATING_IDENTITY.name).forEach {row ->
            val binding=decode<FloatingIdentityBinding>(row.payload)
            terms+=binding.observedNickname;terms+=binding.source.account
        }
        records(owner,OwnedRecordKind.REDACTION.name).singleOrNull()?.let {
            val privacy=decode<RedactionProfile>(it.payload);terms+=privacy.terms;terms+=privacy.aliases.map {a->a.original}
        }
        samples.filter {it.contactId==owner}.mapNotNull {it.importId}.distinct().forEach {id ->
            terms+=lines(owner,id).mapNotNull {decode<ChatLine>(it.payload).speakerName}
        }
    }
    return terms.filter {it.isNotBlank()}.distinct().sorted()
}

/** Re-validate retained provenance on every projection; physical FK survival alone is insufficient. */
internal suspend fun JunshiDao.qualifiedStyleSamples(group:RelationGroup):List<StyleSample> {
    val result=mutableListOf<StyleSample>()
    for(row in samples(group.name)) {
        val sample=decode<StyleSample>(row.payload)
        if(sample.userEdit?.let {it.text.isBlank() || it.text.codePointCount(0,it.text.length)>4000 || it.provenanceFingerprint!=styleSampleProvenance(sample)}==true) continue
        if(sample.relationGroup!=group || sample.text.isBlank() || row.contactId!=sample.sourceContactId || row.importId!=sample.sourceImportId || row.lineId!=sample.sourceMessageId || row.answerId!=sample.sourceAnswerId) continue
        val owner=sample.sourceContactId
        if(owner!=null && (contact(owner)?.let {decode<Contact>(it.payload).relationType.group()}!=group || effectiveCaptureSelection(owner).blocked)) continue
        val eligible=when(sample.origin) {
            StyleOrigin.QUIZ->group==RelationGroup.DEFAULT && owner==null && sample.sourceImportId==null && sample.sourceMessageId==null && sample.sourceAnswerId==null
            StyleOrigin.IMPORT->if(owner==null || sample.sourceMessageId==null || sample.sourceImportId==null) false else {
                val actual=line(owner,requireNotNull(sample.sourceMessageId))?.let {decode<ChatLine>(it.payload)}
                actual?.speaker==Speaker.ME && actual.speakerConfirmed && actual.text==sample.text && actual.importId==sample.sourceImportId && captureImportEligible(owner,requireNotNull(sample.sourceImportId))
            }
            StyleOrigin.EDITED_REPLY->if(owner==null || sample.sourceAnswerId==null) false else {
                val answer=message(owner,requireNotNull(sample.sourceAnswerId))?.let {decode<Message>(it.payload)}
                answer?.role==MessageRole.ASSISTANT && records(owner,OwnedRecordKind.ANALYSIS_RUN.name).map {decode<AnalysisRun>(it.payload)}.firstOrNull {it.requestId==answer.requestId}?.requestSnapshot?.evidence?.all {captureImportEligible(owner,it.id)}!=false
            }
        }
        if(eligible) result+=sample
    }
    return result
}

internal fun styleSampleProvenance(sample:StyleSample):String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(encode(sample.copy(userEdit=null)).toByteArray()).joinToString("") {"%02x".format(it)}

/** Destructive management remains available while paused; it never authorizes new learning. */
internal suspend fun JunshiDao.checkStyleManagementStamp(session:DataSession,stamp:WriteStamp) {
    session.checkGeneration(stamp)
    check(currentConsent().consentVersion==stamp.consentVersion && (metadata()?.styleRevision ?: 0)==stamp.sourceRevision)
}
