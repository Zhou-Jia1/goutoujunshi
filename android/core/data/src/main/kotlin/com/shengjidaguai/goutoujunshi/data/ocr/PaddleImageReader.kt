package com.shengjidaguai.goutoujunshi.data.ocr

import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.IBinder
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONObject

/** Same reversible toolbar/input-area suggestion for URI tiles and live RAM frames. */
internal fun frameUiHint(line:OcrLine,width:Int,height:Int)=line.copy(suggestedUi=line.suggestedUi ||
    line.bottom*height < minOf(width,height)*.18f || line.top*height > height-minOf(width,height)*.13f)

/** Bounded content-only reads. Never persists grants, URIs, encoded images or decoded tiles. */
class PaddleImageReader(private val context:Context) {
    private companion object { val ids=AtomicLong() }
    private suspend fun <T> connected(block:suspend (IIsolatedPaddleOcr)->T):T = withContext(Dispatchers.IO) {
        var registered=false
        var pending:CancellableContinuation<IIsolatedPaddleOcr>?=null
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder) { pending?.takeIf { it.isActive }?.resume(IIsolatedPaddleOcr.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name:ComponentName) { pending?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("OCR_UNAVAILABLE")) }
            override fun onNullBinding(name:ComponentName) = onServiceDisconnected(name)
            override fun onBindingDied(name:ComponentName) = onServiceDisconnected(name)
        }
        try {
            val remote=withTimeout(20_000) { suspendCancellableCoroutine { continuation ->
                pending=continuation
                registered=context.bindService(Intent(context,IsolatedPaddleOcrService::class.java),connection,Context.BIND_AUTO_CREATE)
                if(!registered) continuation.resumeWithException(IllegalStateException("OCR_UNAVAILABLE"))
            } }
            block(remote)
        } catch(e:java.io.IOException) { throw IllegalStateException("OCR_READ") }
        catch(e:SecurityException) { throw IllegalStateException("OCR_READ") }
        finally { pending=null; if(registered) context.unbindService(connection) }
    }
    /** Caller retains ownership; this entry never encodes or persists the frame. */
    suspend fun readBitmap(bitmap:Bitmap):List<OcrLine> = connected { remote ->
        require(!bitmap.isRecycled && bitmap.width.toLong()*bitmap.height<=12_000_000)
        OcrLayout.attachNicknames(recognize(remote,bitmap).map { frameUiHint(it,bitmap.width,bitmap.height) },bitmap.width.toFloat()/bitmap.height)
    }
    suspend fun read(uris:List<Uri>):List<List<OcrLine>> {
        require(uris.size in 1..20 && uris.all { it.scheme=="content" })
        return connected { remote ->
            val coroutine=currentCoroutineContext()
            uris.map { uri ->
                coroutine.ensureActive()
                val bytes=withTimeout(30_000) { runInterruptible { context.contentResolver.openInputStream(uri)?.use { input ->
                    val output=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true) {
                        coroutine.ensureActive()
                        val n=input.read(buffer); if(n<0) break
                        require(output.size()+n<=25*1024*1024) { "OCR_LIMIT" }
                        output.write(buffer,0,n)
                    }
                    output.toByteArray()
                } ?: error("OCR_READ") } }
                @Suppress("DEPRECATION")
                val decoder=BitmapRegionDecoder.newInstance(bytes,0,bytes.size,false) ?: error("OCR_READ")
                try {
                    require(decoder.width in 1..16000 && decoder.height in 1..100000 && decoder.width.toLong()*decoder.height<=120_000_000) { "OCR_LIMIT" }
                    val lines=mutableListOf<OcrLine>()
                    val scale=maxOf(1, (decoder.width+1279)/1280)
                    for(tile in OcrLayout.tiles(decoder.height,1600*scale,200*scale)) {
                        coroutine.ensureActive()
                        val bitmap=decoder.decodeRegion(Rect(0,tile.top,decoder.width,tile.bottom),BitmapFactory.Options().apply { inSampleSize=scale; inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("OCR_READ")
                        try {
                            val result=recognize(remote,bitmap)
                            result.forEach { l ->
                                val y=tile.top+l.top*(tile.bottom-tile.top)
                                val bottom=tile.top+l.bottom*(tile.bottom-tile.top)
                                if(tile.owns((y+bottom)/2)) lines += frameUiHint(l.copy(top=y/decoder.height,bottom=bottom/decoder.height),decoder.width,decoder.height)
                            }
                            require(lines.size<=2000) { "OCR_LIMIT" }
                        } finally { bitmap.recycle() }
                    }
                    val sorted=lines.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
                    OcrLayout.attachNicknames(sorted,decoder.width.toFloat()/decoder.height)
                } finally { decoder.recycle() }
            }
        }
    }
    /** Probe the flat bubble background above the glyphs; fall back to text geometry if unclear. */
    private fun bubbleSide(bitmap:Bitmap,line:OcrLine):OcrSide? {
        val y=((line.top-(line.bottom-line.top)*.2f)*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
        val x=(line.left*bitmap.width-8).toInt().coerceIn(0,bitmap.width-1)
        val color=bitmap.getPixel(x,y)
        fun close(other:Int)=kotlin.math.abs(Color.red(color)-Color.red(other))<24 &&
            kotlin.math.abs(Color.green(color)-Color.green(other))<24 && kotlin.math.abs(Color.blue(color)-Color.blue(other))<24
        var left=x;var right=x
        while(left>0 && close(bitmap.getPixel(left-1,y))) left--
        while(right<bitmap.width-1 && close(bitmap.getPixel(right+1,y))) right++
        val width=(right-left).toFloat()/bitmap.width
        if(width !in .12f.. .85f || right.toFloat()/bitmap.width < line.right-.05f) return null
        val center=(left+right)/2f/bitmap.width
        return when { center<.46f -> OcrSide.LEFT;center>.54f -> OcrSide.RIGHT;else -> null }
    }
    private suspend fun recognize(remote:IIsolatedPaddleOcr,bitmap:Bitmap):List<OcrLine> {
        val id=ids.incrementAndGet()
        try {
            val raw=withTimeout(90_000) { suspendCancellableCoroutine<String> { cont ->
                val completed=java.util.concurrent.atomic.AtomicBoolean(false)
                val death=IBinder.DeathRecipient { if(completed.compareAndSet(false,true) && cont.isActive) cont.resumeWithException(IllegalStateException("OCR_UNAVAILABLE")) }
                remote.asBinder().linkToDeath(death,0)
                cont.invokeOnCancellation { completed.set(true); runCatching { remote.asBinder().unlinkToDeath(death,0) } }
                try { remote.recognize(id,bitmap,object:IPaddleOcrCallback.Stub() {
                    override fun complete(requestId:Long,value:String) {
                        runCatching { remote.asBinder().unlinkToDeath(death,0) }
                        if(requestId==id && completed.compareAndSet(false,true) && cont.isActive) cont.resume(value)
                    }
                }) } catch(e:Exception) { runCatching { remote.asBinder().unlinkToDeath(death,0) }; if(completed.compareAndSet(false,true) && cont.isActive) cont.resumeWithException(e) }
            } }
            require(raw.length<=1_000_000)
            val result=JSONObject(raw)
            check(result.getString("status")=="OK") { "OCR_UNAVAILABLE" }
            val rows=result.getJSONArray("lines")
            return (0 until rows.length()).map { index ->
                val row=rows.getJSONObject(index); val pts=row.getJSONArray("points")
                val x=(0 until pts.length()).map { pts.getJSONArray(it).getDouble(0).toFloat()/bitmap.width }
                val y=(0 until pts.length()).map { pts.getJSONArray(it).getDouble(1).toFloat()/bitmap.height }
                val line=OcrLine(row.getString("text"),row.getDouble("confidence").toFloat(),x.min(),y.min(),x.max(),y.max())
                line.copy(suggestedSide=bubbleSide(bitmap,line))
            }
        } finally { withContext(NonCancellable+Dispatchers.IO) { runCatching { remote.cancel(id) } } }
    }
}
