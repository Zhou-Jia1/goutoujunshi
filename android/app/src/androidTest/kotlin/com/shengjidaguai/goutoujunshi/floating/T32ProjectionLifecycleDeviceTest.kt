package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.capture.SyntheticChatActivity
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.domain.Speaker
import com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Only run on the empty, task-owned API35+ fixture. All shared pixels are synthetic. */
@SdkSuppress(minSdkVersion = 35)
class T32ProjectionLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FloatingCounselActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val automation get() = instrumentation.uiAutomation
    private fun text(id: Int) = app.getString(id)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private val frames = AtomicInteger()
    private val size = AtomicReference(0 to 0)
    private var selectorRequestTask: String? = null

    @After fun cleanup() {
        // A failed selector must not leave its SystemUI modal blocking the next test.
        try {
            selectorRequestTask?.let { task ->
                if (ownSelector(task)) check(automation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            }
            SystemProjectionConsent.cancelOwnDialog()
        }
        finally { main { FloatingRuntime.frameObserver = null; FloatingRuntime.stop(app) } }
    }

    private fun node(label: String, owner: String = app.packageName): AccessibilityNodeInfo {
        val ball = label == text(R.string.floating_ball_menu)
        try { return matchingNode(includeOverlay = ball) {
            val bounds = Rect().also(it::getBoundsInScreen)
            val safe = safeBounds()
            it.packageName?.toString() == owner &&
            (!ball || safe.left in bounds.left..bounds.right || safe.right in bounds.left..bounds.right) &&
            it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
        } catch (failure: IllegalStateException) {
            if (!ball) throw failure
            val screenshot = checkNotNull(automation.takeScreenshot())
            val physical = Rect(0, 0, screenshot.width, screenshot.height)
            screenshot.recycle()
            val geometries = SystemProjectionConsent.nodes().filter {
                it.packageName?.toString() == owner && it.contentDescription?.toString() == label
            }.map { candidate ->
                val bounds = Rect().also(candidate::getBoundsInScreen)
                val window = candidate.window
                val windowBounds = Rect().also { window?.getBoundsInScreen(it) }
                "bounds=$bounds, window=$windowBounds, display=${window?.displayId}, visible=${candidate.isVisibleToUser}"
            }
            throw AssertionError("Ball geometry: default=${screenBounds()}, physical=$physical, nodes=$geometries", failure)
        }
    }

    private fun activeNodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::walk).orEmpty() }
        return automation.rootInActiveWindow?.let(::walk).orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun screenBounds(): Rect {
        val dimensions = android.graphics.Point()
        checkNotNull(app.getSystemService(DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)).getRealSize(dimensions)
        return Rect(0, 0, dimensions.x, dimensions.y)
    }

    private fun safeBounds(): Rect {
        val metrics = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        return Rect(metrics.bounds).apply { inset(insets) }
    }

    private fun touchBounds(node: AccessibilityNodeInfo, partial: Boolean): Rect? {
        val bounds = Rect().also(node::getBoundsInScreen)
        val visible = screenBounds()
        // A docked ball can be drawn over the navigation/cutout area, where a
        // higher system window receives input. Tap only its usable inner half.
        if (partial && !visible.intersect(safeBounds())) return null
        node.window?.let { window ->
            val windowBounds = Rect().also(window::getBoundsInScreen)
            if (!visible.intersect(windowBounds)) return null
        }
        if (!partial && !visible.contains(bounds)) return null
        return bounds.takeIf { !it.isEmpty && it.intersect(visible) }
    }

    private fun matchingNode(includeOverlay: Boolean = false,
        predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var selected: AccessibilityNodeInfo? = null
        var previousGeometry: String? = null
        var stableFrames = 0
        try { SystemProjectionConsent.waitFor {
            // Rotation changes an overlay asynchronously. Fetch fresh geometry;
            // an old portrait edge can still lie inside the landscape display.
            // Docking follows the OS safe edge, including cutout/navigation insets.
            if (includeOverlay) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                check(automation.clearCache())
            }
            val candidates = (if (includeOverlay) SystemProjectionConsent.nodes() else activeNodes())
                .filter(predicate)
            val candidate = candidates.filter { touchBounds(it, includeOverlay) != null }.singleOrNull()
            if (includeOverlay && candidate != null) {
                val bounds = Rect().also(candidate::getBoundsInScreen)
                val windowBounds = Rect().also { candidate.window?.getBoundsInScreen(it) }
                val geometry = "${screenBounds()}|${safeBounds()}|$bounds|$windowBounds"
                stableFrames = if (geometry == previousGeometry) stableFrames + 1 else 0
                previousGeometry = geometry
                selected = candidate.takeIf { stableFrames >= 3 }
            } else {
                previousGeometry = null
                stableFrames = 0
                selected = candidate
            }
            if (selected == null && !includeOverlay && candidates.size == 1) {
                val candidate = candidates.single()
                if (!candidate.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
                    var parent = candidate.parent
                    while (parent != null && !parent.isScrollable) parent = parent.parent
                    parent?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
            selected != null
        } } catch (failure: IllegalStateException) {
            if (!includeOverlay) throw failure
            val windows = automation.windows.map { window ->
                val bounds = Rect().also(window::getBoundsInScreen)
                "type=${window.type}, display=${window.displayId}, bounds=$bounds"
            }
            val balls = SystemProjectionConsent.nodes().filter {
                it.packageName?.toString() == app.packageName &&
                    it.contentDescription?.toString() == text(R.string.floating_ball_menu)
            }.map { candidate ->
                val bounds = Rect().also(candidate::getBoundsInScreen)
                "bounds=$bounds, visible=${candidate.isVisibleToUser}, touch=${touchBounds(candidate, true)}"
            }
            throw IllegalStateException("Overlay geometry: screen=${screenBounds()}, safe=${safeBounds()}, " +
                "active=${FloatingRuntime.active.value}, windows=$windows, balls=$balls", failure)
        }
        return checkNotNull(selected)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = checkNotNull(touchBounds(node,
            node.contentDescription?.toString() == text(R.string.floating_ball_menu))) {
            "Fixture control moved outside its display or window before input"
        }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            check(automation.injectInputEvent(down, true)) { "Physical DOWN failed at fixture bounds $bounds" }
            SystemClock.sleep(60)
            val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            try { check(automation.injectInputEvent(up, true)) } finally { up.recycle() }
        } finally { down.recycle() }
    }

    private fun activityDump() = automation.executeShellCommand("dumpsys activity activities").use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText() }
    }

    private fun dockBall(right: Boolean) {
        val ball = node(text(R.string.floating_ball_menu))
        val from = checkNotNull(touchBounds(ball, true))
        val safe = safeBounds()
        val x = from.centerX().toFloat()
        val y = from.centerY().toFloat()
        val target = (if (right) safe.right - from.width() / 2 else safe.left + from.width() / 2)
            .coerceIn(safe.left + 1, safe.right - 1).toFloat()
        var capturesBefore = 0
        main { capturesBefore = checkNotNull(FloatingRuntime.debugSnapshot()).calls }
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, eventX: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, eventX, y, 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, x)
        SystemClock.sleep(60)
        // Move through the middle even if already docked on the requested side,
        // so this gesture cannot accidentally become a capture tap.
        send(MotionEvent.ACTION_MOVE, safe.centerX().toFloat())
        SystemClock.sleep(60)
        send(MotionEvent.ACTION_MOVE, target)
        SystemClock.sleep(60)
        send(MotionEvent.ACTION_UP, target)
        main { assertEquals("Dragging must not capture", capturesBefore, checkNotNull(FloatingRuntime.debugSnapshot()).calls) }
        matchingNode(includeOverlay = true) { candidate ->
            val bounds = Rect().also(candidate::getBoundsInScreen)
            val edge = if (right) safeBounds().right else safeBounds().left
            candidate.packageName?.toString() == app.packageName && candidate.isVisibleToUser &&
                candidate.contentDescription?.toString() == text(R.string.floating_ball_menu) &&
                edge in bounds.left..bounds.right
        }
    }

    private fun resumed(dump: String): String? =
        Regex("(?:topResumedActivity=|mResumedActivity:)\\s*ActivityRecord\\{([^\\n]+)")
            .find(dump)?.groupValues?.get(1)

    private fun permissionTap(node: AccessibilityNodeInfo) {
        check(SystemProjectionConsent.ownDialog()) { "Projection caller changed before consent" }
        tap(node)
    }

    private fun ownSelector(requestTask: String): Boolean {
        val dump = activityDump()
        val record = resumed(dump) ?: return false
        if (!record.contains("$SYSTEM_UI/.mediaprojection.appselector.MediaProjectionAppSelectorActivity") ||
            Regex("\\bt(\\d+)\\b").find(record)?.groupValues?.get(1) != requestTask) return false
        val token = record.substringBefore(' ')
        return Regex("(?=\\s*\\* Hist\\s+#\\d+:)").split(dump).any { block ->
            block.trimStart().lineSequence().firstOrNull()?.contains("ActivityRecord{$token ") == true &&
                Regex("launchedFromPackage=([^\\s]+)").find(block)?.groupValues?.get(1) == SYSTEM_UI
        }
    }

    private fun bringSynthetic() {
        app.startActivity(Intent(app, SyntheticChatActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        SystemProjectionConsent.waitFor { resumed(activityDump())?.contains(
            "com.shengjidaguai.goutoujunshi.capture.SyntheticChatActivity") == true }
        node(text(R.string.capture_synthetic_title))
    }

    private fun start(fullDisplay: Boolean) {
        compose.onNodeWithText(text(R.string.floating_start)).performClick()
        compose.onNodeWithText(text(R.string.guide_continue)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.guide_skip)).performScrollTo().performClick()
        if (fullDisplay) SystemProjectionConsent.respond(true) else {
            // Explicit single-app choice, followed by the uniquely labelled synthetic task.
            val spinner = matchingNode {
                it.packageName?.toString() == SYSTEM_UI &&
                    it.viewIdResourceName == "$SYSTEM_UI:id/screen_share_mode_spinner" }
            permissionTap(spinner)
            val options = setOf("A single app", "单个应用", "一个应用")
            permissionTap(matchingNode {
                it.packageName?.toString() == SYSTEM_UI && it.text?.toString() in options })
            check(SystemProjectionConsent.ownDialog())
            val requestTask = checkNotNull(Regex("\\bt(\\d+)\\b")
                .find(checkNotNull(resumed(activityDump())))?.groupValues?.get(1))
            selectorRequestTask = requestTask
            permissionTap(matchingNode {
                it.packageName?.toString() == SYSTEM_UI && it.viewIdResourceName == "android:id/button1" })
            SystemProjectionConsent.waitFor { ownSelector(requestTask) }
            // AOSP labels recent-task thumbnails with the application label rather
            // than Activity/TaskDescription labels. Require our only selectable
            // app task to be the synthetic target before selecting that card.
            val ownTasks = app.getSystemService(ActivityManager::class.java).appTasks.mapNotNull { it.taskInfo }
                .filter { it.topActivity?.packageName == app.packageName }
            assertEquals(1, ownTasks.size)
            assertEquals(SyntheticChatActivity::class.java.name, ownTasks.single().topActivity?.className)
            val appLabel = app.applicationInfo.loadLabel(app.packageManager).toString()
            val taskCard = matchingNode {
                it.packageName?.toString() == SYSTEM_UI && it.isVisibleToUser && it.isClickable &&
                    it.contentDescription?.toString() == appLabel }
            check(ownSelector(requestTask))
            tap(taskCard)
        }
        SystemProjectionConsent.waitFor { FloatingRuntime.active.value }
        main { FloatingRuntime.frameObserver = { _, bitmap ->
            size.set(bitmap.width to bitmap.height); frames.incrementAndGet()
        } }
    }

    private fun displayId(): Int {
        val display = app.getSystemService(DisplayManager::class.java).displays
            .filter { it.name == "Junshi single frame" }
        assertEquals("Exactly one real VirtualDisplay must exist", 1, display.size)
        return display.single().displayId
    }

    private fun capture(expectedPages: Int) {
        val before = frames.get()
        val ownBefore = CaptureOwnUi.snapshot()
        val authorityBefore = CounselRuntime.get(app).importAuthority
        val phases = mutableSetOf<CaptureState.Phase>()
        val ball = node(text(R.string.floating_ball_menu))
        val ballBounds = Rect().also(ball::getBoundsInScreen)
        val displayBounds = screenBounds()
        tap(ball)
        val imports = CounselRuntime.imports(app)
        try {
            SystemProjectionConsent.waitFor(20_000) {
                phases += FloatingRuntime.session.capture.phase
                frames.get() > before
            }
            SystemProjectionConsent.waitFor(120_000) {
                phases += FloatingRuntime.session.capture.phase
                frames.get() > before && imports.state.value?.let {
                    it.recognized && it.imageIds.size == expectedPages
                } == true && FloatingRuntime.session.capture.phase == CaptureState.Phase.READY
            }
        } catch (failure: IllegalStateException) {
            val preview = imports.state.value
            var captureDebug: CaptureDebugSnapshot? = null
            main { captureDebug = FloatingRuntime.debugSnapshot() }
            val displays = app.getSystemService(DisplayManager::class.java).displays
                .filter { it.name == "Junshi single frame" }
                .map { "${it.displayId}:${it.mode.physicalWidth}x${it.mode.physicalHeight}" }
            throw AssertionError("Capture stage: frames=$before/${frames.get()}, size=${size.get()}, " +
                "phase=${FloatingRuntime.session.capture.phase}, active=${FloatingRuntime.active.value}, " +
                "phases=$phases, " +
                "pages=${preview?.imageIds?.size}/$expectedPages, recognized=${preview?.recognized}, " +
                "busy=${preview?.busy}, errorPresent=${preview?.error != null}, " +
                "ownUi=$ownBefore/${CaptureOwnUi.snapshot()}, notice=${CaptureOwnUi.notice.value}, " +
                "authority=$authorityBefore/${CounselRuntime.get(app).importAuthority}, " +
                "displays=$displays, ball=$ballBounds, display=$displayBounds, debug=$captureDebug", failure)
        }
        // READY means the service has queued startActivity, not that its confirmation
        // window has resumed. Wait for that actual UI before returning to the chat.
        SystemProjectionConsent.waitFor { resumed(activityDump())?.contains(
            FloatingCounselActivity::class.java.name) == true }
        compose.onNodeWithText(text(R.string.import_title)).assertIsDisplayed()
    }

    private fun reviewFixtureControlRows() {
        val imports = CounselRuntime.imports(app)
        val preview = checkNotNull(imports.state.value)
        val control = text(R.string.capture_synthetic_earlier).substringBefore('（')
        val labels = setOf(text(R.string.capture_synthetic_title), text(R.string.capture_synthetic_peer))
        // Exercise the same reversible UI-row review available to a user. This
        // fixture has a title, peer labels and a navigation button outside its
        // message bodies. These are explicitly reviewed, not auto-filtered.
        // No chat text, speaker, confidence or message ordering is edited here.
        val controls = preview.rows.filter { !it.excluded &&
            (it.text.trim() in labels || it.text.filterNot(Char::isWhitespace).contains(control)) }
        main { controls.forEach { row ->
            imports.editRow(preview.id, row.id) { it.copy(excluded = true, exclusionOverride = true) }
        } }
    }

    @Test fun twoPhysicalScreensPrependWithoutDuplicateAndKeepSpeakers() {
        start(fullDisplay = true)
        ActivityScenario.launch(SyntheticChatActivity::class.java).use {
            node(text(R.string.capture_synthetic_title))
            val originalDisplay = displayId()
            capture(1)
            reviewFixtureControlRows()
            bringSynthetic()
            // Page navigation belongs only to this fictional test target. Exercise
            // its displayed public UI action; every capture still uses real
            // pointer injection into the production floating ball.
            compose.onNodeWithText(text(R.string.capture_synthetic_earlier))
                .assertIsDisplayed().performClick()
            // A clickable message merges its speaker and body into one Android
            // accessibility node; Compose retains the individual Text semantics.
            compose.onNodeWithText(text(R.string.capture_synthetic_prior)).assertIsDisplayed()
            capture(2)
            reviewFixtureControlRows()
            val imports = CounselRuntime.imports(app)
            val preview = checkNotNull(imports.state.value)
            val rows = preview.rows.filterNot { it.excluded }
            // Fixed classifications and digests diagnose OCR/identity boundaries
            // without logging even the synthetic recognized text.
            fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.trim().toByteArray()).take(6).joinToString("") { "%02x".format(it) }
            val boundaries = preview.rows.map { row ->
                "page=${preview.imageIds.indexOf(row.imageId)}, len=${row.text.length}, " +
                    "text=${digest(row.text)}, name=${digest(row.name)}, side=${row.side}, " +
                    "excluded=${row.excluded}, override=${row.exclusionOverride}, prior=${row.text.contains("松鼠")}, " +
                    "overlap=${row.text.contains("周五")}, later=${row.text.contains("三点")}"
            }
            assertEquals("Two-page boundaries: $boundaries", 1, rows.count { it.text.contains("周五") })
            val prior = rows.indexOfFirst { it.text.contains("松鼠") }
            val overlap = rows.indexOfFirst { it.text.contains("周五") }
            val later = rows.indexOfFirst { it.text.contains("三点") }
            val priorRows = preview.rows.filter { it.text.contains("松鼠") }
            assertTrue("Synthetic page order: frame=${size.get()}, indices=$prior/$overlap/$later, " +
                "priorRows=${priorRows.size}, priorExcluded=${priorRows.count { it.excluded }}",
                prior >= 0 && prior < overlap && overlap < later)
            main { imports.side(preview.id, OcrSide.RIGHT) }
            val confirmed = checkNotNull(imports.state.value).rows
            assertEquals(Speaker.ME, confirmed.first { it.text.contains("三点") }.speaker)
            assertEquals(Speaker.THEM, confirmed.first { it.text.contains("松鼠") }.speaker)
            assertEquals(originalDisplay, displayId())
            assertNull(CounselRuntime.get(app).state.value.currentContact)
            assertNull(CounselRuntime.get(app).state.value.currentRequest)
        }
    }

    @Test fun singleApplicationSharingResizesExistingDisplayOnRotation() {
        val resolver = app.contentResolver
        val originalAuto = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        val originalRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
        try {
            check(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
            ActivityScenario.launch(SyntheticChatActivity::class.java).use {
                node(text(R.string.capture_synthetic_title))
                app.startActivity(Intent(app, FloatingCounselActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                node(text(R.string.floating_start))
                start(fullDisplay = false)
                bringSynthetic()
                val originalDisplay = displayId()
                capture(1)
                val portrait = size.get()
                assertTrue(portrait.second > portrait.first)
                bringSynthetic()
                check(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                SystemProjectionConsent.waitFor { app.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                dockBall(right = true)
                capture(2)
                val landscape = size.get()
                assertTrue(landscape.first > landscape.second)
                assertEquals(originalDisplay, displayId())
                bringSynthetic()
                dockBall(right = false)
                capture(3)
                assertTrue(size.get().first > size.get().second)
                assertEquals(originalDisplay, displayId())
                main { FloatingRuntime.stop(app) }
                SystemProjectionConsent.waitFor { !FloatingRuntime.active.value &&
                    app.getSystemService(NotificationManager::class.java).activeNotifications.none {
                        it.id == FloatingCounselService.NOTIFICATION } }
            }
        } finally {
            automation.setRotation(originalRotation)
            if (originalAuto == 1) automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private companion object { const val SYSTEM_UI = "com.android.systemui" }
}
