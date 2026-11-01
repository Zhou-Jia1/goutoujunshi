package com.shengjidaguai.goutoujunshi

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.data.ocr.PaddleImageReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deliberately restricted to the single invented group asset. Never accepts caller input. */
class T22PaddleDiagnosticTest {
    @Test fun syntheticGroupGeometryOnly()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val rows=PaddleImageReader(context).read(listOf(Uri.parse("content://${context.packageName}.synthetic-ocr/group"))).single()
        assertTrue(rows.isNotEmpty())
        rows.forEachIndexed { i,r -> instrumentation.sendStatus(0,Bundle().apply {
            putString("stream","SYNTHETIC_GROUP index=$i text=${r.text} x=${r.left*720},${r.right*720} y=${r.top*1280},${r.bottom*1280} conf=${r.confidence} side=${r.side} name=${r.nickname} ui=${r.suggestedUi}\n")
        }) }
    }
}
