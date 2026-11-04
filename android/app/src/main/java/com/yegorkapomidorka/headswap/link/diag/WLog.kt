package com.yegorkapomidorka.headswap.link.diag

import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog

/**
 * Structural log of communication phases (frame E6): string `WL <фаза> <событие> ключ=значение … t=<мс от начала попытки>`
 * in the common log (only the file and logcat, not the screen). According to these lines, the beta tester log is understood without questions to it.
 *
 * What WL does not and should not appear: SSID and passwords (hotspot, P2P-group), p2pNetInfo and scanRecord (only available)
 * and length), keys, certificates, signatures and random values of handshake, hex-dumps. BT-MAC only through [maskMac].
 */
object WLog {
    /** Communication phases. */
    const val LINK = "link"
    const val TCP = "tcp"
    const val HS = "hs"
    const val VIDEO = "video"
    const val BT = "bt"
    /** Phone entry in Wi-Fi Direct-group head unit (branch A). */
    const val P2P = "p2p"
    /** Autostart by Bluetooth machine (choice, ACL, auto mode service). */
    const val TRIGGER = "trigger"

    @Volatile var clock: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile var sink: (String) -> Unit = { AppLog.i(it, ui = false) }
    @Volatile private var t0 = 0L

    /** Start of connection attempt: t. is considered from it */
    fun begin(kind: String, vararg kv: Pair<String, Any?>) {
        t0 = clock()
        ev(LINK, "begin", "transport" to kind, *kv)
    }

    fun ev(phase: String, event: String, vararg kv: Pair<String, Any?>) {
        sink(format(phase, event, clock() - t0, kv.asList()))
    }

    fun format(phase: String, event: String, tMs: Long, kv: List<Pair<String, Any?>>): String {
        val sb = StringBuilder("WL ").append(phase).append(' ').append(event)
        for ((k, v) in kv) sb.append(' ').append(k).append('=').append(value(v))
        return sb.append(" t=").append(tMs).toString()
    }

    /** Meaning in one word: spaces and quotation marks are screened, the long is cut. */
    private fun value(v: Any?): String {
        val s = (v ?: "-").toString().replace('\n', ' ')
        val cut = if (s.length > MAX_VALUE) s.take(MAX_VALUE) + "…" else s
        return if (cut.isEmpty() || cut.any { it == ' ' || it == '"' || it == '=' }) "\"" + cut.replace("\"", "'") + "\"" else cut
    }

    /** BT-MAC: the first three octets (manufacturer), the rest is hidden. */
    fun maskMac(mac: String?): String {
        if (mac == null) return "-"
        val p = mac.split(':')
        return if (p.size == 6) p.take(3).joinToString(":") + ":xx:xx:xx" else "?"
    }

    private const val MAX_VALUE = 120
}
