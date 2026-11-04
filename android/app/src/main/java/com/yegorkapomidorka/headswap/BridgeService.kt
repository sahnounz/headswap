package com.yegorkapomidorka.headswap

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaConnector
import com.yegorkapomidorka.headswap.aa.AaCertsMissingException
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.aa.AapOptions
import com.yegorkapomidorka.headswap.aa.BridgeHost
import com.yegorkapomidorka.headswap.aa.BridgeMode
import com.yegorkapomidorka.headswap.aa.CallStateSource
import com.yegorkapomidorka.headswap.aa.CarPreset
import com.yegorkapomidorka.headswap.aa.DesktopVideoSelection
import com.yegorkapomidorka.headswap.aa.EqLive
import com.yegorkapomidorka.headswap.aa.MetaRelay
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.PhoneCall
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.RefDecoder
import com.yegorkapomidorka.headswap.aa.VideoGeometry
import com.yegorkapomidorka.headswap.dex.DelegatedDesktopAccess
import com.yegorkapomidorka.headswap.dex.DelegatedDexConnector
import com.yegorkapomidorka.headswap.dex.DesktopAccess
import com.yegorkapomidorka.headswap.dex.DesktopAudioCapture
import com.yegorkapomidorka.headswap.dex.DesktopAudioState
import com.yegorkapomidorka.headswap.dex.DesktopFailure
import com.yegorkapomidorka.headswap.dex.DesktopMusicPlayback
import com.yegorkapomidorka.headswap.dex.DesktopRecoveryPolicy
import com.yegorkapomidorka.headswap.dex.DexPrefs
import com.yegorkapomidorka.headswap.dex.DexState
import com.yegorkapomidorka.headswap.dex.UsbDetachPolicy
import com.yegorkapomidorka.headswap.link.CarTransport
import com.yegorkapomidorka.headswap.link.LinkSetupException
import com.yegorkapomidorka.headswap.link.StreamTransport
import com.yegorkapomidorka.headswap.link.Tcp6Listener
import com.yegorkapomidorka.headswap.link.TransportCaps
import com.yegorkapomidorka.headswap.link.bt.CarBtState
import com.yegorkapomidorka.headswap.link.bt.CarlifeBtProbe
import com.yegorkapomidorka.headswap.link.car.AutoOffDebounce
import com.yegorkapomidorka.headswap.link.car.BT_HINT_MS
import com.yegorkapomidorka.headswap.link.car.BtReconnectHint
import com.yegorkapomidorka.headswap.link.car.CAR_NET_DELAY_MS
import com.yegorkapomidorka.headswap.link.car.CAR_NET_TIMEOUT_MS
import com.yegorkapomidorka.headswap.link.car.CarConnMode
import com.yegorkapomidorka.headswap.link.car.CarMac
import com.yegorkapomidorka.headswap.link.car.CarNetEvent
import com.yegorkapomidorka.headswap.link.car.CarNetPolicy
import com.yegorkapomidorka.headswap.link.car.CarNetState
import com.yegorkapomidorka.headswap.link.car.CarNetwork
import com.yegorkapomidorka.headswap.link.car.CarProfile
import com.yegorkapomidorka.headswap.link.car.CarWifi
import com.yegorkapomidorka.headswap.link.diag.NetProbe
import com.yegorkapomidorka.headswap.link.diag.WLog
import com.yegorkapomidorka.headswap.link.wifi.HotspotBeacon
import com.yegorkapomidorka.headswap.link.wifi.LanIface
import com.yegorkapomidorka.headswap.ui.HeadswapNotification
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Foreground-service (connectedDevice): holds transport (USB-accessory or debug-TCP) and session with head unit.
 * AA-side of bridge (BridgeHost) lives on service time, not one USB-session: USB break-off closes
 * CarLife-session, AA remains live BridgeHost.GRACE_MS and is picked up by the next USB-session.
 * All state of service (session, host, stopRequested) touches only main thread.
 */
