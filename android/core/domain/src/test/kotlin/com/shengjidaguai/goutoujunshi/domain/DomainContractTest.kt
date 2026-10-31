package com.shengjidaguai.goutoujunshi.domain

import org.junit.Assert.*
import org.junit.Test

class DomainContractTest {
    @Test fun schemaAndEveryProtocolSplit() {
        assertEquals(16, RelationType.entries.size)
        assertEquals(14, Goal.entries.size)
        val input = "[[REPLY]]你好+++再见[[ALT]]温和|下次聊[[SCENE]]BOSS|REPORT|high[[FACTS]]F|约定|1,2[[GUESSES]]G|忙|low[[TIP]]倾听|承接|先回应[[MEMORY]][{\"scope\":\"hypothesis\",\"subject_id\":\"p\",\"field\":\"mood\",\"value\":\"忙\",\"source_type\":\"assistant_inference\"}]"
        val expected = SectionParser().apply { feed(input) }.finish()
        for (offset in 0..input.length) {
            val parser = SectionParser()
            parser.feed(input.take(offset)); parser.feed(input.drop(offset))
            assertEquals("split $offset", expected, parser.finish())
        }
        assertEquals(listOf("你好", "再见"), expected.reply)
        assertEquals(listOf("1", "2"), expected.facts.single().messageRefs)
        assertEquals(1, expected.memoryProposals.size)
    }
    @Test fun safetyNeverDowngradesAndMalformedTextSurvives() {
        val malformed = "[[SITUATION]]虚构[昵称1] [手机号1] [昵称999]"
        val projected = SafeAliasRestorer.restore(SectionParser().apply { feed(malformed) }.finish(),
            PreparedPrompt("虚构知识", "虚构输入", listOf(KnowledgeReference("synthetic.md", "a".repeat(64), 4)), "synthetic",
                safeAliases = mapOf("[昵称1]" to "小伙伴")))
        assertEquals(malformed, projected.rawText)
        assertEquals("[[SITUATION]]虚构小伙伴 [手机号1] [昵称999]", projected.freeText)
        val missing = SectionParser().apply { feed("[[SITUATION]]虚构局势") }.finish()
        assertTrue(missing.formatDisordered)
        assertEquals("[[SITUATION]]虚构局势", missing.rawText)
        assertFalse(SectionParser().apply { feed("[[SAFETY]]CRISIS") }.finish().formatDisordered)
        val input = "[[SAFETY]]crisis[[SAFETY]]none[[REPLY]]好[[NEW_TAG]]未知[[ALT]]坏格式[[TIP]]只有一个字段[[SCENE]]bad[[REPLY"
        val answer = SectionParser().apply { input.forEach { feed(it.toString()) } }.finish()
        assertEquals(SafetyLevel.CRISIS, answer.safety)
        assertTrue(answer.freeText.contains("[[NEW_TAG]]未知"))
        assertTrue(answer.freeText.contains("坏格式"))
        assertTrue(answer.rawText.endsWith("[[REPLY"))
        assertEquals("无标记", SectionParser().apply { feed("无标记") }.finish().freeText)
    }
    @Test fun rewriteAndLateRevision() {
        val original = StructuredAnswer(id="a", verdict="原判断", reply=listOf("原回复"), safety=SafetyLevel.CAUTION)
        val request = AnalysisRequest(requestId="r", mode=RequestMode.REWRITE_REPLY, originalAnswer=original, inputRevision=2, conversationRevision=4)
        val incoming = StructuredAnswer(reply=listOf("新回复"), verdict="不采用", safety=SafetyLevel.NONE)
        assertNull(AnswerPolicy.apply(request, incoming, 3, 4))
        assertNull(AnswerPolicy.apply(request, incoming, 2, 5))
        val merged = AnswerPolicy.apply(request, incoming, 2, 4)!!
        assertEquals("原判断", merged.verdict)
        assertEquals(listOf("新回复"), merged.reply)
        assertEquals(SafetyLevel.CAUTION, merged.safety)
    }
    @Test fun threeStyleGroupsCountCodepoints() {
        val formal = StyleFeatureExtractor.extract(listOf("您好。", "谢谢您。"))
        assertTrue(formal.usesHonorific)
        assertEquals(3.5, formal.averageSentenceLength, 0.001)
        val casual = StyleFeatureExtractor.extract(listOf("好呀！😀", "来啦～😀"))
        assertEquals(2, casual.emojiCount)
        assertEquals(1.0, casual.emojiMessageRatio, 0.001)
        assertTrue(casual.particles.containsKey("呀"))
        val supplementaryHan = StyleFeatureExtractor.extract(listOf("𠮷野家", "好的"))
        assertEquals(0, supplementaryHan.emojiCount)
        assertEquals(2.5, supplementaryHan.averageSentenceLength, 0.001)
    }
    @Test fun memoryGatesAndLatestUndo() {
        val consent = ConsentState(enabled=true, consentVersion=2)
        val entry = MemoryEntry(id="m", contactId="p", scope=MemoryScope.USER, subjectId="self", field="name", value="虚构", sourceType=MemorySource.USER_EXPLICIT)
        MemoryPolicy.validateWrite(entry, consent, 2, "p", emptyList())
        assertRejected { MemoryPolicy.validateWrite(entry, consent.copy(paused=true), 2, "p", emptyList()) }
        assertRejected { MemoryPolicy.validateWrite(entry, consent, 1, "p", emptyList()) }
        assertRejected { MemoryPolicy.validateWrite(entry, consent, 2, null, emptyList()) }
        assertRejected { MemoryPolicy.validateWrite(entry.copy(sourceType=MemorySource.ASSISTANT_INFERENCE), consent, 2, "p", emptyList()) }
        assertRejected { MemoryPolicy.validateWrite(entry.copy(value="😀".repeat(201)), consent, 2, "p", emptyList()) }
        val operations = listOf(MemoryOperation(id="one"), MemoryOperation(id="two"))
        assertRejected { MemoryPolicy.undoLatest(emptyList(), operations, "one") }
        assertEquals(1, MemoryPolicy.undoLatest(emptyList(), operations, "two").operations.size)
    }
    @Test fun crisisAndLockAreExecutable() {
        assertTrue(SafetyPolicy.evaluate(8, SafetyLevel.NONE).calm)
        assertFalse(SafetyPolicy.evaluate(0, SafetyLevel.CRISIS).showOrdinaryStrategy)
        assertFalse(LockState(enabled=true, unlocked=false).canDisplaySensitiveContent)
        assertTrue(LockState().canDisplaySensitiveContent)
    }
    @Test fun modelCautionEntersCalmAtZeroEmotion() {
        assertTrue(SafetyPolicy.evaluate(0, SafetyLevel.CAUTION).calm)
    }
    @Test fun localCautionEntersCalmAtZeroEmotion() {
        assertTrue(SafetyPolicy.evaluate(0, SafetyLevel.NONE, localSafety = SafetyLevel.CAUTION).calm)
    }
    @Test fun retriesShareRequestButNeverDuplicateMessage() {
        val request=AnalysisRequest(requestId="r",conversationId="c",userText="虚构",inputRevision=1)
        val started=AnalysisLedger().begin(request,"user1").begin(request,"user2")
        assertEquals(1,started.messages.size)
        assertEquals(started,started.complete(request,StructuredAnswer(),"a",2,0))
        val complete=started.complete(request,StructuredAnswer(reply=listOf("答复")),"a",1,0)
        assertEquals(2,complete.messages.size)
        assertEquals(1,complete.runs.size)
        assertEquals(1,complete.messages.count { it.role==MessageRole.ASSISTANT })
        assertEquals(complete,complete.complete(request,StructuredAnswer(reply=listOf("重复")),"a2",1,0))
        assertEquals(setOf("r"),complete.messages.map { it.requestId }.toSet())
    }
    @Test fun scopedQuotaPruningAndOperationRetention() {
        val events=(0..20).map { MemoryEntry(id="$it",scope=MemoryScope.EVENT,subjectId="p",updatedAt=it.toLong()) }
        val bounded=MemoryPolicy.enforceQuotas(events)
        assertEquals(20,bounded.size)
        assertFalse(bounded.any { it.id=="0" })
        assertRejected { MemoryPolicy.enforceQuotas((0..30).map { MemoryEntry(id="$it",scope=MemoryScope.USER,subjectId="self") }) }
        assertEquals(20,MemoryPolicy.retainOperations((0..30).map { MemoryOperation(id="$it") }).size)
        assertRejected { MemoryPolicy.validateEntry(MemoryEntry(subjectId="x",field="f",value="v",scope=MemoryScope.EVENT,sourceType=MemorySource.ASSISTANT_INFERENCE)) }
        assertRejected { MemoryPolicy.validateDate("2026-02-30") }
        MemoryPolicy.validateDate("2026-10-28T20:00:00+08:00")
    }
    @Test fun allStyleOriginsRecheckConsentAndProvenance() {
        val consent=ConsentState(enabled=true,consentVersion=2)
        for(origin in StyleOrigin.entries) {
            val sample=StyleSample(origin=origin,consentVersion=2,sourceRevision=3,sourceContactId="p",sourceImportId="i",sourceMessageId="m",sourceAnswerId="a")
            StylePersistencePolicy.validate(sample,consent,3,false)
            assertRejected { StylePersistencePolicy.validate(sample,consent.copy(enabled=false),3,false) }
            assertRejected { StylePersistencePolicy.validate(sample,consent.copy(paused=true),3,false) }
            assertRejected { StylePersistencePolicy.validate(sample,consent.copy(consentVersion=3),3,false) }
            assertRejected { StylePersistencePolicy.validate(sample,consent,4,false) }
            assertRejected { StylePersistencePolicy.validate(sample,consent,3,true) }
            if(origin!=StyleOrigin.QUIZ) assertRejected { StylePersistencePolicy.validate(sample.copy(sourceContactId=null),consent,3,false) }
        }
    }
    @Test fun outOfOrderDuplicatesAndTruncationRemainReadable() {
        val parser=SectionParser()
        parser.feed("[[WHY]]原因[[REPLY]]甲[[REPLY]]乙[[SCENE]]不完整[[MEMORY]][{bad[[ALT]]标签|回复[[TIP]]技巧|说明|例子[[UNKNOWN]]额外[[")
        val answer=parser.finish()
        assertEquals(listOf("甲","乙"),answer.reply)
        assertEquals(listOf("原因"),answer.why)
        assertTrue(answer.freeText.contains("不完整"))
        assertTrue(answer.freeText.contains("[{bad"))
        assertTrue(answer.freeText.contains("[[UNKNOWN]]额外[["))
        assertEquals(answer,parser.finish())
    }
    @Test fun sourceMatrixMatchesPythonPolicy() {
        for(scope in MemoryScope.entries) for(source in MemorySource.entries) {
            val allowed=when(scope) {
                MemoryScope.USER -> source==MemorySource.USER_EXPLICIT
                MemoryScope.OBJECT,MemoryScope.RELATIONSHIP -> source in setOf(MemorySource.USER_EXPLICIT,MemorySource.USER_REPORT)
                MemoryScope.EVENT -> source!=MemorySource.ASSISTANT_INFERENCE
                MemoryScope.HYPOTHESIS -> true
            }
            val entry=MemoryEntry(subjectId="p",field="f",value="😀".repeat(200),scope=scope,sourceType=source)
            if(allowed) MemoryPolicy.validateEntry(entry) else assertRejected { MemoryPolicy.validateEntry(entry) }
        }
    }
    @Test fun promptCannotBePreparedWithoutKnowledgeAndStableDefaultsRoundTrip() {
        val json=kotlinx.serialization.json.Json
        val request=AnalysisRequest()
        assertEquals(request,json.decodeFromString(AnalysisRequest.serializer(),json.encodeToString(AnalysisRequest.serializer(),request)))
        try {
            PreparedPrompt("system","user",emptyList(),"v1")
            fail("knowledge required")
        } catch (_:IllegalArgumentException) { }
        val reference=KnowledgeReference("references/example.md","a".repeat(64),10)
        assertEquals(1,PreparedPrompt("system","user",listOf(reference),"v1").references.size)
    }
    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: MemoryWriteRejected) { }
    }
}
