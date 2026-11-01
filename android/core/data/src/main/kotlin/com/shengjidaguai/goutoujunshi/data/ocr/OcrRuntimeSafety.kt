package com.shengjidaguai.goutoujunshi.data.ocr

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps a newly allocated native resource reachable even if its caller cancels mid-load. */
internal class OcrEngineLease<T : Any>(
    private val create: suspend () -> T,
    private val release: suspend (T) -> Unit,
) {
    private val mutex = Mutex()
    private var engine: T? = null
    private var closed = false

    suspend fun <R> use(block: suspend (T) -> R): R = mutex.withLock {
        check(!closed)
        val current = engine ?: withContext(NonCancellable) {
            create().also { engine = it }
        }
        currentCoroutineContext().ensureActive()
        block(current)
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            closed = true
            val current = engine
            engine = null
            if (current != null) release(current)
        }
    }
}

/** Atomic, nonblocking admission; each held input has one idempotent permit. */
internal class OcrAdmission(limit: Int) {
    private val slots = Semaphore(limit.also { require(it > 0) })

    fun tryAcquire(): Permit? = if (slots.tryAcquire()) Permit() else null

    inner class Permit internal constructor() : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) slots.release() }
    }
}
