package com.shengjidaguai.goutoujunshi.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TemporarySessionStoreTest {
    @Test fun clearRevokesOldCallbacksEvenWhenTheSameSessionIdIsExplicitlyReused() = runBlocking {
        val store = TemporarySessionStore()
        val old = store.begin("same-id")
        val screenshot = byteArrayOf(1, 2, 3)
        assertTrue(store.put(old, TemporarySession("same-id", draft = "合成秘密", screenshot = screenshot)))
        store.clear()
        assertArrayEquals(byteArrayOf(0, 0, 0), screenshot)
        assertFalse(store.put(old, TemporarySession("same-id", draft = "旧模型迟到")))
        assertNull(store.get(old))
        val fresh = store.begin("same-id")
        assertFalse(store.put(old, TemporarySession("same-id", draft = "旧回调再次迟到")))
        assertTrue(store.put(fresh, TemporarySession("same-id", draft = "用户显式开始")))
        assertEquals("用户显式开始", store.get(fresh)?.draft)
        assertNull(TemporarySessionStore().get(fresh))
        assertFalse(store.put(fresh, TemporarySession("another-id")))
        assertTrue(store.remove(fresh))
        assertFalse(store.put(fresh, TemporarySession("same-id")))
    }
}
