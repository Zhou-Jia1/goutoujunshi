package com.shengjidaguai.goutoujunshi.followup
import android.app.Application
import android.content.Context
import androidx.work.*
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.data.*
import java.time.ZoneId
class FollowupWorker @JvmOverloads constructor(context:Context,parameters:WorkerParameters,
    private val sessionProvider:suspend ()->DataSession?={CounselRuntime.store(context.applicationContext as Application).existing()},
    private val clock:()->Long=System::currentTimeMillis,private val zone:()->ZoneId=ZoneId::systemDefault):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val id=inputData.getString("id") ?: return Result.failure()
        val generation=inputData.getString("generation") ?: return Result.failure()
        val scheduler=FollowupScheduler(applicationContext)
        val session=sessionProvider() ?: run {scheduler.cancel(id);return Result.success()}
        val repository=FollowupRepository(session);repository.bind(scheduler)
        if(!scheduler.permitted()) {repository.cancelAll();return Result.success()}
        val now=clock()
        val due=repository.current(id,generation) {r,_,_->FollowupTime.eligible(r.due,now,zone())} ?: return Result.success()
        if(due>now) {
            repository.current(id,generation) {r,_,_->scheduler.enqueue(r,due,now,true)}
            return Result.success()
        }
        val alias=repository.current(id,generation) {_,c,_->c.alias}
        repository.deliver(id,generation,scheduler::permitted,clock,ready={record ->
            val current=clock();val earliest=FollowupTime.eligible(record.due,current,zone())
            if(earliest>current) {scheduler.enqueue(record,earliest,current,true);false} else true
        }) {scheduler.notify(it,alias)}
        return Result.success()
    }
}
