package com.shengjidaguai.goutoujunshi.followup
import android.app.*
import android.content.*
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.data.*
import java.util.concurrent.TimeUnit

class FollowupScheduler(private val context:Context,workManager:WorkManager?=null,private val displayAuthority:(((Boolean)->Unit)->Unit)=com.shengjidaguai.goutoujunshi.counsel.CounselRuntime::withNotificationDisplay):FollowupEffects {
    private val work:WorkManager by lazy {workManager ?: WorkManager.getInstance(context)}
    private val known=mutableSetOf<String>()
    fun permitted()=NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun enqueue(r:FollowupRecord,earliest:Long,now:Long,deferred:Boolean=false):Operation {
        known+=r.id
        return work.enqueueUniqueWork(name(r.id),if(deferred) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,request(r,earliest,now))
    }
    override fun clear() {
        known.clear()
        hidePrivate(context)
        work.cancelAllWorkByTag(ALL_TAG)
    }
    override fun cancel(id:String) { known.remove(id);work.cancelUniqueWork(name(id));context.getSystemService(NotificationManager::class.java).cancel(tag(id),0) }
    override fun retain(ids:Set<String>) {
        (known.toSet()-ids).forEach(::cancel)
        context.getSystemService(NotificationManager::class.java).activeNotifications.filter {it.tag?.startsWith(PREFIX)==true && it.tag.removePrefix(PREFIX) !in ids}.forEach {context.getSystemService(NotificationManager::class.java).cancel(it.tag,0)}
        known.addAll(ids)
    }
    fun notify(r:FollowupRecord,alias:String?=null) {
        if(android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return
        displayAuthority {unlocked ->
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.t54_channel),NotificationManager.IMPORTANCE_DEFAULT))
        val intent=FollowupRouter.intent(context,r.id,r.generation)
        val pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=androidx.core.app.NotificationCompat.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.ic_floating_tile).setContentTitle(context.getString(R.string.t54_notification_title))
            .setContentText(if(alias!=null && unlocked && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked && context.getSharedPreferences("followup-settings",0).getBoolean("alias",false)) context.getString(R.string.t54_notification_alias,alias) else context.getString(R.string.t54_notification_body)).setContentIntent(pending).setAutoCancel(true)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE).build()
        manager.notify(tag(r.id),0,notification)
        }
    }
    companion object {
        fun setAliasVisibility(context:Context,enabled:Boolean) {
            com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.withNotificationDisplay {
                context.getSharedPreferences("followup-settings",0).edit().putBoolean("alias",enabled).apply()
                cancelDisplayed(context)
            }
        }
        fun hidePrivate(context:Context) {
            com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.withNotificationDisplay {cancelDisplayed(context)}
        }
        private fun cancelDisplayed(context:Context) {
            // Ordinary notifications currently belong only to T54. Recheck this scope if adding others.
            // cancelAll covers accepted system work too; posted-list enumeration can miss it.
            // Android's cancel contract protects active FGS/UIJ notifications (FloatingCounselService).
            // https://developer.android.com/reference/android/app/NotificationManager#cancelAll()
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
        const val ALL_TAG="junshi-followup"
        const val PREFIX="followup:"
        const val CHANNEL="followup"
        fun tag(id:String)=PREFIX+id
        fun name(id:String)=PREFIX+id
        fun request(r:FollowupRecord,earliest:Long,now:Long)=OneTimeWorkRequestBuilder<FollowupWorker>()
            .setInputData(workDataOf("id" to r.id,"generation" to r.generation))
            .setInitialDelay((earliest-now).coerceAtLeast(0),TimeUnit.MILLISECONDS).addTag(PREFIX+r.id).addTag(ALL_TAG).build()
    }
}
