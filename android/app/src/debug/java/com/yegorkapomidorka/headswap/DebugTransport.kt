package com.yegorkapomidorka.headswap

import android.content.Intent
import android.os.ParcelFileDescriptor
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Debug-transport: CarLife-stream of TCP to the head unit emulator on PC instead of USB-accessor.
 * On PC: `adb reverse tcp:7240 tcp:7240`, `python pc-headunit/carlife_hu.py`; on phone:
 * `adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ei debug_tcp 7240`.
 */
object DebugTransport {
    const val EXTRA_PORT = "debug_tcp"

    const val EXTRA_TCP6 = "debug_tcp6"

    /**
     * Wireless format (6 socket, without external header): phone listens to 7240..9340 on 127.0.0.1, head unit on PC
     * connects via `adb forward tcp:P tcp:P`; `--ez debug_tcp6 true`, on the `carlife_hu.py --transport tcp6` PC.
     */
    fun tcp6(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_TCP6, false) == true

    /** B wireless branch: hotspot phone, beacon 7999, 6 ports on 0.0.0.0 (`--ez debug_wifi_b true`). */
    fun wifiB(intent: Intent?): Boolean = intent?.getBooleanExtra("debug_wifi_b", false) == true

    /** bind address for tcp6: by default 127.0.0.1; `--es debug_tcp6_bind 0.0.0.0` to check incoming TCP from LAN. */
    fun tcp6Bind(intent: Intent?): String = intent?.getStringExtra("debug_tcp6_bind") ?: "127.0.0.1"

    fun port(intent: Intent?): Int? =
        intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it in 1..65535 }

    /** Blocking connection, not with main-flow. socket dup-is in PFD, it is no longer needed. */
    fun connect(port: Int): ParcelFileDescriptor? = try {
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            ParcelFileDescriptor.fromSocket(s)
        }
    } catch (e: Exception) {
        AppLog.e("debug TCP 127.0.0.1:$port", e)
        null
    }
}
