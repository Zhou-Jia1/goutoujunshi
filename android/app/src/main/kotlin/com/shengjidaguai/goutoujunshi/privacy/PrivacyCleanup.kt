package com.shengjidaguai.goutoujunshi.privacy

import android.app.Application
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.floating.FloatingRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object PrivacyCleanup {
    suspend fun stop(application:Application) = withContext(Dispatchers.Main.immediate) {
        CounselRuntime.cancelExistingImports()
        try {com.shengjidaguai.goutoujunshi.followup.FollowupScheduler(application).clear()}
        finally {FloatingRuntime.clearAll(application)}
    }
    suspend fun finish(application:Application) = withContext(Dispatchers.Main.immediate) {
        // The vault owns its durable marker and wrappers; never remove its live DataStore file.
        LauncherAliases.select(application,0)
        listOf("import-notice","followup-settings","style-bias","privacy").forEach {
            check(application.getSharedPreferences(it,0).edit().clear().commit())
        }
        check(application.cacheDir.listFiles().orEmpty().all {it.deleteRecursively()})
        check(application.codeCacheDir.listFiles().orEmpty().all {it.deleteRecursively()})
        FloatingRuntime.timeoutMinutes=10
        PrivacyRuntime.setEnabled(false)
    }
}
