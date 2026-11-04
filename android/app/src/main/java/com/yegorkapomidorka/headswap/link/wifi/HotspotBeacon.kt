package com.yegorkapomidorka.headswap.link.wifi

import android.net.Network
import android.os.SystemClock
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** IPv4-LAN interface, where we send the beacon: hotspot Phone (branch) B) or network head unitWhere the phone went (C′). */
class LanIface(val name: String, val ip: Inet4Address, val prefix: Int, val broadcast: Inet4Address) {
    /** Address in the interface subnet (it also accepts socket head unit). */
    fun contains(a: InetAddress): Boolean {
        if (a !is Inet4Address) return false
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return (toInt(a) and mask) == (toInt(ip) and mask)
    }

    /** Subnet nodes without their address, network and broadcast; only for /24 and already (otherwise too much). */
    fun hosts(): List<Inet4Address> {
        if (prefix < 24 || prefix > 30) return emptyList()
        val mask = -1 shl (32 - prefix)
        val net = toInt(ip) and mask
        val n = 1 shl (32 - prefix)
        val me = toInt(ip)
        return (1 until n - 1).map { net + it }.filter { it != me }.map { fromInt(it) }
    }

    override fun toString() = "$name ${ip.hostAddress}/$prefix"

    companion object {
        fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        fun fromInt(v: Int): Inet4Address = InetAddress.getByAddress(
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()),
        ) as Inet4Address

        /**
         * Whether the interface is suitable for a lighthouse. Mobile data (rmnet, ccmni), loopback, tunnels and dummy is not;
         * p2p is not (it is branch A, as in "mix" mode Baidu). The rest of IPv4 with broadcast-address yes.
         */
        fun eligibleName(name: String): Boolean =
            EXCLUDED_PREFIXES.none { name.startsWith(it) }

        /** hotspot (not STA): the zip unicast- beacons only on them, in the home network via wlan0 not rash. */
        fun sweepName(name: String): Boolean = name != "wlan0"

        private val EXCLUDED_PREFIXES = listOf("rmnet", "ccmni", "lo", "dummy", "tun", "ppp", "p2p", "ip6tnl", "sit", "v4-", "clat")

        /** Current suitable interfaces. */
        fun scan(): List<LanIface> {
            val out = ArrayList<LanIface>()
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                try {
                    if (!ni.isUp || ni.isLoopback || !eligibleName(ni.name)) continue
                } catch (_: SocketException) {
                    continue
                }
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val b = ia.broadcast as? Inet4Address ?: continue
                    out += LanIface(ni.name, a, ia.networkPrefixLength.toInt(), b)
                }
            }
            return out
        }
    }
}

/** Bytes branch B: phone beacon and survey response head unit. */
object BeaconCodec {
    const val BEACON_PORT = 7999
    const val PROBE_PORT = 8999

    /** That Baidu MD 8.6.7 (ConnectManager.sendHotspotUdp): head unit takes only the sender's address. */
    val BEACON: ByteArray = "CarlifeHost".toByteArray(Charsets.US_ASCII)

    /** FT/CL2AA response to the 8999 survey (expansion for separate head unit, there is no listener in the official MD). */
    val READY: ByteArray = """{"carlifehost":"carlife","status":"ready"}""".toByteArray(Charsets.US_ASCII)

    /** Survey head unit: datagram with the line carlifehost (register is important, as in FT). */
    fun isProbe(data: ByteArray, len: Int): Boolean = String(data, 0, len, Charsets.ISO_8859_1).contains("carlifehost")
}

/**
 * B: branch of the phone distributes hotspot, head unit in it client. Once in 2 with beacon "CarlifeHost" on UDP 7999 in broadcast each
 * suitable interface, once in 12 with unicast each address of the subnet hotspot (part of head unit broadcast does not catch: FT, CL2AA);
 * UDP 8999. Works until head unit has connected 6 socket (stop()). All in WL-log, without contents of packages head unit.
 */
