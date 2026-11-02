package com.shengjidaguai.goutoujunshi.counsel

import android.app.Application
import androidx.room.Room
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.importing.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Room repositories -> Controller -> real knowledge/frozen preview -> HTTP. No JVM SecretVault or SQLCipher claim. */
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[28])
class T33RoomCaptureWireTest {
    private suspend fun await(check:()->Boolean)=withTimeout(15000) {while(!check()) delay(2)}
    /** Isolated synthetic fixture only: prove a refused ordinary capture commits no row in any table. */
    private suspend fun databaseRows(f:Fixture):Map<String,List<List<String?>>> = withContext(Dispatchers.IO) {
        val sql=f.db.openHelper.readableDatabase
        val names=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use {cursor->buildList {while(cursor.moveToNext()) add(cursor.getString(0))}}
        names.associateWith {name->sql.query("SELECT * FROM \"${name.replace("\"","\"\"")}\"").use {cursor->
            buildList {while(cursor.moveToNext()) add((0 until cursor.columnCount).map {if(cursor.isNull(it)) null else cursor.getString(it)})}.sortedBy {it.toString()}
        }}
    }
    private class Fixture:AutoCloseable {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).build()
        val session=DataSession::class.java.declaredConstructors.single {it.parameterCount==3}.apply {isAccessible=true}.newInstance(db,1L,null) as DataSession
        val contacts=ContactRepository(session);val conv=ConversationRepository(session);val raw=ImportRepository(session)
        val live=LiveCaptureRepository(session);val memories=MemoryRepository(session)
        val server=MockWebServer();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val temporary=TemporarySessionStore()
        val files=File(System.getProperty("java.io.tmpdir"),"t33-room-wire-${UUID.randomUUID()}").apply {mkdirs()}
        val controller:CounselController
        val imports:ImportCoordinator
        init {
            repeat(8) {server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"[[VERDICT]]合成判断[[REPLY]]合成回复\"}}]}\n\ndata: [DONE]\n\n"))}
            val repository=File(System.getProperty("junshi.repositoryRoot")!!);val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
            val frozen=FrozenPromptAssembler(PromptBuilder(KnowledgeLibrary(KnowledgeSource {path->
                val dir=if(path.startsWith("knowledge/")) bundled else File(repository,"android/core/knowledge/src/main/assets")
                File(dir,path.removePrefix("knowledge/")).readBytes()
            })))
            val provider=OpenAiCompatibleProvider(ModelConnection(server.url("/v1/").toString(),"synthetic","Mock"),frozen,CredentialSource {"sk-synthetic".toByteArray()},allowLocalhostHttpForTests=true)
            val settings=object:ModelSettingsRepository {
                override suspend fun load()=ModelSettingsSnapshot(SavedModelSettings(true,server.url("/v1/").toString(),"synthetic","Mock","r1"),"masked")
                override suspend fun save(settings:SavedModelSettings,newKey:String?,removeKey:Boolean)=load()
                override suspend fun clear() {}
            }
            val fallback=CounselControllerTest.MemoryPersistence()
            val persistence=object:CounselPersistence by fallback {
                override suspend fun initialize() {}
                override suspend fun contacts()=contacts.list()
                override suspend fun createContact(contact:Contact) {contacts.save(contact)}
                override suspend fun memoryContextSnapshot(owner:String?)=memories.contextSnapshot(owner)
                override suspend fun memorySnapshot(owner:String?)=memories.snapshot(owner)
                override suspend fun loadOrCreate(contact:Contact):StoredCounsel {
                    check(contacts.get(contact.id)?.sourceRevision==contact.sourceRevision)
                    val c=conv.list(contact.id).maxByOrNull {it.updatedAt} ?: Conversation("conv-${contact.id}",contact.id,false).also {conv.save(it)}
                    val selected=live.evidence(contact.id)
                    return StoredCounsel(c,conv.draft(contact.id,c.id).orEmpty(),conv.messages(contact.id,c.id),selected.evidence,
                        TimelineRepository(session).runs(contact.id),SummaryRepository(session).list(contact.id),PlanRepository(session).list(contact.id),selected.blocked,selected.preferred)
                }
                override suspend fun update(contact:Contact,previous:Conversation,next:Conversation,draft:String) {conv.updateDraft(contact.id,previous.revision,next,draft,contact.sourceRevision)}
                override suspend fun saveMessage(contact:Contact,conversation:Conversation,message:Message) {
                    check(contacts.get(contact.id)?.sourceRevision==contact.sourceRevision);check(conv.list(contact.id).any {it==conversation});conv.saveMessage(message)
                }
                override suspend fun discardMessage(contact:Contact,message:Message) {conv.deleteMessage(contact.id,message.id)}
                override suspend fun saveRun(contact:Contact,run:AnalysisRun) {TimelineRepository(session).save(run)}
                override suspend fun saveCaptured(contact:Contact,imported:ChatImport,lines:List<ChatLine>,current:()->Boolean)=live.save(imported,lines,contact.sourceRevision,current)
                override suspend fun saveReviewedCaptured(contact:Contact,imported:ChatImport,lines:List<ChatLine>,frames:List<List<ChatLine>>,current:()->Boolean)=live.saveReviewed(imported,lines,contact.sourceRevision,frames,current)
                override suspend fun liveCaptureProvenance(owner:String,lineage:String)=live.provenance(owner,lineage)
                override suspend fun replaceCaptured(change:CapturedImportReplacement,current:()->Boolean)=CapturedImportReplacementRepository(session).replace(change,current)
                override suspend fun saveImport(contact:Contact,imported:ChatImport,lines:List<ChatLine>)=raw.save(imported,lines,contact.sourceRevision)
                override suspend fun saveSummary(summary:TimelineSummary) {SummaryRepository(session).save(summary)}
                override suspend fun discardSummary(summary:TimelineSummary) {SummaryRepository(session).discard(summary)}
            }
            controller=CounselController(persistence,temporary,frozen,provider,scope,settings,{provider}, localSafety={com.shengjidaguai.goutoujunshi.domain.SafetyLevel.NONE})
            imports=ImportCoordinator(controller,scope,{true},{})
        }
        override fun close() {
            scope.cancel();server.close();db.close()
            check(files.canonicalFile.toPath().startsWith(File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()));files.deleteRecursively()
        }
    }
    private fun line(id:String,owner:String,text:String,speaker:Speaker=Speaker.ME,name:String?=null)=ChatLine("$id:0",id,0,speaker,text,contactId=owner,speakerConfirmed=true,speakerName=name)
    private suspend fun seed(f:Fixture,source:ImportSource):Pair<ChatImport,List<ChatLine>> {
        ConsentRepository(f.session).update(true)
        f.contacts.save(Contact("a","合成正式甲",RelationType.FRIEND));f.contacts.save(Contact("b","合成正式乙",RelationType.FRIEND))
        val oldLines=listOf(line("archive-a","a","当前合成原话",name="合成昵称"))
        val archive=ChatImport("archive-a","a",source,LiveCaptureFingerprint.normalized(oldLines),10)
        f.raw.save(archive,oldLines)
        f.raw.save(ChatImport("keep-a","a",ImportSource.PASTE,"unrelated-hash"),listOf(line("keep-a","a","无关来源原文")))
        f.raw.save(ChatImport("b-raw","b",ImportSource.PASTE,"b-hash"),listOf(line("b-raw","b","B独有原文")))
        f.memories.apply("a",MemoryEntry("manual-a","a",scope=MemoryScope.USER,subjectId="a",field="fact",value="保留独立手工事实",sourceType=MemorySource.USER_EXPLICIT),f.session.stamp("a"))
        f.memories.apply("b",MemoryEntry("manual-b","b",scope=MemoryScope.USER,subjectId="b",field="fact",value="B独有手工事实",sourceType=MemorySource.USER_EXPLICIT),f.session.stamp("b"))
        val conversation=Conversation("old-conv","a",false)
        f.conv.save(conversation)
        val oldAnswer=StructuredAnswer(id="old-answer",verdict="旧方向判断",reply=listOf("旧方向回复"))
        val request=AnalysisRequest(requestId="old-run",contactId="a",conversationId=conversation.id,sourceRevision=2,
            evidence=listOf(EvidenceBlock(archive.id,archive.sourceType.name,oldLines)),speakersConfirmed=true)
        f.conv.saveMessage(Message("old-user",conversation.id,MessageRole.USER,request.userText,requestId=request.requestId,status=MessageStatus.COMPLETED,contactId="a"))
        f.conv.saveMessage(Message("old-assistant",conversation.id,MessageRole.ASSISTANT,"旧方向分析",requestId=request.requestId,status=MessageStatus.COMPLETED,contactId="a",providerKind=ProviderKind.BYOK,answerJson=Json.encodeToString(StructuredAnswer.serializer(),oldAnswer)))
        TimelineRepository(f.session).save(AnalysisRun(requestId=request.requestId,conversationId=conversation.id,contactId="a",status=RunStatus.COMPLETED,requestSnapshot=request))
        f.memories.applyProposal("a",MemoryProposal(subjectId="ta",field="pattern",value="旧方向派生推断",sourceRef=request.requestId),f.session.stamp("a"))
        assertTrue(f.memories.operations("a").isNotEmpty())
        val style=StyleRepository(f.session);val group=RelationType.FRIEND.group()
        suspend fun sample(id:String,text:String,origin:StyleOrigin,importId:String?=null,messageId:String?=null,answerId:String?=null) {
            val stamp=f.session.stamp("a")
            style.saveSample(StyleSample(id,relationGroup=group,text=text,origin=origin,sourceContactId="a",sourceImportId=importId,sourceMessageId=messageId,sourceAnswerId=answerId,sourceRevision=stamp.sourceRevision,consentVersion=stamp.consentVersion),stamp)
        }
        sample("raw-style","当前合成原话",StyleOrigin.IMPORT,archive.id,oldLines.single().id)
        sample("edited-style","旧方向编辑建议保留边界",StyleOrigin.EDITED_REPLY,answerId="old-assistant")
        sample("keep-style","无关来源原文",StyleOrigin.IMPORT,"keep-a","keep-a:0")
        val profile=style.profile(group)!!;style.saveProfile(profile.copy(description="旧方向画像"),f.session.stamp("a"))
        SummaryRepository(f.session).save(TimelineSummary("old-summary",request.copy(purpose=OutboundPurpose.LONG_RECORD_SUMMARY,networkSelection=oldLines.map {it.id}),"[${oldLines.single().id}] 旧方向摘要"))
        assertEquals(1,SummaryRepository(f.session).list("a").size)
        return archive to oldLines
    }
    private suspend fun send(f:Fixture,me:Boolean,earlier:Boolean=false) {
        val c=f.controller
        if(!c.state.value.floatingPreviewReady) c.preparePreview(c.state.value.conversation?.id,true)
        await {c.state.value.floatingPreviewReady}
        val body=checkNotNull(c.state.value.outboundBody)
        val prepared=checkNotNull(c.state.value.preview)
        assertTrue(prepared.references.isNotEmpty());assertTrue(prepared.knowledgeVersion.isNotBlank())
        assertEquals(me,prepared.user.contains("我：当前合成原话"))
        assertEquals(1,Regex("当前合成原话").findAll(prepared.user).count())
        if(earlier) {
            assertTrue(prepared.user.contains("较早合成原话"));assertTrue(prepared.user.indexOf("较早合成原话")<prepared.user.indexOf("当前合成原话"))
            assertTrue(body.contains("较早合成原话"));assertTrue(body.indexOf("较早合成原话")<body.indexOf("当前合成原话"))
        }
        for(forbidden in listOf("旧方向","B独有","合成昵称")) assertFalse(body.contains(forbidden))
        assertTrue(body.contains("无关来源原文"));assertTrue(body.contains("保留独立手工事实"))
        val expected=c.state.value.floatingPreviewRequest
        val count=f.server.requestCount;c.analyzeFloating(c.state.value.conversation?.id,expected)
        val wire=checkNotNull(f.server.takeRequest(10,TimeUnit.SECONDS)).body.readUtf8()
        assertEquals(body,wire);assertEquals(count+1,f.server.requestCount)
        await {!c.state.value.streaming}
        assertNull(c.state.value.error)
    }
    private suspend fun sendActual(f:Fixture):String {
        val c=f.controller
        c.preparePreview(c.state.value.conversation?.id,true);await {c.state.value.floatingPreviewReady}
        val body=checkNotNull(c.state.value.outboundBody);val prepared=checkNotNull(c.state.value.preview)
        assertTrue(prepared.references.isNotEmpty());assertTrue(prepared.knowledgeVersion.isNotBlank())
        c.analyzeFloating(c.state.value.conversation?.id,c.state.value.floatingPreviewRequest)
        assertEquals(body,checkNotNull(f.server.takeRequest(10,TimeUnit.SECONDS)).body.readUtf8())
        await {!c.state.value.streaming};assertNull(c.state.value.error)
        return prepared.user
    }
    private suspend fun replayActualFrame(more:Boolean) {
        Fixture().use {f->
            f.contacts.save(Contact("a","合成甲"))
            val original=listOf(OcrLine("重叠合成原话",.99f,.6f,.1f,.9f,.2f),OcrLine("末尾合成原话",.99f,.6f,.3f,.9f,.4f))
            val earlier=listOf(OcrLine("较早合成原话",.99f,.6f,.1f,.9f,.2f),original.first().copy(top=.3f,bottom=.4f))
            var id=f.imports.beginCapture(33) {true}!!;f.imports.captured(id,original)
            if(more) {
                f.imports.target(ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}));f.imports.side(id,OcrSide.RIGHT);f.imports.confirm()
                await {f.imports.state.value==null && f.controller.state.value.floatingCapture!=null}
                val before=f.controller.state.value;assertTrue(f.imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
                id=f.imports.beginCapture(33) {true}!!
            }
            f.imports.captured(id,earlier)
            f.imports.target(ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}))
            if(more) f.imports.continueCaptureMore()
            f.imports.side(id,OcrSide.RIGHT);f.imports.confirm()
            await {f.imports.state.value==null && f.controller.state.value.floatingCapture!=null}
            val before=f.controller.state.value;assertEquals(3,before.evidence.single().lines.size)
            f.controller.switchFloatingSide(before.conversation?.id,before.floatingCapture!!.id)
            await {f.controller.state.value.floatingPreviewReady && f.controller.state.value.floatingCapture?.id!=before.floatingCapture.id}
            assertEquals(setOf(Speaker.THEM),f.controller.state.value.evidence.single().lines.map {it.speaker}.toSet())
            sendActual(f);val persisted=databaseRows(f)
            f.controller.closeFloatingCapture(33)
            id=f.imports.beginCapture(33) {true}!!;f.imports.captured(id,earlier)
            f.imports.target(ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}));f.imports.side(id,OcrSide.RIGHT);f.imports.confirm()
            await {f.imports.state.value==null || f.imports.state.value?.error!=null}
            if(f.imports.state.value==null) {
                val user=sendActual(f)
                println("T33actualFrame more=$more duplicateLineCount=${Regex("重叠合成原话").findAll(user).count()}")
            }
            assertEquals("The real reviewed image frame must retain lineage ownership, not only its aggregate",1,f.server.requestCount)
            assertEquals("CAPTURE_HISTORY_REVIEW",f.imports.state.value?.error);assertEquals(persisted,databaseRows(f))
            f.imports.target(ImportTarget());assertNull(f.imports.state.value!!.error);assertTrue(f.imports.state.value!!.canConfirm)
            f.imports.confirm();await {f.imports.state.value==null && f.controller.state.value.floatingCapture!=null}
            val user=sendActual(f)
            assertFalse(user.contains("末尾合成原话"));assertTrue(user.contains("我：较早合成原话"));assertTrue(user.contains("我：重叠合成原话"))
            assertEquals(2,f.server.requestCount);assertEquals(persisted,databaseRows(f))
        }
    }
    @Test fun eachPhysicalFrameOfFirstReviewedMultiCaptureRetainsOwnershipWithoutArchive()=runBlocking {replayActualFrame(false)}
    @Test fun eachNewPhysicalMoreFrameRetainsOwnershipWithoutArchive()=runBlocking {replayActualFrame(true)}
    @Test fun actualRoomArchivesProofAndFilesStaySafeAcrossCaptureSideFlipBackMoreAndFrozenWire()=runBlocking {
        for(source in listOf(ImportSource.PASTE,ImportSource.FILE,ImportSource.SHARE,ImportSource.SCREENSHOT)) Fixture().use {f->
            val (archive,oldLines)=seed(f,source)
            val image=File(f.files,"original.bin").apply {writeBytes(byteArrayOf(4,3,2,1))}
            if(source==ImportSource.FILE || source==ImportSource.SCREENSHOT) FileCleanupRepository(f.session).register("a",archive.id,image,f.files)
            val stale=f.session.stamp("a")
            val id=f.imports.beginCapture(33) {true}!!
            f.imports.captured(id,listOf(OcrLine("当前合成原话",.99f,.6f,.2f,.9f,.3f,nickname="合成昵称")))
            f.imports.target(ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}));f.imports.side(id,OcrSide.RIGHT);f.imports.confirm()
            await {f.imports.state.value==null && f.controller.state.value.floatingCapture!=null}
            assertTrue(f.controller.state.value.captureSourcePreferred)
            assertEquals(setOf("manual-a"),f.memories.list("a").map {it.id}.toSet());assertEquals(listOf("manual-b"),f.memories.list("b").map {it.id})
            assertTrue(f.memories.operations("a").isEmpty())
            val learnedLine=f.controller.state.value.evidence.single {it.id.startsWith(LiveCaptureFingerprint.PREFIX)}.lines.single {it.speaker==Speaker.ME}
            val survivingStyle=StyleRepository(f.session).samples(RelationType.FRIEND.group())
            assertEquals(setOf("keep-style","style:import:b-raw:0","style:import:${learnedLine.id}"),survivingStyle.map {it.id}.toSet())
            assertFalse(survivingStyle.any {it.id in setOf("raw-style","edited-style")})
            survivingStyle.forEach {sample ->
                val actual=f.raw.lines(requireNotNull(sample.sourceContactId),requireNotNull(sample.sourceImportId)).single {it.id==sample.sourceMessageId}
                assertEquals(Speaker.ME,actual.speaker);assertTrue(actual.speakerConfirmed);assertEquals(actual.text,sample.text)
            }
            assertTrue(SummaryRepository(f.session).list("a").isEmpty())
            assertFalse(StyleRepository(f.session).profile(RelationType.FRIEND.group())?.description.orEmpty().contains("旧方向"))
            assertTrue(runCatching {f.memories.applyProposal("a",MemoryProposal(subjectId="ta",field="pattern",value="迟到旧方向",sourceRef="old-run"),stale)}.isFailure)
            assertTrue(runCatching {f.memories.applyProposal("a",MemoryProposal(subjectId="ta",field="pattern",value="迟到旧方向",sourceRef="old-run"),f.session.stamp("a"))}.isFailure)
            send(f,true)
            repeat(2) {index->
                val state=f.controller.state.value;f.controller.switchFloatingSide(state.conversation?.id,state.floatingCapture!!.id)
                await {f.controller.state.value.floatingPreviewReady && f.controller.state.value.floatingCapture?.id!=state.floatingCapture.id}
                send(f,index==1)
            }
            val before=f.controller.state.value
            assertTrue(f.imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
            val more=f.imports.beginCapture(33) {true}!!
            f.imports.captured(more,listOf(OcrLine("较早合成原话",.99f,.1f,.1f,.4f,.2f,nickname="合成昵称"),OcrLine("当前合成原话",.99f,.6f,.3f,.9f,.4f,nickname="合成昵称")))
            f.imports.target(ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}));f.imports.continueCaptureMore();f.imports.side(more,OcrSide.RIGHT);f.imports.confirm()
            await {f.imports.state.value==null && f.controller.state.value.floatingCapture!=null}
            send(f,true,earlier=true)
            assertEquals(4,f.server.requestCount)
            assertEquals(archive,f.raw.list("a").single {it.id==archive.id});assertEquals(oldLines,f.raw.lines("a",archive.id))
            assertArrayEquals(byteArrayOf(4,3,2,1),image.readBytes())
            assertEquals(listOf("manual-b"),f.memories.list("b").map {it.id})
            val stamp=f.session.stamp("a")
            val future=StyleSample("late-old-import",relationGroup=RelationType.FRIEND.group(),text="当前合成原话",origin=StyleOrigin.IMPORT,sourceContactId="a",sourceImportId=archive.id,sourceMessageId=oldLines.single().id,sourceRevision=stamp.sourceRevision,consentVersion=stamp.consentVersion)
            assertTrue(runCatching {StyleRepository(f.session).saveSample(future,stamp)}.isFailure)
            assertTrue(runCatching {StyleRepository(f.session).saveSample(future.copy(id="late-old-edited",origin=StyleOrigin.EDITED_REPLY,text="旧方向编辑建议保留边界",sourceImportId=null,sourceMessageId=null,sourceAnswerId="old-assistant"),stamp)}.isFailure)
        }
    }
    @Test fun recapturingHistoricalPartialFrameAfterMoreAndSideCannotPublishOppositeWinners()=runBlocking {
        Fixture().use {f->
            val (archive,oldLines)=seed(f,ImportSource.FILE)
            val image=File(f.files,"historical-original.bin").apply {writeBytes(byteArrayOf(4,3,2,1))}
            FileCleanupRepository(f.session).register("a",archive.id,image,f.files)
            suspend fun frame():String {
                val id=f.imports.beginCapture(33) {true}!!
                f.imports.captured(id,listOf(OcrLine("当前合成原话",.99f,.6f,.2f,.9f,.3f,nickname="合成昵称")))
                f.imports.target(ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}));f.imports.side(id,OcrSide.RIGHT)
                return id
            }
            frame();f.imports.confirm();await {f.imports.state.value==null && f.controller.state.value.floatingCapture!=null};send(f,true)
            val before=f.controller.state.value
            assertTrue(f.imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
            val more=f.imports.beginCapture(33) {true}!!
            f.imports.captured(more,listOf(OcrLine("较早合成原话",.99f,.1f,.1f,.4f,.2f,nickname="合成昵称"),OcrLine("当前合成原话",.99f,.6f,.3f,.9f,.4f,nickname="合成昵称")))
            f.imports.target(ImportTarget(f.controller.state.value.contacts.first {it.id=="a"}));f.imports.continueCaptureMore();f.imports.side(more,OcrSide.RIGHT);f.imports.confirm()
            await {f.imports.state.value==null && f.controller.state.value.floatingCapture!=null};send(f,true,earlier=true)
            val merged=f.controller.state.value
            f.controller.switchFloatingSide(merged.conversation?.id,merged.floatingCapture!!.id)
            await {f.controller.state.value.floatingPreviewReady && f.controller.state.value.floatingCapture?.id!=merged.floatingCapture.id}
            send(f,false,earlier=true)
            val preserved=f.live.selection("a");val rowsBefore=databaseRows(f)
            f.controller.closeFloatingCapture(33)
            frame();f.imports.confirm()
            await {f.imports.state.value==null || f.imports.state.value?.error!=null}
            // Exercise the currently successful unsafe branch all the way through its actual frozen HTTP body.
            // After the fix the import stays reviewable and this branch is unreachable, with zero extra requests.
            if(f.imports.state.value==null) {
                val c=f.controller;c.preparePreview(c.state.value.conversation?.id,true);await {c.state.value.floatingPreviewReady}
                val body=checkNotNull(c.state.value.outboundBody);val prepared=checkNotNull(c.state.value.preview)
                assertTrue(prepared.references.isNotEmpty());assertTrue(prepared.knowledgeVersion.isNotBlank())
                c.analyzeFloating(c.state.value.conversation?.id,c.state.value.floatingPreviewRequest)
                val wire=checkNotNull(f.server.takeRequest(10,TimeUnit.SECONDS)).body.readUtf8();assertEquals(body,wire)
                await {!c.state.value.streaming}
                println("T33historicalPartial duplicateLineCount=${Regex("当前合成原话").findAll(prepared.user).count()} hasMe=${prepared.user.contains("我：当前合成原话")} hasThem=${prepared.user.contains("对方：当前合成原话")}")
            }
            assertEquals("A historical partial frame needs explicit full-record review, never a fourth HTTP with two directions",3,f.server.requestCount)
            assertEquals("CAPTURE_HISTORY_REVIEW",f.imports.state.value?.error)
            assertEquals(preserved,f.live.selection("a"))
            assertEquals("A safe refusal must not change any persisted row, including source revision/derived/undo",rowsBefore,databaseRows(f))
            assertEquals(archive,f.raw.list("a").single {it.id==archive.id});assertEquals(oldLines,f.raw.lines("a",archive.id));assertArrayEquals(byteArrayOf(4,3,2,1),image.readBytes())
            assertEquals(listOf("manual-a"),f.memories.list("a").map {it.id});assertEquals(listOf("manual-b"),f.memories.list("b").map {it.id})
            val stamp=f.session.stamp("a")
            assertTrue(runCatching {f.memories.applyProposal("a",MemoryProposal(subjectId="ta",field="pattern",value="迟到旧方向",sourceRef="old-run"),stamp)}.isFailure)
            val obsolete=AnalysisRequest(requestId="late-after-recapture",contactId="a",conversationId="old-conv",sourceRevision=stamp.sourceRevision ?: 0,
                evidence=listOf(EvidenceBlock(archive.id,archive.sourceType.name,oldLines)),speakersConfirmed=true,purpose=OutboundPurpose.LONG_RECORD_SUMMARY,networkSelection=oldLines.map {it.id})
            assertTrue(runCatching {SummaryRepository(f.session).save(TimelineSummary("late-after-recapture",obsolete,"[${oldLines.single().id}] 旧方向摘要"))}.isFailure)
            val sample=StyleSample("late-after-recapture-import",relationGroup=RelationType.FRIEND.group(),text="当前合成原话",origin=StyleOrigin.IMPORT,
                sourceContactId="a",sourceImportId=archive.id,sourceMessageId=oldLines.single().id,sourceRevision=stamp.sourceRevision,consentVersion=stamp.consentVersion)
            assertTrue(runCatching {StyleRepository(f.session).saveSample(sample,stamp)}.isFailure)
            assertTrue(runCatching {StyleRepository(f.session).saveSample(sample.copy(id="late-after-recapture-edited",origin=StyleOrigin.EDITED_REPLY,
                text="旧方向编辑建议保留边界",sourceImportId=null,sourceMessageId=null,sourceAnswerId="old-assistant"),stamp)}.isFailure)
            assertEquals(rowsBefore,databaseRows(f))
            // The offered alternative must work through the real import/knowledge/redaction/send path.
            f.imports.target(ImportTarget())
            assertNull(f.imports.state.value!!.error);assertNull(f.imports.state.value!!.captureContinuationId)
            assertEquals(listOf("当前合成原话"),f.imports.state.value!!.rows.filterNot {it.excluded}.map {it.text})
            assertTrue(f.imports.state.value!!.canConfirm)
            f.imports.confirm();await {f.imports.state.value==null && f.controller.state.value.floatingCapture!=null}
            val c=f.controller;assertNull(c.state.value.currentContact);assertTrue(c.state.value.draft.isBlank())
            c.preparePreview(c.state.value.conversation?.id,true);await {c.state.value.floatingPreviewReady}
            val body=checkNotNull(c.state.value.outboundBody);val prepared=checkNotNull(c.state.value.preview)
            assertTrue(prepared.references.isNotEmpty());assertTrue(prepared.user.contains("我：当前合成原话"))
            assertEquals(1,Regex("当前合成原话").findAll(prepared.user).count())
            for(forbidden in listOf("较早合成原话","无关来源原文","保留独立手工事实","旧方向","B独有","合成昵称")) assertFalse(body.contains(forbidden))
            c.analyzeFloating(c.state.value.conversation?.id,c.state.value.floatingPreviewRequest)
            assertEquals(body,checkNotNull(f.server.takeRequest(10,TimeUnit.SECONDS)).body.readUtf8());await {!c.state.value.streaming}
            assertEquals(4,f.server.requestCount);assertNull(c.state.value.error);assertEquals(rowsBefore,databaseRows(f))
        }
    }
}
