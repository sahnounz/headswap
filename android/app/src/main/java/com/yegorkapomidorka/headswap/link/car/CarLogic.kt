package com.yegorkapomidorka.headswap.link.car

/** Pure autostart logic for Bluetooth machine, without android.*: checked by JVM-tests. */
object CarMac {
    /** The same address? The register is not important (CDM gives MAC lowercase, BluetoothDevice - capital), null is never equal. */
    fun same(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)
}

/**
 * Debounce off machine: head unit when reconnected gives ACL_DISCONNECTED/ACL_CONNECTED surges (in the Vezel log 5 cycles per 7 s),
 * you can not extinguish the wait immediately. [arm] starts the countdown (repeated - shifts), [cancel] takes off (ACL_CONNECTED has arrived),
 * [fire] on the timer answers whether it is time to extinguish: the deadline is out and there was no cancellation; it works once. thread one (main).
 */
class AutoOffDebounce(private val delayMs: Long, private val clock: () -> Long) {
    private var dueAt = -1L

    val pending: Boolean get() = dueAt >= 0

    fun arm() {
        dueAt = clock() + delayMs
    }

    fun cancel() {
        dueAt = -1L
    }

    fun fire(): Boolean {
        if (dueAt < 0 || clock() < dueAt) return false
        dueAt = -1L
        return true
    }
}

/** No more often than once in [intervalMs]: foreign devices send ACL constantly, the log does not clog. */
class MinInterval(private val intervalMs: Long, private val clock: () -> Long) {
    private var last = -1L

    fun tryAcquire(): Boolean {
        val now = clock()
        if (last >= 0 && now - last < intervalMs) return false
        last = now
        return true
    }
}

/** What protection to enter the network of the machine. Successful protection is remembered ([CarWifi.sec]), then without brute force: one network - one MAC. */
object CarSecPolicy {
    fun first(remembered: String?, hasPass: Boolean): String = when {
        !hasPass -> "open"
        remembered == "wpa3" -> "wpa3"
        else -> "wpa2"
    }

    /** Repetition of the WPA3 after the failure of the WPA2 - only while the successful defense is unknown (otherwise the machine is simply not on the air). */
    fun retryWpa3(remembered: String?, tried: String, hasPass: Boolean): Boolean =
        hasPass && tried == "wpa2" && remembered == null
}

/** After how many waits for Wi-Fi without 6/6, we ask you to log in to the machine network (before that head unit can have time to come to the phone network itself). */
const val CAR_NET_DELAY_MS = 15_000L

/**
 * The machine was in communication (session USB/wireless connection, machine network) no longer than this ago: the machine network is requested immediately, without
 * [CAR_NET_DELAY_MS]. The cable was pulled, the Wi-Fi: machine was cut off nearby, there is no need to wait for it in the phone network (Paul's log)
 * 2026-10-17: 15 with silence at each switch wire -> wireless connection.
 */
const val CAR_RECENT_MS = 60_000L

/** How much the system is looking for the machine network and waiting for approval; did not find - a hint about Wi-Fi manually. */
const val CAR_NET_TIMEOUT_MS = 90_000L

/** Because of how many waits without accept on RFCOMM `carlife` prompt to reconnect Bluetooth (head unit opens it only when connecting BT). */
const val BT_HINT_MS = 15_000L

/** As the selected machine connects to wireless connection; tied to its Bluetooth- address (see CarProfile). */
enum class CarConnMode {
    UNKNOWN,
    /** head unit sent TARGET_INFO to Bluetooth: Wi-Fi Direct, the phone is part of her group. */
    BT_CARLIFE,
    /** 6/6 lifted from the lighthouse subnet, without Wi-Fi Direct. */
    HOTSPOT,
}

/** Events in which the fate of a machine network request is decided. */
enum class CarNetEvent { AUTO_OFF, STOP, USB_WINS, DESTROY, SESSION_START, SESSION_END }

/** Pure machine network request logic, without android.*: checked by JVM-tests. */
object CarNetPolicy {
    /**
     * Is it time to send a request: the network of the machine is set, there is a waiting for Wi-Fi (auto or manual), no session (neither USB, nor
     * wireless connection) and 6/6 not yet, the request is not hanging and not issued, since the beginning of the wait has passed no less [delayMs].
     * Wi-Fi Direct-Car Control[wifiDirectCar]) the network is not requested: the phone is in the group head unitThe request only gets in the way.
     */
    fun shouldRequest(
        carWifiSet: Boolean,
        autoOrManualWaiting: Boolean,
        sessionActive: Boolean,
        usbActive: Boolean,
        alreadyRequesting: Boolean,
        waitedMs: Long,
        sixOfSixReady: Boolean,
        wifiDirectCar: Boolean,
        delayMs: Long = CAR_NET_DELAY_MS,
    ): Boolean = carWifiSet && autoOrManualWaiting && !sessionActive && !usbActive && !alreadyRequesting &&
        !sixOfSixReady && !wifiDirectCar && waitedMs >= delayMs

    /**
     * Machine network request delay: 0If the car is less connected [CAR_RECENT_MS] Back ([sinceCarMs], null = not
     * was) or the selected machine is now connected to Bluetooth ([carBtNear]): it is nearby and on, wait for it in the network
     * There is no need for a phone (Paul 2026-10-18: the first pressing of the CarLife tiles on the head unit fell "not found" while the 15 with went).
     * Also 0, if 6/6 have already risen in the network of this machine ([carNetProven]): head unit distributes its Wi-Fi and the phone network is not
     * goes, and to choose a car on Bluetooth is not for everyone (Paul 2026-10-19: CDM fell, 15 with each start).
     */
    fun delayMs(sinceCarMs: Long?, carBtNear: Boolean = false, carNetProven: Boolean = false): Long =
        if (carBtNear || carNetProven || (sinceCarMs != null && sinceCarMs in 0 until CAR_RECENT_MS)) 0L else CAR_NET_DELAY_MS

    /**
     * Whether to let go of the network. Stop waiting, cable and death service - yes. The beginning and end of the wireless session - no: network
     * The machine keeps its TCP, and after the end of the session the wait usually resumes on the same network.
     */
    fun shouldRelease(event: CarNetEvent): Boolean = when (event) {
        CarNetEvent.AUTO_OFF, CarNetEvent.STOP, CarNetEvent.USB_WINS, CarNetEvent.DESTROY -> true
        CarNetEvent.SESSION_START, CarNetEvent.SESSION_END -> false
    }
}

/** The pure logic of the “reconnect Bluetooth” prompt: without android.*, is checked by JVM-tests. */
object BtReconnectHint {
    /**
     * Whether to show: there is a waiting without a session already [BT_HINT_MS], head unit has never connected to RFCOMM `carlife`, although
     * phone with machine Bluetooth connected (Mazda opens carlife only at the time of connection BT), and hotspot
     * this machine has not been connected before. One show to wait. head unit has already sent TARGET_INFO ([targetInfoThisWait]) -
     * carlife she opened, reconnect Bluetooth no need.
     */
    fun shouldShow(
        waiting: Boolean,
        sessionActive: Boolean,
        waitedMs: Long,
        acceptsThisWait: Int,
        targetInfoThisWait: Boolean,
        carBtConnected: Boolean,
        mode: CarConnMode,
        alreadyShown: Boolean,
    ): Boolean = waiting && !sessionActive && waitedMs >= BT_HINT_MS && acceptsThisWait == 0 && !targetInfoThisWait &&
        carBtConnected && mode != CarConnMode.HOTSPOT && !alreadyShown
}