class BridgeService : Service() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: CarLifeSession? = null
    private var host: BridgeHost? = null
    private var stopRequested = false

    // --- mode Headswap Desktop (BridgeMode.DESKTOP); in mode Android Auto all below is not used ---
    /** Current/latest AA- or desktop-host and session mode: removed in startSession, not readable from prefs on the fly. Only main thread. */
    private var hostMode = BridgeMode.ANDROID_AUTO
    private var sessionMode = BridgeMode.ANDROID_AUTO
    /** In this instance of the service was desktop-session: onDestroy/finish clean its global state, even if the mode has already changed. */
    private var desktopUsed = false
    private var desktopAudioImpl: DesktopAudioCapture? = null
    private val desktopAudio: DesktopAudioCapture
        get() = desktopAudioImpl ?: DesktopAudioCapture(applicationContext).also { desktopAudioImpl = it }
    private var lastSessionWasUsb = false
    private var physicalUsbDetached = false
    private val desktopRecovery = DesktopRecoveryPolicy()
    private data class Recovery(val attempt: DesktopRecoveryPolicy.Attempt, val session: CarLifeSession, val owner: BridgeHost)
    private var pendingDesktopRecovery: Recovery? = null

    /** debug tcp6: listener is waiting for 6/6 (thread debug-tcp6); Stop it cancels. */
    @Volatile private var pendingTcp6: Tcp6Listener? = null
    /** tcp6/ wireless (start to 6/6 or fail); service is not off at this time. Only main thread. */
    private var linkWaiting = false
    /** Wireless (beta, B branch) is turned on by the user: after the break we are waiting for the car again. Writes main thread, reads and connector. */
    @Volatile private var wifiBMode = false
    /**
     * Auto mode: selected machine next to Bluetooth (ACL_CONNECTED), waiting for Wi-Fi hold while it is near.
     * Writes main thread, read status callbacks. Mirror in CarLifeState.carAuto for report.
     */
    @Volatile private var autoMode = false
        set(v) {
            field = v
            CarLifeState.carAuto = v
        }
    /** Waiting is started/accepted by auto mode: after leaving the machine it is not renewed. Only main thread. */
    private var autoStarted = false
    /** USB-session ended when the machine is near: through [USB_TO_AIR_MS] we will start to wait for wireless connection. Only main thread. */
    private var usbToAirPending = false
    private val usbToAirTick = Runnable { usbToAir() }
    private val autoOff = AutoOffDebounce(AUTO_OFF_DEBOUNCE_MS) { SystemClock.elapsedRealtime() }
    private val autoOffTick = Runnable { if (autoOff.fire()) applyAutoOff() }
    /** The prompt "connect the phone to the Wi-Fi machine" is already shown (once for auto mode). */
    private var hintShown = false
    private val hintTick = Runnable { showWifiHint() }
    /** Wi-Fi machine input (WifiNetworkSpecifier); created on the first request: without the machine network (and on USB it is not at all. */
    private var carNetImpl: CarNetwork? = null
    private val carNet: CarNetwork
        get() = carNetImpl ?: CarNetwork(this) { CarLifeState.carNet = it.label }.also { carNetImpl = it }
    private val carNetBusy: Boolean
        get() = carNetImpl?.state.let { it == CarNetState.REQUESTING || it == CarNetState.AVAILABLE }
    /** Start waiting for Wi-Fi (auto or manual): from it is considered [CAR_NET_DELAY_MS]. Only main thread. */
    private var waitSince = 0L
    /** When the machine was last in communication (USB/wireless connection session, machine network); 0 = was not. Only main thread. */
    private var carSeenAt = 0L
    /** In this expectation, the selected machine was connected to Bluetooth. Only main thread. */
    private var carBtNear = false
    /** 6/6 received, the session is about to start: the machine network request is no longer needed. Only main thread. */
    private var sixReady = false
    /** Waiting is deliberately interrupted for a new beacon in the machine network: the branch of failure in startTcp6 is not surrender. Only main thread. */
    private var restartingForCarNet = false
    private val carNetTick = Runnable { requestCarNet() }
    /** In this waiting head unit sent TARGET_INFO to Bluetooth: Wi-Fi Direct, the machine network is not needed. Only main thread. */
    private var p2pThisWait = false
    /** Wi-Fi Direct-machine (TARGET_INFO in this wait or profile): machine network is not requested, wifi- prompt is not shown. */
    private val wifiDirectCar: Boolean
        get() = p2pThisWait || CarProfile(this).connMode == CarConnMode.BT_CARLIFE
    /** `car-skip` is already recorded in this expectation. Only main thread. */
    private var carSkipLogged = false
    /** head unit connections to RFCOMM `carlife` in this wait and see if the "reconnect Bluetooth" prompt is shown. Only main thread. */
    private var btAcceptsThisWait = 0
    private var btHintShown = false
    /** Hanging notification "include Wi-Fi" (Wi-Fi Direct pinned in the disabled Wi-Fi). Only main thread. */
    private var wifiOffShown = false
    private val btHintTick = Runnable { checkBtHint() }
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioDiag = AudioDiag()
    /** A branch, diagnostics: RFCOMM `carlife` listen while waiting for the car on Wi-Fi. Kolbaki with bt-thread to switch to the main. */
    private val btProbe by lazy {
        CarlifeBtProbe(
            this,
            onAccept = { main.post { onBtAccept() } },
            onTargetInfo = { mac -> main.post { onBtTargetInfo(mac) } },
            onP2pBusy = { main.post { onP2pBusy() } },
            onWifi = { on -> main.post { onP2pWifi(on) } },
        )
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-grace").also { it.isDaemon = true } }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                AppLog.i("USB_ACCESSORY_DETACHED")
                if (sessionMode == BridgeMode.DESKTOP) {
                    // old message should not extinguish a new accessory or wireless session: no check list
                    observePhysicalUsbDetach("USB accessory detached broadcast")
                    return
                }
                // wireless session disconnection USB-accessory does not apply
                session?.takeIf { it.transportKind == TransportCaps.USB_AOA.kind }?.close("аксессуар отключён (broadcast)")
            }
        }
    }

    /** DESKTOP: USB mode is stopped physically; 8-second grace desktop and activation remain. */
    private fun observePhysicalUsbDetach(reason: String): Boolean {
        val present = runCatching { getSystemService(UsbManager::class.java).accessoryList.orEmpty().isNotEmpty() }.getOrNull()
        if (!UsbDetachPolicy.confirmedDetached(lastSessionWasUsb, present)) return false
        if (physicalUsbDetached) return true
        physicalUsbDetached = true
        DesktopMusicPlayback.physicalDetach(this)
        cancelDesktopRecovery()
        autoMode = false
        wifiBMode = false
        autoStarted = false
        usbToAirPending = false
        main.removeCallbacks(usbToAirTick)
        getSystemService(NotificationManager::class.java).cancel(USB_TO_AIR_NOTIF_ID)
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.STOP, "usb-detach")
        pendingTcp6?.abort()
        CarLifeState.wireless = null
        btProbe.stop("USB отключён")
        AppLog.i("DESKTOP USB отключён: рабочий стол держу 8 с, активация остаётся ($reason)", false)
        val current = session
        if (current?.transportKind == TransportCaps.USB_AOA.kind) current.close("физически отключён USB")
        else if (host == null) finish()      // grace and host closure leads BridgeHost itself
        return true
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        AppLog.init(this)
        AaPrefs(this).migrateTo30()        // the application could run from USB without opening (the same in MainActivity.onCreate)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_bridge), NotificationManager.IMPORTANCE_LOW))
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), Context.RECEIVER_EXPORTED)
        audioDiag.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DESKTOP_HOME) {
            // command applies only to live desktop-session: late click should not raise the service and a new desktop
            val accepted = !stopRequested && session != null && host?.showDesktop() == true
            AppLog.i("DESKTOP home request accepted=$accepted", false)
            if (session == null && host == null && !linkWaiting) stopSelfResult(startId)
            return stickiness()
        }
        if (intent?.action == ACTION_LAUNCHER_DEBUG) {
            // Stand launcher: screen, section, theme and map without taps and without window MainActivity on top of desktop
            com.yegorkapomidorka.headswap.launcher.LauncherDebug.apply(this, intent)
            if (session == null && host == null && !linkWaiting) stopSelfResult(startId)
            return stickiness()
        }
        DexState.desktopSession = foregroundMode() == BridgeMode.DESKTOP
        // startForegroundService requires startForeground for 5 with, do directly
        val n = HeadswapNotification.build(this, CHANNEL, NOTIF_ID)
        if (intent == null) {
            // process killed by system (LMK in the middle of session on wireless connection, h610), Android raised service on START_STICKY
            try {
                startForegroundTyped(n)
            } catch (e: Exception) {
                AppLog.e("сервис: перезапуск системой, foreground не дали", e)
                WLog.ev(WLog.TRIGGER, "sticky-fail", "err" to e.javaClass.simpleName)
                stopSelf()
                return START_NOT_STICKY
            }
            onStickyRestart()
            return stickiness()
        }
        startForegroundTyped(n)
        command(intent)
        return stickiness()
    }

    /**
     * Wireless or auto mode enabled: after killing the process, Android will restart the service ([onStickyRestart]).
     * The ACL_CONNECTED itself will not come - the Bluetooth car did not break, and without this, the wireless connection did not rise until the next trip (h610).
     */
    private fun stickiness() = if ((wifiBMode || autoMode) && !stopRequested) START_STICKY else START_NOT_STICKY

    /** Restart after killing the process: state lost; selected machine next to Bluetooth - waiting for it, as in ACL_CONNECTED. */
    private fun onStickyRestart() {
        AppLog.i("сервис: перезапущен системой после убийства процесса")
        val mac = CarProfile(this).btMac
        if (!BuildConfig.WIRELESS_BETA || mac == null) {
            WLog.ev(WLog.TRIGGER, "sticky-restart", "resume" to false, "why" to "no-car")
            finish()
            return
        }
        CarBtState.check(this, mac) { near ->
            WLog.ev(WLog.TRIGGER, "sticky-restart", "near" to near)
            if (session != null || wifiBMode || stopRequested) return@check      // while checking, came command (ACL, cable, Stop)
            if (near) onCarAuto() else if (host == null && !linkWaiting) finish()
        }
    }

    private fun command(intent: Intent?) {
        if (intent?.action == ACTION_STOP) {
            AppLog.i("сервис: стоп по команде")
            stopRequested = true
            if (sessionMode == BridgeMode.DESKTOP) {
                DesktopMusicPlayback.cancel("explicit Stop")
                cancelDesktopRecovery()
            }
            wifiBMode = false
            autoMode = false      // to the next ACL_CONNECTED
            autoStarted = false
            cancelAutoOff()
            cancelHint()
            stopCarNet(CarNetEvent.STOP, "stop")
            CarLifeState.wireless = null
            btProbe.stop("стоп")
            pendingTcp6?.abort()
            val s = session
            if (s != null) {
                s.close("остановлено пользователем")        // onSessionClosed will see stopRequested: AA close immediately
            } else {
                val h = host
                if (h != null) h.shutdown("стоп пользователем") else finish()
            }
            return
        }
        // autostart according to Bluetooth machine: process before checking session, only the flag is needed during the upcoming session
        if (intent?.action == ACTION_WIFI_B_AUTO) {
            onCarAuto()
            return
        }
        if (intent?.action == ACTION_WIFI_B_AUTO_OFF) {
            onCarAutoOff()
            return
        }
        if (session != null) {
            AppLog.i("сервис: сессия уже идёт, повторный старт игнорирую")
            val wanted = BridgeMode.effective(AaPrefs(this).bridgeMode, Build.VERSION.SDK_INT)
            if (wanted != sessionMode) AppLog.i("сервис: режим ${sessionMode.key} -> ${wanted.key}, применится к следующему подключению (текущая сессия не прерывается)")
            return
        }

        // only debug-assembly: instead of USB-accessory TCP to emulator head unit on PC (adb reverse), in release port() always null
        val dbgPort = DebugTransport.port(intent)
        if (dbgPort != null) {
            AppLog.i("сервис: debug-транспорт, подключаюсь к 127.0.0.1:$dbgPort")
            Thread({
                val pfd = DebugTransport.connect(dbgPort)
                main.post {
                    if (pfd == null) {
                        CarLifeState.status = "debug TCP: нет соединения"
                        failStart()
                    } else if (session != null) {
                        pfd.close()
                    } else {
                        startSession(StreamTransport.fromPfd(pfd, TransportCaps.TCP_DEBUG, "TCP 127.0.0.1:$dbgPort"))
                    }
                }
            }, "debug-tcp").start()
            return
        }

        // only debug-assembly: wireless format, 6 TCP-socket on 127.0.0.1 (adb forward), head unit connects itself
        if (DebugTransport.tcp6(intent)) {
            startTcp6Debug(DebugTransport.tcp6Bind(intent))
            return
        }
        // wireless, B branch (hotspot phone + beacon): button in beta assembly settings or debug-input
        if (intent?.action == ACTION_WIFI_B || DebugTransport.wifiB(intent)) {
            if (!BuildConfig.WIRELESS_BETA && !DebugTransport.wifiB(intent)) {
                AppLog.i("сервис: беспровод в этой сборке выключен")
                failStart()
                return
            }
            if (wifiBMode && linkWaiting && !stopRequested) {
                // re-press, waiting already goes: accept/TARGET_INFO counters do not reset (otherwise false bt-hint, h126)
                autoStarted = false
                WLog.ev(WLog.TRIGGER, "manual-mark", "why" to "waiting")
                return
            }
            wifiBMode = true
            autoStarted = false      // manual start: leaving the machine on Bluetooth does not extinguish it
            stopRequested = false
            beginWait()
            startWifiB()
            btProbe.start()      // after WLog.begin in startWifiB: t= in lines WL bt from start of attempt
            return
        }

        val usb = getSystemService(UsbManager::class.java)
        val acc = usb.accessoryList?.firstOrNull()
        if (acc == null) {
            AppLog.i("сервис: аксессуара нет, останавливаюсь")
            CarLifeState.status = "аксессуара нет"
            failStart()
            return
        }
        if (!usb.hasPermission(acc)) {
            AppLog.i("сервис: нет разрешения на аксессуар, останавливаюсь")
            CarLifeState.status = "нет разрешения на аксессуар"
            failStart()
            return
        }
        val pfd = try {
            usb.openAccessory(acc)
        } catch (e: Exception) {
            AppLog.e("openAccessory", e)
            null
        }
        if (pfd == null) {
            AppLog.i("сервис: openAccessory вернул null")
            CarLifeState.status = "openAccessory: ошибка"
            failStart()
            return
        }
        AppLog.i("сервис: аксессуар открыт: ${MainActivity.describe(acc)}")
        if (wifiBMode || pendingTcp6 != null) {
            // cable defeats wireless connection: waiting machine by Wi-Fi cancel; after USB-session will be resumed only if
            // machine next to Bluetooth and no cable (usbToAir)
            AppLog.i("сервис: кабель подключён, ожидание по Wi-Fi отменяю (USB побеждает)")
            WLog.ev(WLog.LINK, "usb-wins")
            wifiBMode = false
            autoStarted = false
            cancelHint()
            stopCarNet(CarNetEvent.USB_WINS, "usb")
            CarLifeState.wireless = null
            btProbe.stop("кабель")
            pendingTcp6?.abort()
        }
        startSession(StreamTransport.fromPfd(pfd, TransportCaps.USB_AOA, MainActivity.describe(acc)))
    }

    /** debug tcp6: listen to 6 ports on loopback, wait for head unit (pc-headunit --transport tcp6), then regular session. */
    private fun startTcp6Debug(bind: String) = startTcp6("tcp6", bind, TCP6_WAIT_MS, beacon = null)

    /**
     * B (hotspot phone): CarlifeHost beacon on 7999 and 8999 Responder, 6 ports on 0.0.0.0, let only
     * addresses from the subnets where the beacon goes. hotspot includes a person. So far, only debug- is logged in (`--ez debug_wifi_b true`).
     */
    private fun startWifiB() {
        CarLifeState.wireless = waitingText("…")
        // phone is already on the machine network (WifiNetworkSpecifier): lighthouse and responder only in it, socket are tied to this network
        val carIface = carNetImpl?.ifaceName
        val carNetwork = if (carIface != null) carNetImpl?.current else null
        val beacon = HotspotBeacon(
            ifaces = { LanIface.scan().let { all -> if (carNetwork != null) all.filter { it.name == carIface } else all } },
            bindNetwork = carNetwork,
            onIfaces = { list ->
                // Wi-Fi Direct-Machine hotspot is not needed, and included it takes Wi-Fi Direct (h214): do not ask him to turn on
                CarLifeState.wireless = if (list.isEmpty()) {
                    if (wifiDirectCar) waitingText("Wi-Fi Direct") else getString(R.string.wl_state_no_hotspot)
                }
                else waitingText(list.joinToString(", ") { it.name })
            },
        )
        startTcp6("wifi-b", "0.0.0.0", WIFI_B_WAIT_MS, beacon)
        if (linkWaiting) WLog.ev(WLog.LINK, "car-wifi", "carWifi" to if (CarWifi(this).isSet) "set" else "unset", "bound" to (carNetwork != null))
        if (autoMode && !hintShown && linkWaiting) {
            main.removeCallbacks(hintTick)
            main.postDelayed(hintTick, AUTO_HINT_MS)
        }
        if (linkWaiting) scheduleCarNet()
    }

    /** New Wi-Fi wait (auto or manual): reset counters and prompt timer about Bluetooth. */
    private fun beginWait() {
        waitSince = SystemClock.elapsedRealtime()
        carBtNear = false
        p2pThisWait = false
        carSkipLogged = false
        btAcceptsThisWait = 0
        btHintShown = false
        main.removeCallbacks(btHintTick)
        main.postDelayed(btHintTick, BT_HINT_MS)
    }

    private fun markCarSeen() {
        carSeenAt = SystemClock.elapsedRealtime()
    }

    private fun sinceCarMs(): Long? = if (carSeenAt == 0L) null else SystemClock.elapsedRealtime() - carSeenAt

    /**
     * Machine network request: via [CAR_NET_DELAY_MS] from the start of waiting (if the network is set), and if the machine has just been
     * Contact Us - Immediately[CarNetPolicy.delayMs]). Wi-Fi Direct-No need for a car.
     */
    private fun scheduleCarNet() {
        main.removeCallbacks(carNetTick)
        if (!CarWifi(this).isSet) return
        if (wifiDirectCar) {
            logCarSkip()
            return
        }
        val since = sinceCarMs()
        val proven = CarWifi(this).proven
        val delay = CarNetPolicy.delayMs(since, carBtNear, proven)
        if (delay == 0L) WLog.ev(WLog.LINK, "car-net-now", "sinceCarMs" to since, "carBt" to carBtNear, "proven" to proven)
        main.postDelayed(carNetTick, (delay - (SystemClock.elapsedRealtime() - waitSince)).coerceAtLeast(0))
        if (delay > 0L) checkCarBtNear()
    }

    /** The selected machine is connected according to Bluetooth: it is nearby, the machine network is requested immediately. Without the selected machine do not look (earphones). */
    private fun checkCarBtNear() {
        val mac = CarProfile(this).btMac ?: return
        CarBtState.check(this, mac) { near ->
            if (!near || carBtNear || !wifiBMode || stopRequested || !linkWaiting || session != null) return@check
            carBtNear = true
            scheduleCarNet()
        }
    }

    private fun requestCarNet() {
        val w = CarWifi(this)
        val ssid = w.ssid ?: return
        val ok = CarNetPolicy.shouldRequest(
            carWifiSet = w.isSet,
            autoOrManualWaiting = wifiBMode && linkWaiting && !stopRequested,
            sessionActive = session != null,
            usbActive = session?.transportKind == TransportCaps.USB_AOA.kind,
            alreadyRequesting = carNetBusy,
            waitedMs = SystemClock.elapsedRealtime() - waitSince,
            sixOfSixReady = sixReady,
            wifiDirectCar = wifiDirectCar,
            delayMs = CarNetPolicy.delayMs(sinceCarMs(), carBtNear, w.proven),
        )
        if (!ok) return
        carNet.request(ssid, w.pass, CAR_NET_TIMEOUT_MS, ::onCarNetAvailable, ::onCarNetUnavailable, ::onCarNetLost)
    }

    /** Stop waiting (or death of the service): remove the pending request and [CarNetPolicy]request of the network. */
    private fun stopCarNet(event: CarNetEvent, why: String) {
        main.removeCallbacks(carNetTick)
        restartingForCarNet = false
        if (CarNetPolicy.shouldRelease(event)) carNetImpl?.release(why)
    }

    private fun logCarSkip() {
        if (carSkipLogged) return
        carSkipLogged = true
        WLog.ev(WLog.LINK, "car-skip", "why" to "wifi-direct")
    }

    /** head unit sent TARGET_INFO (Wi-Fi Direct): the machine network is not needed, wifi- prompt too. */
    private fun onBtTargetInfo(mac: String?) {
        if (!wifiBMode || stopRequested) return
        val profile = CarProfile(this)
        // the machine is selected and it is she: remember forever; not selected or someone else's - only for this expectation
        if (CarMac.same(profile.btMac, mac)) profile.connMode = CarConnMode.BT_CARLIFE
        p2pThisWait = true
        main.removeCallbacks(carNetTick)
        if (carNetBusy) carNetImpl?.release("p2p")      // Callbacks are removed without calls: onUnavailable/onLost will not come
        if (CarWifi(this).isSet) logCarSkip()
        cancelHint()
    }

    /** discoverPeers returned BUSY: to Samsung is the included access point, please turn it off. */
    private fun onP2pBusy() {
        if (!wifiBMode || stopRequested || !linkWaiting) return
        CarLifeState.wireless = getString(R.string.wl_state_p2p_busy)
    }

    private fun onBtAccept() {
        if (!wifiBMode || stopRequested) return
        btAcceptsThisWait++
        cancelBtHint()
    }

    private fun onCarNetAvailable(@Suppress("UNUSED_PARAMETER") network: Network) {
        markCarSeen()
        restartForCarNet()
    }

    /** The network of the car appeared or disappeared while we wait: waiting again for the lighthouse to go (or stop going) through it. Do not touch the session. */
    private fun restartForCarNet() {
        if (!wifiBMode || stopRequested || session != null || sixReady || !linkWaiting || wifiDirectCar) return
        val l = pendingTcp6
        if (l == null) {      // Listener is still open (or has already given the result): we will repeat while waiting
            main.postDelayed({ restartForCarNet() }, 300)
            return
        }
        restartingForCarNet = true
        WLog.ev(WLog.LINK, "rearm", "after" to "car-net")
        l.abort()
    }

    private fun onCarNetUnavailable(reason: String) {
        if (wifiDirectCar) return
        if (autoMode && !hintShown) showWifiHint()      // network machine will not: let the person connect Wi-Fi itself
    }

    private fun onCarNetLost() {
        markCarSeen()
        if (!wifiBMode || stopRequested || session != null) return
        restartForCarNet()
        scheduleCarNet()
    }

    /** Standby status; in auto mode with a note that the machine is next to Bluetooth. */
    private fun waitingText(ifaces: String) = getString(if (autoMode) R.string.wl_state_auto else R.string.wl_state_waiting, ifaces)

    /** ACL_CONNECTED of the selected machine: wait for Wi-Fi while it is near. Do not touch the upcoming session (USB or wireless connection). */
    private fun onCarAuto() {
        if (!BuildConfig.WIRELESS_BETA) {
            AppLog.i("сервис: беспровод в этой сборке выключен")
            failStart()
            return
        }
        cancelAutoOff()
        autoMode = true
        autoStarted = true
        if (session != null) {
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "session", "kind" to session?.transportKind)
            return
        }
        if (wifiBMode && linkWaiting) {
            // ACL: surge is already waiting. Without RFCOMM and the group, restart the probe - head unit opens SPP on fresh listeners (h211)
            val restart = btProbe.idle()
            WLog.ev(WLog.TRIGGER, "auto-mark", "why" to "waiting", "btRestart" to restart)
            if (restart) btProbe.restart("ACL вернулся")
            return
        }
        hintShown = false
        wifiBMode = true
        stopRequested = false
        beginWait()
        startWifiB()
        btProbe.start()
        WLog.ev(WLog.TRIGGER, "auto-start")
    }

    /** ACL_DISCONNECTED of the selected machine: not immediately (ACL bursts in head unit), but through [AUTO_OFF_DEBOUNCE_MS]. */
    private fun onCarAutoOff() {
        if (session == null && host == null && !linkWaiting && !wifiBMode) {
            // service is raised from scratch only for the sake of elimination (the process is killed, the receiver has reached an empty service): nothing to extinguish
            autoMode = false
            finish()
            return
        }
        autoOff.arm()
        main.removeCallbacks(autoOffTick)
        main.postDelayed(autoOffTick, AUTO_OFF_DEBOUNCE_MS)
        WLog.ev(WLog.TRIGGER, "auto-off-armed", "ms" to AUTO_OFF_DEBOUNCE_MS)
    }

    private fun cancelAutoOff() {
        autoOff.cancel()
        main.removeCallbacks(autoOffTick)
    }

    /** The car went by Bluetooth and the new ACL_CONNECTED did not come: we turn off the auto modem. USB- session is never touched. */
    private fun applyAutoOff() {
        autoMode = false
        cancelHint()
        if (session != null) {
            // session is coming (wireless connection: head unit can keep Wi-Fi longer than Bluetooth; cable is none of our business); after its end wait will not resume
            WLog.ev(WLog.TRIGGER, "auto-off", "session" to true)
            return
        }
        if (wifiBMode && autoStarted) {
            // order is important: the mode is off to abort, the failure branch in startTcp6 will see wifiBMode=false
            wifiBMode = false
            autoStarted = false
            CarLifeState.wireless = null
            btProbe.stop("машина ушла")
            stopCarNet(CarNetEvent.AUTO_OFF, "auto-off")
            pendingTcp6?.abort()
            WLog.ev(WLog.TRIGGER, "auto-off", "stopped" to true)
            failStart()
        } else {
            WLog.ev(WLog.TRIGGER, "auto-off", "stopped" to false)
        }
    }

    /**
     * Through [USB_TO_AIR_MS] after the end of USB-session: no cable (on G6S EIO often immediately reconnects the accessory),
     * machine next by Bluetooth - notification "try by wireless connection" and waiting as by ACL_CONNECTED.
     */
    private fun usbToAir() {
        usbToAirPending = false
        if (session != null || wifiBMode || !autoMode || stopRequested) {
            if (session == null && host == null && !linkWaiting && !wifiBMode) finish()
            return
        }
        if (getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true) {
            WLog.ev(WLog.LINK, "usb-to-air", "skip" to "кабель на месте")
            if (session == null && host == null) finish()
            return
        }
        WLog.ev(WLog.LINK, "usb-to-air")
        AppLog.i("сервис: кабель отключён, машина рядом по Bluetooth - жду её по воздуху")
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_usb_to_air_title))
            .setContentText(getString(R.string.wl_usb_to_air_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(USB_TO_AIR_NOTIF_MS)
            .build()
        getSystemService(NotificationManager::class.java).notify(USB_TO_AIR_NOTIF_ID, n)
        onCarAuto()
    }

    /** Remove both waiting prompts (about Wi-Fi and about Bluetooth) and their timers. */
    private fun cancelHint() {
        main.removeCallbacks(hintTick)
        getSystemService(NotificationManager::class.java).cancel(HINT_NOTIF_ID)
        cancelBtHint()
    }

    private fun cancelBtHint() {
        main.removeCallbacks(btHintTick)
        getSystemService(NotificationManager::class.java).cancel(BT_HINT_NOTIF_ID)
        cancelWifiOff()
    }

    private fun cancelWifiOff() {
        if (!wifiOffShown) return
        wifiOffShown = false
        getSystemService(NotificationManager::class.java).cancel(WIFI_OFF_NOTIF_ID)
    }

    /**
     * The head unit group entry is pinned to the off Wi-Fi (Android keeps P2P off, discover meets BUSY): please enable
     * Wi-Fi and turn off the access point. Wi-Fi turned on - remove the request, P2pJoin enters itself. Pressing opens the panel Wi-Fi.
     */
    private fun onP2pWifi(on: Boolean) {
        if (on) {
            if (CarLifeState.wireless == getString(R.string.wl_state_wifi_off)) CarLifeState.wireless = waitingText("…")
            cancelWifiOff()
            return
        }
        if (!wifiBMode || stopRequested || session != null) return
        wifiOffShown = true
        CarLifeState.wireless = getString(R.string.wl_state_wifi_off)
        val open = PendingIntent.getActivity(
            this, 4, Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_wifi_off_title))
            .setContentText(getString(R.string.wl_wifi_off_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.wl_wifi_off_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WIFI_OFF_NOTIF_ID, n)
        WLog.ev(WLog.TRIGGER, "wifi-off-hint")
    }

    /** 30 with waiting in auto mode, and 6/6 no: head unit, perhaps not on the phone network. Pressing opens the panel Wi-Fi. */
    private fun showWifiHint() {
        if (!autoMode || !wifiBMode || !linkWaiting || session != null || hintShown) return
        if (wifiDirectCar) return      // Wi-Fi Direct: phone itself is part of the group head unit, Wi-Fi do not need to connect
        // the machine network is requested or already received: a person does not need to connect Wi-Fi, a hint will come by onUnavailable
        if (carNetBusy) return
        hintShown = true
        val open = PendingIntent.getActivity(
            this, 2, Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_hint_title))
            .setContentText(getString(R.string.wl_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(HINT_NOTIF_ID, n)
        WLog.ev(WLog.TRIGGER, "wifi-hint")
    }

    /** 15 with waiting, and head unit to RFCOMM `carlife` did not connect: if the phone with the machine according to Bluetooth, please reconnect it. */
    private fun checkBtHint() {
        if (!wifiBMode || stopRequested || session != null || btHintShown || btAcceptsThisWait > 0 || p2pThisWait) return
        CarBtState.check(this, CarProfile(this).btMac) { carBt ->
            val mode = CarProfile(this).connMode
            val show = BtReconnectHint.shouldShow(
                waiting = wifiBMode && linkWaiting && !stopRequested,
                sessionActive = session != null,
                waitedMs = SystemClock.elapsedRealtime() - waitSince,
                acceptsThisWait = btAcceptsThisWait,
                targetInfoThisWait = p2pThisWait,
                carBtConnected = carBt,
                mode = mode,
                alreadyShown = btHintShown,
            )
            if (show) showBtHint(carBt, mode)
        }
    }

    /** Pressing opens Bluetooth. settings */
    private fun showBtHint(carBt: Boolean, mode: CarConnMode) {
        btHintShown = true
        val open = PendingIntent.getActivity(
            this, 3, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.wl_bt_hint_title))
            .setContentText(getString(R.string.wl_bt_hint_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(BT_HINT_NOTIF_ID, n)
        CarLifeState.btProbe = "машина подключена по Bluetooth, но carlife не открыла: переподключите Bluetooth"
        WLog.ev(WLog.TRIGGER, "bt-hint", "carBt" to carBt, "mode" to mode.name)
    }

    private fun startTcp6(kind: String, bind: String, waitMs: Long, beacon: HotspotBeacon?) {
        if (linkWaiting) {
            AppLog.i("сервис: $kind: уже жду ГУ, повторный старт игнорирую")
            return
        }
        linkWaiting = true
        sixReady = false
        WLog.begin(kind, "bind" to bind, "ACCESS_LOCAL_NETWORK" to NetProbe.localNetworkGranted(this))
        AppLog.i("сервис: $kind, слушаю 6 портов на $bind, жду ГУ ${waitMs / 1000} с")
        CarLifeState.status = "$kind: жду ГУ"
        val waitStart = SystemClock.elapsedRealtime()
        Thread({
            var hotspotPeer = false      // 6/6 has assembled not P2P-group head unit, but a subnet of a lighthouse
            val result: Any = try {
                val peerOk: (java.net.InetAddress) -> Boolean =
                    if (beacon == null) { _ -> true } else { a -> beacon.current.any { it.contains(a) } || btProbe.ownsPeer(a) }
                val l = Tcp6Listener(java.net.InetAddress.getByName(bind), peerOk = peerOk).open()
                pendingTcp6 = l
                beacon?.start()
                l.await(waitMs, "$kind $bind").also { hotspotPeer = beacon != null && l.peerAddress?.let { !btProbe.ownsPeer(it) } == true }
            } catch (e: Exception) {
                e
            } finally {
                pendingTcp6 = null
            }
            beacon?.stop(if (result is CarTransport) "6/6" else "нет ГУ")
            main.post {
                linkWaiting = false
                val restarting = restartingForCarNet
                restartingForCarNet = false
                when {
                    result is CarTransport && session != null -> result.close()
                    result is CarTransport -> {
                        sixReady = true
                        // connection without Wi-Fi Direct: this machine P2P is not needed (if in this waiting TARGET_INFO was, mode do not touch)
                        if (hotspotPeer && !p2pThisWait) CarProfile(this).connMode = CarConnMode.HOTSPOT
                        // 6/6 came from the network of the machine (the beacon walked only in it): then we ask her right away, without CAR_NET_DELAY_MS
                        if (hotspotPeer && carNetImpl?.current != null) CarWifi(this).let { w ->
                            if (w.isSet && !w.proven) {
                                w.markProven()
                                WLog.ev(WLog.LINK, "car-net-proven")
                            }
                        }
                        if (beacon != null) {
                            cancelHint()      // 6/6: no more need for a hint about Wi-Fi
                            CarLifeState.wireless = getString(R.string.wl_state_connected)
                        }
                        startSession(result)
                    }
                    // abort for a new beacon in the network of the machine: do not change, mode and status do not touch, through a pause we wait again
                    restarting && beacon != null && wifiBMode && !stopRequested && session == null -> {
                        main.postDelayed({
                            if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
                        }, WIFI_B_REARM_MS)
                    }
                    else -> {
                        val msg = (result as Exception).message
                        if (result !is LinkSetupException) AppLog.e(kind, result)
                        AppLog.i("сервис: $msg")
                        // auto mode: machine next to Bluetooth, timeout is not change, but a new waiting circle. Failure ahead of schedule
                        // (port busy, etc.) does not get here: otherwise cycle once in 1,5 s
                        val timedOut = SystemClock.elapsedRealtime() - waitStart >= waitMs
                        if (beacon != null && wifiBMode && autoMode && !stopRequested && session == null && timedOut) {
                            WLog.ev(WLog.LINK, "rearm", "after" to "timeout-auto")
                            main.postDelayed({
                                if (wifiBMode && autoMode && !stopRequested && session == null && !linkWaiting) startWifiB()
                            }, WIFI_B_REARM_MS)
                            return@post
                        }
                        if (beacon != null && wifiBMode) {
                            wifiBMode = false
                            autoStarted = false
                            cancelHint()
                            stopCarNet(CarNetEvent.AUTO_OFF, "gave-up")      // Waiting is over: the network of the machine is no longer needed
                            btProbe.stop("не дождались")
                            CarLifeState.wireless = getString(R.string.wl_state_gave_up, (WIFI_B_WAIT_MS / 60_000).toInt())
                        }
                        // cancellation of waiting by cable: status already USB-session, do not rub
                        if (session == null) CarLifeState.status = msg ?: "$kind: ошибка"
                        failStart()
                    }
                }
            }
        }, "link-$kind").start()
    }

    private fun startSession(transport: CarTransport) {
        main.removeCallbacks(usbToAirTick)
        usbToAirPending = false
        getSystemService(NotificationManager::class.java).cancel(USB_TO_AIR_NOTIF_ID)
        main.removeCallbacks(carNetTick)      // session is coming: no new machine network requests (we keep the request ourselves, CarNetEvent.SESSION_START)
        cancelBtHint()
        CarLifeState.accessory = transport.label
        CarLifeState.wirelessSession = transport.caps.kind == TransportCaps.TCP6.kind
        if (transport.caps.kind != TransportCaps.TCP6.kind) WLog.begin(transport.caps.kind)

        // bridge mode is removed for connection; DESKTOP on Android below 13 is treated as Android Auto
        val wantedMode = BridgeMode.effective(AaPrefs(this).bridgeMode, Build.VERSION.SDK_INT)
        if (wantedMode == BridgeMode.DESKTOP && desktopNotActivated(transport)) return
        reconcileHostMode(wantedMode)
        sessionMode = wantedMode
        DexState.desktopSession = wantedMode == BridgeMode.DESKTOP
        if (wantedMode == BridgeMode.DESKTOP) {
            startDesktopSession(transport)
            return
        }

        val aaPrefs = AaPrefs(this)
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val cfg = SessionConfig(
            screenOn, aaPrefs.micVrStatus, aaPrefs.fpsSetting,
            metaMusic = aaPrefs.metaMusic, metaNavi = aaPrefs.metaNavi, naviField6 = aaPrefs.naviField6,
        )
        MetaRelay.PROGRESS_UNIT = if (aaPrefs.progressSeconds) 1 else 1000

        // AA-session to server head unit on 127.0.0.1:5277 lives at the service level (BridgeHost), not this USB-session
        var h = host
        // the mode is fixed to the connection (SDR is already gone). The head unit server is a one-time, and no one can restart it now
        // (there is no special feature service), so live AA with different geometry or DPI do not close: we work with the old mode
        val chosenGeometry = aaPrefs.videoGeometry
        val wantMode = chosenGeometry.effective(aaPrefs.transcode)
        val wantDensity = aaPrefs.aaDensity
        if (h != null && !h.isDead && h.core.videoMode != wantMode) {
            AppLog.i("BRIDGE: видео-режим/DPI сменился (${h.core.videoMode.label} -> ${wantMode.label}), применится после перезапуска сервера ГУ")
        } else if (h != null && !h.isDead && h.core.densityOverride != wantDensity) {
            AppLog.i("BRIDGE: видео-режим/DPI сменился (DPI ${h.core.densityOverride} -> $wantDensity, 0 = авто), применится после перезапуска сервера ГУ")
        }
        if (h == null || h.isDead) {
            h = createHost(aaPrefs, chosenGeometry, wantMode, wantDensity, previous = h)
            host = h
            if (h == null) {
                // no head unit certificate or the bridge is not assembled: the reason is already in AaState.setupError, USB-session do not open
                transport.close()
                CarLifeState.status = AaState.setupError ?: "мост не стартовал"
                failStart()
                return
            }
        } else {
            AppLog.i("BRIDGE: AA-хост уже есть, переиспользую (grace=${h.isInGrace})")
        }
        // transcoding - customizable; passthrough straight wireless connection also allowed: bitrate AA not adjustable, head unit restored
        // our IDR. Host mode do not touch (without transcoding it is already R720, it can be recoded), the host does not recreate:
        // AA is a one-time server. The old live host mode with crop (WIDE*, R1024) in passthrough lives only with SPS-crop (h529):
        // if the area is scaled (WIDE_976, its with stretching) - transcoding.
        val hostMode = h.core.videoMode
        val transcode = aaPrefs.transcode || (hostMode.needsTranscode && !hostMode.spsCropable)
        if (h.core.transcodeEnabled != transcode) {
            AppLog.i("BRIDGE видео: ${if (transcode) "перекодирование включаю принудительно (режим хоста ${h.core.videoMode.label})" else "возвращаю passthrough по настройке"}")
            h.core.transcodeEnabled = transcode
        }
        if (!transcode && transport.caps == TransportCaps.TCP6) AppLog.i("BRIDGE видео: воздух, прямой проброс${if (aaPrefs.ownIdr) " со своим IDR" else ""} (битрейт не регулируется)")
        // its IDR only in passthrough: at transcoding it is not; turned off by a check mark for developers
        val spliceMs = if (transcode || !aaPrefs.ownIdr) 0L else IDR_SPLICE_MS
        if (h.core.spliceMs != spliceMs) {
            AppLog.i("BRIDGE свой IDR в passthrough: ${if (spliceMs > 0) "раз в $spliceMs мс" else if (transcode) "выключен (перекодирование)" else "выключен в настройках для разработчиков"}")
            h.core.spliceMs = spliceMs
        }
        h.core.carVwId = aaPrefs.carPreset == CarPreset.VW_ID_UNYX
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headswap:session")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(transport, cfg, h.core, PhoneThermal(this), PhoneCalls(this, aaPrefs.callsToHu), onHuSize = { w, hh -> aaPrefs.saveHuSize(w, hh) }) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        s.start()
        h.onUsbSessionStarted()
    }

    /**
     * A live host (grace after USB break) of another mode of the new session is not suitable: close it, a new host will be created
     * under the desired mode and wait for the closure of the former (previous). Host of the same mode do not touch.
     */
    private fun reconcileHostMode(wanted: BridgeMode) {
        val h = host
        if (h != null && !h.isDead && !BridgeMode.hostReusable(hostMode, wanted)) {
            AppLog.i("BRIDGE: режим сменился (${hostMode.key} -> ${wanted.key}), прежний хост закрываю")
            h.shutdown("смена режима")
        }
        hostMode = wanted
    }

    /** The mode under which the service now works: the current session keeps its own, otherwise we take the setting. */
    private fun foregroundMode(): BridgeMode =
        if (session != null) sessionMode else BridgeMode.effective(AaPrefs(this).bridgeMode, Build.VERSION.SDK_INT)

    /**
     * DESKTOP mode without activation from PC: session does not start, the reason is log, status and separate notification
     * (foreground- notification will go with the service). true = start canceled.
     */
    private fun desktopNotActivated(transport: CarTransport): Boolean {
        if (DesktopAccess.isReady(this)) return false
        val msg = getString(R.string.desk_not_activated)
        AppLog.i("DESKTOP: сессия не стартует, доступ не активирован (${DelegatedDesktopAccess.status(this)}): $msg")
        DexState.error = msg
        CarLifeState.status = msg
        transport.close()
        val open = PendingIntent.getActivity(
            this, 6, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.desk_notif_title))
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(DESKTOP_NOT_ACTIVATED_NOTIF_ID, n)
        failStart()
        return true
    }

    /** Session in DESKTOP mode (DelegatedDexConnector instead of AA): video from a virtual display, CarLife- sound by setting up. */
    private fun startDesktopSession(transport: CarTransport) {
        getSystemService(NotificationManager::class.java).cancel(DESKTOP_NOT_ACTIVATED_NOTIF_ID)
        desktopUsed = true
        val isUsb = transport.caps.kind == TransportCaps.USB_AOA.kind
        lastSessionWasUsb = isUsb
        physicalUsbDetached = false
        DexState.error = null

        val aaPrefs = AaPrefs(this)
        val dexPrefs = DexPrefs(this)
        // display size - the same resolution as selected in the "Video" section
        val desktopVideo = DesktopVideoSelection.forAaVideo(aaPrefs.videoGeometry.effective(aaPrefs.transcode), dexPrefs.desktopDensity)
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val cfg = SessionConfig(
            screenOn, false, dexPrefs.desktopFps,
            metaMusic = false, metaNavi = false, naviField6 = aaPrefs.naviField6, desktopVideo = desktopVideo,
        )
        MetaRelay.PROGRESS_UNIT = if (aaPrefs.progressSeconds) 1 else 1000

        // new physical connection: recovery budget again; recovery does not update it
        cancelDesktopRecovery()
        desktopRecovery.newConnection()
        // desktop source is experiencing a short break USB in grace BridgeHost
        var h = host
        val wantGeometry: VideoGeometry = desktopVideo.initialGeometry()
        val wantDensity = desktopVideo.densityFor(wantGeometry)
        if (h == null || h.isDead) {
            h = createDesktopHost(aaPrefs, wantGeometry, wantDensity, previous = h)
            host = h
            if (h == null) {
                // source is not going: open USB without video can not be left
                transport.close()
                CarLifeState.status = AaState.setupError ?: "мост не стартовал"
                failStart()
                return
            }
        } else {
            AppLog.i("DEX: источник уже есть, переиспользую (grace=${h.isInGrace})")
        }
        val owner: BridgeHost = h
        // temp frame CarLife, repeat static frame and encoder remain as the main video conveyor
        owner.core.transcodeEnabled = true
        owner.core.spliceMs = 0L
        owner.core.carVwId = aaPrefs.carPreset == CarPreset.VW_ID_UNYX
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headswap:session")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(transport, cfg, owner.core, PhoneThermal(this), CallStateSource.NONE, onHuSize = { w, hh -> aaPrefs.saveHuSize(w, hh) }) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        // tcp-debug is only in debug/smoke (head unit emulator): for sound we consider it USB to check the grip on the stand
        val audioUsb = isUsb || transport.caps === TransportCaps.TCP_DEBUG
        DesktopMusicPlayback.attach(applicationContext, this, s, audioUsb,
            ready = { session === s && DexState.running && s.hasFreshDesktopVideo() },
            audioReady = { !DexPrefs(this).usbPlaybackAudio || !owner.core.audioViaUsb || DesktopAudioState.running })
        desktopAudio.attach(owner.core, audioUsb) { session === s && s.canCaptureDesktopAudio() }
        s.start()
        owner.onUsbSessionStarted()
    }

    /** DESKTOP mode uses the CarLife video pipeline; Android Auto certificates are not required. */
    private fun createDesktopHost(
        aaPrefs: AaPrefs, mode: VideoGeometry, densityOverride: Int, previous: BridgeHost?, recoveredCore: AaBridgeCore? = null,
    ): BridgeHost? {
        return try {
            AaState.setupError = null
            AaState.reset()
            AaState.cert = getString(R.string.desk_cert_na)
            AaState.status = "рабочий стол: подключение"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = recoveredCore ?: AaBridgeCore(
                log,
                clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 },
                connectingText = getString(R.string.desk_connecting),
                waitingText = getString(R.string.desk_waiting_frame),
                disconnectedText = getString(R.string.desk_disconnected),
            )
            core.micModeProvider = { MicMode.CAR }
            core.phoneMic = null
            core.usbAudioEnabled = false
            core.mapHomeKey = true
            core.desktopInputEnabled = true
            core.touchDecimationProvider = { aaPrefs.touchDecimation }
            core.transcodeEnabled = true
            core.refDecoderFactory = { sink -> RefDecoder(sink) { AppLog.i(it, false) } }
            core.videoMode = mode
            core.densityOverride = densityOverride
            core.marginsAtBottom = false
            core.spliceMs = 0L
            core.expectFeatureConfig = false
            val ref = arrayOfNulls<BridgeHost>(1)
            val conn = DelegatedDexConnector(applicationContext, core) { reason ->
                main.post { ref[0]?.let { onDesktopSourceFailed(it, reason) } }
            }
            AppLog.i("DEX: видео ${mode.outW}x${mode.outH} через CarLife; звук по USB по настройке; микрофон CarLife не передаётся")
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post { ref[0]?.let(::onDesktopHostTornDown) }
            })
            ref[0] = h
            h
        } catch (e: Exception) {
            AppLog.e("DEX: мост не стартовал", e)
            DexState.error = "${e.javaClass.simpleName}: ${e.message}"
            AaState.setupError = DexState.error
            AaState.status = "ошибка: рабочий стол не стартовал"
            null
        }
    }

    /** All recovery status belongs to the service, not Activity. */
    private fun canRecoverDesktop(current: CarLifeSession): Boolean = runCatching {
        alive && !stopRequested && session === current &&
            current.transportKind == TransportCaps.USB_AOA.kind && DesktopAccess.isReady(this) &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isDeviceLocked &&
            getSystemService(UsbManager::class.java).let { usb ->
                usb.accessoryList.orEmpty().any { usb.hasPermission(it) }
            }
    }.getOrDefault(false)

    private fun onDesktopSourceFailed(owner: BridgeHost, reason: DesktopFailure) {
        if (host !== owner) {
            // reconnection could already replace the completed host: we close only the latch of the old one, it can wait for the successor
            owner.shutdown("завершение предыдущего источника")
            return
        }
        val current = session
        val attempt = desktopRecovery.begin(reason == DesktopFailure.HELPER_UNAVAILABLE && current != null && canRecoverDesktop(current))
        if (attempt != null && current != null) {
            pendingDesktopRecovery = Recovery(attempt, current, owner)
            AppLog.i("DESKTOP recovery planned attempt=${attempt.number}/2; server ready, keep CarLife transport", false)
        }
        // TerminalSource does not allow you to re-use the host in grace before this callback
        owner.shutdown("источник рабочего стола завершился")
    }

    private fun onDesktopHostTornDown(owner: BridgeHost) {
        if (host === owner) host = null
        AppLog.i("DEX: источник закрыт")
        val recovery = pendingDesktopRecovery
        if (recovery != null && recovery.owner === owner && desktopRecovery.isCurrent(recovery.attempt)) {
            // Kolback confirms closure of both preferred manufacturer and encoder with Surface
            DexState.error = null
            DexState.status = getString(R.string.desk_recovering, recovery.attempt.number)
            main.postDelayed({ recoverDesktop(recovery) }, recovery.attempt.delayMs)
        }
        if (session == null && host == null && !linkWaiting && !wifiBMode && !usbToAirPending) finish()
    }

    private fun recoverDesktop(recovery: Recovery) {
        if (pendingDesktopRecovery !== recovery || !desktopRecovery.consume(recovery.attempt)) return
        pendingDesktopRecovery = null
        if (host != null || session !== recovery.session || !recovery.owner.isTornDown) return
        // after a pause, check again: Stop, disable, revoke access, server death or lock cancel recovery
        if (!canRecoverDesktop(recovery.session)) {
            DexState.error = getString(R.string.desk_recovery_stopped)
            AppLog.i("DESKTOP recovery cancelled: current session/access no longer eligible", false)
            return
        }
        val core = recovery.owner.core
        if (!core.rearmDesktopSurfaceAfterStop()) {
            DexState.error = getString(R.string.desk_recovery_screen_busy)
            return
        }
        val replacement = createDesktopHost(AaPrefs(this), core.videoMode, core.densityOverride,
            previous = recovery.owner, recoveredCore = core) ?: return
        host = replacement
        AppLog.i("DESKTOP recovery bind attempt=${recovery.attempt.number}/2; fresh producer and encoder generation", false)
        replacement.onUsbSessionStarted()
    }

    private fun cancelDesktopRecovery() {
        pendingDesktopRecovery = null
        desktopRecovery.cancel()
    }

    /** DESKTOP: physical detach USB session closure, sound, music, recovery. */
    private fun onDesktopSessionClosing(s: CarLifeSession) {
        if (!stopRequested && s.transportKind == TransportCaps.USB_AOA.kind) {
            observePhysicalUsbDetach("USB session closed and accessory absent")
        }
        desktopAudioImpl?.detach()
        DesktopMusicPlayback.linkClosed(s)
        cancelDesktopRecovery()
    }

    /** New AA-host: core, connector, certificates. null = bridge did not rise (cause in AaState.setupError). */
    private fun createHost(aaPrefs: AaPrefs, chosen: VideoGeometry, mode: VideoGeometry, densityOverride: Int, previous: BridgeHost?): BridgeHost? {
        return try {
            val loaded = AaCreds.load(this, aaPrefs.certSet)
            AaState.setupError = null
            AaState.reset()
            AaState.cert = loaded.creds.certSet + if (loaded.error != null) " (ОТКАТ с ${loaded.requested})" else ""
            AaState.status = "мост: старт"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = AaBridgeCore(log, clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 })
            core.micModeProvider = { aaPrefs.micMode }
            val audio = getSystemService(AudioManager::class.java)
            core.phoneCallProvider = {
                when (audio?.mode) {
                    AudioManager.MODE_RINGTONE -> PhoneCall.RINGING
                    AudioManager.MODE_IN_CALL -> PhoneCall.ACTIVE
                    else -> PhoneCall.IDLE                  // IN_COMMUNICATION does not take: the voice of the assistant looks the same
                }
            }
            core.musicRateProvider = { aaPrefs.musicRate }
            core.mediaChunkProvider = { aaPrefs.mediaChunk }
            core.voiceChunkProvider = { aaPrefs.voiceChunk }
            core.ttsPrerollProvider = { aaPrefs.ttsPreroll }
            core.touchDecimationProvider = { aaPrefs.touchDecimation }
            core.phoneMic = PhoneMicCapture(applicationContext)
            core.usbAudioEnabled = aaPrefs.usbAudio
            EqLive.settings = aaPrefs.eq
            core.transcodeEnabled = aaPrefs.transcode
            core.refDecoderFactory = { sink -> RefDecoder(sink) { AppLog.i(it, false) } }
            core.videoMode = mode
            core.densityOverride = densityOverride
            // h529: field at right/bottom only for SPS-crop in direct passthrough; transcoder cuts center
            val bottom = !aaPrefs.transcode && mode.spsCropable
            core.marginsAtBottom = bottom
            if (bottom) AppLog.i("BRIDGE SPS-кроп: поля ${mode.marginW} px справа и ${mode.marginH} px снизу (ui_config.margins), интерфейс в левом верхнем углу")
            core.expectFeatureConfig = true
            if (chosen != mode) AppLog.i("BRIDGE видео-режим ${chosen.label} недоступен без перекодирования, использую ${mode.label}")
            // audio syncs (MEDIA/SPEECH) are announced at the time of the phone request, when the sound solution is already known
            val conn = AaConnector(
                core, loaded.creds, log,
                options = { AapOptions(sdProvider = { mode.sdOptions(fullAudio = core.audioViaUsb, densityOverride = densityOverride, carMake = aaPrefs.carMake, marginsAtBottom = bottom) }) },
            )
            AppLog.i("BRIDGE звук по USB: ${if (aaPrefs.usbAudio) "включён (решение по FEATURE_CONFIG ГУ)" else "выключен в UI"}")
            AppLog.i("BRIDGE: AA cert=${loaded.creds.certSet}, grace=${BridgeHost.GRACE_MS} мс")
            val ref = arrayOfNulls<BridgeHost>(1)
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post {
                    if (host === ref[0]) host = null
                    AppLog.i("BRIDGE: AA закрыта полностью")
                    if (session == null && host == null && !linkWaiting && !wifiBMode && !usbToAirPending) finish()
                }
            })
            ref[0] = h
            h
        } catch (e: AaCertsMissingException) {
            AppLog.e("BRIDGE не стартовал: нет сертификата head unit", e)
            AaState.setupError = e.message
            AaState.status = "ошибка: нет сертификата"
            null
        } catch (e: Throwable) {
            AppLog.e("BRIDGE не стартовал (сертификаты AA)", e)
            AaState.setupError = getString(R.string.svc_bridge_failed, "${e.javaClass.simpleName}: ${e.message}")
            AaState.status = "ошибка: мост не стартовал"
            null
        }
    }

    /**
     * Foreground-type connectedDevice; microphone add only if there is RECORD_AUDIO and phone microphone
     * in general you may need (the mode is not "machine"): otherwise SecurityException. From the background Android 14+ type microphone
     * does not give (while-in-use) - then stay on connectedDevice.
     */
    private fun startForegroundTyped(n: Notification) {
        if (foregroundMode() == BridgeMode.DESKTOP) {
            // Headswap Desktop does not take off the microphone of either phone, nor head unit: only connectedDevice
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            return
        }
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val micWanted = AaPrefs(this).micMode != MicMode.CAR &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micWanted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, n, type)
            if (micWanted) AppLog.i("сервис: foreground-тип connectedDevice|microphone")
        } catch (e: Exception) {      // SecurityException or ForegroundServiceStartNotAllowedException
            AppLog.e("сервис: foreground-тип с microphone не дали, остаюсь на connectedDevice", e)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    /** Start failed: if AA-host is live (grace is available), the service is not extinguished, otherwise finish. */
    private fun failStart() {
        if (linkWaiting) return      // waiting machine according to wireless connection: service needed
        val h = host
        if (h != null && !h.isDead) return
        if (h == null) finish()
    }

    private fun onSessionClosed(s: CarLifeSession, reason: String) {
        AppLog.i("сервис: сессия завершена ($reason)")
        if (session !== s) return
        if (sessionMode == BridgeMode.DESKTOP) onDesktopSessionClosing(s)
        session = null
        markCarSeen()
        CarLifeState.wirelessSession = false
        if (wifiBMode && autoStarted && !autoMode && !stopRequested) {
            // machine went Bluetooth while there was a wireless session (head unit held Wi-Fi): wait do not start again
            wifiBMode = false
            autoStarted = false
            CarLifeState.wireless = null
            btProbe.stop("машина ушла")
            stopCarNet(CarNetEvent.AUTO_OFF, "auto-off")
            WLog.ev(WLog.TRIGGER, "auto-off", "after" to "session")
        }
        if (!wifiBMode && autoMode && !stopRequested && BuildConfig.WIRELESS_BETA && s.transportKind == TransportCaps.USB_AOA.kind) {
            // cable pulled out, and the machine next to Bluetooth: new ACL will not come, wait for wireless connection start yourself (h214)
            usbToAirPending = true
            main.removeCallbacks(usbToAirTick)
            main.postDelayed(usbToAirTick, USB_TO_AIR_MS)
            WLog.ev(WLog.LINK, "usb-to-air-armed", "ms" to USB_TO_AIR_MS)
        }
        val h = host
        if (h == null) {
            if ((!wifiBMode && !usbToAirPending) || stopRequested) finish()
        } else {
            // break USB: CarLife closed, AA hold graceMs (or close immediately if Stop)
            h.onUsbSessionClosed(immediate = stopRequested)
            if (sessionMode == BridgeMode.DESKTOP && !stopRequested && s.transportKind == TransportCaps.USB_AOA.kind) {
                // Part of Firmware Updates accessoryList Later Reading Error Message
                for (delay in longArrayOf(250, 1000)) main.postDelayed({
                    if (!stopRequested && session == null && host === h) observePhysicalUsbDetach("post-close USB absence check")
                }, delay)
            }
        }
        if (wifiBMode && !stopRequested) {
            // wireless: head unit gone (Wi-Fi break, turned off the machine) - again beacon and wait, AA while warm (grace)
            CarLifeState.wireless = getString(R.string.wl_state_lost)
            WLog.ev(WLog.LINK, "rearm", "after" to reason)
            main.postDelayed({
                if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
            }, WIFI_B_REARM_MS)
        }
    }

    private fun finish() {
        if (desktopUsed) cancelDesktopRecovery()
        stopCarNet(CarNetEvent.STOP, "finish")
        CarLifeState.running = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(detachReceiver)
        } catch (_: Exception) {
        }
        stopRequested = true
        alive = false
        autoMode = false
        cancelAutoOff()
        cancelHint()
        stopCarNet(CarNetEvent.DESTROY, "destroy")
        pendingTcp6?.abort()
        btProbe.stop("сервис уничтожен")
        audioDiag.stop()
        if (desktopUsed) {
            cancelDesktopRecovery()
            desktopAudioImpl?.close()
            DesktopMusicPlayback.serviceDestroyed(this, physicalUsbDetached)
        }
        session?.close("сервис уничтожен")
        host?.shutdown("сервис уничтожен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.yegorkapomidorka.headswap.STOP"
        /** The DESKTOP: mode shows the desktop (the HOME button for the upcoming session). Only for a live session, the service does not raise itself. */
        const val ACTION_DESKTOP_HOME = "com.yegorkapomidorka.headswap.DESKTOP_HOME"
        /** adb shell am startservice -a com.yegorkapomidorka.headswap.LAUNCHER_DEBUG --es area settings --es page music --es theme light --es map left */
        const val ACTION_LAUNCHER_DEBUG = "com.yegorkapomidorka.headswap.LAUNCHER_DEBUG"
        /** Wireless (beta): wait for the machine according to Wi-Fi, branch B (hotspot phone + beacon). */
        const val ACTION_WIFI_B = "com.yegorkapomidorka.headswap.WIFI_B"
        /** The selected machine is connected by Bluetooth (ACL_CONNECTED): wait for Wi-Fi while it is near. */
        const val ACTION_WIFI_B_AUTO = "com.yegorkapomidorka.headswap.WIFI_B_AUTO"
        /** The machine is disconnected by Bluetooth (ACL_DISCONNECTED): through debounce to remove the wait. */
        const val ACTION_WIFI_B_AUTO_OFF = "com.yegorkapomidorka.headswap.WIFI_B_AUTO_OFF"
        private const val CHANNEL = "bridge"
        private const val IDR_SPLICE_MS = 2000L             // the IDR to passthrough
        private const val NOTIF_ID = 1
        private const val HINT_NOTIF_ID = 2
        private const val USB_TO_AIR_NOTIF_ID = 5
        /** Pause after the end of the USB-session before waiting for the wireless connection: to see the accessory reconnection. */
        private const val USB_TO_AIR_MS = 3_000L
        private const val USB_TO_AIR_NOTIF_MS = 60_000L
        private const val BT_HINT_NOTIF_ID = 3
        private const val WIFI_OFF_NOTIF_ID = 4
        private const val DESKTOP_NOT_ACTIVATED_NOTIF_ID = 6

        /** From UI (on-screen app): startService, not startForegroundService, to not require startForeground for command without session. */
        fun requestDesktopHome(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(ACTION_DESKTOP_HOME))
        }

        /** The service was created and not destroyed (one process): the receiver ACL no need to wake up an empty service for the sake of fending off. */
        @Volatile var alive = false
            private set
        /** debug tcp6: how to expect all 6 socket head unit. */
        private const val TCP6_WAIT_MS = 60_000L
        /** B: branch how much to loom and wait for head unit (person includes hotspot and opens CarLife on head unit). */
        private const val WIFI_B_WAIT_MS = 10 * 60_000L
        /** Pause before a new wait after the break of the wireless session. */
        private const val WIFI_B_REARM_MS = 1_500L
        /** Auto mode: how long to wait for the new ACL_CONNECTED after the ACL_DISCONNECTED before you extinguish the wait. */
        private const val AUTO_OFF_DEBOUNCE_MS = 10_000L
        /** autoregime: after how many waits without 6/6 show hint about Wi-Fi. */
        private const val AUTO_HINT_MS = 30_000L
    }
}
