package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/** The bridge settings that the car selection puts in the "My Car" section. */
data class CarSettings(
    val transcode: Boolean = false,
    val videoMode: VideoMode = VideoMode.DEFAULT,
    val aaDensity: Int = AaDensity.AUTO,
    val fps: FpsSetting = FpsSetting.DEFAULT,
    val musicRate: MusicRate = MusicRate.DEFAULT,
)

/** Korean head unit weak: on 30 frame and 48 kHz USB, we advise 32 kHz (h139, h218, h260); frame "Auto" as everyone else. */
private val KOREAN = CarSettings(musicRate = MusicRate.HZ32)

/**
 * Confirmed machines (README, "Compatibility") and community-tested settings for each.
 * The choice of machine is written by [settings] in AaPrefs, then any setting can be changed manually.
 * All machines are the same: direct passthrough and frame "Auto" (default 1280×720, cars with a special screen - its resolution), music "Auto"; individual values only verified live.
 * Exceptions - Elantra 7: transcoding and 15 frame; Tucson NX4c: transcoding (vertical frame with fields on both axes).
 * Features of the head unit, which the bridge determines itself (Hyundai CN7 - music 32 kHz in "Auto", VW ID - ceiling bitrate), are not duplicated here.
 * The item name is stored in prefs: without renaming.
 */
enum class CarPreset(val titleRes: Int, val settings: CarSettings = CarSettings()) {
    /** head unit 1920×640 (h269). */
    CITROEN_C5_X(R.string.car_citroen_c5_x, CarSettings(videoMode = VideoMode.WIDE_640)),
    /** Screen 1024×600, weak USB head unit (h277The bridge is crossing itself transcoding and ceiling 800 kbit/s */
    DONGFENG_DF6_2023(R.string.car_dongfeng_df6_2023, CarSettings(videoMode = VideoMode.R1024)),
    PEUGEOT_5008_2024(R.string.car_peugeot_5008_2024),
    HONDA_BREEZE_2(R.string.car_honda_breeze_2),
    /** h243: 1920×1080, "works decently". */
    HONDA_CRV_6(R.string.car_honda_crv_6, CarSettings(videoMode = VideoMode.R1080)),
    /** SEMIDRIVE X9, screen 1920×720; on frame "Auto" strips and flicker (h72). */
    HONDA_CRV_2026(R.string.car_honda_crv_2026, CarSettings(videoMode = VideoMode.WIDE_HD)),
    HONDA_CRV_2026_HYBRID(R.string.car_honda_crv_2026_hybrid, CarSettings(videoMode = VideoMode.WIDE_HD)),
    /** Egor Machine: 1920×1080, 30 frame. */
    HONDA_VEZEL_2022(R.string.car_honda_vezel_2022, CarSettings(videoMode = VideoMode.R1080)),
    /** head unit CN7: line passthrough blinks and goes out (h638), channel weak - transcoding 1280×480 on 15 frame. */
    HYUNDAI_ELANTRA_7(R.string.car_hyundai_elantra_7, KOREAN.copy(transcode = true, videoMode = VideoMode.WIDE, fps = FpsSetting.FPS15)),
    /** HSAE, vertical screen: 1280×480 works fast (h86). */
    HYUNDAI_IX35_2021(R.string.car_hyundai_ix35_2021, KOREAN.copy(videoMode = VideoMode.WIDE)),
    /** Vertical Screen 960×1280, CarLife modified to full screen, bottom climate 130 px: 960×1152 C transcoding - no stretching (h670). */
    HYUNDAI_TUCSON_NX4C(R.string.car_hyundai_tucson_nx4c, KOREAN.copy(transcode = true, videoMode = VideoMode.PORTRAIT_1152)),
    JETTA_VS(R.string.car_jetta_vs),
    KIA_K3_2023(R.string.car_kia_k3_2023, KOREAN),
    KIA_SPORTAGE_ACE(R.string.car_kia_sportage_ace, KOREAN),

    /** Mazda Connect 2 from China (channel 20622111, h238/h359): head unit requests 1280×480. */
    MAZDA_3_CN_2025(R.string.car_mazda_3_cn_2025, CarSettings(videoMode = VideoMode.WIDE)),
    /** Desay SV G6S: native screen 1728×648. */
    MAZDA_CX5_2025(R.string.car_mazda_cx5_2025, CarSettings(videoMode = VideoMode.WIDE_MID)),
    /** alps, CarLife 4.1: 1920×720 (h166). */
    MAZDA_CX50(R.string.car_mazda_cx50, CarSettings(videoMode = VideoMode.WIDE_HD)),
    /** yftech: earned on 15 frame (h10, h41). */
    MITSUBISHI_OUTLANDER_4(R.string.car_mitsubishi_outlander_4, CarSettings()),
    SKODA_KAMIQ_GT_2021(R.string.car_skoda_kamiq_gt_2021),
    /** CNS3 requests 2:1, 1280×640 (h292); at 1080p expires (h305). */
    SKODA_OCTAVIA_PRO(R.string.car_skoda_octavia_pro, CarSettings(videoMode = VideoMode.WIDE_2_1)),
    /** HSAE 21070007, 1920×720 (h420): encoder Xiaomi on 1920×720 falling, head unit Ignored crop SPS and takes the top 720 lines - direct passthrough with fields below. */
    TOYOTA_COROLLA_CROSS_2024(R.string.car_toyota_corolla_cross_2024, CarSettings(videoMode = VideoMode.WIDE_HD)),
    TOYOTA_LEVIN(R.string.car_toyota_levin),
    /** China 2025 and neighboring years: 1280×480. */
    TOYOTA_RAV4_2025(R.string.car_toyota_rav4_2025, CarSettings(videoMode = VideoMode.WIDE)),
    /** China: 1920×1080, 30 frame (Egor, 18.10). */
    VW_BORA_2021(R.string.car_vw_bora_2021, CarSettings(videoMode = VideoMode.R1080)),
    /** 1920×1080, rest of "Auto" (Alexander). */
    VW_GOLF_8_PRO(R.string.car_vw_golf_8_pro, CarSettings(videoMode = VideoMode.R1080)),
    VW_ID_UNYX(R.string.car_vw_id_unyx),
    VW_LAMANDO_L(R.string.car_vw_lamando_l),
    VW_SAGITAR_2021(R.string.car_vw_sagitar_2021),
    VW_TROC_2026(R.string.car_vw_troc_2026),
    VW_THARU_XR(R.string.car_vw_tharu_xr),

    /** VW CNS3: window CarLife 2240×976 on screen 2240×1260. */
    VW_TIGUAN_L_PRO(R.string.car_vw_tiguan_l_pro, CarSettings(videoMode = VideoMode.WIDE_976));

    companion object {
        /** By name from prefs; unknown/empty = machine not selected. */
        fun parse(key: String?): CarPreset? = values().firstOrNull { it.name == key }
    }
}
