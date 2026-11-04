package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R
import java.util.Locale

/** The rectangle of the original frame in shares (u to the right, v up: v=1 is the top row of frame; for the unsymmetrical crop this is important). */
data class VideoCrop(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val isNone: Boolean get() = this == NONE

    companion object {
        val NONE = VideoCrop(0f, 0f, 1f, 1f)
    }
}

/**
 * Video geometry: the only source of truth for AA (SDR), CarLife output (INIT_DONE, encoder), crop and touch.
 * It is removed once per connection (AaBridgeCore.videoMode) and then everywhere else is taken from there, prefs is not readable.
 * Implement Preset [VideoMode] and user [CustomVideoMode].
 */
interface VideoGeometry {
    /** Resolution AA in SDR (SDR-line AapMessages.serviceDiscoveryResponse). */
    val aaResolution: String
    val aaW: Int
    val aaH: Int
    /** Fields margin_width / margin_height in VideoConfiguration SDR (total, divided in half at the edges). */
    val marginW: Int
    val marginH: Int
    val density: Int
    /** The size of the frame, which goes to the head unit (INIT_DONE and encoder). */
    val outW: Int
    val outH: Int
    val bitrate: Int

    /** Short label for logs: name enum or "custom 2240x976". */
    val label: String

    /** crop Transcoder: Central strip without margin-s (AA draws interface only within them). */
    val crop: VideoCrop
        get() = if (marginW == 0 && marginH == 0) VideoCrop.NONE
        else VideoCrop(marginW / 2f / aaW, marginH / 2f / aaH, 1f - marginW / 2f / aaW, 1f - marginH / 2f / aaH)

    /**
     * crop Transcoder with this AA. field layout With SPS-crop (h529) fields go right and down, interface
     * in the upper left corner; if transcoding is forced (Mobis, Pioneer), center band
     * [crop] would cut off the top of the interface and leave a black field (h521) at the bottom. v in GL grows up: top lines frame -
     * this is v from marginH/aaH to 1 (h242test6 with v 0.0,667 cut the bottom).
     */
    fun cropFor(marginsAtBottom: Boolean): VideoCrop =
        if (!marginsAtBottom || crop.isNone) crop else VideoCrop(0f, marginH.toFloat() / aaH, 1f - marginW.toFloat() / aaW, 1f)

    /** crop Transcoder: passthrough WIDE is only available through SPS-crop ([spsCropable]). */
    val needsTranscode: Boolean get() = !crop.isNone

    /**
     * Fields can be cut in passthrough by SPS (h529): 1:1 interface area with frame head unit, no scale,
     * and half of each field is multiples of crop 4:2:0 (2 px).
     */
    val spsCropable: Boolean
        get() = !crop.isNone && outW == aaW - marginW && outH == aaH - marginH && (marginW / 2) % 2 == 0 && (marginH / 2) % 2 == 0

    /** Geometry that really applies to this transcoding: switch in the passthrough crop is only SPS-crop (h529). */
    fun effective(transcode: Boolean): VideoGeometry =
        if (!transcode && needsTranscode && !spsCropable) VideoMode.R720 else this

    /** Output label for UI: "1280×720". */
    val outLabel: String get() = "${outW}×$outH"

    /**
     * Coordinates touch head unit (frame outW×outH) -> coordinates AA. The only place of such mapping.
     * With margin-s, AA is waiting for touch in the coordinates of the interface area (without fields), not the entire frame:
     * The shift on the margin/2 gave a slip on the 120 px down (Desay G6S, log 2026-10-10). Therefore, without a shift, only the clamp into the area.
     * If the interface area is scaled to frame head unit (WIDE_976: 1920×836 -> 2240×976), the coordinates are recalculated proportionally.
     * Without margin-s - 1:1 as is, without clamp.
     */
    fun mapTouch(x: Int, y: Int): Pair<Int, Int> {
        if (marginW == 0 && marginH == 0) return x to y
        val cw = aaW - marginW
        val ch = aaH - marginH
        val sx = if (cw == outW) x else (x.toLong() * cw / outW).toInt()
        val sy = if (ch == outH) y else (y.toLong() * ch / outH).toInt()
        return sx.coerceIn(0, cw - 1) to sy.coerceIn(0, ch - 1)
    }