class HotspotBeacon(
    private val ifaces: () -> List<LanIface> = { LanIface.scan() },
    private val intervalMs: Long = 2_000,
    private val sweepMs: Long = 12_000,
    private val beaconPort: Int = BeaconCodec.BEACON_PORT,
    private val probePort: Int = BeaconCodec.PROBE_PORT,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** The set of networks has changed (hotspot enabled/off): for on-screen status. */
    private val onIfaces: (List<LanIface>) -> Unit = {},
    /**
     * Network of the machine into which the phone entered (WifiNetworkSpecifier): socket beacon and the defendant are tied to it to
     * packages did not go to the main network. The unicast swipe is turned off: the machine network can be on wlan1, and not on hotspot
     * interface. List [ifaces] caller narrows to the interface of this network itself. null - as before.
     */
    private val bindNetwork: Network? = null,
) {
    private val running = AtomicBoolean(false)
    private var sender: Thread? = null
    private var responder: Thread? = null
    @Volatile private var probeSocket: DatagramSocket? = null
    @Volatile private var known: Set<String>? = null   // null: has not yet been viewed, the first survey always reports

    @Volatile var sent = 0L
        private set
    @Volatile var failed = 0L
        private set
    @Volatile var probes = 0L
        private set

    /** The actual port of the defendant (in 0 = any tests). -1 is not yet open. */
    val probePortBound: Int get() = probeSocket?.localPort ?: -1

    /** Subnet, where the lighthouse is now going: Tcp6Listener launches head unit. on them */
    @Volatile var current: List<LanIface> = emptyList()
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        sender = Thread({ sendLoop() }, "wl-beacon").apply { isDaemon = true; start() }
        responder = Thread({ probeLoop() }, "wl-probe-8999").apply { isDaemon = true; start() }
    }

    fun stop(why: String) {
        if (!running.compareAndSet(true, false)) return
        probeSocket?.close()
        sender?.interrupt()
        WLog.ev("udp", "beacon-stop", "why" to why, "sent" to sent, "failed" to failed, "probes" to probes)
    }

    private fun refresh(): List<LanIface> {
        val list = try {
            ifaces()
        } catch (e: Exception) {
            WLog.ev("net", "iface-fail", "err" to e.javaClass.simpleName)
            current
        }
        val keys = list.map { it.toString() }.toSet()
        if (keys != known) {
            val was = known.orEmpty()
            for (k in keys - was) WLog.ev("net", "iface-up", "iface" to k)
            for (k in was - keys) WLog.ev("net", "iface-down", "iface" to k)
            if (keys.isEmpty()) WLog.ev("net", "no-lan", "hint" to "хотспот выключен или нет подходящего интерфейса")
            known = keys
            try {
                onIfaces(list)
            } catch (_: Exception) {
            }
        }
        current = list
        return list
    }

    private fun sendLoop() {
        val firstOk = HashSet<String>()
        val firstErr = HashSet<String>()
        val firstSweep = HashSet<String>()
        var lastSweep = Long.MIN_VALUE / 2
        var lastSummary = clock()
        try {
            openSender().use { s ->
                s.broadcast = true
                while (running.get()) {
                    val now = clock()
                    val list = refresh()
                    val sweep = bindNetwork == null && now - lastSweep >= sweepMs
                    if (sweep) lastSweep = now
                    for (i in list) {
                        val targets = ArrayList<InetAddress>()
                        targets += i.broadcast
                        if (sweep && LanIface.sweepName(i.name)) targets += i.hosts()
                        for (t in targets) {
                            if (!running.get()) return   // head unit has already connected: the swipe is not sent
                            try {
                                s.send(DatagramPacket(BeaconCodec.BEACON, BeaconCodec.BEACON.size, t, beaconPort))
                                sent++
                                if (firstOk.add(i.name)) WLog.ev("udp", "beacon-first", "iface" to i.toString(), "to" to t.hostAddress)
                            } catch (e: Exception) {
                                failed++
                                if (firstErr.add(i.name)) WLog.ev("udp", "beacon-fail", "iface" to i.toString(), "err" to "${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                        if (sweep && LanIface.sweepName(i.name) && i.hosts().isNotEmpty() && firstSweep.add(i.name)) {
                            WLog.ev("udp", "sweep", "iface" to i.name, "hosts" to i.hosts().size)
                        }
                    }
                    if (now - lastSummary >= SUMMARY_MS) {
                        lastSummary = now
                        WLog.ev("udp", "beacon", "sent" to sent, "failed" to failed, "probes" to probes, "ifaces" to list.joinToString(",") { it.name })
                    }
                    Thread.sleep(intervalMs)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            WLog.ev("udp", "beacon-dead", "err" to "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** socket beacon; with [bindNetwork] attached to the machine's network before first dispatch. */
    private fun openSender(): DatagramSocket {
        val net = bindNetwork ?: return DatagramSocket()
        val s = DatagramSocket(null)
        bindToNetwork(s, net, "beacon")
        s.bind(InetSocketAddress(0))
        return s
    }

    /** Binding socket to the machine network; failure is not fatal (packages will go along the system route), but gets into the log. */
    private fun bindToNetwork(s: DatagramSocket, net: Network, what: String) {
        try {
            net.bindSocket(s)
        } catch (e: Exception) {
            WLog.ev("udp", "bind-net-fail", "sock" to what, "err" to e.javaClass.simpleName)
        }
    }

    private fun probeLoop() {
        try {
            val s = DatagramSocket(null)
            probeSocket = s
            if (bindNetwork != null) bindToNetwork(s, bindNetwork, "probe")
            s.reuseAddress = true
            s.bind(InetSocketAddress(probePort))
            WLog.ev("udp", "probe-listen", "port" to s.localPort)
            val buf = ByteArray(1500)
            s.use {
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val hit = BeaconCodec.isProbe(p.data, p.length)
                    probes++
                    var res = "skip"
                    if (hit) {
                        res = try {
                            s.send(DatagramPacket(BeaconCodec.READY, BeaconCodec.READY.size, p.socketAddress))
                            "replied"
                        } catch (e: Exception) {
                            e.javaClass.simpleName
                        }
                    }
                    if (probes <= 5 || probes % 50 == 0L) {
                        WLog.ev("udp", "probe", "from" to p.address.hostAddress, "len" to p.length, "carlifehost" to hit, "res" to res, "n" to probes)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) WLog.ev("udp", "probe-fail", "port" to probePort, "err" to "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val SUMMARY_MS = 30_000L
    }
}
