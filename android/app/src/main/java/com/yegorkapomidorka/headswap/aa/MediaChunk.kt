package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Experimental setting of the "Size of the package of music" (section for developers): what pieces of the bridge cut PCM music
 * before CarLife MEDIA_DATA. Auto = as gives Android Auto (up to CarlifeAudio.MAX_CHUNK). Dimensions multiples of 4 (frame stereo PCM16).
 * The bridge reads the value for each music buffer, the change is effective immediately.
 */
enum class MediaChunk(val titleRes: Int, val bytes: Int?) {
    AUTO(R.string.mchunk_auto, null),
    B4096(R.string.mchunk_4096, 4096),
    B2560(R.string.mchunk_2560, 2560),
    B2048(R.string.mchunk_2048, 2048),
    B1024(R.string.mchunk_1024, 1024);

    companion object {
        val DEFAULT = AUTO

        /** By name from prefs; unknown/empty = by default. */
        fun parse(key: String?): MediaChunk = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
