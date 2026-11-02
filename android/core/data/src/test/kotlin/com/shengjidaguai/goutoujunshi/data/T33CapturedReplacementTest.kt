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

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class T33CapturedReplacementTest {
    private lateinit var db:JunshiDatabase
    private lateinit var session:DataSession
    private lateinit var change:CapturedImportReplacement
    private val oldId=LiveCaptureFingerprint.id("old")
    private val newId=LiveCaptureFingerprint.id("new")
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).build();session=DataSession(db,1)
        ContactRepository(session).save(Contact("a","虚构甲"))
        val live=LiveCaptureRepository(session).save(ChatImport("old","a",ImportSource.SCREENSHOT,"old-hash"),listOf(ChatLine("old:0","old",0,Speaker.ME,"原方向",contactId="a",speakerConfirmed=true)),0)
        ImportRepository(session).save(ChatImport("keep","a",ImportSource.PASTE,"keep-hash"),listOf(ChatLine("keep:0","keep",0,Speaker.ME,"其他来源",contactId="a",speakerConfirmed=true)))
        val rows=listOf(ChatLine("$newId:0",newId,0,Speaker.THEM,"原方向",contactId="a",speakerConfirmed=true))
        change=CapturedImportReplacement(oldId,live.imported.contentHash,2,ChatImport(newId,"a",ImportSource.SCREENSHOT,LiveCaptureFingerprint.stored(live.provenance.lineageId,LiveCaptureFingerprint.normalized(rows))),rows,provenance=live.provenance)
    }
    @After fun close() { db.close() }
    @Test fun replacementIsAtomicAndIdempotentAndPreservesOtherSources()=runBlocking {
        val repo=CapturedImportReplacementRepository(session)
        assertEquals(3,repo.replace(change).sourceRevision)
        assertEquals(3,repo.replace(change).sourceRevision)
        assertEquals(setOf("keep",newId),ImportRepository(session).list("a").map { it.id }.toSet())
        assertTrue(ImportRepository(session).lines("a",oldId).isEmpty())
        assertEquals(Speaker.THEM,ImportRepository(session).lines("a",newId).single().speaker)
    }
    @Test fun finalAuthorityFailureRollsBackOldRemovalAndNewInsertion()=runBlocking {
        var checks=0
        assertTrue(runCatching { CapturedImportReplacementRepository(session).replace(change) { ++checks<2 } }.isFailure)
        assertEquals(2,checks)
        assertEquals(2,ContactRepository(session).get("a")!!.sourceRevision)
        assertEquals(setOf(oldId,"keep"),ImportRepository(session).list("a").map { it.id }.toSet())
    }
    @Test fun derivedMemoriesAndEditedReplySamplesArePrunedInTheSameTransaction()=runBlocking {
        ConsentRepository(session).update(true)
        val memories=MemoryRepository(session)
        memories.apply("a",MemoryEntry("manual","a",scope=MemoryScope.USER,subjectId="a",field="fact",value="保留手工事实",sourceType=MemorySource.USER_EXPLICIT),session.stamp("a"))
        val conversations=ConversationRepository(session)
        conversations.save(Conversation("conv","a",false))
        val oldLines=ImportRepository(session).lines("a",oldId)
        val snapshot=memories.snapshot("a")
        val request=AnalysisRequest(requestId="source-r",contactId="a",conversationId="conv",sourceRevision=2,userText="合成来源问题",
            evidence=listOf(EvidenceBlock(oldId,"SCREENSHOT",oldLines)),speakersConfirmed=true,
            actualDataContext=MemoryContextPolicy.context("a",snapshot.consent,snapshot.stamp!!.memoryRevision ?: 0,snapshot.outboundEntries()))
        conversations.saveMessage(Message("user-r","conv",MessageRole.USER,request.userText,requestId="source-r",status=MessageStatus.COMPLETED,contactId="a"))
        conversations.saveMessage(Message("assistant-r","conv",MessageRole.ASSISTANT,"旧方向分析",requestId="source-r",status=MessageStatus.COMPLETED,contactId="a",providerKind=ProviderKind.BYOK))
        TimelineRepository(session).save(AnalysisRun(requestId="source-r",conversationId="conv",contactId="a",status=RunStatus.COMPLETED,requestSnapshot=request))
        memories.applyProposal("a",MemoryProposal(subjectId="ta",field="pattern",value="旧方向派生推测",sourceRef="source-r"),session.stamp("a"))
        val stamp=session.stamp("a")
        val edited=StyleSample("edited",relationGroup=RelationGroup.SOCIAL,text="旧方向编辑样本",origin=StyleOrigin.EDITED_REPLY,sourceContactId="a",sourceAnswerId="assistant-r",sourceRevision=stamp.sourceRevision,consentVersion=stamp.consentVersion)
        val keep=StyleSample("keep-sample",relationGroup=RelationGroup.SOCIAL,text="其他来源",origin=StyleOrigin.IMPORT,sourceContactId="a",sourceImportId="keep",sourceMessageId="keep:0",sourceRevision=stamp.sourceRevision,consentVersion=stamp.consentVersion)
        session.transaction {
            put(StyleSampleRow(edited.id,"SOCIAL","a",null,null,"assistant-r",stamp.sourceRevision,stamp.consentVersion,encode(edited)))
            put(StyleSampleRow(keep.id,"SOCIAL","a","keep","keep:0",null,stamp.sourceRevision,stamp.consentVersion,encode(keep)))
        }
        val binding=FloatingIdentityBinding("binding","a",FloatingSourceContext("合成应用","合成账号"),"合成昵称",true,2)
        FloatingIdentityRepository(session).save(binding)
        val updated=binding.copy(rightIsMe=false,contactRevision=3,revision=2)
        CapturedImportReplacementRepository(session).replace(change.copy(binding=updated,expectedBindingRevision=1))
        assertEquals(listOf("保留手工事实"),memories.list("a").map { it.value })
        assertTrue(memories.operations("a").isEmpty())
        assertEquals(listOf("keep-sample"),session.transaction { samples("SOCIAL").map { it.id } })
        assertEquals(updated,FloatingIdentityRepository(session).list("a").single())
        assertTrue(session.transaction { records("a",OwnedRecordKind.SUMMARY.name) }.isEmpty())
        val profile=StyleRepository(session).profile(RelationGroup.SOCIAL)
        assertFalse(profile?.description.orEmpty().contains("旧方向"))
        assertTrue(ImportRepository(session).lines("a",oldId).isEmpty())
    }
    @Test fun staleSourceOrHashCannotReplace()=runBlocking {
        val repo=CapturedImportReplacementRepository(session)
        assertTrue(runCatching { repo.replace(change.copy(expectedSourceRevision=0)) }.isFailure)
        assertTrue(runCatching { repo.replace(change.copy(oldHash="wrong")) }.isFailure)
        assertEquals(2,ContactRepository(session).get("a")!!.sourceRevision)
    }
}
