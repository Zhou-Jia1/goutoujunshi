package com.shengjidaguai.goutoujunshi.followup
import android.content.*
import android.net.Uri
import com.shengjidaguai.goutoujunshi.MainActivity
/** No contact ID, alias or body crosses this boundary. The encrypted record owns routing. */
object FollowupRouter {
    const val ACTION="com.shengjidaguai.goutoujunshi.FOLLOWUP"
    fun intent(context:Context,id:String,generation:String)=Intent(context,MainActivity::class.java).setAction(ACTION)
        .setData(Uri.Builder().scheme("junshi-followup").authority(id).appendPath(generation).build())
    fun read(intent:Intent):Pair<String,String>? {
        if(intent.action!=ACTION) return null
        val uri=intent.data ?: return null
        val id=uri.host ?: return null;val generation=uri.pathSegments.singleOrNull() ?: return null
        if(uri.scheme!="junshi-followup" || !uuid(id) || !uuid(generation)) return null
        intent.data=null;intent.action=null
        return id to generation
    }
    private fun uuid(value:String)=runCatching {java.util.UUID.fromString(value).toString()==value}.getOrDefault(false)
}
