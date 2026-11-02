package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

/** Archived imports/files stay immutable. A live source has its own namespace and owned witness. */
class LiveCaptureRepository(private val session:DataSession) {
    suspend fun save(imported:ChatImport,lines:List<ChatLine>,expectedSourceRevision:Long,current:()->Boolean={true}) =
        saveReviewed(imported,lines,expectedSourceRevision,listOf(lines),current)
    suspend fun saveReviewed(imported:ChatImport,lines:List<ChatLine>,expectedSourceRevision:Long,reviewedFrames:List<List<ChatLine>>,current:()->Boolean={true}):LiveCaptureReceipt = session.transaction {
        check(current());val owner=requireNotNull(imported.contactId);val contact=requireContact(owner)
        require(imported.sourceType==ImportSource.SCREENSHOT && lines.isNotEmpty() && lines.all {it.contactId==owner && it.speakerConfirmed})
        val normalized=LiveCaptureFingerprint.normalized(lines);val semantic=LiveCaptureFingerprint.semantic(lines)
        val witnessed=reviewedFrameWitnesses(lines,reviewedFrames)+CaptureFrameWitness(normalized,semantic)
        val projections=captureProjections(owner)
        if(projections.any {!it.completeFrameHistory()} || conflictingCaptureClaims(projections).isNotEmpty()) throw CaptureHistoryReviewRequired()
        val matching=projections.filter {p->p.frames.any {it.semanticHash==semantic}}
        check(matching.all {it.contactId==owner}) {"Capture lineage owner changed"}
        if(matching.size>1) throw CaptureHistoryReviewRequired()
        val before=matching.singleOrNull()
        // A confirmed historical frame cannot replace the larger, later reviewed record.
        // Neither discard its earlier rows nor guess how the new side applies to them.
        if(before!=null && before.semanticHash!=semantic) throw CaptureHistoryReviewRequired()
        val retry=before?.takeIf {it.operationId==imported.id && it.expectedSourceRevision==expectedSourceRevision && contact.revision==expectedSourceRevision+1 && it.normalizedHash==normalized && it.frames.containsAll(witnessed) && !it.requiresConfirmation}
        check(contact.revision==expectedSourceRevision || retry!=null) {"Contact source changed"}
        if(retry!=null) {
            check(effectiveCaptureSelection(owner).imports.any {it==retry.winner});check(current())
            return@transaction LiveCaptureReceipt(retry.winner,retry,true)
        }
        val lineage=before?.lineageId ?: imported.id
        val history=before?.normalizedHistory.orEmpty()+witnessed.map {it.normalizedHash}
        val previousAliases=before?.aliases.orEmpty().filter {(_,w)->
            val actual=imported(owner,w.id)?.let {decode<ChatImport>(it.payload)}
            if(actual==null) {check(before?.requiresConfirmation==true);false}
            else {check(actual.contentHash==w.contentHash && actual.sourceType==w.source && LiveCaptureFingerprint.normalized(lines(owner,w.id).map {decode<ChatLine>(it.payload)})==w.normalizedHash);true}
        }
        val aliases=previousAliases+captureArchiveAliases(owner,history)
        if(before!=null) {
            val stored=imported(owner,before.winner.id)?.let {decode<ChatImport>(it.payload)}
            check((stored==null && before.requiresConfirmation) || (stored==before.winner && stored.sourceType==ImportSource.SCREENSHOT && files(owner).none {it.importId==stored.id})) {"Live source needs confirmation"}
            if(stored!=null) check(LiveCaptureFingerprint.normalized(lines(owner,stored.id).map {decode<ChatLine>(it.payload)})==before.normalizedHash)
            if(!before.requiresConfirmation && before.normalizedHash==normalized && aliases==before.aliases && before.frames.containsAll(witnessed)) {
                check(current());return@transaction LiveCaptureReceipt(requireNotNull(stored),before,true)
            }
        }
        val id=LiveCaptureFingerprint.id(imported.id)
        require(ownerOfImport(id).let {it==null || (before?.winner?.id==id && it==owner)})
        val hash=LiveCaptureFingerprint.stored(lineage,normalized)
        check(imports(owner).none {it.id!=before?.winner?.id && it.hash==hash})
        val winner=imported.copy(id=id,contentHash=hash)
        val nextLines=lines.sortedBy {it.seq}.mapIndexed {index,line->line.copy(id="$id:$index",importId=id,seq=index)}
        nextLines.forEach {line->require(ownerOfLine(line.id).let {it==null || before?.winner?.id==id})}
        val affected=aliases.keys+listOfNotNull(before?.winner?.id)
        before?.let {deleteImport(owner,it.winner.id)}
        put(ImportRow(id,owner,hash,encode(winner)));nextLines.forEach {put(ChatLineRow(it.id,owner,id,encode(it)))}
        val provenance=LiveCaptureProvenance(lineage,owner,winner,normalized,semantic,(before?.revision ?: 0)+1,aliases,history,false,imported.id,expectedSourceRevision,
            LiveCaptureFingerprint.FRAME_HISTORY_VERSION,before?.frames.orEmpty()+witnessed)
        if(conflictingCaptureClaims(projections.filterNot {it.lineageId==lineage}+provenance).isNotEmpty()) throw CaptureHistoryReviewRequired()
        putCaptureProvenance(provenance)
        val nextContact=decode<Contact>(contact.payload).copy(sourceRevision=contact.revision+1)
        put(ContactRow(owner,nextContact.sourceRevision,encode(nextContact)))
        revokeCaptureDependents(owner,affected)
        invalidateAndRecompute(ownerId=owner)
        check(current());LiveCaptureReceipt(winner,provenance,false)
    }
    suspend fun selection(owner:String):EffectiveImportSelection = session.transaction {requireContact(owner);effectiveCaptureSelection(owner)}
    suspend fun evidence(owner:String):EffectiveCaptureEvidence = session.transaction {
        requireContact(owner);val selected=effectiveCaptureSelection(owner)
        EffectiveCaptureEvidence(selected.imports.map {EvidenceBlock(it.id,it.sourceType.name,lines(owner,it.id).map {row->decode<ChatLine>(row.payload)})},selected.blocked,selected.preferred)
    }
    suspend fun provenance(owner:String,lineage:String):LiveCaptureProvenance? = session.transaction {requireContact(owner);captureProjections(owner).singleOrNull {it.lineageId==lineage}}
}

