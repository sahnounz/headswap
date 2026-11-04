package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Experimental setting "touch thinning" (developer section): how many single MOVE one finger
 * The bridge passes before being sent to AA. ×2 = Every second person leaves (see [TouchDecimator]). Explicit value is valid
 * on any head unit, by default off. Bridge reads value for each event.
 */
enum class TouchDecimation(val titleRes: Int, val every: Int) {
    OFF(R.string.tdec_off, 1),
    X2(R.string.tdec_x2, 2);

    companion object {
        val DEFAULT = OFF

        /** By name from prefs; unknown/empty = by default. */
        fun parse(key: String?): TouchDecimation = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
