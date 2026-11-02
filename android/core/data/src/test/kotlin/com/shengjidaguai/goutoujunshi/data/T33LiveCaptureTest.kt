package com.shengjidaguai.goutoujunshi.data

import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class T33LiveCaptureTest {
    private lateinit var db:JunshiDatabase
    private lateinit var session:DataSession
    private lateinit var root:File
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).build();session=DataSession(db,1)
        root=File(System.getProperty("java.io.tmpdir"),"t33-live-${java.util.UUID.randomUUID()}").apply {mkdirs()}
    }
    @After fun close() {db.close();root.deleteRecursively()}
    private fun lines(id:String,owner:String,text:String="合成原话",speaker:Speaker=Speaker.ME)=listOf(ChatLine("$id:0",id,0,speaker,text,contactId=owner,speakerConfirmed=true))
    private suspend fun databaseRows():Map<String,List<List<String?>>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sql=db.openHelper.readableDatabase
        val names=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use {cursor->buildList {while(cursor.moveToNext()) add(cursor.getString(0))}}
        names.associateWith {name->sql.query("SELECT * FROM \"${name.replace("\"","\"\"")}\"").use {cursor->
            buildList {while(cursor.moveToNext()) add((0 until cursor.columnCount).map {if(cursor.isNull(it)) null else cursor.getString(it)})}.sortedBy {it.toString()}
        }}
    }
    @Test fun crossSourceDuplicatesPreserveArchivesAndCreateAnIndependentReplaceableLiveSource()=runBlocking {
        for((index,source) in listOf(ImportSource.PASTE,ImportSource.FILE,ImportSource.SHARE,ImportSource.SCREENSHOT).withIndex()) {
            val owner="a$index";ContactRepository(session).save(Contact(owner,"合成甲"))
            val rawLines=lines("archive-$index",owner)
            val raw=ChatImport("archive-$index",owner,source,LiveCaptureFingerprint.normalized(rawLines),10)
            ImportRepository(session).save(raw,rawLines)
            val file=File(root,"source-$index.bin").apply {writeBytes(byteArrayOf(1,2,3,4))}
            if(source==ImportSource.SCREENSHOT || source==ImportSource.FILE) FileCleanupRepository(session).register(owner,raw.id,file,root)
            val input=ChatImport("fresh-$index",owner,ImportSource.SCREENSHOT,raw.contentHash,20)
            val live=LiveCaptureRepository(session).save(input,lines(input.id,owner),1)
            assertNotEquals("A duplicate archive is not a replaceable live capture",raw.id,live.imported.id)
            assertEquals(ImportSource.SCREENSHOT,live.imported.sourceType)
            assertEquals(listOf(raw.id),live.provenance.aliases.keys.toList())
            assertEquals(raw,ImportRepository(session).list(owner).single {it.id==raw.id})
            assertEquals(rawLines,ImportRepository(session).lines(owner,raw.id))
            assertArrayEquals(byteArrayOf(1,2,3,4),file.readBytes())
            assertEquals(listOf(live.imported.id),LiveCaptureRepository(session).selection(owner).imports.map {it.id})
            assertFalse(LiveCaptureRepository(session).selection(owner).blocked)
        }
    }
    @Test fun repeatedLiveConfirmationReturnsActualLiveProvenanceAndKeepsOneEffectiveSource()=runBlocking {
        ContactRepository(session).save(Contact("a","合成甲"))
        val first=ChatImport("capture-one","a",ImportSource.SCREENSHOT,LiveCaptureFingerprint.normalized(lines("capture-one","a")),20)
        val one=LiveCaptureRepository(session).save(first,lines(first.id,"a"),0)
        val second=first.copy(id="capture-two",importedAt=30)
        val two=LiveCaptureRepository(session).save(second,lines(second.id,"a"),1)
        assertEquals(one.imported,two.imported);assertEquals(one.provenance.lineageId,two.provenance.lineageId)
        assertTrue(two.duplicate)
        assertTrue(one.imported.id.startsWith(LiveCaptureFingerprint.PREFIX))
        assertEquals(1,LiveCaptureRepository(session).selection("a").imports.size)
    }
    @Test fun distinctSemanticLineagesCannotIndependentlyClaimOneExactArchiveWitness()=runBlocking {
        ContactRepository(session).save(Contact("a","合成甲"));ContactRepository(session).save(Contact("b","合成乙"))
        val original=lines("claimed-archive","a")
        val archive=ChatImport("claimed-archive","a",ImportSource.PASTE,LiveCaptureFingerprint.normalized(original))
        ImportRepository(session).save(archive,original)
        val input=ChatImport("claim-first","a",ImportSource.SCREENSHOT,archive.contentHash)
        val first=LiveCaptureRepository(session).save(input,lines(input.id,"a"),1)
        val otherLines=lines(LiveCaptureFingerprint.id("claim-second"),"a","另一份完整记录")
        val normalized=LiveCaptureFingerprint.normalized(otherLines)
        val second=first.provenance.copy(lineageId="claim-second",winner=input.copy(id=otherLines.single().importId,contentHash=LiveCaptureFingerprint.stored("claim-second",normalized)),
            normalizedHash=normalized,semanticHash=LiveCaptureFingerprint.semantic(otherLines),normalizedHistory=first.provenance.normalizedHistory+normalized,
            frames=first.provenance.frames+CaptureFrameWitness(normalized,LiveCaptureFingerprint.semantic(otherLines)))
        session.transaction {
            put(ImportRow(second.winner.id,"a",second.winner.contentHash,encode(second.winner)))
            otherLines.forEach {put(ChatLineRow(it.id,"a",second.winner.id,encode(it)))}
            putCaptureProvenance(second)
        }
        val reopened=DataSession(db,2)
        assertTrue("Exact shared archive claims must fail closed even when current semantic hashes differ",LiveCaptureRepository(reopened).selection("a").blocked)
        assertTrue(runCatching {MemoryRepository(reopened).contextSnapshot("a")}.isFailure)
        assertEquals(archive,ImportRepository(reopened).list("a").single {it.id==archive.id})
        assertNotNull(ContactRepository(reopened).get("b"))
    }
    @Test fun persistedProjectionWithoutCompleteFrameHistoryIsBlockedAcrossSessionReopen()=runBlocking {
        ContactRepository(session).save(Contact("a","合成甲"))
        val input=ChatImport("legacy-history","a",ImportSource.SCREENSHOT,"input")
        val live=LiveCaptureRepository(session).save(input,lines(input.id,"a"),0)
        val encoded=kotlinx.serialization.json.Json.parseToJsonElement(encode(live.provenance)) as kotlinx.serialization.json.JsonObject
        val legacy=kotlinx.serialization.json.JsonObject(encoded.filterKeys {it !in setOf("historyVersion","frames")}).toString()
        session.transaction {put(OwnedRecordRow("CAPTURE_PROJECTION:${live.provenance.lineageId}","a",OwnedRecordKind.CAPTURE_PROJECTION,legacy))}
        val reopened=DataSession(db,2)
        assertTrue("Missing historical frame witnesses cannot silently restore a winner",LiveCaptureRepository(reopened).selection("a").blocked)
        assertTrue(runCatching {MemoryRepository(reopened).contextSnapshot("a")}.isFailure)
        assertEquals(listOf(live.imported),ImportRepository(reopened).list("a"))
        val retry=runCatching {LiveCaptureRepository(reopened).save(input.copy(id="not-a-history-upgrade"),lines("not-a-history-upgrade","a"),ContactRepository(reopened).get("a")!!.sourceRevision)}
        assertTrue(retry.exceptionOrNull() is CaptureHistoryReviewRequired)
        assertEquals(legacy,reopened.transaction {records("a",OwnedRecordKind.CAPTURE_PROJECTION.name).single().payload})
    }
    @Test fun saveAndMoreCannotCommitANewFrameClaimAlreadyOwnedByAnotherLineage()=runBlocking {
        ContactRepository(session).save(Contact("a","合成甲"))
        fun rows(id:String,vararg texts:String)=texts.mapIndexed {index,text->ChatLine("$id:$index",id,index,Speaker.ME,text,contactId="a",speakerConfirmed=true)}
        val initialRows=rows("preflight-a","重叠原话","末尾原话")
        val first=LiveCaptureRepository(session).save(ChatImport("preflight-a","a",ImportSource.SCREENSHOT,"input"),initialRows,0)
        val otherRows=rows("preflight-e","较早原话","重叠原话")
        LiveCaptureRepository(session).save(ChatImport("preflight-e","a",ImportSource.SCREENSHOT,"input"),otherRows,1)
        val baseline=databaseRows();val before=LiveCaptureRepository(session).selection("a")
        assertFalse(before.blocked);assertEquals(2,before.imports.size)
        val ordinaryRows=rows("preflight-new","较早原话","重叠原话","末尾原话")
        val ordinary=runCatching {LiveCaptureRepository(session).saveReviewed(ChatImport("preflight-new","a",ImportSource.SCREENSHOT,"input"),ordinaryRows,2,listOf(ordinaryRows.take(2)))}
        assertTrue(ordinary.exceptionOrNull() is CaptureHistoryReviewRequired);assertEquals(baseline,databaseRows())
        val id=LiveCaptureFingerprint.id("preflight-more");val merged=rows(id,"较早原话","重叠原话","末尾原话")
        val change=CapturedImportReplacement(first.imported.id,first.imported.contentHash,2,
            ChatImport(id,"a",ImportSource.SCREENSHOT,LiveCaptureFingerprint.stored(first.provenance.lineageId,LiveCaptureFingerprint.normalized(merged))),merged,
            provenance=first.provenance,reviewedFrames=listOf(merged.take(2)))
        val more=runCatching {CapturedImportReplacementRepository(session).replace(change)}
        assertTrue(more.exceptionOrNull() is CaptureHistoryReviewRequired)
        assertEquals(baseline,databaseRows());assertEquals(before,LiveCaptureRepository(session).selection("a"))
    }
    private suspend fun replace(owner:String,before:LiveCaptureProvenance,operation:String,body:List<ChatLine>):LiveCaptureProvenance {
        val id=LiveCaptureFingerprint.id(operation)
        val rows=body.mapIndexed {i,line->line.copy(id="$id:$i",importId=id,seq=i)}
        val next=ChatImport(id,owner,ImportSource.SCREENSHOT,LiveCaptureFingerprint.stored(before.lineageId,LiveCaptureFingerprint.normalized(rows)),40)
        val change=CapturedImportReplacement(before.winner.id,before.winner.contentHash,ContactRepository(session).get(owner)!!.sourceRevision,next,rows,provenance=before)
        val repo=CapturedImportReplacementRepository(session)
        repo.replace(change);repo.replace(change) // Simulate committed result / lost acknowledgement.
        val selected=LiveCaptureRepository(session).selection(owner)
        assertFalse("A valid replacement cannot leave a dangling old winner",selected.blocked)
        assertTrue(selected.imports.any {it==next})
        val result=LiveCaptureRepository(session).provenance(owner,before.lineageId)!!
        assertEquals(next,result.winner);assertEquals(before.revision+1,result.revision)
        assertEquals(before.frames+CaptureFrameWitness(LiveCaptureFingerprint.normalized(rows),LiveCaptureFingerprint.semantic(rows)),result.frames)
        assertEquals(LiveCaptureFingerprint.FRAME_HISTORY_VERSION,result.historyVersion)
        return result
    }
    @Test fun exactFrameHistorySurvivesMoreSideAcknowledgementsAndColdReopenWithoutArchiveAliases()=runBlocking {
        ContactRepository(session).save(Contact("a","合成甲"))
        val input=ChatImport("history-first","a",ImportSource.SCREENSHOT,"input")
        val initial=LiveCaptureRepository(session).save(input,lines(input.id,"a"),0)
        val merged=replace("a",initial.provenance,"history-more",listOf(lines("ignored","a","较早原话").single(),lines("ignored","a").single().copy(seq=1)))
        val corrected=replace("a",merged,"history-side",ImportRepository(session).lines("a",merged.winner.id).map {it.copy(speaker=Speaker.THEM)})
        assertTrue(corrected.aliases.isEmpty());assertEquals(3,corrected.frames.size)
        val reopened=DataSession(db,2);val repo=LiveCaptureRepository(reopened)
        assertEquals(corrected,repo.provenance("a",corrected.lineageId))
        val revision=ContactRepository(reopened).get("a")!!.sourceRevision
        val recordsBefore=reopened.transaction {records("a",OwnedRecordKind.CAPTURE_PROJECTION.name)}
        val importsBefore=ImportRepository(reopened).list("a");val linesBefore=ImportRepository(reopened).lines("a",corrected.winner.id)
        for(speaker in listOf(Speaker.ME,Speaker.THEM)) {
            val result=runCatching {repo.save(input.copy(id="partial-$speaker"),lines("partial-$speaker","a",speaker=speaker),revision)}
            assertTrue("Both newly confirmed directions must reject an exact historical partial frame",result.exceptionOrNull() is CaptureHistoryReviewRequired)
            assertEquals(revision,ContactRepository(reopened).get("a")!!.sourceRevision)
            assertEquals(recordsBefore,reopened.transaction {records("a",OwnedRecordKind.CAPTURE_PROJECTION.name)})
            assertEquals(importsBefore,ImportRepository(reopened).list("a"));assertEquals(linesBefore,ImportRepository(reopened).lines("a",corrected.winner.id))
            assertFalse(repo.selection("a").blocked)
        }
    }
    @Test fun sharedHistoricalSemanticWitnessBlocksDistinctCurrentWinnersWithoutAnyArchiveClaim()=runBlocking {
        ConsentRepository(session).update(true)
        ContactRepository(session).save(Contact("a","合成甲"));ContactRepository(session).save(Contact("b","合成乙"))
        val input=ChatImport("semantic-first","a",ImportSource.SCREENSHOT,"input")
        val first=LiveCaptureRepository(session).save(input,lines(input.id,"a"),0)
        val merged=replace("a",first.provenance,"semantic-more",listOf(lines("x","a","较早原话").single(),lines("x","a").single().copy(seq=1)))
        val memory=MemoryRepository(session)
        for(owner in listOf("a","b")) memory.apply(owner,MemoryEntry("user-$owner",owner,scope=MemoryScope.USER,subjectId=owner,field="fact",value="独立手工事实",sourceType=MemorySource.USER_EXPLICIT),session.stamp(owner))
        val conversation=Conversation("graph-conversation","a",false);ConversationRepository(session).save(conversation)
        val sourceLines=ImportRepository(session).lines("a",merged.winner.id)
        val request=AnalysisRequest(requestId="graph-run",contactId="a",conversationId=conversation.id,sourceRevision=ContactRepository(session).get("a")!!.sourceRevision,
            evidence=listOf(EvidenceBlock(merged.winner.id,"SCREENSHOT",sourceLines)),speakersConfirmed=true)
        ConversationRepository(session).saveMessage(Message("graph-user",conversation.id,MessageRole.USER,request.userText,requestId=request.requestId,status=MessageStatus.COMPLETED,contactId="a"))
        ConversationRepository(session).saveMessage(Message("graph-answer",conversation.id,MessageRole.ASSISTANT,"旧来源建议",requestId=request.requestId,status=MessageStatus.COMPLETED,
            contactId="a",providerKind=ProviderKind.BYOK,answerJson=encode(StructuredAnswer(id="graph-answer",reply=listOf("合成回复")))))
        TimelineRepository(session).save(AnalysisRun(requestId=request.requestId,conversationId=conversation.id,contactId="a",status=RunStatus.COMPLETED,requestSnapshot=request))
        val proposal=MemoryProposal(subjectId="ta",field="pattern",value="来源派生推断",sourceRef=request.requestId)
        memory.applyProposal("a",proposal,session.stamp("a"))
        val group=ContactRepository(session).get("a")!!.relationType.group();val style=StyleRepository(session)
        val importStamp=session.stamp("a")
        val importSample=StyleSample("graph-import",relationGroup=group,text=sourceLines.first().text,origin=StyleOrigin.IMPORT,sourceContactId="a",sourceImportId=merged.winner.id,
            sourceMessageId=sourceLines.first().id,sourceRevision=importStamp.sourceRevision,consentVersion=importStamp.consentVersion)
        style.saveSample(importSample,importStamp)
        val editedStamp=session.stamp("a")
        val editedSample=importSample.copy(id="graph-edited",text="旧方向编辑建议保留边界",origin=StyleOrigin.EDITED_REPLY,sourceImportId=null,sourceMessageId=null,sourceAnswerId="graph-answer",
            sourceRevision=editedStamp.sourceRevision,consentVersion=editedStamp.consentVersion)
        style.saveSample(editedSample,editedStamp)
        val summary=TimelineSummary("graph-summary",request.copy(purpose=OutboundPurpose.LONG_RECORD_SUMMARY,networkSelection=sourceLines.map {it.id}),"[${sourceLines.first().id}] 合成摘要")
        SummaryRepository(session).save(summary)
        val otherRows=lines(LiveCaptureFingerprint.id("semantic-other"),"a","不同当前完整记录")
        val normalized=LiveCaptureFingerprint.normalized(otherRows);val semantic=LiveCaptureFingerprint.semantic(otherRows)
        val second=first.provenance.copy(lineageId="semantic-other",winner=input.copy(id=otherRows.single().importId,contentHash=LiveCaptureFingerprint.stored("semantic-other",normalized)),
            normalizedHash=normalized,semanticHash=semantic,normalizedHistory=first.provenance.normalizedHistory+normalized,frames=first.provenance.frames+CaptureFrameWitness(normalized,semantic))
        assertTrue(merged.aliases.isEmpty());assertTrue(second.aliases.isEmpty());assertNotEquals(merged.semanticHash,second.semanticHash)
        session.transaction {
            put(ImportRow(second.winner.id,"a",second.winner.contentHash,encode(second.winner)))
            otherRows.forEach {put(ChatLineRow(it.id,"a",second.winner.id,encode(it)))};putCaptureProvenance(second)
        }
        val reopened=DataSession(db,2);val selected=LiveCaptureRepository(reopened).selection("a")
        assertTrue(selected.blocked);assertTrue(selected.imports.isEmpty())
        assertTrue(runCatching {MemoryRepository(reopened).contextSnapshot("a")}.isFailure)
        assertFalse(LiveCaptureRepository(reopened).selection("b").blocked)
        assertTrue(SummaryRepository(reopened).list("a").isEmpty());assertTrue(runCatching {SummaryRepository(reopened).save(summary)}.isFailure)
        val currentStamp=reopened.stamp("a")
        assertTrue(runCatching {MemoryRepository(reopened).applyProposal("a",proposal,currentStamp)}.isFailure)
        assertTrue(runCatching {StyleRepository(reopened).saveSample(importSample.copy(id="late-graph-import",sourceRevision=currentStamp.sourceRevision,consentVersion=currentStamp.consentVersion),currentStamp)}.isFailure)
        assertTrue(runCatching {StyleRepository(reopened).saveSample(editedSample.copy(id="late-graph-edited",sourceRevision=currentStamp.sourceRevision,consentVersion=currentStamp.consentVersion),currentStamp)}.isFailure)
        assertEquals(listOf("user-a"),MemoryRepository(reopened).list("a").map {it.id})
        assertEquals(listOf("user-b"),MemoryRepository(reopened).contextSnapshot("b").entries.map {it.id})
    }
    @Test fun crossSourceAndFileBackedArchivesSurviveSideFlipBackMoreAndRepeatedAcknowledgements()=runBlocking {
        for((index,source) in listOf(ImportSource.PASTE,ImportSource.FILE,ImportSource.SHARE,ImportSource.SCREENSHOT).withIndex()) {
            val owner="flip$index";ContactRepository(session).save(Contact(owner,"合成甲"))
            val original=lines("raw-$index",owner)
            val archive=ChatImport("raw-$index",owner,source,LiveCaptureFingerprint.normalized(original),10)
            ImportRepository(session).save(archive,original)
            val file=File(root,"original-$index.bin").apply {writeBytes(byteArrayOf(7,8,9))}
            if(source==ImportSource.FILE || source==ImportSource.SCREENSHOT) FileCleanupRepository(session).register(owner,archive.id,file,root)
            ImportRepository(session).save(ChatImport("keep-$index",owner,ImportSource.PASTE,"different-$index"),lines("keep-$index",owner,"无关来源",Speaker.THEM))
            val first=ChatImport("start-$index",owner,ImportSource.SCREENSHOT,archive.contentHash,20)
            var live=LiveCaptureRepository(session).save(first,lines(first.id,owner),2).provenance
            live=replace(owner,live,"side-$index",lines("ignored",owner,speaker=Speaker.THEM))
            assertEquals(listOf(Speaker.THEM),ImportRepository(session).lines(owner,live.winner.id).map {it.speaker})
            live=replace(owner,live,"back-$index",lines("ignored",owner,speaker=Speaker.ME))
            val merged=listOf(ChatLine("ignored:0","ignored",0,Speaker.THEM,"较早合成原话",contactId=owner,speakerConfirmed=true),lines("ignored",owner).single().copy(seq=1))
            live=replace(owner,live,"more-$index",merged)
            assertEquals(listOf("较早合成原话","合成原话"),ImportRepository(session).lines(owner,live.winner.id).map {it.text})
            assertEquals(setOf(live.winner.id,"keep-$index"),LiveCaptureRepository(session).selection(owner).imports.map {it.id}.toSet())
            assertEquals(archive,ImportRepository(session).list(owner).single {it.id==archive.id})
            assertEquals(original,ImportRepository(session).lines(owner,archive.id));assertArrayEquals(byteArrayOf(7,8,9),file.readBytes())
            assertEquals(if(source==ImportSource.FILE || source==ImportSource.SCREENSHOT) listOf(archive.id) else emptyList<String>(),session.transaction {files(owner).mapNotNull {it.importId}})
        }
    }

    @Test fun movedWinnerCannotLetAnotherOwnerStealTheOriginalLineageRecord()=runBlocking {
        ContactRepository(session).save(Contact("a","合成甲"));ContactRepository(session).save(Contact("b","合成乙"))
        val input=ChatImport("shared-operation","a",ImportSource.SCREENSHOT,"input",20)
        val live=LiveCaptureRepository(session).save(input,lines(input.id,"a"),0)
        val corrected=replace("a",live.provenance,"moved-winner",lines("other","a",speaker=Speaker.THEM))
        val before=LiveCaptureRepository(session).provenance("a",live.provenance.lineageId)
        assertTrue("An owned projection ID must never move to another owner",runCatching {
            LiveCaptureRepository(session).save(input.copy(contactId="b"),lines(input.id,"b"),0)
        }.isFailure)
        assertEquals(before,LiveCaptureRepository(session).provenance("a",live.provenance.lineageId))
        assertEquals(listOf(corrected.winner),LiveCaptureRepository(session).selection("a").imports)
        assertTrue(ImportRepository(session).list("b").isEmpty())
    }

    @Test fun hiddenPhysicalArchiveCannotAuthorizeANewSummaryWithCurrentOwnerRevision()=runBlocking {
        ContactRepository(session).save(Contact("a","合成甲"))
        val rawLines=lines("summary-raw","a")
        val raw=ChatImport("summary-raw","a",ImportSource.PASTE,LiveCaptureFingerprint.normalized(rawLines))
        ImportRepository(session).save(raw,rawLines)
        ConversationRepository(session).save(Conversation("summary-conv","a",false))
        val live=LiveCaptureRepository(session).save(ChatImport("summary-live","a",ImportSource.SCREENSHOT,raw.contentHash),lines("summary-live","a"),1)
        val request=AnalysisRequest(requestId="late-summary",contactId="a",conversationId="summary-conv",sourceRevision=2,
            evidence=listOf(EvidenceBlock(raw.id,raw.sourceType.name,rawLines)),speakersConfirmed=true,purpose=OutboundPurpose.LONG_RECORD_SUMMARY,networkSelection=rawLines.map {it.id})
        assertTrue("A physically retained but shadowed import must be ineligible for a summary",runCatching {
            SummaryRepository(session).save(TimelineSummary("late",request,"[${rawLines.single().id}] 旧方向摘要"))
        }.isFailure)
        assertEquals(listOf(live.imported),LiveCaptureRepository(session).selection("a").imports)
    }

    @Test fun deletingLiveOrAliasStaysBlockedAcrossNewSessionUntilExplicitReconfirmation()=runBlocking {
        for(deleteLive in listOf(true,false)) {
            val owner=if(deleteLive) "delete-live" else "delete-alias"
            ContactRepository(session).save(Contact(owner,"合成甲"))
            val rawLines=lines("raw-$owner",owner)
            val archive=ChatImport("raw-$owner",owner,ImportSource.SCREENSHOT,LiveCaptureFingerprint.normalized(rawLines))
            ImportRepository(session).save(archive,rawLines)
            val image=File(root,"$owner.bin").apply {writeBytes(byteArrayOf(9,9,9))}
            FileCleanupRepository(session).register(owner,archive.id,image,root)
            val capture=ChatImport("first-$owner",owner,ImportSource.SCREENSHOT,archive.contentHash)
            val live=LiveCaptureRepository(session).save(capture,lines(capture.id,owner),1)
            ImportRepository(session).delete(owner,if(deleteLive) live.imported.id else archive.id)
            val reopened=DataSession(db,2)
            assertTrue(LiveCaptureRepository(reopened).selection(owner).blocked)
            assertTrue(LiveCaptureRepository(reopened).provenance(owner,live.provenance.lineageId)!!.requiresConfirmation)
            assertTrue(runCatching {MemoryRepository(reopened).contextSnapshot(owner)}.isFailure)
            if(deleteLive) {assertEquals(archive,ImportRepository(reopened).list(owner).single());assertArrayEquals(byteArrayOf(9,9,9),image.readBytes())}
            val fresh=capture.copy(id="reconfirmed-$owner")
            val confirmed=LiveCaptureRepository(reopened).save(fresh,lines(fresh.id,owner),ContactRepository(reopened).get(owner)!!.sourceRevision)
            assertFalse(LiveCaptureRepository(reopened).selection(owner).blocked)
            assertEquals(listOf(confirmed.imported),LiveCaptureRepository(reopened).selection(owner).imports)
        }
    }
    @Test fun sourceWitnessMutationAndTransactionRevocationFailClosedWithoutTouchingArchives()=runBlocking {
        ContactRepository(session).save(Contact("a","合成甲"))
        val rows=lines("witness-raw","a")
        val archive=ChatImport("witness-raw","a",ImportSource.PASTE,LiveCaptureFingerprint.normalized(rows))
        ImportRepository(session).save(archive,rows)
        val input=ChatImport("witness-live","a",ImportSource.SCREENSHOT,archive.contentHash)
        val captured=LiveCaptureRepository(session).save(input,lines(input.id,"a"),1)
        val before=ImportRepository(session).list("a");var checks=0
        assertTrue(runCatching {LiveCaptureRepository(session).save(input.copy(id="revoked-new"),lines("revoked-new","a","另一段"),2) {++checks<2}}.isFailure)
        assertEquals(2,checks);assertEquals(before,ImportRepository(session).list("a"));assertEquals(captured.provenance,LiveCaptureRepository(session).provenance("a",captured.provenance.lineageId))
        session.transaction {put(ImportRow(archive.id,"a","changed-source-hash",encode(archive.copy(contentHash="changed-source-hash"))))}
        assertTrue(LiveCaptureRepository(session).selection("a").blocked)
        assertTrue(runCatching {MemoryRepository(session).contextSnapshot("a")}.isFailure)
        assertEquals("changed-source-hash",ImportRepository(session).list("a").single {it.id==archive.id}.contentHash)
        assertEquals(rows,ImportRepository(session).lines("a",archive.id))
    }
    @Test fun onDiskReopenContactDeletionAndClearedSessionKeepOwnershipAndTerminalBoundaries()=runBlocking {
        db.close()
        val databaseFile=File(root,"reopen.db")
        db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java,databaseFile.absolutePath).build();session=DataSession(db,1)
        ContactRepository(session).save(Contact("a","合成甲"));ContactRepository(session).save(Contact("b","合成乙"))
        val rows=lines("disk-raw","a")
        val archive=ChatImport("disk-raw","a",ImportSource.FILE,LiveCaptureFingerprint.normalized(rows))
        ImportRepository(session).save(archive,rows)
        val image=File(root,"disk-source.bin").apply {writeBytes(byteArrayOf(5,5))}
        FileCleanupRepository(session).register("a",archive.id,image,root)
        val input=ChatImport("disk-live","a",ImportSource.SCREENSHOT,archive.contentHash)
        val first=LiveCaptureRepository(session).save(input,lines(input.id,"a"),1)
        val winner=replace("a",first.provenance,"disk-side",lines("x","a",speaker=Speaker.THEM))
        session.active=false;db.close()
        db=Room.databaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java,databaseFile.absolutePath).build()
        val reopened=DataSession(db,2)
        assertEquals(winner,LiveCaptureRepository(reopened).provenance("a",winner.lineageId))
        assertEquals(listOf(winner.winner),LiveCaptureRepository(reopened).selection("a").imports)
        assertEquals(archive,ImportRepository(reopened).list("a").single {it.id==archive.id});assertArrayEquals(byteArrayOf(5,5),image.readBytes())
        assertTrue(runCatching {LiveCaptureRepository(session).save(input,lines(input.id,"a"),1)}.isFailure)
        ContactRepository(reopened).delete("a")
        assertTrue(db.dao().records("a",OwnedRecordKind.CAPTURE_PROJECTION.name).isEmpty());assertTrue(db.dao().imports("a").isEmpty())
        assertNotNull(ContactRepository(reopened).get("b"))
        reopened.active=false;kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {db.clearAllTables()}
        assertTrue(runCatching {LiveCaptureRepository(reopened).save(input,lines(input.id,"a"),0)}.isFailure)
        assertTrue(db.dao().contacts().isEmpty());assertTrue(db.dao().records("a",OwnedRecordKind.CAPTURE_PROJECTION.name).isEmpty())
    }

}
