package com.shengjidaguai.goutoujunshi.data.ocr

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic fixtures only. Invoke this class explicitly on the task-owned emulator. */
@RunWith(AndroidJUnit4::class)
class PaddleOcrIsolationTest {
    @Test fun bundledModelsRecognizeSixFixturesWithoutNetworkPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkPermission(Manifest.permission.INTERNET, Process.myPid(), Process.myUid()))
        val ready = CountDownLatch(1)
        var service: IIsolatedPaddleOcr? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IIsolatedPaddleOcr.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        assertTrue(context.bindService(Intent(context, IsolatedPaddleOcrService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("OCR service connection timeout", ready.await(20, TimeUnit.SECONDS))
            val isolation = JSONObject(service!!.isolationStatus())
            assertNotEquals(Process.myUid(), isolation.getInt("uid"))
            assertTrue(isolation.getBoolean("internetDenied"))
            assertTrue(isolation.getBoolean("socketDenied"))
            for ((index, name) in listOf("light_1", "light_2", "dark_1", "dark_2", "long", "group").withIndex()) {
                val bitmap = instrumentation.context.assets.open("ocr/$name.png").use(BitmapFactory::decodeStream)!!
                val done = CountDownLatch(1)
                var response = ""
                val started = SystemClock.elapsedRealtime()
                try {
                    service!!.recognize(index.toLong() + 1, bitmap, object : IPaddleOcrCallback.Stub() {
                        override fun complete(requestId: Long, value: String) {
                            assertEquals(index.toLong() + 1, requestId)
                            response = value; done.countDown()
                        }
                    })
                } finally { bitmap.recycle() }
                assertTrue("OCR fixture timeout: $name", done.await(90, TimeUnit.SECONDS))
                val result = JSONObject(response)
                assertEquals("OCR fixture error: $name", "OK", result.getString("status"))
                val lines = result.getJSONArray("lines")
                assertTrue("Too few detected lines: $name", lines.length() >= 4)
                var found = false
                for (row in 0 until lines.length()) {
                    val item = lines.getJSONObject(row)
                    if (item.getString("text").contains("讨论")) found = true
                    val confidence = item.getDouble("confidence")
                    assertTrue(confidence.isFinite() && confidence in 0.0..1.0)
                    assertEquals(4, item.getJSONArray("points").length())
                }
                assertTrue("Expected synthetic word absent: $name", found)
                // Report fixture ID, timing and count only, never recognized text.
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "PADDLE_FIXTURE $name elapsedMs=${SystemClock.elapsedRealtime()-started} lines=${lines.length()}\n")
                })
            }
        } finally { context.unbindService(connection) }
    }
}
