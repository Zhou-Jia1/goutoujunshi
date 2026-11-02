package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.MemorySnapshot
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.importing.ImportCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class T33ColdContextReadTest {
    @Test fun coldCaptureDoesNotImplicitlyOpenGlobalMemoryManagementPage()=runBlocking {
        val base=CounselControllerTest.MemoryPersistence()
        base.people+=Contact("candidate","合成候选")
        var managementReads=0;var detailReads=0;var contextReads=0
        val p=object:CounselPersistence by base {
            override suspend fun memorySnapshot(owner:String?):MemorySnapshot { managementReads++;return MemorySnapshot() }
            override suspend fun memoryContextSnapshot(owner:String?):MemorySnapshot { contextReads++;assertNull(owner);return MemorySnapshot() }
            override suspend fun contactDetails(contact:Contact):com.shengjidaguai.goutoujunshi.data.ContactDetails { detailReads++;return base.contactDetails(contact) }
        }
        val f=CounselControllerTest.Fixture(persistence=base)
        // Use the real controller with tracked persistence, preserving its existing assembler/provider seams.
        val c=CounselController(p,com.shengjidaguai.goutoujunshi.data.TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("合成系统","合成输入",listOf(KnowledgeReference("synthetic","a".repeat(64),1)),"v1")
        }),f.provider,f.scope)
        try {
            val imports=ImportCoordinator(c,f.scope,{true},{})
            assertNotNull(imports.beginCapture(77) {true})
            assertEquals(0,managementReads);assertEquals(0,detailReads);assertTrue(contextReads>0)
            c.openMemory(null)
            assertTrue(managementReads>0);assertTrue(detailReads>0)
        } finally { f.close() }
    }
}
