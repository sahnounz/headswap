package com.yegorkapomidorka.headswap.link.bt

/** What to do with TARGET_INFO from head unit. */
enum class P2pOnTarget { START, RESTART, SKIP }

/** Pure P2P- group entry logic head unit, without android.*: checked by JVM-tests. */
object P2pPolicy {
    /**
     * New TARGET_INFO (head unit sends it to every connection Bluetooth): in the group already - skip, IP is retransmitted by
     * IP_REQUEST; attempt is on but not included (BUSY, feast not found, Wi-Fi included later, head unit changed name) - start
     * new with new details (h126). The same details, and try less [freshMs] - do not touch: rejoin asks
     * TARGET_INFO times in 5-10 with, and restart for each answer to tear search and hanging invitation (h211, Ravil 21.10).
     */
    fun onTarget(active: Boolean, joined: Boolean, same: Boolean = false, ageMs: Long = Long.MAX_VALUE, freshMs: Long = 30_000L): P2pOnTarget = when {
        joined -> P2pOnTarget.SKIP
        active && same && ageMs < freshMs -> P2pOnTarget.SKIP
        active -> P2pOnTarget.RESTART
        else -> P2pOnTarget.START
    }

    /**
     * Group head unit It broke up, and Bluetooth Aliveh211, Mazda G6S: The reverse is longer.25 (c): itself head unit TARGET_INFO does not send,
     * until reconnect Bluetooth, and this happens only after the machine sleeps. We ask for it ourselves: through 2 s, 5 s, further
     * Once in 10 c; null - enough ( ~ 10 min without entrance, head unit Obviously he doesn’t want to).
     */
    fun rejoinDelayMs(attempt: Int): Long? = when {
        attempt < 0 -> null
        attempt == 0 -> 2_000L
        attempt == 1 -> 5_000L
        attempt < REJOIN_ATTEMPTS -> 10_000L
        else -> null
    }

    /**
     * Queries TARGET_INFO on 2, 7, 17 and 27 seconds head unit responds, but the feast in the air never appeared (the first 30-second
     * The entry attempt ended in ~ 32 (c): Desay G6S Essence Wi-Fi Direct Only on a new connection Bluetooth (h211,
     * S23 23.10: 2 min rejoin without feast, after reconnection BT the Feast for 1,5 c; h185). On ~ 37 with a ditch RFCOMM.
     */
    fun rejoinDropBt(attempt: Int): Boolean = attempt >= REJOIN_DROP_BT_AFTER

    const val REJOIN_ATTEMPTS = 60
    const val REJOIN_DROP_BT_AFTER = 4
}