    /** Density, which will go to SDR: manual from the settings or its own mode (AaDensity.AUTO). On crop and touch does not affect. */
    fun densityFor(densityOverride: Int): Int = if (densityOverride == AaDensity.AUTO) density else densityOverride

    /** SDR options for AA of this geometry; densityOverride - manual DPI interface AA (AaDensity.AUTO = density mode). */
    fun sdOptions(fullAudio: Boolean, densityOverride: Int = AaDensity.AUTO, carMake: String? = null, marginsAtBottom: Boolean = false) = AapSdOptions(
        resolution = aaResolution, density = densityFor(densityOverride), marginW = marginW, marginH = marginH, fullAudio = fullAudio,
        carMake = carMake, marginsAtBottom = marginsAtBottom,
    )

    /** Line for log. */
    fun describe(densityOverride: Int = AaDensity.AUTO): String
}

/** Video resolution - preset. */
enum class VideoMode(
    val titleRes: Int,
    val subRes: Int,
    override val aaResolution: String,
    override val marginW: Int,
    override val marginH: Int,
    override val density: Int,
    override val outW: Int,
    override val outH: Int,
    override val bitrate: Int,
) : VideoGeometry {
    R800(R.string.video_r800_title, R.string.video_r800_sub, "800x480", 0, 0, 120, 800, 480, 4_000_000),
    /** 1024×600 - Frequent permission of Chinese head unit (Pioneer RM56E8B, h277) from frame AA 1280×720 the Center 1024×600, 1:1. */
    R1024(R.string.video_r1024_title, R.string.video_r1024_sub, "1280x720", 256, 120, 160, 1024, 600, 2_000_000),
    R720(R.string.video_r720_title, R.string.video_r720_sub, "1280x720", 0, 0, 160, 1280, 720, 4_000_000),
    R1080(R.string.video_r1080_title, R.string.video_r1080_sub, "1920x1080", 0, 0, 240, 1920, 1080, 8_000_000),
    WIDE(R.string.video_wide_title, R.string.video_wide_sub, "1280x720", 0, 240, 160, 1280, 480, 4_000_000),
    /** 1728×648 - native screen Desay SV G6S (Mazda): from frame AA 1920×1080 Cut out the center, fields on both axes. */
    WIDE_MID(R.string.video_wide_mid_title, R.string.video_wide_mid_sub, "1920x1080", 192, 432, 160, 1728, 648, 5_000_000),
    WIDE_HD(R.string.video_wide_hd_title, R.string.video_wide_hd_sub, "1920x1080", 0, 360, 160, 1920, 720, 6_000_000),
    /** 1920×640 - screen Citroen C5 X (head unit Requests 1920×640, h269) from frame AA 1920×1080 the Central Strip 1920×640. */
    WIDE_640(R.string.video_wide_640_title, R.string.video_wide_640_sub, "1920x1080", 0, 440, 160, 1920, 640, 6_000_000),
    /** 1280×640 - screen Skoda Octavia A8 Pro (head unit SKODA CNS3 Requests 1280×640, h292) from frame AA 1280×720 the Central Strip 1280×640, 1:1. */
    WIDE_2_1(R.string.video_wide_2_1_title, R.string.video_wide_2_1_sub, "1280x720", 0, 80, 160, 1280, 640, 4_000_000),
    /** 2240×976 - window CarLife on VW CNS3 (Tiguan L Pro, screen 2240×1260 with regular strips head unit) from frame AA 1920×1080 the Central Strip 1920×836 and scaled ×1,167. */
    WIDE_976(R.string.video_wide_976_title, R.string.video_wide_976_sub, "1920x1080", 0, 244, 160, 2240, 976, 8_000_000),
    /**
     * 1920×836 - window VW BT 1420 (2240×976) the same proportion, but without scale (h529, Paul): from frame AA 1920×1080 Cutter
     * 1920×836 strip one to one, suitable for SPS-crop; to the window 2240×976 stretches itself head unit. In UI separate line
     * not shown: this is WIDE_976 in passthrough ([effective]).
     */
    WIDE_836(R.string.video_wide_836_title, R.string.video_wide_836_sub, "1920x1080", 0, 244, 160, 1920, 836, 6_000_000),
    /** 960×1280 - vertical screen 3:4 All of UsHyundai Tucson NX4c with the climate on top, h670From the vertical frame AA 1080×1920 the Center 960×1280, 1:1. */
    PORTRAIT_960(R.string.video_portrait_960_title, R.string.video_portrait_960_sub, "1080x1920", 120, 640, 160, 960, 1280, 6_000_000),
    /** 960×1152 - screen Tucson NX4c Climate Without Stripes1280 - 130Rounded to multiple 16; h670 - no stretching): centre 960×1152 from 1080×1920, 1:1. */
    PORTRAIT_1152(R.string.video_portrait_1152_title, R.string.video_portrait_1152_sub, "1080x1920", 120, 768, 160, 960, 1152, 6_000_000);

    override val aaW: Int = aaResolution.substringBefore('x').toInt()
    override val aaH: Int = aaResolution.substringAfter('x').toInt()
    override val label: String get() = name

    /**
     * The mode that really applies with this transcoding. WIDE_976 switch scales the area
     * and SPS-crop is not cut: in passthrough instead WIDE_836, to the window 2240×976 stretches itself head unit (h529).
     */
    override fun effective(transcode: Boolean): VideoMode = when {
        transcode -> this
        this == WIDE_976 -> WIDE_836
        needsTranscode && !spsCropable -> R720
        else -> this
    }

    /**
     * Line for log: "video mode WIDE: AA 1280x720 margins 0x240 density 160, CarLife 1280x480, crop v 0.167..0.833".
     * With manual DPI: "density 200 (manually, car 160)".
     */
    override fun describe(densityOverride: Int): String {
        fun f(v: Float) = String.format(Locale.US, "%.3f", v)
        val cropText = if (crop.isNone) "без кропа" else buildString {
            append("кроп")
            if (crop.u0 != 0f) append(" u ${f(crop.u0)}..${f(crop.u1)}")
            if (crop.v0 != 0f) append(" v ${f(crop.v0)}..${f(crop.v1)}")
        }
        val dens = if (densityOverride == AaDensity.AUTO) "$density" else "$densityOverride (вручную, авто $density)"
        return "видео-режим $name: AA $aaResolution margins ${marginW}x$marginH density $dens, CarLife ${outW}x$outH, $cropText"
    }

    companion object {
        val DEFAULT = R720

        /** By name from prefs; unknown/empty = by default. */
        fun parse(key: String?): VideoMode = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}

/** The reason why the size for [CustomVideoMode] is not accepted. */
enum class CustomResError { WIDTH_RANGE, HEIGHT_RANGE, ODD }

/**
 * frame outW×outH for head unit, which is built as in WIDE-modes:
 * AA substrate (800x480, 1280x720 or 1920x1080) + field + crop center and scale in transcoder.
 * The smallest substrate, in which frame fits entirely = fields, scale 1:1. Wider than 1920 - the aspect fits into 1920x1080
 * and the area is stretched to frame (as WIDE_976). Only with transcoding. All fields except outW/outH are calculated,
 * Therefore, the value comparison (data class) covers all geometry.
 * The designer does not check the range (so old presets like 1024x600 are played); for user input - [from].
 */
data class CustomVideoMode(override val outW: Int, override val outH: Int) : VideoGeometry {
    private val plate: Pair<Int, Int> = plateFor(outW, outH)
    private val area: Pair<Int, Int> = areaFor(outW, outH, plate)

    override val aaW: Int get() = plate.first
    override val aaH: Int get() = plate.second
    override val aaResolution: String get() = "${aaW}x$aaH"
    override val marginW: Int get() = plate.first - area.first
    override val marginH: Int get() = plate.second - area.second
    override val density: Int get() = DENSITY
    override val label: String get() = "custom ${outW}x$outH"

    /** The size of the AA interface area (fieldless substrate), which is scaled in frame. */
    val areaW: Int get() = area.first
    val areaH: Int get() = area.second

    /** bitrate Growing with the Square frame: 4 Mbit / s 1280x720within the 2..8 Mbit/s */
    override val bitrate: Int
        get() = (BASE_BITRATE.toLong() * outW * outH / (1280L * 720L)).coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()

    /** Scope scale up to frame: 1.0 = 1:1. */
    val scale: Double get() = maxOf(outW.toDouble() / areaW, outH.toDouble() / areaH)

    /** Without transcoding, its size is not available even when there are no fields (1280x720): frame builds only a transcoder. */
    override val needsTranscode: Boolean get() = true

    override fun describe(densityOverride: Int): String {
        val dens = densityFor(densityOverride)
        val mbps = bitrate / 1_000_000.0
        val rate = if (bitrate % 1_000_000 == 0) "${bitrate / 1_000_000}M" else String.format(Locale.US, "%.1fM", mbps)
        return "custom ${outW}x$outH ← AA $aaResolution поля ${marginW}x$marginH ×${String.format(Locale.US, "%.3f", scale)} dpi $dens $rate"
    }

    companion object {
        const val MIN_W = 640
        const val MAX_W = 2240
        const val MIN_H = 640
        const val MAX_H = 1080
        const val DENSITY = 160
        const val BASE_BITRATE = 4_000_000
        const val MIN_BITRATE = 2_000_000
        const val MAX_BITRATE = 8_000_000
        private val PLATES = listOf(800 to 480, 1280 to 720, 1920 to 1080)
        /** Vertical substrates AA (codes 6 and 7 in SDR); 480x800 AA does not. */
        private val PORTRAIT_PLATES = listOf(720 to 1280, 1080 to 1920)

        private fun platesFor(w: Int, h: Int) = if (w < h) PORTRAIT_PLATES else PLATES

        /** Why the size is not suitable; null = is suitable. Vertical frame - the same ranges with axes in reverse. */
        fun check(w: Int, h: Int): CustomResError? {
            val portrait = w < h
            val wRange = if (portrait) MIN_H..MAX_H else MIN_W..MAX_W
            val hRange = if (portrait) MIN_W..MAX_W else MIN_H..MAX_H
            return when {
                w !in wRange -> CustomResError.WIDTH_RANGE
                h !in hRange -> CustomResError.HEIGHT_RANGE
                w % 2 != 0 || h % 2 != 0 -> CustomResError.ODD
                else -> null
            }
        }

        /** Valid size -> mode, otherwise null. */
        fun from(w: Int, h: Int): CustomVideoMode? = if (check(w, h) == null) CustomVideoMode(w, h) else null

        /** The resulting geometry of prefs: is included and valid - it, otherwise preset. Invalid stored values = rollback to preset. */
        fun resolve(enabled: Boolean, w: Int, h: Int, preset: VideoMode): VideoGeometry =
            (if (enabled) from(w, h) else null) ?: preset

        private fun plateFor(w: Int, h: Int): Pair<Int, Int> =
            platesFor(w, h).let { p -> p.firstOrNull { w <= it.first && h <= it.second } ?: p.last() }

        private fun even(v: Double): Int = (Math.round(v / 2.0) * 2).toInt()

        private fun areaFor(w: Int, h: Int, plate: Pair<Int, Int>): Pair<Int, Int> {
            if (w <= plate.first && h <= plate.second) return w to h
            // does not fit into any substrate: the aspect fits into the largest (1920x1080 or 1080x1920)
            val (pw, ph) = plate
            return if (w.toDouble() / h >= pw.toDouble() / ph) pw to even(pw.toDouble() * h / w)
            else even(ph.toDouble() * w / h) to ph
        }
    }
}

/**
 * Manual DPI interface AA (density field in VideoConfiguration SDR). Absolute value, not percentage of the car:
 * AA layout defines width in dp = aaW * 160 / density, that is, the absolute density.
 */
object AaDensity {
    /** Do not override: density mode. */
    const val AUTO = 0
    const val MIN = 100
    const val MAX = 280
    const val STEP = 20

    /** Allowable manual values: 100, 120 .. 280. */
    val VALUES: List<Int> = (MIN..MAX step STEP).toList()

    /** From prefs: out of grid/range = auto. */
    fun parse(v: Int): Int = if (v in VALUES) v else AUTO

    /**
     * Step "-"/"+" in UI. From car to nearest grid value strictly below/above density mode
     * (If the car is above the maximum grid, "+" does not give anything, "-" puts a maximum).
     * On the edge of the grid remains as it was.
     */
    fun step(current: Int, auto: Int, up: Boolean): Int {
        val base = if (current == AUTO) auto else current
        return if (up) VALUES.firstOrNull { it > base } ?: current
        else VALUES.lastOrNull { it < base } ?: current
    }
}
