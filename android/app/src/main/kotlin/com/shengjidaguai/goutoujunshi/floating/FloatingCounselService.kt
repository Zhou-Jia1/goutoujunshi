package com.shengjidaguai.goutoujunshi.floating

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.designsystem.*
import kotlinx.coroutines.*

class FloatingCounselService : Service() {
    private var window: FloatingBallWindow? = null
    private var owners: OverlayOwners? = null
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var receiverRegistered = false
    private var ownedTicket: Long? = null
    private var appOps: AppOpsManager? = null
    private val overlayChanged=AppOpsManager.OnOpChangedListener { _, packageName ->
        if(packageName==this.packageName) Handler(Looper.getMainLooper()).post { if(!Settings.canDrawOverlays(this)) close() }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { close() }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF))
        receiverRegistered = true
        appOps=getSystemService(AppOpsManager::class.java).also { it.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,packageName,overlayChanged) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { close(); return START_NOT_STICKY }
        val ticket=intent?.getLongExtra(TICKET,-1) ?: -1
        if (!FloatingRuntime.session.accept(ticket)) {
            if (window == null) { cleanup(); stopSelf(startId) }
            return START_NOT_STICKY
        }
        ownedTicket=ticket
        if (!Settings.canDrawOverlays(this) || getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !floatingContentAllowed(CounselRuntime.get(application).state.value.lockState)) {
            fail(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.floating_title), NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(com.shengjidaguai.goutoujunshi.designsystem.R.drawable.ic_junshi_monochrome)
                .setContentTitle(getString(R.string.floating_notification)).setContentIntent(stop).setOngoing(true).setShowWhen(false)
                .apply { if(Build.VERSION.SDK_INT>=31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }.build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
            if (window == null) attach()
            FloatingRuntime.active.value = true
        } catch (_: RuntimeException) { fail() }
        return START_NOT_STICKY
    }
    private fun geometry(): FloatingBounds {
        val density = resources.displayMetrics.density
        val size = (JunshiLayout.touch.value * 2 * density).toInt()
        val reachable = (JunshiLayout.touch.value * density).toInt()
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.maximumWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            return FloatingBounds(inset.left, inset.top, metrics.bounds.width() - inset.right, metrics.bounds.height() - inset.bottom, size, reachable)
        }
        val display = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(display)
        val safe = (JunshiLayout.touch.value * density).toInt()
        val insets=window?.rootWindowInsets
        @Suppress("DEPRECATION")
        return FloatingBounds(insets?.systemWindowInsetLeft ?: 0, insets?.systemWindowInsetTop ?: safe,
            display.widthPixels-(insets?.systemWindowInsetRight ?: 0),display.heightPixels-(insets?.systemWindowInsetBottom ?: safe),size,reachable)
    }
    private fun positionKey() = "${resources.configuration.orientation}"
    private fun restorePosition() {
        val bounds = geometry()
        val prefs = getSharedPreferences(POSITIONS, 0)
        val side = prefs.getBoolean("${positionKey()}-right", true)
        val fraction = prefs.getFloat("${positionKey()}-y", .4f).coerceIn(0f, 1f)
        val point = bounds.dock(if(side) bounds.right else bounds.left - bounds.size, (bounds.top + fraction * maxOf(0, bounds.bottom - bounds.top - bounds.size)).toInt())
        params.x = point.x; params.y = point.y
    }
    private fun attach() {
        val bounds = geometry()
        params = WindowManager.LayoutParams(bounds.size, bounds.size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            // Params and geometry both use display coordinates; system/cutout insets are applied
            // exactly once in geometry, never again by the WM parent frame.
            if(Build.VERSION.SDK_INT>=30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if(Build.VERSION.SDK_INT>=28) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        restorePosition()
        val owner = OverlayOwners().also { owners = it }
        val view = FloatingBallWindow(this).also { window = it }
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.composeView.setViewTreeLifecycleOwner(owner)
        view.composeView.setViewTreeViewModelStoreOwner(owner)
        view.composeView.setViewTreeSavedStateRegistryOwner(owner)
        view.composeView.setContent { JunshiTheme { Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
            FloatingMenuBall(if(params.x < 0) Side.LEFT else Side.RIGHT,::openMenu)
        } } }
        var startX=0; var startY=0; var downX=0f; var downY=0f; var moved=false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: android.view.MotionEvent) = true
            override fun onSingleTapUp(event: android.view.MotionEvent): Boolean { if(!moved) openMenu(); return true }
            override fun onLongPress(event: android.view.MotionEvent) { if(!moved) openMenu() }
        })
        view.handleMotion = motion@{ event ->
            if (!Settings.canDrawOverlays(this)) { close(); return@motion true }
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=params.x; startY=params.y; downX=event.rawX; downY=event.rawY; moved=false }
                MotionEvent.ACTION_MOVE -> {
                    val dx=event.rawX-downX; val dy=event.rawY-downY
                    if(kotlin.math.hypot(dx,dy)>ViewConfiguration.get(this).scaledTouchSlop) moved=true
                    if(moved) { params.x=startX+dx.toInt(); params.y=startY+dy.toInt(); updateWindow() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if(moved) dock()
            }
            detector.onTouchEvent(event)
            true
        }
        wm.addView(view, params)
        scope.launch {
            CounselRuntime.get(application).state.collect { state ->
                if (!floatingContentAllowed(state.lockState)) {
                    // The ball contains no private content; any Activity/menu is gated separately.
                    if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) close()
                }
            }
        }
    }
    private fun openMenu() { if(window!=null && FloatingRuntime.active.value) FloatingRuntime.open(this, contacts = true) }
    private fun dock() {
        val bounds=geometry(); val point=bounds.dock(params.x,params.y)
        params.x=point.x; params.y=point.y
        getSharedPreferences(POSITIONS,0).edit().putBoolean("${positionKey()}-right",point.x>=bounds.left)
            .putFloat("${positionKey()}-y",(point.y-bounds.top).toFloat()/maxOf(1,bounds.bottom-bounds.top-bounds.size)).apply()
        updateWindow()
    }
    private fun updateWindow() { try { window?.let { wm.updateViewLayout(it,params) } } catch (_:RuntimeException) { close() } }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if(window!=null) { val bounds=geometry(); params.width=bounds.size; params.height=bounds.size; restorePosition(); updateWindow() } }
    private fun close() { FloatingRuntime.session.stop(); cleanup(); stopSelf() }
    private fun fail() { ownedTicket?.let(FloatingRuntime.session::cancel); cleanup(); stopSelf() }
    private fun cleanup() {
        FloatingRuntime.active.value=false
        window?.let { view -> window=null; view.handleMotion={false}; try { wm.removeViewImmediate(view) } catch (_: RuntimeException) { }; view.composeView.disposeComposition() }
        owners?.destroy(); owners=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
    override fun onDestroy() { ownedTicket?.let(FloatingRuntime.session::cancel); scope.cancel(); cleanup(); appOps?.stopWatchingMode(overlayChanged); appOps=null; if(receiverRegistered) { unregisterReceiver(screen); receiverRegistered=false }; super.onDestroy() }
    companion object {
        const val TICKET="ticket"
        const val STOP="com.shengjidaguai.goutoujunshi.FLOATING_STOP"
        const val NOTIFICATION=3101
        private const val CHANNEL="floating-counsel"
        internal const val POSITIONS="floating-position"
    }
}