/** Each physical frame member names its retained reviewed evidence row, including exact dedup counterparts. */
internal fun reviewedFrameWitnesses(lines:List<ChatLine>,frames:List<List<ChatLine>>):Set<CaptureFrameWitness> {
    val byId=lines.associateBy {it.id}
    if(frames.size>20 || frames.sumOf {it.size}>512) throw CaptureHistoryReviewRequired()
    return frames.filter {it.isNotEmpty()}.map {frame->
        if(frame.map {it.id}.distinct().size!=frame.size || frame.any {row->
            val kept=byId[row.id]
            kept==null || !row.speakerConfirmed || row.contactId!=kept.contactId || row.importId!=kept.importId || row.speaker!=kept.speaker || row.sentAt!=kept.sentAt ||
                !com.shengjidaguai.goutoujunshi.domain.ocr.OcrLayout.sameMessage(row.text,null,row.speakerName,kept.text,null,kept.speakerName)
        }) throw CaptureHistoryReviewRequired()
        CaptureFrameWitness(LiveCaptureFingerprint.normalized(frame),LiveCaptureFingerprint.semantic(frame))
    }.toSet()
}

internal suspend fun JunshiDao.captureProjections(owner:String)=records(owner,OwnedRecordKind.CAPTURE_PROJECTION.name).map {decode<LiveCaptureProvenance>(it.payload)}
internal fun LiveCaptureProvenance.completeFrameHistory():Boolean = historyVersion==LiveCaptureFingerprint.FRAME_HISTORY_VERSION &&
    frames.isNotEmpty() && frames.all {it.normalizedHash.isNotBlank() && it.semanticHash.isNotBlank()} &&
    frames.map {it.normalizedHash}.toSet()==normalizedHistory && CaptureFrameWitness(normalizedHash,semanticHash) in frames &&
    aliases.values.all {it.normalizedHash in normalizedHistory}
