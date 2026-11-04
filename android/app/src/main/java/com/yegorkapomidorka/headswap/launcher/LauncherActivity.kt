package com.yegorkapomidorka.headswap.launcher

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import com.yegorkapomidorka.headswap.dex.DexState

/**
 * Home screen Headswap Desktop (Compose) on the virtual display head unit. Runs only on this display,
 * The phone does not open anything and the home phone application does not become.
 * API 30+ (display, insetsController): activity starts only in Desktop mode, and it is available with Android 13.
 */
@SuppressLint("NewApi")
class LauncherActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var connected by mutableStateOf(false)
    private var visible = false
    private var ownershipDeadline = 0L

    private val awaitOwnership = object : Runnable {
        override fun run() {
            if (!visible || isDestroyed) return
            when {
                ownsDisplay() -> connected = true
                currentDisplayId() <= 0 || SystemClock.elapsedRealtime() >= ownershipDeadline -> finish()
                else -> main.postDelayed(this, 100)
            }
        }
    }

    /** Locking the phone during the session: the log shows what happened to the screen and whether the desktop holds a security key. */
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
            AppLog.i("SCREEN ${intent.action?.substringAfterLast('.')} keyguard=$locked дисплей=${currentDisplayId()} " +
                "активность=${if (visible) "видна" else "не видна"} подключён=$connected", false)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shell may launch this Activity before the connector receives start()'s display ID.
        // Render a neutral first frame; reveal the launcher only after the client confirms ownership.
        if (currentDisplayId() <= 0) { finish(); return }
        AppLog.init(this)
        // The desktop remains on the screen when the phone is locked (the display itself is not blocked: ALWAYS_UNLOCKED).
        setShowWhenLocked(true)
        ContextCompat.registerReceiver(this, screenEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        LauncherModel.init(this)
        ForeignActions.reload(this, currentDisplayId(), goHome = false) // directly in foreign launcher, if it is selected
        // child windows live with head unit display density
        val dpi = resources.displayMetrics.densityDpi.coerceIn(96, 1280)
        LauncherModel.densityDpi = dpi
        EmbedHost.densityDpi = dpi
        window.setDecorFitsSystemWindows(false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back inside the launcher must not close it and leave the video surface empty.
            override fun handleOnBackPressed() { LauncherActions.hardwareBack() }
        })
        setContent { LauncherRoot(connected) }
    }

    /**
     * Here come HOME head unit and launcher change in phone settings (both via showDesktop): selection from DexPrefs is reread
     * and in the alien mode launcher is performed by "Home".
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (currentDisplayId() <= 0) return
        AppLog.i("LAUNCHER onNewIntent: HOME или смена лаунчера", false)
        ForeignActions.reload(this, currentDisplayId(), goHome = true)
    }

    override fun onResume() {
        super.onResume()
        if (currentDisplayId() <= 0) { finish(); return }
        ForeignActions.reload(this, currentDisplayId(), goHome = false) // “Home” only on onNewIntent, otherwise each return to the screen would reset launcher
        visible = true
        connected = ownsDisplay()
        AppLog.i("LAUNCHER активность на экране, дисплей=${currentDisplayId()}", false)
        LauncherModel.reloadCatalog() // during the work could put or remove the application
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ownershipDeadline = SystemClock.elapsedRealtime() + 10_000L
        main.removeCallbacks(awaitOwnership)
        main.post(awaitOwnership)
    }

    override fun onPause() {
        AppLog.i("LAUNCHER активность ушла с экрана (onPause), дисплей=${currentDisplayId()}", false)
        visible = false
        main.removeCallbacks(awaitOwnership)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenEvents) }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun currentDisplayId(): Int = display?.displayId ?: -1
    private fun ownsDisplay(): Boolean = DesktopAppCatalog.ownsDisplay(currentDisplayId(), DexState.displayId)
}
