package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class T51TeachingControllerTest {
    private class Persistence(val base:CounselControllerTest.MemoryPersistence=CounselControllerTest.MemoryPersistence()):CounselPersistence by base {
        var favorites=listOf<Tip>()
        var gate:CompletableDeferred<Unit>?=null
        var entered=false
        var writes=0
        var habitWrites=0
        private val days=mutableSetOf<String>()
        override suspend fun habit(request:AnalysisRequest,answerId:String,day:String,disabled:String?,current:()->Boolean):Boolean {
            check(current());val key="${request.contactId}:$day";val shown=days.add(key);if(shown) habitWrites++;return shown
        }
        var tipsGate:CompletableDeferred<Unit>?=null
        var tipsEntered:CompletableDeferred<Unit>?=null
        override suspend fun tips():List<Tip> {tipsEntered?.complete(Unit);tipsGate?.await();return favorites}
        override suspend fun favorite(tip:Tip,request:AnalysisRequest?,answerId:String,saved:Boolean,current:()->Boolean) {
            entered=true;gate?.await();check(current())
            favorites=if(saved) favorites.filterNot {it.name==tip.name}+tip else favorites.filterNot {it.name==tip.name}
            writes++
        }
    }
    private class Fixture(real:Boolean=false,val persistence:Persistence=Persistence()) {
        val provider=CounselControllerTest.ControlledProvider()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=CounselController(persistence,TemporarySessionStore(),object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("合成系统","合成提示",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")
        },if(real) object:AnalysisProvider by provider {override val kind=ProviderKind.BYOK} else provider,scope, localSafety={com.shengjidaguai.goutoujunshi.domain.SafetyLevel.NONE})
        suspend fun generate(id:String,name:String="技巧$id") {
            val previousReceipt=controller.state.value.teaching.receipt
            controller.editDraft("合成问题$id");await {controller.state.value.draft=="合成问题$id"}
            val before=provider.calls.size
            controller.analyze();await {provider.calls.size==before+1}
            provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(id=id,reply=listOf("我理解，明天再聊。"),tips=listOf(Tip(name=name,explanation="先理解再说明",example="我理解")),habit="演示建议")))
            await {controller.state.value.teaching.answerId==id && controller.state.value.teaching.receipt!=previousReceipt}
        }
    }
    companion object {private suspend fun await(predicate:()->Boolean)=withTimeout(5_000) {while(!predicate()) yield()}}
    @Test fun demoOffersTipWithoutHabitOrLearningAndOldAnswerCannotSaveNewTip()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();await {!f.controller.state.value.loading};f.generate("a")
            assertNull(f.controller.state.value.teaching.habit)
            val origin=f.controller.state.value.conversation!!.id
            val firstReceipt=f.controller.state.value.teaching.receipt
            f.controller.toggleTip(origin,firstReceipt);await {f.persistence.writes==1}
            assertTrue(f.controller.state.value.teaching.saved)
            f.controller.toggleTip(origin,firstReceipt);await {f.persistence.writes==2};assertTrue(f.persistence.favorites.isEmpty())
            f.generate("a","技巧b");f.controller.toggleTip(origin,firstReceipt);yield()
            assertEquals(2,f.persistence.writes);assertEquals("技巧b",f.controller.state.value.teaching.tip!!.name)
        } finally {f.scope.cancel()}
    }
    @Test fun restoredFormalTipSurvivesAToBToAWithoutReplayingHabit()=runBlocking {
        val f=Fixture(true)
        try {
            f.persistence.base.people+=listOf(Contact("a","合成甲"),Contact("b","合成乙"))
            f.controller.initialize();await {!f.controller.state.value.loading}
            f.controller.selectContact("a");await {f.controller.state.value.currentContact?.id=="a"}
            f.generate("formal-tip")
            val original=f.controller.state.value.teaching
            f.controller.toggleTip(f.controller.state.value.conversation!!.id,original.receipt);await {f.persistence.writes==1}
            f.controller.selectContact("b");await {f.controller.state.value.currentContact?.id=="b"}
            f.persistence.favorites=emptyList()
            f.controller.selectContact("a");await {f.controller.state.value.currentContact?.id=="a"}
            val state=f.controller.state.value
            assertTrue(state.hasCurrentAnswerSource())
            assertEquals("技巧formal-tip",state.teaching.tip?.name)
            assertNotEquals(original.receipt,state.teaching.receipt)
            assertFalse(state.teaching.saved);assertNull(state.teaching.habit);assertEquals(1,f.persistence.habitWrites)
            f.controller.toggleTip(state.conversation!!.id,original.receipt);yield();assertEquals(1,f.persistence.writes)
        } finally {f.scope.cancel()}
    }
    @Test fun newControllerRestoresCompletedFormalTipAndFreshFavoriteState()=runBlocking {
        val f=Fixture(true)
        var reopened:Fixture?=null
        try {
            f.persistence.base.people+=Contact("a","合成甲")
            f.controller.initialize();await {!f.controller.state.value.loading}
            f.controller.selectContact("a");await {f.controller.state.value.currentContact?.id=="a"};f.generate("formal-tip")
            val old=f.controller.state.value.teaching
            f.controller.toggleTip(f.controller.state.value.conversation!!.id,old.receipt);await {f.persistence.writes==1}
            f.scope.cancel()
            val next=Fixture(true,f.persistence);reopened=next
            next.controller.initialize();await {!next.controller.state.value.loading}
            next.controller.selectContact("a");await {next.controller.state.value.currentContact?.id=="a"}
            val state=next.controller.state.value
            assertTrue(state.hasCurrentAnswerSource())
            assertEquals("技巧formal-tip",state.teaching.tip?.name)
            assertTrue(state.teaching.saved);assertNotEquals(old.receipt,state.teaching.receipt)
            assertNull(state.teaching.habit);assertEquals(1,f.persistence.habitWrites)
            assertTrue(next.provider.calls.isEmpty())
        } finally {f.scope.cancel();reopened?.scope?.cancel()}
    }
    @Test fun unlockRestoresOnlyCurrentTipWithFreshReceiptWithoutHabitConsumption()=runBlocking {
        val f=Fixture(true)
        try {
            f.persistence.base.people+=Contact("a","合成甲")
            f.controller.initialize();await {!f.controller.state.value.loading}
            f.controller.selectContact("a");await {f.controller.state.value.currentContact?.id=="a"};f.generate("formal-tip")
            val old=f.controller.state.value.teaching
            f.controller.updateLockState(LockState(true,false));assertNull(f.controller.state.value.teaching.tip)
            f.controller.updateLockState(LockState(true,true));f.controller.openTips();await {f.controller.state.value.tipsReceipt!=null}
            val state=f.controller.state.value
            assertTrue(state.hasCurrentAnswerSource());assertEquals("技巧formal-tip",state.teaching.tip?.name)
            assertNotEquals(old.receipt,state.teaching.receipt);assertNull(state.teaching.habit);assertEquals(1,f.persistence.habitWrites)
        } finally {f.scope.cancel()}
    }
    @Test fun suspendedRestorationCannotPublishAfterSwitchLockClearOrSourceRevocation()=runBlocking {
        for(revoke in listOf("switch","lock","clear","source")) {
            val f=Fixture(true)
            try {
                f.persistence.base.people+=listOf(Contact("a","合成甲"),Contact("b","合成乙"))
                f.controller.initialize();await {!f.controller.state.value.loading}
                f.controller.selectContact("a");await {f.controller.state.value.currentContact?.id=="a"};f.generate("formal-tip")
                f.controller.selectContact("b");await {f.controller.state.value.currentContact?.id=="b"}
                f.persistence.tipsGate=CompletableDeferred();f.persistence.tipsEntered=CompletableDeferred()
                f.controller.selectContact("a");f.persistence.tipsEntered!!.await()
                when(revoke) {
                    "switch"->f.controller.selectContact("b")
                    "lock"->f.controller.updateLockState(LockState(true,false))
                    "clear"->f.controller.clearAll()
                    "source"->f.persistence.base.people.replaceAll {if(it.id=="a") it.copy(sourceRevision=it.sourceRevision+1) else it}
                }
                f.persistence.tipsGate!!.complete(Unit);f.persistence.tipsGate=null;yield()
                if(revoke=="switch") await {f.controller.state.value.currentContact?.id=="b"}
                if(revoke=="clear") await {!f.controller.state.value.loading && f.controller.state.value.answer==null}
                assertNull("revocation=$revoke",f.controller.state.value.teaching.tip)
                assertEquals(1,f.provider.calls.size)
            } finally {f.scope.cancel()}
        }
    }
    @Test fun realFormalHabitIsConsumedOnlyOnceAcrossFreshAnswersAndTemporaryNeverUsesFormalMetadata()=runBlocking {
        val f=Fixture(true)
        try {
            f.persistence.base.people+=Contact("formal","合成正式")
            f.controller.initialize();await {!f.controller.state.value.loading}
            f.controller.selectContact("formal");await {f.controller.state.value.currentContact?.id=="formal"}
            f.generate("first");assertNotNull(f.controller.state.value.teaching.habit);assertEquals(1,f.persistence.habitWrites)
            f.generate("second");assertNull(f.controller.state.value.teaching.habit);assertEquals(1,f.persistence.habitWrites)
            f.controller.newTemporary();await {f.controller.state.value.currentContact==null}
            f.generate("temporary");assertNotNull(f.controller.state.value.teaching.habit);assertEquals(1,f.persistence.habitWrites)
            f.generate("temporary-again");assertNull(f.controller.state.value.teaching.habit);assertEquals(1,f.persistence.habitWrites)
        } finally {f.scope.cancel()}
    }
    @Test fun revokedTemporaryResultCannotConsumeHabitAfterLockAndUnlock()=runBlocking {
        val f=Fixture(true)
        try {
            f.controller.initialize();await {!f.controller.state.value.loading}
            f.persistence.tipsGate=CompletableDeferred();f.persistence.tipsEntered=CompletableDeferred()
            f.controller.editDraft("合成旧问题");await {f.controller.state.value.draft=="合成旧问题"}
            f.controller.analyze();await {f.provider.calls.size==1}
            f.provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(id="stale",reply=listOf("我理解"),tips=listOf(Tip(name="旧技巧",explanation="解释",example="我理解")),habit="旧提醒")))
            f.persistence.tipsEntered!!.await()
            f.controller.updateLockState(LockState(true,false));f.controller.updateLockState(LockState(true,true))
            f.persistence.tipsGate!!.complete(Unit);f.persistence.tipsGate=null
            f.generate("current")
            assertNotNull(f.controller.state.value.teaching.habit)
            assertEquals("技巧current",f.controller.state.value.teaching.tip!!.name)
        } finally {f.scope.cancel()}
    }
    @Test fun lockRevokesSuspendedFavoriteBeforeCommit()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();await {!f.controller.state.value.loading};f.generate("a")
            f.persistence.gate=CompletableDeferred()
            f.controller.toggleTip(f.controller.state.value.conversation!!.id,f.controller.state.value.teaching.receipt);await {f.persistence.entered}
            f.controller.updateLockState(LockState(true,false));f.persistence.gate!!.complete(Unit)
            yield();assertEquals(0,f.persistence.writes);assertNull(f.controller.state.value.teaching.tip)
        } finally {f.scope.cancel()}
    }
    @Test fun clearRevokesSuspendedFavoriteAndErasesBookState()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();await {!f.controller.state.value.loading};f.generate("a")
            f.persistence.gate=CompletableDeferred()
            f.controller.toggleTip(f.controller.state.value.conversation!!.id,f.controller.state.value.teaching.receipt);await {f.persistence.entered}
            f.controller.clearAll();f.persistence.gate!!.complete(Unit)
            await {!f.controller.state.value.loading && f.controller.state.value.answer==null}
            assertEquals(0,f.persistence.writes);assertTrue(f.controller.state.value.savedTips.isEmpty())
        } finally {f.scope.cancel()}
    }
}