/** Independent guards: a shared archive claim is invalid even if current frame semantics differ. */
internal fun conflictingCaptureClaims(projections:List<LiveCaptureProvenance>):Set<String> {
    fun <T> shared(claims:List<Pair<T,String>>) = claims.groupBy {it.first}.values
        .map {rows->rows.map {it.second}.toSet()}.filter {it.size>1}.flatten().toSet()
    return shared(projections.flatMap {p->p.aliases.values.map {it to p.lineageId}})+
        shared(projections.flatMap {p->p.frames.map {it.semanticHash to p.lineageId}})+
        shared(projections.map {it.semanticHash to it.lineageId})
}
internal suspend fun JunshiDao.putCaptureProvenance(value:LiveCaptureProvenance) {
    require(value.winner.contactId==value.contactId)
    val id="CAPTURE_PROJECTION:${value.lineageId}"
    require(ownerOfRecord(id).let {it==null || it==value.contactId}) {"Capture lineage belongs to another owner"}
    put(OwnedRecordRow(id,value.contactId,OwnedRecordKind.CAPTURE_PROJECTION,encode(value)))
}
internal suspend fun JunshiDao.captureArchiveAliases(owner:String,normalized:Set<String>):Map<String,CaptureAliasWitness> = imports(owner)
    .filter { !it.id.startsWith(LiveCaptureFingerprint.PREFIX) && it.hash in normalized }.mapNotNull {row->
        val source=decode<ChatImport>(row.payload);val actual=LiveCaptureFingerprint.normalized(lines(owner,row.id).map {decode<ChatLine>(it.payload)})
        if(source.contactId==owner && source.contentHash==row.hash && actual==row.hash) row.id to CaptureAliasWitness(row.id,row.hash,source.sourceType,actual) else null
    }.toMap()

/** Single owner-scoped selection used by analysis and source eligibility. A broken witness fails closed. */
internal suspend fun JunshiDao.effectiveCaptureSelection(owner:String):EffectiveImportSelection {
    val all=imports(owner).map {decode<ChatImport>(it.payload)};val byId=all.associateBy {it.id}
    val projections=captureProjections(owner);val conflicts=conflictingCaptureClaims(projections)
    val excluded=mutableSetOf<String>();var blocked=conflicts.isNotEmpty()
    val fileIds=if(projections.isEmpty()) emptySet() else files(owner).mapNotNull {it.importId}.toSet()
    projections.forEach {p->
        var valid=p.contactId==owner && !p.requiresConfirmation && p.completeFrameHistory() && p.lineageId !in conflicts
        p.aliases.values.forEach {w->
            val actual=byId[w.id]
            val matches=actual?.contactId==owner && actual.contentHash==w.contentHash && actual.sourceType==w.source &&
                LiveCaptureFingerprint.normalized(lines(owner,w.id).map {decode<ChatLine>(it.payload)})==w.normalizedHash
            if(matches) excluded+=w.id else valid=false
        }
        val winner=byId[p.winner.id]
        valid=valid && winner==p.winner && winner?.sourceType==ImportSource.SCREENSHOT && winner.id !in fileIds &&
            winner.contentHash==LiveCaptureFingerprint.stored(p.lineageId,p.normalizedHash) &&
            LiveCaptureFingerprint.normalized(lines(owner,p.winner.id).map {decode<ChatLine>(it.payload)})==p.normalizedHash &&
            LiveCaptureFingerprint.semantic(lines(owner,p.winner.id).map {decode<ChatLine>(it.payload)})==p.semanticHash
        if(!valid) {blocked=true;excluded+=p.winner.id}
    }
    val known=projections.map {it.winner.id}.toSet()
    all.filter {it.id.startsWith(LiveCaptureFingerprint.PREFIX) && it.id !in known}.forEach {excluded+=it.id;blocked=true}
    return EffectiveImportSelection(all.filterNot {it.id in excluded},blocked,!blocked && projections.any {it.aliases.isNotEmpty()})
}
internal suspend fun JunshiDao.captureImportEligible(owner:String,id:String):Boolean = effectiveCaptureSelection(owner).let {selection-> !selection.blocked && selection.imports.any {it.id==id}}
internal suspend fun JunshiDao.invalidateCaptureWitness(owner:String,id:String) {
    captureProjections(owner).filter {it.winner.id==id || id in it.aliases}.forEach {p->putCaptureProvenance(p.copy(requiresConfirmation=true,revision=p.revision+1))}
}
internal suspend fun JunshiDao.revokeCaptureDependents(owner:String,imports:Set<String>) {
    val affected=records(owner,OwnedRecordKind.ANALYSIS_RUN.name).map {decode<AnalysisRun>(it.payload)}
        .filter {it.requestSnapshot?.evidence?.any {b->b.id in imports || b.lines.any {line->line.importId in imports}}==true}.map {it.requestId}.toSet()
    val answers=conversations(owner).flatMap {messages(owner,it.id)}.map {decode<Message>(it.payload)}.filter {it.requestId in affected}.map {it.id}.toSet()
    samplesForContact(owner).filter {it.importId in imports || it.answerId in answers}.forEach {deleteSample(it.id)}
    clearOperations(owner) // Old undo payloads must not revive a shadowed source, even if no derived row is currently active.
}
