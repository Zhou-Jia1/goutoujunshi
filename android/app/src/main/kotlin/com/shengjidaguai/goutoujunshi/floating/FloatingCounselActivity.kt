package com.shengjidaguai.goutoujunshi.floating

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.ui.ContactsScreen

/** Only this resumed, non-exported user interface may request a foreground start. */
class FloatingCounselActivity : ComponentActivity() {
    private var ticket: Long? = null // deliberately not restored from Bundle
    private var waitingOverlay = false
    private var message by mutableStateOf(R.string.floating_off)
    private var contacts by mutableStateOf(false)
    private var authEpoch: Long? = null
    private var authLockRevision: Long? = null
    private var tileHandoff=false
    private val controller by lazy { CounselRuntime.get(application) }
    private val viewModel by lazy { androidx.lifecycle.ViewModelProvider(this)[CounselViewModel::class.java] }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) window.decorView.post { startIfResumed() } else { cancelPending(); message=R.string.floating_permission_denied }
    }
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val epoch=authEpoch; authEpoch=null
        if(result.resultCode==RESULT_OK && epoch!=null && epoch==FloatingRuntime.session.epoch() && authLockRevision==controller.lockRevision && controller.state.value.lockState.enabled) {
            controller.updateLockState(controller.state.value.lockState.copy(unlocked=true))
            window.decorView.post { resumeTileHandoff() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FloatingRuntime.watchScreen(application)
        val handoff=intent.getLongExtra(FloatingCounselService.TICKET,-1)
        if(intent.action==FloatingRuntime.TILE_START && FloatingRuntime.session.valid(handoff)) {
            ticket=handoff; tileHandoff=true
        }
        setContent {
            val state by controller.state.collectAsState()
            val active by FloatingRuntime.active.collectAsState()
            JunshiTheme {
                Surface(Modifier.fillMaxSize()) {
                    if(intent.action==FloatingRuntime.UNLOCK) {
                        if(floatingContentAllowed(state.lockState)) LaunchedEffect(Unit) { finish() }
                        else FloatingLockGate(::unlock,::finish,if(message==R.string.floating_lock_unavailable) message else R.string.floating_lock_hint)
                    } else if(contacts && floatingContentAllowed(state.lockState)) {
                        ContactsScreen(viewModel) { finish() }
                    } else FloatingControls(active, floatingContentAllowed(state.lockState), message,
                        onStart=::begin, onStop={ cancelPending(); FloatingRuntime.stop(this); finish() },
                        onContacts={ if(floatingContentAllowed(controller.state.value.lockState)) contacts=true },
                        onUnlock=::unlock, onClose=::finish)
                }
            }
        }
    }
    private fun unlock() {
        val keyguard=getSystemService(KeyguardManager::class.java)
        if(!keyguard.isDeviceSecure) { message=R.string.floating_lock_unavailable; return }
        @Suppress("DEPRECATION") val intent=keyguard.createConfirmDeviceCredentialIntent(getString(R.string.floating_unlock),getString(R.string.floating_lock_hint))
        if(intent!=null) { authEpoch=FloatingRuntime.session.epoch(); authLockRevision=controller.lockRevision; credential.launch(intent) }
    }
    private fun begin() {
        if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if(!floatingContentAllowed(controller.state.value.lockState)) { message=R.string.floating_lock_hint; return }
        ticket=FloatingRuntime.session.request()
        requestPermissions()
    }
    private fun requestPermissions() {
        if(!Settings.canDrawOverlays(this)) {
            waitingOverlay=true
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
            catch(_:RuntimeException) { waitingOverlay=false; cancelPending(); message=R.string.floating_unavailable }
        } else permissionOrStart()
    }
    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post { resumeTileHandoff() }
    }
    private fun resumeTileHandoff() {
        if(!tileHandoff || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !floatingContentAllowed(controller.state.value.lockState)) return
        tileHandoff=false
        if(ticket?.let(FloatingRuntime.session::valid)==true) requestPermissions()
    }
    override fun onResume() {
        super.onResume()
        if(waitingOverlay) {
            waitingOverlay=false
            if(Settings.canDrawOverlays(this)) permissionOrStart() else { cancelPending(); message=R.string.floating_permission_denied }
        }
    }
    private fun permissionOrStart() {
        val current=ticket ?: return
        if(!FloatingRuntime.session.valid(current)) { ticket=null; return }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else window.decorView.post { startIfResumed() }
    }
    private fun startIfResumed() {
        val current=ticket ?: return
        if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !FloatingRuntime.session.valid(current)) return
        ticket=null
        if(!Settings.canDrawOverlays(this) || !floatingContentAllowed(controller.state.value.lockState)) { FloatingRuntime.session.cancel(current); message=R.string.floating_permission_denied; return }
        try {
            startForegroundService(Intent(this,FloatingCounselService::class.java).putExtra(FloatingCounselService.TICKET,current))
            message=R.string.floating_off
        } catch(_:RuntimeException) { FloatingRuntime.session.cancel(current); message=R.string.floating_unavailable }
    }
    private fun cancelPending() { ticket?.let(FloatingRuntime.session::cancel); ticket=null; waitingOverlay=false; tileHandoff=false; authEpoch=null; authLockRevision=null }
    override fun onDestroy() { if(ticket!=null || authEpoch!=null) cancelPending(); super.onDestroy() }
}

@Composable
internal fun FloatingLockGate(onUnlock:()->Unit,onClose:(()->Unit)?=null,message:Int=R.string.floating_lock_hint) {
    JunshiColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        JunshiTopBar(stringResource(R.string.floating_locked),onClose)
        BodyText(stringResource(message))
        PrimaryButton(stringResource(R.string.floating_unlock),onUnlock)
    }
}

@Composable
internal fun FloatingControls(active: Boolean, unlocked: Boolean, message: Int,
    onStart:()->Unit, onStop:()->Unit, onContacts:()->Unit, onUnlock:()->Unit, onClose:()->Unit) {
    JunshiColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        JunshiTopBar(stringResource(R.string.floating_title),onClose)
        BodyText(stringResource(if(active) R.string.floating_started else message))
        if(active) SecondaryButton(stringResource(R.string.floating_stop),onStop)
        else if(unlocked) PrimaryButton(stringResource(R.string.floating_start),onStart)
        BodyText(stringResource(R.string.floating_capture_unavailable),secondary=true)
        // No capture backend exists yet; the action is explicitly disabled.
        PrimaryButton(stringResource(R.string.floating_capture),{},enabled=false)
        if(unlocked) SecondaryButton(stringResource(R.string.floating_contacts),onContacts)
        else {
            BodyText(stringResource(R.string.floating_lock_hint))
            PrimaryButton(stringResource(R.string.floating_unlock),onUnlock)
        }
    }
}
