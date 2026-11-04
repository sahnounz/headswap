package com.yegorkapomidorka.headswap.launcher

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Relay and status bar: time, network, Bluetooth, Wi-Fi and phone media volume. Nothing is pressed. */
data class StatusInfo(
    val time: String = "--:--", val date: String = "", val net: String = NetLabel.NONE,
    /** Cellular signal level 0..4 for columns in the rail; -1 - not cellular network or level unknown. */
    val signal: Int = -1,
    val bluetooth: Boolean = false, val wifi: Boolean = false, val volume: Int = 0,
)

internal object StatusReader {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM")

    fun read(ctx: Context, now: LocalDateTime = LocalDateTime.now()): StatusInfo = StatusInfo(
        time = now.format(timeFormat),
        date = weekday(now.dayOfWeek) + " " + now.format(dateFormat),
        net = network(ctx),
        signal = signal(ctx),
        bluetooth = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }
            .getOrDefault(false),
        wifi = runCatching { ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false),
        volume = runCatching { ctx.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 }
            .getOrDefault(0),
    )

    private fun weekday(d: DayOfWeek): String =
        d.getDisplayName(TextStyle.SHORT, Locale.getDefault()).replaceFirstChar { it.uppercase() }

    /** Mobile network in status (Wi-Fi has its own icon): the type of network even with active Wi-Fi, as on tape. */
    private fun network(ctx: Context): String = NetLabel.cellular(cellularType(ctx))

    /** Signal level of the mobile network 0..4; -1 - no network (no SIM, air mode): then the icon is not drawn. */
    private fun signal(ctx: Context): Int {
        val level = runCatching { ctx.getSystemService(TelephonyManager::class.java)?.signalStrength?.level }.getOrNull()
        return level?.coerceIn(0, 4) ?: -1
    }

    /** The network type is READ_PHONE_STATE; without it, the signature "4G" is default. */
    private fun cellularType(ctx: Context): Int? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching { ctx.getSystemService(TelephonyManager::class.java)?.dataNetworkType }.getOrNull()
    }
}
