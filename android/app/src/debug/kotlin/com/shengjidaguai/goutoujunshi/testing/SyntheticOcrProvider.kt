package com.shengjidaguai.goutoujunshi.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/** Debug only: six allowlisted, invented fixtures; cannot open a caller-supplied path. */
class SyntheticOcrProvider:ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri:Uri)="image/png"
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        val name=uri.lastPathSegment
        if(mode!="r" || name !in setOf("light_1","light_2","dark_1","dark_2","long","group")) throw FileNotFoundException()
        val pipe=ParcelFileDescriptor.createPipe()
        Thread {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> context!!.assets.open("ocr/$name.png").use { it.copyTo(out) } } }
            catch(_:Exception) { runCatching { pipe[1].close() } }
        }.start()
        return pipe[0]
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
}
