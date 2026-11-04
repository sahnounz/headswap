package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Experimental configuration of the Voice Pack Size (developer section): which pieces of the bridge the PCM speech cuts into
 * (assistant reply in VR, navigation prompts in TTS) before CarLife. Auto = 1024 B on VW CNS2 Delphi (h425),
 * on the rest of head unit as gives Android Auto (up to CarlifeAudio.MAX_CHUNK). Dimensions multiples of 2 (mono PCM16).
 * The bridge reads the value on each speech packet, the change is effective immediately.
 */
enum class VoiceChunk(val titleRes: Int, val bytes: Int?) {
    AUTO(R.string.vchunk_auto, null),
    B2048(R.string.vchunk_2048, 2048),
    B1024(R.string.vchunk_1024, 1024),
    B512(R.string.vchunk_512, 512);

    companion object {
        val DEFAULT = AUTO

        /** By name from prefs; unknown/empty = by default. */
        fun parse(key: String?): VoiceChunk = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
