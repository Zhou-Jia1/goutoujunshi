package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

/** One SQLCipher transaction replaces only the explicitly confirmed captured source. */
class CapturedImportReplacementRepository(private val session:DataSession) {
    suspend fun replace(change:CapturedImportReplacement,current:()->Boolean = {true}):Contact = session.transaction {
        check(current()) { "Capture correction authority changed" }
        val next=change.replacement
        val ownerId=requireNotNull(next.contactId)
        val owner=requireContact(ownerId)
        val proof=requireNotNull(change.provenance) {"Only a witnessed live capture can be replaced"}
        require(proof.contactId==ownerId && next.id.startsWith(LiveCaptureFingerprint.PREFIX))
        val storedProof=requireNotNull(captureProjections(ownerId).singleOrNull {it.lineageId==proof.lineageId})
        if(!storedProof.completeFrameHistory() || conflictingCaptureClaims(captureProjections(ownerId)).isNotEmpty()) throw CaptureHistoryReviewRequired()
        require(next.contentHash==LiveCaptureFingerprint.stored(proof.lineageId,LiveCaptureFingerprint.normalized(change.lines)))
        require(next.sourceType==ImportSource.SCREENSHOT && next.id!=change.oldImportId && next.id.isNotBlank() && next.contentHash.isNotBlank())
        require(ownerOfImport(next.id).let { it==null || it==ownerId })
        val existing=imported(ownerId,next.id)
        if(existing!=null) {
            check(owner.revision==change.expectedSourceRevision+1 && imported(ownerId,change.oldImportId)==null)
            check(decode<ChatImport>(existing.payload)==next && lines(ownerId,next.id).map { decode<ChatLine>(it.payload) }==change.lines)
            check(storedProof.winner==next && storedProof.revision==proof.revision+1 && storedProof.operationId==next.id && !storedProof.requiresConfirmation)
            check(storedProof.frames.containsAll(reviewedFrameWitnesses(change.lines,change.reviewedFrames)))
            check(!effectiveCaptureSelection(ownerId).blocked)
            change.binding?.let { binding -> check(records(ownerId,OwnedRecordKind.FLOATING_IDENTITY.name).any { decode<FloatingIdentityBinding>(it.payload)==binding }) }
            check(current())
            return@transaction decode<Contact>(owner.payload)
        }
        check(owner.revision==change.expectedSourceRevision) { "Contact source changed" }
        check(storedProof==proof && proof.winner.id==change.oldImportId && proof.winner.contentHash==change.oldHash && !proof.requiresConfirmation)
        check(!effectiveCaptureSelection(ownerId).blocked) {"Capture source needs confirmation"}
        val old=decode<ChatImport>(requireNotNull(imported(ownerId,change.oldImportId)).payload)
        check(old.contentHash==change.oldHash && old.sourceType==ImportSource.SCREENSHOT)
        require(files(ownerId).none { it.importId==old.id }) { "A captured text source must not own image files" }
        require(change.lines.isNotEmpty() && change.lines.map { it.id }.distinct().size==change.lines.size)
        require(change.lines.all { it.contactId==ownerId && it.importId==next.id && it.id.isNotBlank() && it.speakerConfirmed && ownerOfLine(it.id)==null })
        check(imports(ownerId).none { it.id!=old.id && it.hash==next.contentHash }) { "Corrected source duplicates a different import" }
        val affectedRequests=records(ownerId,OwnedRecordKind.ANALYSIS_RUN.name).map { decode<AnalysisRun>(it.payload) }
            .filter { run -> run.requestSnapshot?.evidence?.any { it.id==old.id || it.lines.any { line -> line.importId==old.id } }==true }.map { it.requestId }.toSet()
        conversations(ownerId).forEach { conversation ->
            messages(ownerId,conversation.id).map { decode<Message>(it.payload) }.filter { it.requestId in affectedRequests }.forEach { deleteAnswerSamples(it.id) }
        }
        deleteImport(ownerId,old.id) // Cascades import/line sample FKs; new line IDs revoke memory proofs.
        put(ImportRow(next.id,ownerId,next.contentHash,encode(next)))
        change.lines.forEach { put(ChatLineRow(it.id,ownerId,next.id,encode(it))) }
        val witnessed=reviewedFrameWitnesses(change.lines,change.reviewedFrames)+CaptureFrameWitness(LiveCaptureFingerprint.normalized(change.lines),LiveCaptureFingerprint.semantic(change.lines))
        val history=proof.normalizedHistory+witnessed.map {it.normalizedHash}
        val aliases=proof.aliases+captureArchiveAliases(ownerId,history)
        val nextProof=proof.copy(winner=next,normalizedHash=LiveCaptureFingerprint.normalized(change.lines),semanticHash=LiveCaptureFingerprint.semantic(change.lines),
            revision=proof.revision+1,aliases=aliases,normalizedHistory=history,operationId=next.id,expectedSourceRevision=change.expectedSourceRevision,
            frames=proof.frames+witnessed)
        if(conflictingCaptureClaims(captureProjections(ownerId).filterNot {it.lineageId==proof.lineageId}+nextProof).isNotEmpty()) throw CaptureHistoryReviewRequired()
        putCaptureProvenance(nextProof)
        revokeCaptureDependents(ownerId,aliases.keys+old.id)
        val contact=decode<Contact>(owner.payload).copy(sourceRevision=owner.revision+1)
        put(ContactRow(ownerId,contact.sourceRevision,encode(contact)))
        change.binding?.let { binding ->
            require(binding.contactId==ownerId && binding.contactRevision==contact.sourceRevision && binding.revision==change.expectedBindingRevision+1)
            val previous=records(ownerId,OwnedRecordKind.FLOATING_IDENTITY.name).firstOrNull { it.id=="FLOATING_IDENTITY:${binding.id}" }?.let { decode<FloatingIdentityBinding>(it.payload) }
            check(previous?.revision==change.expectedBindingRevision && previous.contactRevision==owner.revision && previous.source==binding.source)
            put(OwnedRecordRow("FLOATING_IDENTITY:${binding.id}",ownerId,OwnedRecordKind.FLOATING_IDENTITY,encode(binding)))
        }
        invalidateAndRecompute(ownerId=ownerId) // Includes transitive memory pruning, undo revocation, summary/profile clearing.
        check(current()) { "Capture correction authority changed before commit" }
        contact
    }
}
