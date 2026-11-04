package com.yegorkapomidorka.headswap.link.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Whether the phone access point is enabled. The interface name itself is not the answer: Samsung swlan0, most ap0,
 * and Xiaomi/Qualcomm hotspot sits on wlan1 (h420), and the same wlan1 is the second STA (machine network, WifiNetworkSpecifier).
 * Order: hidden API WifiManager; if not, the names of interfaces where wlan[1-9] take only when it is definitely not STA.
 */
object HotspotDetect {
    private const val WIFI_AP_STATE_ENABLED = 13

    /** hotspot names without reservation. */
    fun apName(name: String): Boolean = name.startsWith("swlan") || name.startsWith("ap")

    /**
     * The interface is similar to hotspot. [sta] - the names of the interfaces on which the phone itself client Wi-Fi (known from
     * ConnectivityManager); null - did not work out, then wlan[1-9] do not touch: it may be the second STA.
     */
    fun apIface(name: String, sta: Set<String>?): Boolean {
        if (apName(name)) return true
        return sta != null && name !in sta && SECOND_WLAN.matches(name)
    }

    private val SECOND_WLAN = Regex("wlan[1-9]\\d*")

    fun isUp(ctx: Context): Boolean = apEnabledByApi(ctx) ?: ifacesSayUp(staIfaces(ctx))

    /** Hidden isWifiApEnabled()/getWifiApState(): null, if the system has not given them away (hidden API, SecurityException). */
    private fun apEnabledByApi(ctx: Context): Boolean? {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        try {
            return wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as? Boolean
        } catch (_: Throwable) {
        }
        try {
            val st = wm.javaClass.getMethod("getWifiApState").invoke(wm) as? Int ?: return null
            return st == WIFI_AP_STATE_ENABLED
        } catch (_: Throwable) {
            return null
        }
    }

    /** Interfaces Wi-Fi-networks known ConnectivityManager (hotspot phone among them does not exist). null - did not work. */
    @Suppress("DEPRECATION")
    private fun staIfaces(ctx: Context): Set<String>? = try {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .mapNotNull { cm.getLinkProperties(it)?.interfaceName }
            .toSet()
    } catch (_: Throwable) {
        null
    }

    private fun ifacesSayUp(sta: Set<String>?): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
            apIface(ni.name, sta) && ni.isUp && ni.inetAddresses.toList().any { it is Inet4Address }
        }
    } catch (_: Throwable) {
        false
    }
}
