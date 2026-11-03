package com.shengjidaguai.goutoujunshi.privacy

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.MainActivity
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.domain.LockState
import com.shengjidaguai.goutoujunshi.floating.FloatingCounselActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Owned, empty fixture only. Root supplies a synthetic PIN; never read a user's credential. */
@RunWith(AndroidJUnit4::class)
class T55SystemPrivacyDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private var keepEnabledForColdProbe=false
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as Application
    private val controller get()=CounselRuntime.get(app)
    private fun until(test:()->Boolean) {val end=SystemClock.uptimeMillis()+15000;while(!test() && SystemClock.uptimeMillis()<end) SystemClock.sleep(50);assertTrue(test())}
    private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use {reader->reader.readText()}}
    private fun ownCredentialToken():String?=shell("dumpsys activity activities").let {dump ->
        val resumed=Regex("(?:topResumedActivity=|mResumedActivity:)\\s*ActivityRecord\\{([^\\n]+)").find(dump)?.groupValues?.get(1).orEmpty()
        val credential=(resumed.contains("com.android.settings") || resumed.contains("com.android.systemui")) && (resumed.contains("ConfirmLock") || resumed.contains("ConfirmDeviceCredential"))
        val token=resumed.substringBefore(' ')
        token.takeIf {credential && Regex("(?=\\s*\\* Hist\\s+#\\d+:)").split(dump).any {block ->
            val first=block.trimStart().lineSequence().firstOrNull().orEmpty()
            first.contains("ActivityRecord{$token ") && (first.contains("ConfirmLock") || first.contains("ConfirmDeviceCredential")) &&
                Regex("launchedFromPackage=([^\\s]+)").find(block)?.groupValues?.get(1)==context.packageName
        }}
    }
    private fun ownCredential()=ownCredentialToken()!=null
    private enum class ImeVisibility { SHOWN,HIDDEN,UNKNOWN }
    private fun imeVisibility():ImeVisibility {
        val blocks=Regex("(?=  Window #\\d+ Window\\{)").split(shell("dumpsys window windows"))
        val ime=blocks.filter {Regex("\\bty=INPUT_METHOD\\b").containsMatchIn(it) && it.contains("mDisplayId=0 ")}.singleOrNull() ?: return ImeVisibility.UNKNOWN
        fun field(value:String)=ime.lineSequence().any {it.trim()==value}
        return when {
            ime.contains("mViewVisibility=0x0 ") && ime.contains("mHasSurface=true isReadyForDisplay()=true") && field("isOnScreen=true") && field("isVisible=true") -> ImeVisibility.SHOWN
            ime.contains("mViewVisibility=0x8 ") && field("isOnScreen=false") && field("isVisible=false") -> ImeVisibility.HIDDEN
            else -> ImeVisibility.UNKNOWN
        }
    }
    private fun authFocus():String {
        val activities=shell("dumpsys activity activities").lineSequence().filter {it.contains("topResumedActivity=") || it.contains("mResumedActivity:") || it.contains("mFocusedApp=")}.joinToString(" | ").ifEmpty {"<not-reported>"}
        val windows=instrumentation.uiAutomation.windows.joinToString(" | ") {"id=${it.id},type=${it.type},active=${it.isActive},focused=${it.isFocused},package=${it.root?.packageName}"}.ifEmpty {"<not-reported>"}
        return "activities=$activities windows=$windows"
    }
    private fun nodes():List<android.view.accessibility.AccessibilityNodeInfo> {
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo
        if(info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS==0) {
            info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.serviceInfo=info
        }
        check(automation.clearCache())
        return com.shengjidaguai.goutoujunshi.floating.SystemProjectionConsent.nodes()
    }
    private fun credentialEditor()=nodes().firstOrNull {
        it.packageName?.toString()=="com.android.systemui" && it.viewIdResourceName=="com.android.systemui:id/lockPassword" &&
            it.isVisibleToUser && it.isEnabled && it.isFocused && it.isEditable && it.isPassword
    }
    private fun unlockButton():android.view.accessibility.AccessibilityNodeInfo? = nodes().asSequence()
        .filter {it.packageName?.toString()==context.packageName && it.text?.toString()==context.getString(com.shengjidaguai.goutoujunshi.R.string.floating_unlock) && it.isVisibleToUser}
        .mapNotNull {label ->
            var candidate:android.view.accessibility.AccessibilityNodeInfo?=label
            while(candidate!=null && !candidate.isClickable) candidate=candidate.parent
            candidate?.takeIf {it.packageName?.toString()==context.packageName && it.isVisibleToUser && it.isEnabled && (it.window?.isActive==true || it.window?.isFocused==true)}
        }.maxByOrNull {it.window?.layer ?: Int.MIN_VALUE}
    private fun enterSyntheticPin() {
        val pin=InstrumentationRegistry.getArguments().getString("t55SyntheticPin") ?: error("Root must supply owned AVD synthetic PIN fixture")
        require(pin.matches(Regex("[0-9]{4,12}")))
        assertTrue(ownCredential())
        assertNotNull("the owned real system credential window must expose its focused password editor",credentialEditor())
        shell("input text $pin");shell("input keyevent 66")
    }
    private fun launchAuthentication() {
        try {
            context.startActivity(Intent(context,FloatingCounselActivity::class.java).setAction("privacy-enable").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            until {unlockButton()!=null}
            // The accessibility node can appear before the opening window transition accepts
            // physical input. Synchronize the actual Compose root, then invoke its production
            // button's semantics action; do not inject a tap into that transition.
            compose.waitForIdle()
            val node=checkNotNull(unlockButton())
            val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
            check(!bounds.isEmpty)
            val unlock=compose.onNodeWithText(context.getString(com.shengjidaguai.goutoujunshi.R.string.floating_unlock))
                .assertIsDisplayed().assertHasClickAction()
            println("T55_AUTH_PHASE owned_unlock_compose_idle bounds=$bounds windowId=${node.windowId} active=${node.window?.isActive} focused=${node.window?.isFocused}")
            println("T55_AUTH_FOCUS "+authFocus())
            unlock.performClick()
            println("T55_AUTH_PHASE production_unlock_semantics_clicked")
            until {ownCredential() && credentialEditor()!=null}
            println("T55_AUTH_PHASE owned_credential_focused_editor_ready")
        } catch(failure:Throwable) {captureAuthFailure();throw failure}
    }
    private fun captureAuthFailure() {
            // Capture before ActivityScenario.use/@After closes the failed window. Exclude
            // node text/descriptions so the diagnostic never prints a password or private UI.
            runCatching {
                for(node in nodes()) {
                    val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
                    println("T55_AUTH_FAILURE_NODE package=${node.packageName} class=${node.className} id=${node.viewIdResourceName} bounds=$bounds visible=${node.isVisibleToUser} enabled=${node.isEnabled} focused=${node.isFocused} clickable=${node.isClickable} editable=${node.isEditable} password=${node.isPassword} windowId=${node.windowId}")
                }
            }.onFailure {println("T55_AUTH_FAILURE_NODE_CAPTURE ${it.javaClass.name}")}
            runCatching {println("T55_AUTH_FAILURE_WINDOW\n"+shell("dumpsys window windows"))}
            runCatching {println("T55_AUTH_FAILURE_ACTIVITY\n"+shell("dumpsys activity activities"))}
            runCatching {println("T55_AUTH_FAILURE_FOCUS "+authFocus())}
    }
    private fun cancelRealCredential() {
        val end=SystemClock.uptimeMillis()+15000
        fun await(phase:String,test:()->Boolean) {
            while(SystemClock.uptimeMillis()<end) {
                if(test()) return
                SystemClock.sleep(50)
            }
            error("Credential cancellation timed out at $phase")
        }
        try {
            val token=checkNotNull(ownCredentialToken())
            fun sameOwner()=check(ownCredentialToken()==token) {"Owned credential record changed before cancellation"}
            // This owned fixture has an actual soft PIN keyboard. Observe it, then give each
            // BACK one distinct purpose; never guess that an absent/transitioning IME is hidden.
            await("IME shown") {sameOwner();credentialEditor()!=null && imeVisibility()==ImeVisibility.SHOWN}
            println("T55_AUTH_CANCEL ime_shown token=$token "+authFocus())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("IME hidden, same owned credential") {sameOwner();imeVisibility()==ImeVisibility.HIDDEN}
            sameOwner();check(imeVisibility()==ImeVisibility.HIDDEN)
            val editor=checkNotNull(credentialEditor())
            check(editor.window?.isActive==true || editor.window?.isFocused==true)
            println("T55_AUTH_CANCEL ime_hidden_same_owner token=$token "+authFocus())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            // OS callbacks use real time, while this rule owns Compose frames. Give the
            // unchanged physical-UI predicate frame progress inside the same wall deadline.
            val remaining=end-SystemClock.uptimeMillis()
            check(remaining>0);check(compose.mainClock.autoAdvance)
            println("T55_AUTH_CANCEL compose_frame_sync remainingMs=$remaining")
            compose.waitUntil(timeoutMillis=remaining) {
                !ownCredential() && unlockButton()!=null && nodes().any {
                    it.packageName?.toString()==context.packageName && it.isVisibleToUser &&
                        it.text?.toString()==context.getString(com.shengjidaguai.goutoujunshi.R.string.floating_lock_unavailable)
                }
            }
            check(SystemClock.uptimeMillis()<=end) {"Credential cancellation exceeded its original deadline"}
            assertFalse(PrivacyRuntime.enabled)
            println("T55_AUTH_CANCEL returned_to_app_lock_disabled")
        } catch(failure:Throwable) {captureAuthFailure();throw failure}
    }
    @After fun restore() {instrumentation.runOnMainSync {
        com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.stop(context);LauncherAliases.select(context,0)
        if(!keepEnabledForColdProbe) {PrivacyRuntime.setEnabled(false);controller.updateLockState(LockState())}
    }}
    @Test fun realDeviceCredentialEnablesLockAndProtectsActualMainWindow() {
        assertTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use {main ->
            launchAuthentication();assertFalse(PrivacyRuntime.enabled)
            enterSyntheticPin();until {PrivacyRuntime.enabled && controller.state.value.lockState.unlocked}
            main.onActivity {assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)}
            println("T55_NATIVE real_os_credential_secure_window_pass")
        }
    }
    @Test fun cancelingRealCredentialDoesNotEnableLock() {
        launchAuthentication();assertFalse(PrivacyRuntime.enabled)
        cancelRealCredential();assertFalse(PrivacyRuntime.enabled)
        println("T55_NATIVE real_os_cancel_pass")
    }
    @Test fun clearDuringRealCredentialCannotEnableOrRestoreOldState() {
        launchAuthentication()
        instrumentation.runOnMainSync {controller.clearAll()}
        until {!controller.state.value.loading}
        if(ownCredential()) enterSyntheticPin()
        until {!ownCredential()}
        assertFalse(PrivacyRuntime.enabled);assertTrue(controller.state.value.contacts.isEmpty());assertNull(controller.state.value.currentContact)
        println("T55_NATIVE clear_during_real_os_authentication_pass")
    }
    @Test fun everyAliasKeepsExplicitMainAndShareReachable() {
        for(index in LauncherAliases.names.indices) {
            instrumentation.runOnMainSync {LauncherAliases.select(context,index)}
            val launcher=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
            assertEquals(1,context.packageManager.queryIntentActivities(launcher,0).size)
            assertEquals("com.shengjidaguai.goutoujunshi.${LauncherAliases.names[index]}",context.packageManager.queryIntentActivities(launcher,0).single().activityInfo.name)
            val main=ComponentName(context,MainActivity::class.java)
            assertTrue(context.packageManager.getActivityInfo(main,0).enabled)
            assertTrue(context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName),0).any {it.activityInfo.name==main.className})
        }
        println("T55_NATIVE real_alias_main_share_reachable_pass")
    }
    @Test fun configurationRecreationKeepsUnlockButActualHomeImmediatelyLocks() {
        ActivityScenario.launch(MainActivity::class.java).use {main ->
            instrumentation.runOnMainSync {PrivacyRuntime.setEnabled(true);PrivacyRuntime.setDelay(0);controller.updateLockState(LockState(true,true))}
            main.recreate()
            main.onActivity {assertTrue(controller.state.value.lockState.unlocked);assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)}
            shell("input keyevent 3")
            until {!controller.state.value.lockState.unlocked}
            println("T55_NATIVE configuration_and_home_lock_pass")
        }
    }
    @Test fun prepareRealAuthenticatedPersistentLockForIndependentProcessProbe() {
        assertTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        ActivityScenario.launch(MainActivity::class.java).use {
            launchAuthentication();enterSyntheticPin()
            until {PrivacyRuntime.enabled && controller.state.value.lockState.unlocked}
            assertTrue(context.getSharedPreferences("privacy",0).getBoolean("enabled",false))
            keepEnabledForColdProbe=true
            println("T55_COLD_PREP pid=${android.os.Process.myPid()} realCredentialSucceeded=true enabledPersisted=true")
        }
    }
    private fun ownedDocumentRecord(taskId:Int):String? {
        val dump=shell("dumpsys activity activities")
        val record=Regex("(?:topResumedActivity=|mResumedActivity:)\\s*ActivityRecord\\{([^\\n]+)").find(dump)?.groupValues?.get(1) ?: return null
        if(!record.contains("com.android.documentsui/") && !record.contains("com.google.android.documentsui/")) return null
        if(Regex("\\bt(\\d+)\\b").find(record)?.groupValues?.get(1)!=taskId.toString()) return null
        val token=record.substringBefore(' ')
        return record.takeIf {Regex("(?=\\s*\\* Hist\\s+#\\d+:)").split(dump).any {block ->
            block.trimStart().lineSequence().firstOrNull()?.contains("ActivityRecord{$token ")==true &&
                Regex("launchedFromPackage=([^\\s]+)").find(block)?.groupValues?.get(1)==context.packageName
        }}
    }
    private fun ownedDeviceHome():ComponentName {
        val raw=shell("cmd package resolve-activity --components --user current -a android.intent.action.MAIN -c android.intent.category.HOME")
        val lines=raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val component=lines.singleOrNull()?.let(ComponentName::unflattenFromString)
        println("T55_HOME_RESOLUTION raw=${raw.trim()} normalized=$component")
        check(component!=null && component.packageName!="android" && component.packageName!=context.packageName) {"Expected one concrete owned-device HOME component"}
        return component
    }
    private fun resumedComponent():ComponentName? {
        val dump=shell("dumpsys activity activities")
        val raw=Regex("(?:topResumedActivity=|mResumedActivity:)\\s*ActivityRecord\\{\\S+\\s+u\\d+\\s+([^\\s}]+)")
            .find(dump)?.groupValues?.get(1) ?: return null
        return ComponentName.unflattenFromString(raw)
    }
    @Test fun realOpenDocumentHomeMinuteStaysInteractiveAndReturnsLocked() {
        val power=context.getSystemService(android.os.PowerManager::class.java)
        val oldDelay=PrivacyRuntime.delayMillis
        ActivityScenario.launch(MainActivity::class.java).use {main ->
            val armed=java.util.concurrent.atomic.AtomicBoolean(false)
            val firstFrame=java.util.concurrent.atomic.AtomicReference<Pair<Boolean,Boolean>?>(null)
            var listener:android.view.ViewTreeObserver.OnPreDrawListener?=null
            var taskId=-1
            try {
                launchAuthentication();enterSyntheticPin()
                until {PrivacyRuntime.enabled && controller.state.value.lockState.unlocked && main.state==androidx.lifecycle.Lifecycle.State.RESUMED}
                instrumentation.runOnMainSync {PrivacyRuntime.setDelay(60_000)}
                main.onActivity {activity ->
                    taskId=activity.taskId
                    listener=android.view.ViewTreeObserver.OnPreDrawListener {
                        if(armed.compareAndSet(true,false)) firstFrame.set(controller.state.value.lockState.unlocked to
                            (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0))
                        true
                    }
                    activity.window.decorView.viewTreeObserver.addOnPreDrawListener(checkNotNull(listener))
                }
                compose.waitForIdle()
                if(compose.onAllNodesWithText(context.getString(com.shengjidaguai.goutoujunshi.R.string.t55_restart)).fetchSemanticsNodes().isNotEmpty())
                    compose.onNodeWithText(context.getString(com.shengjidaguai.goutoujunshi.R.string.t55_restart)).performClick()
                compose.waitUntil(15000) {!controller.state.value.loading && controller.state.value.conversation!=null}
                compose.onNodeWithText(context.getString(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_file)).performScrollTo().performClick()
                until {ownedDocumentRecord(taskId)!=null && main.state==androidx.lifecycle.Lifecycle.State.CREATED}
                val documentBefore=checkNotNull(ownedDocumentRecord(taskId))
                assertTrue(power.isInteractive)
                val expectedHome=ownedDeviceHome()
                shell("input keyevent 3")
                until {resumedComponent()==expectedHome && instrumentation.uiAutomation.windows.any {
                    it.isActive && it.isFocused && it.root?.packageName?.toString()==expectedHome.packageName
                }}
                println("T55_HOME_CONFIRMED expected=$expectedHome resumed=${resumedComponent()} interactive=${power.isInteractive}")
                val homeAt=SystemClock.elapsedRealtime();var samples=0;var nextLog=15_000L
                while(SystemClock.elapsedRealtime()-homeAt<61_000) {
                    assertTrue("screen OFF would hide the system-trip deadline regression",power.isInteractive);samples++
                    val elapsed=SystemClock.elapsedRealtime()-homeAt
                    if(elapsed>=nextLog) {println("T55_PICKER_HOME interactive=true elapsedMs=$elapsed samples=$samples");nextLog+=15_000}
                    SystemClock.sleep(250)
                }
                val elapsed=SystemClock.elapsedRealtime()-homeAt
                assertTrue(elapsed>=61_000);assertTrue(power.isInteractive)
                armed.set(true)
                @Suppress("DEPRECATION")
                val task=context.getSystemService(android.app.ActivityManager::class.java).appTasks.single {it.taskInfo?.id==taskId}
                // Return the existing task, including its real picker, without a new Intent/result.
                task.moveToFront()
                until {ownedDocumentRecord(taskId)!=null}
                println("T55_PICKER_RETURN sameTask=$taskId before=$documentBefore after=${ownedDocumentRecord(taskId)} elapsedMs=$elapsed interactive=${power.isInteractive}")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                until {main.state==androidx.lifecycle.Lifecycle.State.RESUMED && firstFrame.get()!=null}
                assertFalse(controller.state.value.lockState.unlocked)
                assertEquals(false to true,firstFrame.get())
                assertTrue(power.isInteractive);assertNull(CounselRuntime.imports(app).state.value)
                compose.waitForIdle();compose.onNodeWithText(context.getString(com.shengjidaguai.goutoujunshi.R.string.floating_locked)).assertIsDisplayed()
                println("T55_NATIVE real_picker_home_minute_locked_secure_pass elapsedMs=$elapsed interactiveSamples=$samples firstPrivateFrame=${firstFrame.get()}")
            } catch(failure:Throwable) {captureAuthFailure();throw failure}
            finally {
                main.onActivity {activity ->listener?.let {activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(it)}}
                instrumentation.runOnMainSync {PrivacyRuntime.setDelay(oldDelay)}
            }
        }
    }
    @Test fun immediateLockRealExternalCaptureReachesSecurePanelAndFullThenHomeLocks() {
        val floating=com.shengjidaguai.goutoujunshi.floating.FloatingRuntime
        val externalPackage="com.shengjidaguai.goutoujunshi.data.test"
        val external=ComponentName(externalPackage,"com.shengjidaguai.goutoujunshi.data.T55ExternalSyntheticChatActivity")
        val imports=CounselRuntime.imports(app)
        val oldDelay=PrivacyRuntime.delayMillis
        val oldPump=com.shengjidaguai.goutoujunshi.floating.T33NativeUi.pump
        var failureCapturedBeforeClose=false
        fun text(id:Int)=context.getString(id)
        fun clickImport(id:Int) {
            compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(id)))
            compose.onNodeWithText(text(id)).performClick()
        }
        fun sourceField(id:Int,value:String) {
            compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(id)))
            compose.onNodeWithText(text(id)).performTextReplacement(value)
        }
        com.shengjidaguai.goutoujunshi.floating.T33NativeUi.pump={compose.mainClock.advanceTimeByFrame();compose.waitForIdle()}
        try {
            assertNotEquals(android.os.Process.myUid(),context.packageManager.getApplicationInfo(externalPackage,0).uid)
            ActivityScenario.launch(MainActivity::class.java).use {main ->
                var originalLaunchIntent:Intent?=null
                var originalMainTaskId=-1
                lateinit var actualMain:MainActivity
                main.onActivity {actualMain=it;originalLaunchIntent=Intent(it.intent);originalMainTaskId=it.taskId}
                fun mainAt(stage:androidx.test.runner.lifecycle.Stage,state:androidx.lifecycle.Lifecycle.State):Boolean {
                    var matches=false
                    instrumentation.runOnMainSync {
                        val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        matches=monitor.getLifecycleStageOf(actualMain)==stage && actualMain.lifecycle.currentState==state
                    }
                    return matches
                }
                try {
                launchAuthentication();enterSyntheticPin()
                until {PrivacyRuntime.enabled && controller.state.value.lockState.unlocked && mainAt(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.lifecycle.Lifecycle.State.RESUMED)}
                instrumentation.runOnMainSync {PrivacyRuntime.setDelay(0)}
                compose.waitForIdle()
                if(compose.onAllNodesWithText(text(com.shengjidaguai.goutoujunshi.R.string.t55_restart)).fetchSemanticsNodes().isNotEmpty())
                    compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.R.string.t55_restart)).performClick()
                compose.waitUntil(15000) {!controller.state.value.loading && controller.state.value.conversation!=null}
                floating.open(context)
                compose.waitUntil(15000) {compose.onAllNodesWithText(text(com.shengjidaguai.goutoujunshi.R.string.floating_start)).fetchSemanticsNodes().size==1}
                compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.R.string.floating_start)).performClick()
                com.shengjidaguai.goutoujunshi.floating.SystemProjectionConsent.continueGuide(compose)
                com.shengjidaguai.goutoujunshi.floating.SystemProjectionConsent.respond(true)
                compose.waitUntil(15000) {floating.active.value}
                context.startActivity(Intent().setComponent(external).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                until {nodes().any {it.packageName?.toString()==externalPackage && it.contentDescription?.toString()=="T55_EXTERNAL_SYNTHETIC_CHAT" && it.isVisibleToUser}}
                var privateActivityStarted=true
                until {
                    instrumentation.runOnMainSync {
                        val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        privateActivityStarted=(monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)+monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.STARTED))
                            .any {it is MainActivity || it is FloatingCounselActivity}
                    }
                    !privateActivityStarted
                }
                assertTrue(controller.state.value.lockState.unlocked)
                println("T55_DEFAULT_EXTERNAL uid=${context.packageManager.getApplicationInfo(externalPackage,0).uid} ownUid=${android.os.Process.myUid()} privateActivityStarted=$privateActivityStarted policy=${PrivacyRuntime.delayMillis} leaseStart=${controller.currentPrivateUiHandoff()?.started}")
                var ball:android.view.accessibility.AccessibilityNodeInfo?=null
                compose.waitUntil(15000) {
                    ball=com.shengjidaguai.goutoujunshi.floating.T33NativeUi.nodes().singleOrNull {
                        it.contentDescription?.toString()==text(com.shengjidaguai.goutoujunshi.R.string.floating_ball_menu) && com.shengjidaguai.goutoujunshi.floating.T33NativeUi.touchBounds(it)!=null
                    };ball!=null
                }
                com.shengjidaguai.goutoujunshi.floating.T33NativeUi.tap(checkNotNull(ball))
                compose.waitUntil(120000) {imports.state.value?.recognized==true || !controller.state.value.lockState.unlocked}
                assertTrue("real OCR must reach private confirmation within the original bounded handoff",controller.state.value.lockState.unlocked)
                assertTrue(checkNotNull(imports.state.value).recognized)
                compose.waitUntil(15000) {compose.onAllNodesWithTag("import-preview").fetchSemanticsNodes().size==1}
                var reviewTaskId=-1
                instrumentation.runOnMainSync {
                    val review=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<FloatingCounselActivity>()
                        .single {it.intent.action==floating.CAPTURE_CONFIRM && it.intent.getStringExtra(floating.CAPTURE_PREVIEW)==imports.state.value?.id}
                    reviewTaskId=review.taskId
                    assertNotEquals("the private review must not expose background Main when it finishes",originalMainTaskId,review.taskId)
                    assertTrue(review.isTaskRoot)
                    assertTrue(review.intent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS!=0)
                    assertTrue(review.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
                }
                println("T55_REVIEW_TASK isolated=true taskId=$reviewTaskId mainTaskId=$originalMainTaskId recentsExcluded=true secure=true")
                if(!checkNotNull(imports.state.value).rightsAccepted) clickImport(com.shengjidaguai.goutoujunshi.R.string.import_rights_accept)
                val target=text(com.shengjidaguai.goutoujunshi.R.string.import_target)+"："+text(com.shengjidaguai.goutoujunshi.R.string.import_choose_target)
                compose.onNodeWithTag("import-preview").performScrollToNode(hasText(target));compose.onNodeWithText(target).performClick()
                compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.R.string.temporary_title)).performClick()
                sourceField(com.shengjidaguai.goutoujunshi.R.string.t33_source_app,"T55SyntheticExternalChat")
                sourceField(com.shengjidaguai.goutoujunshi.R.string.t33_source_account,"T55SyntheticAccount")
                clickImport(com.shengjidaguai.goutoujunshi.R.string.t33_confirm_source)
                clickImport(com.shengjidaguai.goutoujunshi.R.string.ocr_right_me)
                for(row in checkNotNull(imports.state.value).rows.filter {!it.excluded && it.speaker==null}) {
                    val tag="ocr-speaker-"+row.id
                    compose.onNodeWithTag("import-preview").performScrollToNode(hasTestTag(tag))
                    compose.onNode(hasText(text(com.shengjidaguai.goutoujunshi.R.string.import_speaker)+"："+text(com.shengjidaguai.goutoujunshi.R.string.import_unconfirmed)) and hasAnyAncestor(hasTestTag(tag))).performClick()
                    compose.onNodeWithText(text(com.shengjidaguai.goutoujunshi.R.string.import_them)).performClick()
                }
                while(checkNotNull(imports.state.value).rows.any {!it.excluded && (it.ocrConfidence ?: 1f)<.8f && !it.reviewed}) {
                    compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(com.shengjidaguai.goutoujunshi.R.string.ocr_reviewed)))
                    compose.onAllNodesWithText(text(com.shengjidaguai.goutoujunshi.R.string.ocr_reviewed)).onFirst().performClick()
                }
                assertTrue(checkNotNull(imports.state.value).canConfirm)
                clickImport(com.shengjidaguai.goutoujunshi.R.string.import_save)
                val panelDeadline=SystemClock.uptimeMillis()+15_000
                var handle:android.view.accessibility.AccessibilityNodeInfo?=null
                var returnedExternal:ComponentName?=null
                compose.waitUntil(15_000) {
                    val node=com.shengjidaguai.goutoujunshi.floating.T33NativeUi.visible(text(com.shengjidaguai.goutoujunshi.R.string.t33_resize))
                    var matchingActualWindow=false
                    if(node!=null && node.packageName?.toString()==context.packageName) instrumentation.runOnMainSync {
                        matchingActualWindow=android.view.inspector.WindowInspector.getGlobalWindowViews().any {v ->
                            v is androidx.compose.ui.platform.ComposeView && v.isAttachedToWindow && v.isShown && v.windowVisibility==android.view.View.VISIBLE &&
                                (v.layoutParams as? WindowManager.LayoutParams)?.let {
                                    it.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && it.width==WindowManager.LayoutParams.MATCH_PARENT &&
                                        it.height>context.resources.displayMetrics.heightPixels/3 && it.flags and WindowManager.LayoutParams.FLAG_SECURE!=0
                                }==true && v.createAccessibilityNodeInfo()?.windowId==node.windowId
                        }
                    }
                    if(matchingActualWindow) handle=node
                    if(matchingActualWindow) returnedExternal=resumedComponent()
                    imports.state.value==null && matchingActualWindow && controller.state.value.lockState.unlocked &&
                        mainAt(androidx.test.runner.lifecycle.Stage.STOPPED,androidx.lifecycle.Lifecycle.State.CREATED) && returnedExternal==external && instrumentation.uiAutomation.windows.any {
                            it.isActive && it.isFocused && it.root?.packageName?.toString()==externalPackage
                        }
                }
                check(SystemClock.uptimeMillis()<=panelDeadline) {"Private panel readiness exceeded its original 15s deadline"}
                assertTrue(controller.state.value.lockState.unlocked)
                val actualHandle=checkNotNull(handle)
                println("T55_PRIVATE_PANEL_READY windowId=${actualHandle.windowId} attachedShownSecure=true remainingMs=${panelDeadline-SystemClock.uptimeMillis()}")
                println("T55_RETURN_EXTERNAL confirmed=true mainStarted=false resumed=$returnedExternal")
                com.shengjidaguai.goutoujunshi.floating.T33NativeUi.tap(com.shengjidaguai.goutoujunshi.floating.T33NativeUi.scrollTo(text(com.shengjidaguai.goutoujunshi.designsystem.R.string.ds_open_full),actualHandle.windowId))
                val fullDeadline=SystemClock.uptimeMillis()+15_000
                compose.waitUntil(15000) {
                    mainAt(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.lifecycle.Lifecycle.State.RESUMED) &&
                        resumedComponent()==actualMain.componentName && instrumentation.uiAutomation.windows.any {
                            it.isActive && it.isFocused && it.root?.packageName?.toString()==context.packageName
                        }
                }
                check(SystemClock.uptimeMillis()<=fullDeadline) {"Full return exceeded its original 15s deadline"}
                println("T55_FULL_MAIN_READY instance=${System.identityHashCode(actualMain)} resumedFocused=true remainingMs=${fullDeadline-SystemClock.uptimeMillis()}")
                assertTrue("the user's real Full action must reach Main before immediate locking",controller.state.value.lockState.unlocked)
                instrumentation.runOnMainSync {assertTrue(actualMain.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)}
                shell("input keyevent 3")
                compose.waitUntil(15000) {!controller.state.value.lockState.unlocked && !floating.active.value && android.view.inspector.WindowInspector.getGlobalWindowViews().none {(it.layoutParams as? WindowManager.LayoutParams)?.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY}}
                assertNull(controller.state.value.currentContact)
                println("T55_NATIVE immediate_real_external_capture_secure_panel_full_home_locks_pass")
                } catch(failure:Throwable) {
                    println("T55_DEFAULT_FAILURE beforeScenarioClose=true")
                    captureAuthFailure();failureCapturedBeforeClose=true;throw failure
                } finally {
                    // Full uses production onNewIntent/setIntent. Scenario's launch-Intent observer
                    // can miss those events; restore only for cleanup and finish the actual instance.
                    instrumentation.runOnMainSync {
                        actualMain.intent=Intent(checkNotNull(originalLaunchIntent))
                        if(!actualMain.isFinishing && !actualMain.isDestroyed) actualMain.finish()
                    }
                    compose.waitUntil(15000) {mainAt(androidx.test.runner.lifecycle.Stage.DESTROYED,androidx.lifecycle.Lifecycle.State.DESTROYED)}
                    println("T55_MAIN_TEARDOWN actualDestroyed=true launchIntentRestored=true")
                }
            }
        } catch(failure:Throwable) {if(!failureCapturedBeforeClose) captureAuthFailure();throw failure}
        finally {
            com.shengjidaguai.goutoujunshi.floating.T33NativeUi.pump=oldPump
            com.shengjidaguai.goutoujunshi.floating.SystemProjectionConsent.cancelOwnDialog()
            context.startActivity(Intent("T55_FINISH_EXTERNAL_SYNTHETIC_CHAT").setComponent(external).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            instrumentation.runOnMainSync {floating.stop(context);PrivacyRuntime.setDelay(oldDelay)}
        }
    }
    @Test fun realCredentialProtectsMainAndActualRecentsPixelsWithPositiveControl() {
        ActivityScenario.launch(MainActivity::class.java).use {main ->
            var probe:android.view.View?=null
            main.onActivity {activity ->
                probe=object:android.view.View(activity) {
                    private val paint=android.graphics.Paint()
                    override fun onDraw(canvas:android.graphics.Canvas) {
                        super.onDraw(canvas)
                        for(y in 0 until 12) for(x in 0 until 12) {
                            paint.color=if((x+y)%2==0) android.graphics.Color.MAGENTA else android.graphics.Color.CYAN
                            canvas.drawRect(x*width/12f,y*height/12f,(x+1)*width/12f,(y+1)*height/12f,paint)
                        }
                    }
                }
                (activity.window.decorView as android.view.ViewGroup).addView(probe,android.view.ViewGroup.LayoutParams(-1,-1))
            }
            try {
                until {main.state==androidx.lifecycle.Lifecycle.State.RESUMED}
                SystemClock.sleep(500)
                val positive=shot("main-before-lock");assertTrue("positive control must show the private probe",hasProbe(positive));positive.recycle()
                showRecents()
                val recentPositive=shot("recents-before-lock");assertTrue("the real recents pipeline must retain the unprotected probe",hasProbe(recentPositive));recentPositive.recycle()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                until {main.state==androidx.lifecycle.Lifecycle.State.RESUMED}
                launchAuthentication();enterSyntheticPin()
                until {PrivacyRuntime.enabled && controller.state.value.lockState.unlocked && main.state==androidx.lifecycle.Lifecycle.State.RESUMED}
                SystemClock.sleep(500)
                val secured=shot("main-after-real-credential");assertFalse("secure Main must hide probe pixels",hasProbe(secured));secured.recycle()
                showRecents()
                val recentSecured=shot("recents-after-real-credential");assertFalse("real recents must hide private probe pixels",hasProbe(recentSecured));recentSecured.recycle()
                println("T55_NATIVE real_credential_main_and_recents_pixel_controls_pass")
            } finally {main.onActivity {activity ->probe?.let {(activity.window.decorView as android.view.ViewGroup).removeView(it)}}}
        }
    }
    private fun showRecents() {
        val service=instrumentation.uiAutomation.serviceInfo
        service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo=service
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_APP_SWITCH)
        until {com.shengjidaguai.goutoujunshi.floating.SystemProjectionConsent.nodes().any {it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/overview_panel")==true}}
        until {com.shengjidaguai.goutoujunshi.floating.SystemProjectionConsent.nodes().any {node ->
            node.isVisibleToUser && (node.text?.toString()?.contains(context.getString(com.shengjidaguai.goutoujunshi.R.string.app_name))==true || node.contentDescription?.toString()?.contains(context.getString(com.shengjidaguai.goutoujunshi.R.string.app_name))==true)
        }}
        SystemClock.sleep(700)
    }
    private val evidenceId=java.util.UUID.randomUUID().toString()
    private fun shot(label:String):android.graphics.Bitmap {
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val relative="cache/t55-native-evidence/$evidenceId-$label.png"
        val file=java.io.File(context.cacheDir,"t55-native-evidence/$evidenceId-$label.png")
        file.parentFile!!.mkdirs();file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {"%02x".format(it)}
        println("T55_PIXEL_EVIDENCE label=$label relative=$relative sha256=$sha")
        return bitmap
    }
    private fun hasProbe(bitmap:android.graphics.Bitmap):Boolean {
        var magenta=0;var cyan=0;var horizontal=0;var vertical=0
        val previousRow=IntArray((bitmap.width+2)/3)
        for(y in 0 until bitmap.height step 3) {
            var previous=0
            for(x in 0 until bitmap.width step 3) {
                val pixel=bitmap.getPixel(x,y);val r=android.graphics.Color.red(pixel);val g=android.graphics.Color.green(pixel);val b=android.graphics.Color.blue(pixel)
                val color=when {
                    r>35 && b>35 && g<minOf(r,b)*.3f -> {magenta++;1}
                    g>35 && b>35 && r<minOf(g,b)*.3f -> {cyan++;2}
                    else -> 0
                }
                if(color!=0 && previous!=0 && color!=previous) horizontal++
                if(color!=0 && previousRow[x/3]!=0 && color!=previousRow[x/3]) vertical++
                previous=color;previousRow[x/3]=color
            }
        }
        return magenta>64 && cyan>64 && horizontal>100 && vertical>100
    }
}
