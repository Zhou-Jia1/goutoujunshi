package com.shengjidaguai.goutoujunshi.importing

import android.app.Application
import android.net.Uri
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest.Fixture
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class PaddleImportTest {
    private val uris=listOf(Uri.parse("content://synthetic/1"),Uri.parse("content://synthetic/2"))
    private fun line(text:String,confidence:Float=.99f)=OcrLine(text,confidence,.7f,.4f,.9f,.45f)
    @Test fun explicitSideLowConfidenceAndRightsGateBeforeTemporaryCommit()=runBlocking {
        val f=Fixture(); f.controller.initialize()
        val c=ImportCoordinator(f.controller,f.scope,{false},{}, { listOf(listOf(line("合成重复"),line("合成重复")),listOf(line("合成重复"),line("合成尾行",.5f))) })
        try {
            c.images(uris);c.recognize()
            val p=c.state.value!!
            assertTrue(p.recognized); assertEquals(4,p.rows.size);assertEquals(1,p.rows.count { it.excluded })
            c.target(ImportTarget());c.acceptRights();c.confirm()
            assertTrue(f.controller.state.value.evidence.isEmpty())
            assertFalse(c.state.value!!.canConfirm)
            c.side(p.id,OcrSide.RIGHT)
            assertFalse(c.state.value!!.canConfirm)
            val low=c.state.value!!.rows.last()
            c.editRow(p.id,low.id) { it.copy(reviewed=true,text="合成修正") }
            assertTrue(c.state.value!!.canConfirm)
            c.confirm()
            assertNull(c.state.value)
            assertTrue(f.persistence.imports.isEmpty())
            val lines=f.controller.state.value.evidence.single().lines
            assertEquals(3,lines.size);assertEquals(.5f,lines.last().ocrConfidence)
            assertTrue(lines.all { it.speaker==Speaker.ME && it.speakerConfirmed })
        } finally { c.cancel();f.close() }
    }
    @Test fun stableImageAndRowIdentityRejectOldPreviewAndSurviveOrderChanges()=runBlocking {
        val f=Fixture();f.controller.initialize()
        val c=ImportCoordinator(f.controller,f.scope,{true},{},{ listOf(listOf(line("第一张")),listOf(line("第二张"))) })
        try {
            c.images(uris);c.recognize();val p=c.state.value!!
            c.moveImage(p.id,p.imageIds.first(),1)
            assertEquals(uris.reversed(),c.state.value!!.images)
            assertEquals(1,c.state.value!!.imageLabels[p.imageIds.first()])
            c.editRow(p.id,p.rows.first().id) { it.copy(text="第一张改好") }
            assertEquals("第一张改好",c.state.value!!.rows.last().text)
            c.images(uris)
            c.moveImage(p.id,p.imageIds.first(),1)
            c.editRow(p.id,p.rows.first().id) { it.copy(text="旧回调") }
            c.side(p.id,OcrSide.LEFT)
            assertTrue(c.state.value!!.rows.isEmpty());assertNull(c.state.value!!.mySide)
        } finally { c.cancel();f.close() }
    }
    @Test fun cancelledAndChangedOwnerDiscardLateResults()=runBlocking {
        for(changeOwner in listOf(false,true)) {
            val f=Fixture();f.controller.initialize();val gate=CompletableDeferred<Unit>()
            val c=ImportCoordinator(f.controller,f.scope,{true},{},{ withContext(NonCancellable) { gate.await() };listOf(listOf(line("迟到")),emptyList()) })
            try {
                c.images(uris);c.recognize()
                if(changeOwner) f.controller.newTemporary() else c.cancel()
                gate.complete(Unit)
                withTimeout(3000) { while(c.state.value!=null) yield() }
                assertNull(c.state.value)
                assertTrue(f.controller.state.value.evidence.isEmpty())
            } finally { c.cancel();f.close() }
        }
    }
    @Test fun groupNicknameRelationsAndRestoredOverlapSurviveReorder()=runBlocking {
        val f=Fixture();f.controller.initialize()
        val left=line("合成相同").copy(left=.1f,right=.3f,nickname="合成甲")
        val c=ImportCoordinator(f.controller,f.scope,{true},{},{ listOf(listOf(left),listOf(left,left.copy(text="合成不同",nickname="合成乙"))) })
        try {
            c.images(uris);c.recognize();val p=c.state.value!!;c.side(p.id,OcrSide.RIGHT)
            assertEquals(Speaker.OTHER,c.state.value!!.rows.first().speaker)
            val duplicate=c.state.value!!.rows.first { it.excluded }
            c.editRow(p.id,duplicate.id) { it.copy(excluded=false,exclusionOverride=false) }
            c.moveImage(p.id,p.imageIds.first(),1);c.moveImage(p.id,p.imageIds.first(),-1)
            assertFalse(c.state.value!!.rows.first { it.id==duplicate.id }.excluded)
            assertTrue(c.state.value!!.rows.all { it.speaker==Speaker.OTHER })
        } finally { c.cancel();f.close() }
    }
    @Test fun equalTextAndSideFromDifferentGroupNamesRemainRealMessages()=runBlocking {
        val f=Fixture();f.controller.initialize()
        val a=line("合成收到").copy(nickname="合成甲")
        var nextName:String?="合成乙"
        val c=ImportCoordinator(f.controller,f.scope,{true},{},{ listOf(listOf(a),listOf(a.copy(nickname=nextName))) })
        try {
            for(name in listOf("合成乙",null)) {
                nextName=name;c.images(uris);c.recognize()
                assertEquals(2,c.state.value!!.rows.count { !it.excluded })
            }
        } finally { c.cancel();f.close() }
    }
    @Test fun unavailableKeepsRamOrderAndSupportsRetry()=runBlocking {
        val f=Fixture();f.controller.initialize();var count=0
        val c=ImportCoordinator(f.controller,f.scope,{true},{},{ if(++count==1) error("synthetic failure");listOf(listOf(line("恢复")),emptyList()) })
        try {
            c.images(uris);c.recognize();assertEquals("OCR_UNAVAILABLE",c.state.value!!.error)
            assertEquals(uris,c.state.value!!.images);c.retry()
            assertTrue(c.state.value!!.recognized);assertNull(c.state.value!!.error)
        } finally { c.cancel();f.close() }
    }
}
