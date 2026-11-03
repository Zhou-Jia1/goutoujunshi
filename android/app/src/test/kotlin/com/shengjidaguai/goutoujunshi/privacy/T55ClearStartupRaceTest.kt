package com.shengjidaguai.goutoujunshi.privacy

import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest
import com.shengjidaguai.goutoujunshi.domain.Contact
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Test
import org.junit.Assert.*

class T55ClearStartupRaceTest {
    private fun exercise(load:Boolean) = runBlocking {
        val f=CounselControllerTest.Fixture()
        val gate=CompletableDeferred<Unit>()
        f.persistence.people+=Contact("synthetic-old","合成旧联系人")
        if(load) f.persistence.beforeLoad=gate else f.persistence.beforeContacts=gate
        var clearing=false
        val published=mutableListOf<String>()
        // Observe each synchronous publication, rather than a conflated event-loop sample.
        val observer=f.scope.launch(object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable)=block.run()
        },start=CoroutineStart.UNDISPATCHED) {f.controller.state.collect {state ->
            if(clearing && state.lockState.canDisplaySensitiveContent) {
                published+=state.contacts.map {it.id};state.currentContact?.let {published+=it.id}
            }
        }}
        try {
            f.controller.initialize()
            clearing=true;f.controller.clearAll()
            gate.complete(Unit)
            yield()
            assertTrue("clear must suppress every old startup publication: $published",published.isEmpty())
        } finally {observer.cancel();f.close()}
    }
    @Test fun suspendedInitialContactsCannotReappearDuringClear()=exercise(false)
    @Test fun suspendedInitialLoadCannotReappearDuringClear()=exercise(true)
}
