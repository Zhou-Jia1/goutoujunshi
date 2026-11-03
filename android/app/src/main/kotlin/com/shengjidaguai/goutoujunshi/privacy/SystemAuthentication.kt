package com.shengjidaguai.goutoujunshi.privacy

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.CounselController

/** Result tickets are RAM only and consumed once. Rotation and clear invalidate the old owner. */
internal class SystemAuthentication(private val activity:ComponentActivity,private val controller:CounselController,private val result:(Boolean)->Unit) {
    private var attempt:CounselController.AuthenticationAttempt?=null
    private var enabling=false
    private var signal:CancellationSignal?=null
    private var credentialOutstanding=false
    private val credentials=activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {credentialOutstanding=false;finish(it.resultCode==Activity.RESULT_OK)}
    fun authenticate(enable:Boolean=false,biometric:Boolean=false) {
        if(credentialOutstanding) return
        cancel()
        val keyguard=activity.getSystemService(KeyguardManager::class.java)
        if(!keyguard.isDeviceSecure) {result(false);return}
        enabling=enable;attempt=controller.beginAuthentication()
        PrivacyRuntime.systemTrip(activity)
        try {
            if(biometric && Build.VERSION.SDK_INT>=29) {
                val current=attempt
                signal=CancellationSignal()
                @Suppress("DEPRECATION")
                val prompt=BiometricPrompt.Builder(activity).setTitle(activity.getString(R.string.floating_unlock)).setDeviceCredentialAllowed(true).build()
                prompt.authenticate(signal!!,activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(value:BiometricPrompt.AuthenticationResult) {if(attempt==current) finish(true)}
                    override fun onAuthenticationError(code:Int,message:CharSequence) {if(attempt==current) finish(false)}
                })
            } else {
                @Suppress("DEPRECATION")
                val intent=keyguard.createConfirmDeviceCredentialIntent(activity.getString(R.string.floating_unlock),activity.getString(R.string.floating_lock_hint))
                if(intent==null) finish(false) else {credentialOutstanding=true;credentials.launch(intent)}
            }
        } catch(_:RuntimeException) {finish(false)}
    }
    private fun finish(success:Boolean) {
        val ticket=attempt ?: return
        attempt=null;signal=null;PrivacyRuntime.systemReturn(activity)
        result(controller.finishAuthentication(ticket,success,enabling) {!enabling || PrivacyRuntime.setEnabled(true)})
        PrivacyRuntime.completeSystemTrip(activity)
    }
    fun cancel() {
        val ticket=attempt;attempt=null;signal?.cancel();signal=null
        if(ticket!=null) {controller.finishAuthentication(ticket,false);PrivacyRuntime.finishHandoff(activity.application,activity)}
        PrivacyRuntime.systemReturn(activity)
    }
}
