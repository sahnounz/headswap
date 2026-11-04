package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Experimental setting "Silence before the hint" (section for developers): how many milliseconds of zero bridge
 * send to CarLife immediately after TTS_INIT, so that head unit could open the sound before the speech. Auto = 400 ms on VW
 * (20082120/20082122/20082134, h222), on the other head unit no. The explicit value is valid for any head unit, "No" = off.
 * The bridge reads the value on each TTS_INIT.
 */
enum class TtsPreroll(val titleRes: Int, val ms: Int?) {
    AUTO(R.string.tpreroll_auto, null),
    MS0(R.string.tpreroll_0, 0),
    MS100(R.string.tpreroll_100, 100),
    MS200(R.string.tpreroll_200, 200),
    MS400(R.string.tpreroll_400, 400);

    companion object {
        val DEFAULT = AUTO

        /** By name from prefs; unknown/empty = by default. */
        fun parse(key: String?): TtsPreroll = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
