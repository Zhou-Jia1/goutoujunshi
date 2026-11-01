package com.shengjidaguai.goutoujunshi.ui

import org.junit.Assert.*
import org.junit.Test
import com.shengjidaguai.goutoujunshi.domain.MemoryScope

class MemoryModalGateTest {
    @Test fun sameModalAcceptsRepeatedGroupAndSameFrameInputsThenFreezesLatestPayload() {
        val gate=MemoryModalGate()
        val buffer=MemoryEditorBuffer(gate.issue())
        val input={ value: String -> if(gate.accepts(buffer.token)) buffer.replace(value) }
        val choose={ if(gate.accepts(buffer.token)) buffer.select(MemoryScope.USER) }
        val confirm={ if(gate.accepts(buffer.token)) buffer.freeze() else null }
        choose();choose() // identical selection, no recomposition or modal-token change required
        input("同帧第一个值");input("同帧第二个值")
        val submitted=confirm()!! // immediately after input, without a frame or another closure
        assertEquals("同帧第二个值",submitted.value);assertEquals(MemoryScope.USER,submitted.group)
        input("提交后不得悄悄改重试载荷")
        assertEquals(submitted,confirm());assertEquals(submitted.value,buffer.value)
        assertTrue(gate.accepts(buffer.token))
        gate.revoke() // cancel remains reachable even after selecting the same group twice
        assertNull(confirm())
        val later=MemoryEditorBuffer(gate.issue())
        later.replace("后来表单")
        input("旧表单回调")
        assertEquals("后来表单",later.value);assertFalse(gate.accepts(buffer.token))
    }
    @Test fun invalidValuesDoNotFreezeAndCanBeCorrectedBeforeAnySubmission() {
        for(invalid in listOf("虚".repeat(201),"虚构\u0001内容")) {
            val buffer=MemoryEditorBuffer(1)
            buffer.select(MemoryScope.USER);buffer.replace(invalid)
            assertNull(buffer.freeze());assertNull(buffer.submitted)
            val valid="😀".repeat(200) // code points, not UTF-16 code units
            buffer.replace(valid)
            assertEquals(valid,buffer.freeze()!!.value)
        }
    }
    @Test fun closedModalCallbacksCannotActOnAnotherDialog() {
        val gate=MemoryModalGate()
        val shown=gate.issue()
        var effects=0
        val oldConfirm={ if(gate.accepts(shown)) effects++ }
        gate.revoke();gate.issue()
        oldConfirm();assertEquals(0,effects)
    }
}
