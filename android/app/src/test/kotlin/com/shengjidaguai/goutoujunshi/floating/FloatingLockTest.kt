package com.shengjidaguai.goutoujunshi.floating

import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest
import com.shengjidaguai.goutoujunshi.importing.ImportCoordinator
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FloatingLockTest {
    @Test fun lockingInvalidatesFrozenPreviewAndAllImportSources() = runBlocking {
        val fixture=CounselControllerTest.Fixture()
        try {
            val controller=fixture.controller
            controller.initialize(); controller.editDraft("虚构询问")
            controller.preparePreview()
            withTimeout(2000) { while(controller.state.value.preview==null) yield() }
            val imports=ImportCoordinator(controller,fixture.scope,{true},{})
            imports.text("虚构甲：虚构内容")
            assertNotNull(imports.state.value)
            val revision=controller.lockRevision
            controller.updateLockState(LockState(enabled=true,unlocked=false))
            yield()
            assertTrue(controller.lockRevision>revision)
            assertNull(controller.state.value.preview)
            assertNull(controller.state.value.outboundBody)
            assertNull(imports.state.value)
            assertFalse(floatingContentAllowed(controller.state.value.lockState))
        } finally { fixture.close() }
    }
}
