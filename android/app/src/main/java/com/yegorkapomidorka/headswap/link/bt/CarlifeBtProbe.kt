package com.yegorkapomidorka.headswap.link.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.link.diag.WLog
import java.io.IOException
import java.util.UUID

/**
 * A branch (Bluetooth + Wi-Fi Direct): RFCOMM-`carlife` servers, as in Baidu MD (BluetoothClient:28-29,76,119).
 * If head unit can wirelessly CarLife through Wi-Fi Direct, it connects itself here. Exchange: WIRELESS_INFO_REQUEST,
 * TARGET_INFO_REQUEST, according to the details from TARGET_INFO, the phone is part of P2P-group head unit ([P2pJoin]), on IP_REQUEST
 * answers MD_WIFI_IP with an address in the group. Next head unit connects TCP-channels to Tcp6Listener (runs [ownsPeer]).
 * Bluetooth-connection is kept while waiting for the car and the session. Secrets in the log does not write (see BtSummary).
 */
class CarlifeBtProbe(
    private val ctx: Context,
    /** head unit is connected to RFCOMM (thread bt-accept-*). */
    private val onAccept: () -> Unit = {},
    /** head unit sent the details of Wi-Fi Direct, mac - its address is Bluetooth (thread bt-accept-*). */
    private val onTargetInfo: (mac: String?) -> Unit = {},
    /** Wi-Fi Direct busy (usually included hotspot phone), main thread. */
    private val onP2pBusy: () -> Unit = {},
    /** Wi-Fi on the phone is disabled (false, Wi-Fi Direct will not be included) or enabled (true) at the entrance to the group head unit (main thread). */
    private val onWifi: (on: Boolean) -> Unit = {},
) {
    @Volatile private var running = false
    private val servers = ArrayList<BluetoothServerSocket>()
    private val lock = Any()
    @Volatile private var accepts = 0
    @Volatile private var active: BluetoothSocket? = null
    private val p2p = P2pJoin(ctx, onBusy = { onP2pBusy() }, onWifi = { on -> onWifi(on) }, onJoined = { ip -> onP2pJoined(ip) }, onLost = { onP2pLost() })
    /** The phone address in the head unit group (after login) and IP request from head unit: MD_WIFI_IP leaves when there are both. */
    @Volatile private var p2pIp: String? = null
    @Volatile private var ipRequested = false
    private val txLock = Any()
    private val main = Handler(Looper.getMainLooper())
    /** The last details from head unit, even missed "already in the group": they are re-entered after the breakup of the group (h211). */
    @Volatile private var lastTarget: P2pTarget? = null
    /** Generation recovery after the breakup of the group: new BT-session, group login or stop it is canceled. */
    @Volatile private var rejoinGen = 0
    @Volatile private var rejoining = false
    private var adapterReceiver: BroadcastReceiver? = null

    /** Incoming TCP from P2P-group head unit: run in Tcp6Listener on a par with the subnets of the lighthouse. */
    fun ownsPeer(a: java.net.InetAddress): Boolean = p2p.owns(a)

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        p2pIp = null
        ipRequested = false
        lastTarget = null
        rejoinGen++
        rejoining = false
        accepts = 0     // for this launch: in the line `stop` accepts= are considered connections head unit with the last start
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> return fail("нет адаптера")
            !hasPermission() -> return fail("нет разрешения BLUETOOTH_CONNECT")
            !adapter.isEnabled -> return fail("Bluetooth выключен")
        }
        for ((tag, uuid) in UUIDS) {
            val ss = try {
                adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, uuid)
            } catch (e: Exception) {
                WLog.ev(WLog.BT, "listen-fail", "uuid" to tag, "err" to e.message)
                continue
            }
            synchronized(lock) { servers += ss }
            WLog.ev(WLog.BT, "listen", "uuid" to tag, "name" to SERVICE_NAME)
            Thread({ acceptLoop(tag, ss) }, "bt-accept-$tag").apply { isDaemon = true }.start()
        }
        CarLifeState.btProbe = if (synchronized(lock) { servers.isEmpty() }) "не слушает" else "слушаю carlife, ГУ не подключалась"
        watchAdapter()
    }

    /**
     * Waiting for no use: there is no RFCOMM from head unit, neither entry to the band, nor listeners died. h211: head unit closed RFCOMM immediately
     * after the invitation and for ~ 5 the National Museum of Art ACLThe old listener and P2P They kept the target for minutes, and head unit
     * opened SPP only after a clean restart.
     */
    fun idle(): Boolean = active == null && p2pIp == null || synchronized(lock) { running && servers.isEmpty() }

    /** stop + start: new entries of SDP, P2P for old purpose are being removed. */
    fun restart(why: String) {
        stop(why)
        start()
    }

    /** Bluetooth status of the phone in log: by log it is otherwise impossible to understand whose BT was turned off (h211). */
    private fun watchAdapter() {
        if (adapterReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val st = when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> "off"
                    BluetoothAdapter.STATE_TURNING_OFF -> "turning-off"
                    BluetoothAdapter.STATE_ON -> "on"
                    BluetoothAdapter.STATE_TURNING_ON -> "turning-on"
                    else -> "?"
                }
                WLog.ev(WLog.BT, "adapter", "state" to st)
            }
        }
        adapterReceiver = r
        ctx.registerReceiver(r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
    }

    fun stop(why: String) {
        val list = synchronized(lock) {
            if (!running) return
            running = false
            servers.toList().also { servers.clear() }
        }
        for (s in list) try { s.close() } catch (_: IOException) {}
        adapterReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: IllegalArgumentException) {} }
        adapterReceiver = null
        rejoinGen++
        rejoining = false
        active?.let { try { it.close() } catch (_: IOException) {} }
        p2p.stop(why)
        WLog.ev(WLog.BT, "stop", "why" to why, "accepts" to accepts)
    }

    private fun fail(why: String) {
        running = false
        CarLifeState.btProbe = "не слушает: $why"
        WLog.ev(WLog.BT, "off", "why" to why)
    }

    private fun acceptLoop(tag: String, ss: BluetoothServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                // not stop(): listener died by himself (BT phone turned off); restart on the next ACL will pick up again
                if (running) {
                    synchronized(lock) { servers.remove(ss) }
                    WLog.ev(WLog.BT, "listen-dead", "uuid" to tag, "err" to e.message)
                }
                return
            }
            accepts++
            onAccept()
            talk(tag, s)
        }
    }

    @SuppressLint("MissingPermission")
    private fun talk(tag: String, s: BluetoothSocket) {
        val dev = s.remoteDevice
        val name = try { dev?.name } catch (_: SecurityException) { null }
        WLog.ev(WLog.BT, "accept", "uuid" to tag, "dev" to WLog.maskMac(dev?.address), "devName" to name)
        AppLog.i("BT carlife: ГУ подключилась по Bluetooth ($tag, ${name ?: "?"})")
        CarLifeState.btProbe = "ГУ подключилась ($tag, ${name ?: "?"})"
        val t0 = SystemClock.elapsedRealtime()
        val parser = BtFrameParser()
        var why = "?"
        var targetAsked = false
        active?.let { old -> try { old.close() } catch (_: IOException) {} }      // head unit reconnected: old channel close
        active = s
        ipRequested = false
        rejoinGen++      // head unit reconnected BT: TARGET_INFO will come with the usual exchange, we remove our requests
        rejoining = false
        try {
            val inp = s.inputStream
            Thread.sleep(300)
            send(s, BtMsg.MD_WIRELESS_INFO_REQUEST)
            val buf = ByteArray(1024)
            while (running) {
                val n = inp.read(buf)
                if (n < 0) { why = "EOF"; break }
                for (f in parser.feed(buf, n)) {
                    val kv: List<Pair<String, Any?>> = when (f.type) {
                        BtMsg.HU_WIRELESS_INFO -> BtSummary.wirelessInfo(f.body)
                        BtMsg.HU_TARGET_INFO -> BtSummary.targetInfo(f.body)
                        BtMsg.HU_STATUS -> BtSummary.status(f.body)
                        else -> listOf("shape" to BtSummary.shape(f.body))
                    }
                    WLog.ev(WLog.BT, "rx", "type" to BtMsg.name(f.type), "len" to f.body.size, *kv.toTypedArray())
                    when (f.type) {
                        BtMsg.HU_WIRELESS_INFO -> {
                            CarLifeState.btProbe = "ГУ ответила: ${kv.joinToString(" ") { "${it.first}=${it.second}" }}"
                            if (!targetAsked) {
                                targetAsked = true
                                send(s, BtMsg.MD_TARGET_INFO_REQUEST)
                            }
                        }
                        BtMsg.HU_TARGET_INFO -> {
                            CarLifeState.btProbe = "ГУ прислала реквизиты Wi-Fi Direct, вхожу в группу"
                            val t = P2pTarget.parse(f.body)
                            if (t == null) WLog.ev(WLog.P2P, "off", "why" to "TARGET_INFO не разобран")
                            else {
                                lastTarget = t
                                onTargetInfo(dev?.address)
                                p2p.start(t)
                            }
                        }
                        BtMsg.HU_IP_REQUEST -> {
                            ipRequested = true
                            CarLifeState.btProbe = "ГУ просит IP телефона" + if (p2pIp == null) ", жду вход в группу" else ""
                            sendIpIfReady()
                        }
                    }
                }
            }
            if (why == "?") why = "остановлено"
        } catch (e: Exception) {
            why = "ошибка: ${e.message}"
        } finally {
            if (active === s) active = null
            try { s.close() } catch (_: IOException) {}
            WLog.ev(WLog.BT, "close", "why" to why, "aliveMs" to SystemClock.elapsedRealtime() - t0, "skipped" to parser.skipped)
        }
    }

    private fun send(s: BluetoothSocket, type: Int, body: ByteArray? = null) {
        synchronized(txLock) {
            s.outputStream.write(BtMsg.frame(type, body))
            s.outputStream.flush()
        }
        WLog.ev(WLog.BT, "tx", "type" to BtMsg.name(type), "len" to (body?.size ?: 0))
    }

    private fun onP2pJoined(ip: java.net.Inet4Address) {
        p2pIp = ip.hostAddress
        CarLifeState.btProbe = "телефон в группе ГУ (${ip.hostAddress})" + if (ipRequested) "" else ", ГУ ещё не просила IP"
        if (rejoining) WLog.ev(WLog.P2P, "rejoin-joined", "ipRequested" to ipRequested)
        rejoining = false
        rejoinGen++
        // MD_WIFI_IP on IP_REQUEST only, as Baidu (BluetoothWirlessHelper:65-69, 289-291): without request do not send
        sendIpIfReady()
    }

    /**
     * The head unit group has broken up (main thread). If Bluetooth also fell, head unit will reconnect it and send TARGET_INFO itself.
     * If he is alive (h211: Mazda G6S in reverse shoots Wi-Fi Direct, RFCOMM holds), the new TARGET_INFO will not come to sleep
     * machines: re-enter the latest details and ask TARGET_INFO yourself until we enter.
     */
    private fun onP2pLost() {
        p2pIp = null
        val s = active
        if (!running || s == null) return
        rejoining = true
        val g = ++rejoinGen
        WLog.ev(WLog.P2P, "rejoin-arm", "cached" to (lastTarget != null))
        AppLog.i("Wi-Fi Direct: группа ГУ распалась, Bluetooth жив - сама прошу у ГУ реквизиты")
        scheduleRejoin(g, s, 0)
    }

    private fun scheduleRejoin(g: Int, s: BluetoothSocket, attempt: Int) {
        val d = P2pPolicy.rejoinDelayMs(attempt)
        if (d == null) {
            WLog.ev(WLog.P2P, "rejoin-give-up", "attempts" to attempt)
            AppLog.i("Wi-Fi Direct: ГУ не подняла группу за $attempt запросов, жду переподключения Bluetooth")
            return
        }
        main.postDelayed({ rejoinStep(g, s, attempt) }, d)
    }

    private fun rejoinStep(g: Int, s: BluetoothSocket, attempt: Int) {
        if (g != rejoinGen || !running || active !== s || p2pIp != null) return
        if (P2pPolicy.rejoinDropBt(attempt)) return dropBt(s, attempt)
        if (attempt == 0) lastTarget?.let {
            WLog.ev(WLog.P2P, "rejoin-cached")
            p2p.start(it)
        }
        Thread({
            try {
                WLog.ev(WLog.P2P, "rejoin-ask", "n" to attempt + 1)
                send(s, BtMsg.MD_TARGET_INFO_REQUEST)
            } catch (e: IOException) {
                WLog.ev(WLog.BT, "tx-fail", "type" to "TARGET_INFO_REQUEST", "err" to e.message)
            }
        }, "bt-rejoin").apply { isDaemon = true }.start()
        scheduleRejoin(g, s, attempt + 1)
    }

    /**
     * head unit answers TARGET_INFO, and the group does not raise (h211, h185): close only active socket, listeners remain.
     * The accept cycle takes the head unit reconnection, and the TARGET_INFO comes with a normal exchange. Trying to log in: otherwise
     * the same TARGET_INFO after reconnection will get in SKIP "attempt goes". [stop] here is impossible: it will break listeners.
     */
    private fun dropBt(s: BluetoothSocket, attempts: Int) {
        rejoining = false
        rejoinGen++
        WLog.ev(WLog.P2P, "rejoin-drop-bt", "attempts" to attempts)
        AppLog.i("Wi-Fi Direct: ГУ не подняла группу за $attempts запросов, рву Bluetooth - жду, пока она переподключится")
        CarLifeState.btProbe = "Bluetooth сброшен, жду переподключения ГУ"
        p2p.stop("сброс BT")
        try { s.close() } catch (_: IOException) {}
    }

    /** MD_WIFI_IP after IP_REQUEST and group login; we will reply to the same address again. */
    private fun sendIpIfReady() {
        val ip = p2pIp ?: return
        val s = active ?: return
        if (!ipRequested) return
        try {
            send(s, BtMsg.MD_WIFI_IP, BtMsg.wifiIp(ip))
            ipRequested = false
            CarLifeState.btProbe = "отдал ГУ IP $ip, жду TCP от ГУ"
            AppLog.i("BT carlife: отдал ГУ адрес телефона $ip")
        } catch (e: IOException) {
            WLog.ev(WLog.BT, "tx-fail", "type" to "WIFI_IP", "err" to e.message)
        }
    }

    companion object {
        const val SERVICE_NAME = "carlife"
        val UUIDS = listOf(
            "spp" to UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            "carlife" to UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78"),
        )
    }
}
