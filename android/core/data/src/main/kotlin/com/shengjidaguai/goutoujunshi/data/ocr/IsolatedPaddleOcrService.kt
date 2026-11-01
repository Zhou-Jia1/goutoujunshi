package com.shengjidaguai.goutoujunshi.data.ocr

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Process
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Only this isolated component may create an OCR engine. Inputs/results remain in RAM. */
class IsolatedPaddleOcrService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val admission = OcrAdmission(2)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val engineLease = OcrEngineLease(create = {
        check(OpenCVUtils.init(applicationContext))
        PaddleOCR.create(applicationContext, PaddleOCRConfig(), EngineConfig(numThreads = 2),
            "ocr/paddle/det.onnx", "ocr/paddle/rec.onnx", "ocr/paddle/rec.yml")
    }, release = PaddleOCR::release)

    private fun internetDenied() = checkPermission(Manifest.permission.INTERNET,
        Process.myPid(), Process.myUid()) != PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        check(internetDenied()) { "OCR requires isolated process" }
    }

    private val binder = object : IIsolatedPaddleOcr.Stub() {
        override fun isolationStatus(): String {
            val socketDenied = try {
                Socket().use { it.bind(InetSocketAddress("127.0.0.1", 0)) }; false
            } catch (_: Exception) { true }
            return JSONObject().put("uid", Process.myUid()).put("internetDenied", internetDenied())
                .put("socketDenied", socketDenied).toString()
        }

        override fun recognize(requestId: Long, bitmap: Bitmap, callback: IPaddleOcrCallback) {
            if (!internetDenied() || requestId <= 0 || bitmap.isRecycled ||
                bitmap.width.toLong()*bitmap.height > 12_000_000L) {
                bitmap.recycle(); respond(callback, requestId, JSONObject().put("status", "UNAVAILABLE")); return
            }
            val permit = admission.tryAcquire()
            if (permit == null) {
                bitmap.recycle(); respond(callback, requestId, JSONObject().put("status", "BUSY")); return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val response = engineLease.use { ocr ->
                        ensureActive()
                        val result = ocr.recognize(bitmap)
                        ensureActive()
                        val rows = JSONArray()
                        var characters = 0
                        for (line in result.results) {
                            characters += line.text.length
                            check(characters <= 50_000 && rows.length() < 1_000)
                            val points = JSONArray()
                            line.box.points.forEach { points.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
                            rows.put(JSONObject().put("text", line.text).put("confidence", line.confidence.toDouble())
                                .put("points", points))
                        }
                        JSONObject().put("status", "OK").put("lines", rows)
                    }
                    respond(callback, requestId, response)
                } catch (_: CancellationException) {
                    respond(callback, requestId, JSONObject().put("status", "CANCELLED"))
                } catch (_: Exception) {
                    respond(callback, requestId, JSONObject().put("status", "UNAVAILABLE"))
                } catch (_: LinkageError) {
                    respond(callback, requestId, JSONObject().put("status", "UNAVAILABLE"))
                }
            }
            if (jobs.putIfAbsent(requestId, job) != null) {
                job.cancel(); bitmap.recycle(); permit.close()
                respond(callback, requestId, JSONObject().put("status", "BUSY")); return
            }
            // This also runs when a queued/lazy job is cancelled before its body starts.
            // Completion occurs only after any blocking native call has returned.
            job.invokeOnCompletion {
                try { bitmap.recycle() } finally { jobs.remove(requestId, job); permit.close() }
            }
            job.start()
        }

        override fun cancel(requestId: Long) { jobs[requestId]?.cancel() }
    }

    private fun respond(callback: IPaddleOcrCallback, requestId: Long, response: JSONObject) {
        try { callback.complete(requestId, response.toString()) } catch (_: Exception) { /* Client has gone. */ }
    }

    override fun onBind(intent: Intent) = binder

    override fun onDestroy() {
        scope.cancel()
        // Native inference is blocking. Release only after its mutex is relinquished.
        CoroutineScope(Dispatchers.IO).launch {
            engineLease.close()
        }
        super.onDestroy()
    }
}
