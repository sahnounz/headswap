package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLocale
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaServerCheck
import com.yegorkapomidorka.headswap.aa.ManualServerCheck
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.ProbeResult
import com.yegorkapomidorka.headswap.aa.TranscodeSelfCheck

/**
 * Actions that used to live right in MainActivity (buttons UI and command intent):
 * scanning USB, start/stop of bridge service, launch of head unit server, diagnostics.
 */
object Actions {
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val AA_SETTINGS_ACTIVITY = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"

    /** Scan: show and pledge all the fields of the accessory; without permission to request; with permission to run the service. */
    fun scan(ctx: Context, reason: String) {
        val usb = ctx.getSystemService(UsbManager::class.java)
        AppLog.i("сканирование ($reason)")
        val list = usb.accessoryList
        if (list == null || list.isEmpty()) {
            AppLog.i("аксессуар не найден (getAccessoryList=${if (list == null) "null" else "[]"})")
            CarLifeState.accessory = "не найден"
            CarLifeState.status = "ждём аксессуар"
            return
        }
        for ((i, acc) in list.withIndex()) {
            AppLog.i("аксессуар[$i]: ${MainActivity.describe(acc)} hasPermission=${usb.hasPermission(acc)}")
            val isBaidu = acc.manufacturer == "Baidu" && acc.model == "CarLife"
            AppLog.i("  строки Baidu/CarLife: ${if (isBaidu) "да" else "НЕТ (другие строки, смотри выше)"}")
        }
        val acc = list[0]
        CarLifeState.accessory = MainActivity.describe(acc)
        if (usb.hasPermission(acc)) {
            startBridge(ctx)
        } else {
            AppLog.i("прошу разрешение на аксессуар")
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(MainActivity.ACTION_PERMISSION).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(acc, pi)
        }
    }

    fun startBridge(ctx: Context) {
        AppLog.i("запускаю сервис")
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
    }

    fun stopBridge(ctx: Context) {
        AppLog.i("кнопка Стоп")
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
    }

    /** Self-checking certificates AA and TLS without network: result in log and AaState.selftest. */
    fun aaSelftest(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ AaCreds.selfTest(app) }, "aa-selftest").start()
    }

    fun transcodeTest(ctx: Context, opts: String) = TranscodeSelfCheck.run(ctx, opts)

    /** Settings Android Auto (server head unit): HyperOS hides them from system settings. */
    fun openAaSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName(AA_PACKAGE, AA_SETTINGS_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            AppLog.i("открыты настройки Android Auto")
        } catch (e: Exception) {
            AppLog.e("не открыть настройки Android Auto", e)
            Toast.makeText(ctx, AppLocale.wrap(ctx).getString(R.string.act_open_aa_failed, "${e.javaClass.simpleName}: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Button «Open Android Auto" On the Main Screen and in onboarding: server head unit Includes the user himself
     * (⋮ → Start head unit server), so only clear the Car disconnected state and open AA settings.
     */
    fun startServerFromUi(ctx: Context) {
        HeadswapStatus.markServerRequested()
        openAaSettings(ctx)
    }

    /**
     * Manually sample the head unit ("For Developers") server: one attempt at full handshake, total toast and strings of SRVCHECK into a log.
     * Only without the bridge and USB: sample knocks down Android Auto. The main screen condition does not affect.
     */
    fun checkServer(ctx: Context) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        val str = AppLocale.wrap(app)
        val refusal = AaServerCheck.start(app) { result, detail ->
            toast(
                when (result) {
                    ProbeResult.ALIVE -> str.getString(R.string.dev_check_server_alive)
                    ProbeResult.REFUSED -> str.getString(R.string.dev_check_server_refused)
                    ProbeResult.HUNG -> str.getString(R.string.dev_check_server_hung)
                    ProbeResult.ERROR -> str.getString(R.string.dev_check_server_error, detail ?: "?")
                },
            )
        }
        if (refusal != null) {
            toast(
                str.getString(
                    when (refusal) {
                        ManualServerCheck.Refusal.BRIDGE -> R.string.dev_check_server_bridge
                        ManualServerCheck.Refusal.USB -> R.string.dev_check_server_usb
                        ManualServerCheck.Refusal.RUNNING -> R.string.dev_check_server_running
                    },
                ),
            )
        }
    }

    /** Checking the microphone of the phone without a machine: 2 with AudioRecord, in log how many bytes (waiting about 64000) and peak. */
    fun micTest(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            val m = PhoneMicCapture(app)
            var bytes = 0L
            val ok = m.start { bytes += it.size }
            AppLog.i("mic_test: старт=$ok (RECORD_AUDIO ${if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "есть" else "НЕТ"})")
            if (ok) {
                Thread.sleep(2000)
                m.stop()
                Thread.sleep(300)
                AppLog.i("mic_test: за 2 с получено $bytes Б (ждём около 64000)")
            }
        }, "mic-test").start()
    }
}
