package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Music Frequency Settings: Auto (48 kHz, for head unit Hyundai CN7 - 32 kHz) or fixed for any head unit.
 * The bridge records the frequency at the time of the MEDIA INIT, so the change is valid from the next INIT (connection or new track).
 */
enum class MusicRate(val titleRes: Int, val hz: Int?) {
    AUTO(R.string.mrate_auto, null),
    HZ48(R.string.mrate_48, 48000),
    HZ44(R.string.mrate_44, 44100),
    HZ32(R.string.mrate_32, 32000),
    HZ24(R.string.mrate_24, 24000),
    HZ22(R.string.mrate_22, 22050);

    companion object {
        val DEFAULT = AUTO

        /** By name from prefs; unknown/empty = by default. */
        fun parse(key: String?): MusicRate = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
