package com.yegorkapomidorka.headswap.aa

/**
 * Leakage MOVE one finger (setting) [TouchDecimation]). head unit Like. VW CNS2 Delphi sends to ~ 70 events per second,
 * video goes up to 30 fps, extra points AA does not show. From a row going single MOVE leaves each every-, counting
 * from the first after the last other event. We keep missed MOVE as postponed: any other event (DOWN, UP,
 * second finger, MOVE with coordinates of the second finger) first sends it because the last point before letting go
 * Finger should not be lost. Synchronous, without timers.
 */
class TouchDecimator(
    private val sink: (action: Int, x: Int, y: Int, px: Int, py: Int) -> Unit,
) {
    private class Ev(val action: Int, val x: Int, val y: Int, val px: Int, val py: Int)

    private val lock = Any()
    private var count = 0                      // single MOVE from last other event
    private var pending: Ev? = null            // last missed MOVE

    fun onTouch(action: Int, x: Int, y: Int, px: Int, py: Int, every: Int) = synchronized(lock) {
        val single = (action and 0xFF) == AapMessages.TOUCH_MOVE && px == 0 && py == 0 && action ushr 8 == 0
        if (every <= 1 || !single) {
            flushLocked()
            count = 0
            sink(action, x, y, px, py)
            return@synchronized
        }
        if (count++ % every == 0) {
            pending = null
            sink(action, x, y, px, py)
        } else {
            pending = Ev(action, x, y, px, py)
        }
    }

    /** Session closed: counter and delayed MOVE reset. */
    fun reset() = synchronized(lock) {
        count = 0
        pending = null
    }

    private fun flushLocked() {
        val e = pending ?: return
        pending = null
        sink(e.action, e.x, e.y, e.px, e.py)
    }
}
