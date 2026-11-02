package com.shengjidaguai.goutoujunshi.floating

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.designsystem.*
import java.util.Locale

internal enum class GuideBrand(val steps: Int) {
    XIAOMI(R.string.guide_xiaomi), HUAWEI(R.string.guide_huawei), HONOR(R.string.guide_honor),
    OPPO(R.string.guide_oppo), ONEPLUS(R.string.guide_oneplus), VIVO(R.string.guide_vivo), UNKNOWN(R.string.guide_unknown);
    companion object {
        fun identify(manufacturer: String, brand: String): GuideBrand {
            val names=listOf(manufacturer,brand).map { it.trim().lowercase(Locale.ROOT) }
            fun has(vararg values:String)=names.any { it in values }
            return when {
                has("redmi","poco","xiaomi") -> XIAOMI
                has("honor") -> HONOR
                has("huawei") -> HUAWEI
                has("oneplus","one plus") -> ONEPLUS
                has("oppo") -> OPPO
                has("vivo","iqoo") -> VIVO
                else -> UNKNOWN
            }
        }
    }
}

/** No unverified OEM component names. A candidate must resolve AND launch; details is the fallback. */
internal class GuideSettingsLauncher(
    private val resolves:(Intent)->Boolean,
    private val launch:(Intent)->Unit,
    private val packageName:String,
) {
    fun open(candidate:Intent?=null):Boolean {
        val details=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))
        for(intent in listOfNotNull(candidate,details)) {
            try { if(resolves(intent)) { launch(intent); return true } }
            catch(_:RuntimeException) { /* Settings may disappear or deny access after resolve. */ }
        }
        return false
    }
    companion object {
        fun forContext(context:Context)=GuideSettingsLauncher(
            { it.resolveActivity(context.packageManager)!=null },context::startActivity,context.packageName)
    }
}

internal enum class GuidePage { INTRO, OVERLAY, BACKGROUND, NOTIFICATION_DENIED }
internal data class PermissionAuthority(val ticket:Long,val lockRevision:Long)

/** Every UI and system result carries the original RAM identity, including a denied notification. */
internal class PermissionGuideGate {
    var authority:PermissionAuthority?=null; private set
    fun begin(ticket:Long,revision:Long):PermissionAuthority = PermissionAuthority(ticket,revision).also { authority=it }
    fun valid(expected:PermissionAuthority?,ticket:Long?,revision:Long,allowed:Boolean,sessionValid:(Long)->Boolean):Boolean =
        expected!=null && expected==authority && expected.ticket==ticket && expected.lockRevision==revision && allowed && sessionValid(expected.ticket)
    fun cancel() { authority=null }
}

@Composable
internal fun FloatingPermissionGuide(page:GuidePage,brand:GuideBrand,onContinue:()->Unit,onSettings:()->Unit,onCancel:()->Unit) {
    JunshiColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        JunshiTopBar(stringResource(R.string.guide_title),onCancel)
        val title=when(page) {
            GuidePage.INTRO -> R.string.guide_intro_title
            GuidePage.OVERLAY -> R.string.guide_overlay_title
            GuidePage.BACKGROUND -> R.string.guide_background_title
            GuidePage.NOTIFICATION_DENIED -> R.string.guide_notification_title
        }
        val body=when(page) {
            GuidePage.INTRO -> R.string.guide_intro_body
            GuidePage.OVERLAY -> R.string.guide_overlay_body
            GuidePage.BACKGROUND -> brand.steps
            GuidePage.NOTIFICATION_DENIED -> R.string.guide_notification_body
        }
        PermissionStepCard(page.ordinal+1,4,stringResource(title),stringResource(body),Illustration.SHIELD,
            primary=stringResource(if(page==GuidePage.OVERLAY) R.string.guide_open_overlay else if(page==GuidePage.BACKGROUND) R.string.guide_skip else R.string.guide_continue) to
                if(page==GuidePage.OVERLAY) onSettings else onContinue,
            secondary=stringResource(R.string.guide_cancel) to onCancel)
        if(page==GuidePage.BACKGROUND) {
            BodyText(stringResource(R.string.guide_version_notice),secondary=true)
            SecondaryButton(stringResource(R.string.guide_open_details),onSettings)
        }
        BodyText(stringResource(R.string.guide_single_app),secondary=true)
    }
}

/** Process-wide non-reuse across Activity recreation; exhaustion fails closed rather than wrapping. */
internal class ProjectionRequestCodes(private var next:Int=3201) {
    @Synchronized fun allocate():Int? = if(next<=65000) next++ else null
    companion object { val process=ProjectionRequestCodes() }
}
