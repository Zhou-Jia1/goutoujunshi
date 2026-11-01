package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.data.ocr.OcrAdmission
import com.shengjidaguai.goutoujunshi.data.ocr.OcrEngineLease
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OcrRuntimeSafetyTest {
    @Test fun cancellationDuringColdLoadKeepsResourceReachableForExactlyOneRelease() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finishLoad = CompletableDeferred<Unit>()
        val resource = Any()
        var allocated = 0
        var released = 0
        var inferred = false
        val lease = OcrEngineLease(create = {
            // Match SDK create: a cancellable dispatcher hop around blocking native allocation.
            withContext(Dispatchers.IO) {
                withContext(NonCancellable) {
                    started.complete(Unit); finishLoad.await(); allocated++; resource
                }
            }
        }, release = { assertSame(resource, it); released++ })
        val work = launch(Dispatchers.Default) { lease.use { inferred = true } }
        try {
            withTimeout(5_000) { started.await() }
            work.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { lease.close() }
            assertFalse(closing.isCompleted)
            assertEquals(0, released)
            finishLoad.complete(Unit)
            withTimeout(5_000) { work.join(); closing.await() }
            assertFalse(inferred)
            assertEquals(1, allocated)
            assertEquals(1, released)
            lease.close()
            assertEquals(1, released)
        } finally { finishLoad.complete(Unit); work.cancelAndJoin(); lease.close() }
    }

    @Test fun cancellationDoesNotReleaseResourceBeforeBlockingInferenceReturns() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finishNative = CompletableDeferred<Unit>()
        var released = 0
        val lease = OcrEngineLease(create = { Any() }, release = { released++ })
        val work = launch(Dispatchers.Default) {
            lease.use {
                withContext(NonCancellable) { entered.complete(Unit); finishNative.await() }
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            work.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { lease.close() }
            assertFalse(closing.isCompleted)
            assertEquals(0, released)
            finishNative.complete(Unit)
            withTimeout(5_000) { work.join(); closing.await() }
            assertEquals(1, released)
        } finally { finishNative.complete(Unit); work.cancelAndJoin(); lease.close() }
    }

    @Test fun concurrentAdmissionRetainsAtMostTwoInputsAndDoubleCloseCannotAddCapacity() {
        val admission = OcrAdmission(2)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val held = mutableListOf<OcrAdmission.Permit>()
        try {
            val futures = (1..32).map {
                pool.submit<OcrAdmission.Permit?> { start.await(); admission.tryAcquire() }
            }
            start.countDown()
            held += futures.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, held.size)
            assertNull(admission.tryAcquire())
            held.forEach { it.close(); it.close() }
            held.clear()
            repeat(2) { held += requireNotNull(admission.tryAcquire()) }
            assertNull(admission.tryAcquire())
        } finally { start.countDown(); held.forEach { it.close() }; pool.shutdownNow() }
    }
}
