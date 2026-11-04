package com.yegorkapomidorka.headswap.link.car

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * Autostart trigger: ACL_CONNECTED/DISCONNECTED selected machine ([CarProfile]) starts and extinguishes waiting by Wi-Fi.
 * The manifest is off (enabled=false), [CarProfile.enableTrigger] is turned on when the machine is selected. Alien devices are ignored.
 * On Android 12+ without BLUETOOTH_CONNECT, the system does not deliver these mailings.
 */
class CarAclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        if (!BuildConfig.WIRELESS_BETA) return
        val mac = CarProfile(context).btMac ?: return
        AppLog.init(context)      // mailing can raise the process before activity and service
        val addr = device(intent)?.address
        if (!CarMac.same(addr, mac)) {
            if (ignoreLog.tryAcquire()) WLog.ev(WLog.TRIGGER, "ignore", "action" to action.substringAfterLast('.'))
            return
        }
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            WLog.ev(WLog.TRIGGER, "acl-connected", "dev" to WLog.maskMac(addr))
            try {
                context.startForegroundService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO))
            } catch (e: Exception) {      // ForegroundServiceStartNotAllowedException etc.: from the background without exception, launch is prohibited
                AppLog.e("ACL: старт сервиса", e)
                WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName)
            }
        } else {
            WLog.ev(WLog.TRIGGER, "acl-disconnected", "dev" to WLog.maskMac(addr))
            // no service - there is nothing to extinguish; startService does not need an empty service from the background
            if (!BridgeService.alive) return
            try {
                context.startService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO_OFF))
            } catch (e: Exception) {
                AppLog.e("ACL: отбой сервису", e)
                WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName, "action" to "off")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private companion object {
        /** The phone has many other devices (earphones, watches): in the log no more than once a minute. */
        val ignoreLog = MinInterval(60_000L) { SystemClock.elapsedRealtime() }
    }
}
