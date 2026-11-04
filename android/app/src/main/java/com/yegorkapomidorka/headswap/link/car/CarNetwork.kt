package com.yegorkapomidorka.headswap.link.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.link.diag.WLog

/** Machine network request status (in the report as [label]). */
enum class CarNetState(val label: String) {
    IDLE("idle"),
    REQUESTING("requesting"),
    AVAILABLE("available"),
    UNAVAILABLE("unavailable"),
}

/**
 * Phone input to Wi-Fi machine via WifiNetworkSpecifier: local network without internet, internet remains on main
 * network (bindProcessToNetwork not used). The callbacks come to main thread. Request is held until called [release]
 * (or network is not lost: then [state] resets to IDLE and caller decides whether to ask again).
 * The first attempt of WPA2, if you fail with a password - one repeated WPA3; without a password, the network is open.
 * SSID and the password in WL do not fall into the log, only the class name goes to the log.
 *
 * [onState] - status mirror for the report: writes only an instance of the service, checking from the settings does not transmit it.
 */
class CarNetwork(ctx: Context, private val onState: (CarNetState) -> Unit = {}) {
    private val app = ctx.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    var state: CarNetState = CarNetState.IDLE
        private set(v) {
            field = v
            onState(v)
        }

    /** Machine network until [state] == AVAILABLE. */
    @Volatile var current: Network? = null
        private set

    /** Machine network interface (wlan0/wlan1): we select addresses for the beacon. Known together with [current]. */
    @Volatile var ifaceName: String? = null
        private set

    private var cb: Cb? = null

    private enum class Sec(val label: String) { OPEN("open"), WPA2("wpa2"), WPA3("wpa3") }

    private class Params(
        val ssid: String,
        val pass: String,
        val timeoutMs: Long,
        val onAvailable: (Network) -> Unit,
        val onUnavailable: (String) -> Unit,
        val onLost: () -> Unit,
    )

    /**
     * Request a network. We already ask or received - we do nothing. [onAvailable] is called when both the network and the name are known
     * interface; [onUnavailable] - network not found, dialogue failure or request error (reason - short code).
     */
    fun request(
        ssid: String,
        pass: String,
        timeoutMs: Long,
        onAvailable: (Network) -> Unit,
        onUnavailable: (String) -> Unit,
        onLost: () -> Unit,
    ) {
        if (state == CarNetState.REQUESTING || state == CarNetState.AVAILABLE) return
        logStaConcurrency()
        val first = CarSecPolicy.first(rememberedSec(ssid), pass.isNotEmpty())
        start(Params(ssid, pass, timeoutMs, onAvailable, onUnavailable, onLost), Sec.entries.first { it.label == first })
    }

    /** Good protection from [CarWifi], if we ask for the saved network of the machine. */
    private fun rememberedSec(ssid: String): String? = CarWifi(app).let { if (it.ssid == ssid) it.sec else null }

    /** Remove the request (and network, if only he held it). You can call as many times as you want. */
    fun release(why: String) {
        val c = cb
        cb = null
        if (c != null) {
            c.unregister()
            WLog.ev(NET, "car-release", "why" to why)
        }
        current = null
        ifaceName = null
        if (state != CarNetState.IDLE) state = CarNetState.IDLE
    }

    private fun start(p: Params, sec: Sec) {
        val req = try {
            val spec = WifiNetworkSpecifier.Builder().setSsid(p.ssid).apply {
                when (sec) {
                    Sec.OPEN -> {}
                    Sec.WPA2 -> setWpa2Passphrase(p.pass)
                    Sec.WPA3 -> setWpa3Passphrase(p.pass)
                }
            }.build()
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
        } catch (e: Exception) {      // length SSID, short password: message can contain SSID, in log only class
            cb = null
            fail(p, sec, e.javaClass.simpleName)
            return
        }
        val c = Cb(p, sec)
        cb = c
        state = CarNetState.REQUESTING
        WLog.ev(NET, "car-request", "sec" to sec.label, "timeoutMs" to p.timeoutMs)
        try {
            cm.requestNetwork(req, c, handler, p.timeoutMs.toInt())
            c.registered = true
        } catch (e: Exception) {
            cb = null
            fail(p, sec, e.javaClass.simpleName)
        }
    }

    private fun fail(p: Params, sec: Sec, err: String) {
        WLog.ev(NET, "car-unavailable", "sec" to sec.label, "err" to err)
        state = CarNetState.UNAVAILABLE
        p.onUnavailable("error:$err")
    }

    private inner class Cb(val p: Params, val sec: Sec) : ConnectivityManager.NetworkCallback() {
        var registered = false
        private var net: Network? = null
        private var ready = false

        /** Colback of a previous attempt or already withdrawn request. */
        private fun stale() = cb !== this

        fun unregister() {
            if (!registered) return
            registered = false
            try {
                cm.unregisterNetworkCallback(this)
            } catch (_: IllegalArgumentException) {      // Timeout: registration system has already been removed
            }
        }

        override fun onAvailable(network: Network) {
            if (!stale()) net = network
        }

        /** The name of the interface is taken from here: onAvailable LinkProperties does not yet exist. */
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (stale() || ready || network != net) return
            val name = linkProperties.interfaceName ?: return
            ready = true
            current = network
            ifaceName = name
            state = CarNetState.AVAILABLE
            CarWifi(app).let { if (it.ssid == p.ssid) it.rememberSec(sec.label) }
            WLog.ev(NET, "car-available", "iface" to name, "sec" to sec.label,
                "addr" to linkProperties.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress)
            p.onAvailable(network)
        }

        override fun onUnavailable() {
            if (stale()) return
            unregister()
            WLog.ev(NET, "car-unavailable", "sec" to sec.label)
            if (CarSecPolicy.retryWpa3(rememberedSec(p.ssid), sec.label, p.pass.isNotEmpty())) {
                start(p, Sec.WPA3)      // network could be WPA3: one retry
                return
            }
            cb = null
            state = CarNetState.UNAVAILABLE
            p.onUnavailable("no-network")
        }

        override fun onLost(network: Network) {
            if (stale() || network != net) return
            unregister()
            cb = null
            current = null
            ifaceName = null
            state = CarNetState.IDLE
            WLog.ev(NET, "car-lost")
            p.onLost()
        }
    }

    private fun logStaConcurrency() {
        if (staLogged) return
        staLogged = true
        val v = if (Build.VERSION.SDK_INT >= 31) {
            try {
                app.getSystemService(WifiManager::class.java).isStaConcurrencyForLocalOnlyConnectionsSupported.toString()
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
        } else "n/a"
        WLog.ev(NET, "sta-concurrency", "local" to v)
    }

    private companion object {
        const val NET = "net"
        @Volatile var staLogged = false
    }
}
