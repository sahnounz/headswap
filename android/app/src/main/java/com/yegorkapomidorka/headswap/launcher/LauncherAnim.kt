package com.yegorkapomidorka.headswap.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Teyes.dc.html. animations click curves */
object PressEasing {
    /** Logo and gear: cubic-bezier(.45,0,.2,1). */
    val SPIN = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)
    /** "Card View": cubic-bezier(.3,0,.2,1). */
    val DROP = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
    /** "Back": CSS ease-in-out. */
    val IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    /** Navigation arrow: cubic-bezier(.15,.85,.25,1). */
    val NAV = CubicBezierEasing(0.15f, 0.85f, 0.25f, 1f)
}

/**
 * 0..1 animation progress, linear: curve and key frame uses icon.
 * At rest 1 (end of animation = original view); each DOWN restarts from scratch.
 */
class PressAnim(private val scope: CoroutineScope, private val ms: Int) {
    private val anim = Animatable(1f)
    val progress: Float get() = anim.value
    fun play() {
        if (!LauncherModel.animations) return
        scope.launch {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(ms, easing = LinearEasing))
        }
    }
}

@Composable
fun rememberPressAnim(ms: Int): PressAnim {
    val scope = rememberCoroutineScope()
    return remember(ms) { PressAnim(scope, ms) }
}

/** Navigation arrow: rot + = 720 + 45 * (1.7), 1,6 s, cubic-bezier(.15,.85,.25,1); always not the side where it stood. */
class NavSpin(private val scope: CoroutineScope) {
    private val anim = Animatable(0f)
    private var target = 0f
    val degrees: Float get() = anim.value
    fun play() {
        if (!LauncherModel.animations) return
        target = NavSpinLogic.next(target, (1..7).random())
        scope.launch { anim.animateTo(target, tween(1600, easing = PressEasing.NAV)) }
    }
}

@Composable
fun rememberNavSpin(): NavSpin {
    val scope = rememberCoroutineScope()
    return remember { NavSpin(scope) }
}

internal object NavSpinLogic {
    /** Next angle: two turns plus 1..7 steps along 45 degrees. */
    fun next(rot: Float, steps: Int): Float = rot + 720f + 45f * steps.coerceIn(1, 7)
}

/** key frame "Back": The chevron flies to the left and goes out to 40%, on 41% appears to the right and returns. */
internal object BackKeyframes {
    /** X shift in viewBox 24 and transparency. */
    fun at(p: Float): Pair<Float, Float> = when {
        p >= 1f -> 0f to 1f
        p <= 0.4f -> PressEasing.IN_OUT.transform(p / 0.4f).let { -14f * it to 1f - it }
        p < 0.41f -> 14f to 0f
        else -> PressEasing.IN_OUT.transform((p - 0.41f) / 0.59f).let { 14f * (1f - it) to it }
    }
}

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

private fun DrawScope.strokePath(p: Path, color: Color, width: Float) =
    drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** Chevron "Back" rails with animation by [progress]. */
@Composable
fun BackIcon(sizePx: Int, color: Color, progress: () -> Float) {
    val m = LocalMetrics.current
    val path = remember { parse(Ico.CHEV_BACK[0]) }
    Canvas(Modifier.size(m.dp(sizePx))) {
        val sc = size.minDimension / 24f
        val (dx, alpha) = BackKeyframes.at(progress())
        scale(sc, sc, pivot = Offset.Zero) {
            translate(left = dx) { strokePath(path, color.copy(alpha = color.alpha * alpha), 2f) }
        }
    }
}

/** The Headswap: arrow logo makes the 2 revolve around the center, the screen in the center is still. */
@Composable
fun LogoIcon(sizePx: Int, color: Color, progress: () -> Float) {
    val m = LocalMetrics.current
    val paths = remember { VIco.LOGO.map { parse(it.d) } }
    Canvas(Modifier.size(m.dp(sizePx))) {
        val sc = size.minDimension / VIco.LOGO_VIEWPORT
        val deg = 720f * PressEasing.SPIN.transform(progress().coerceIn(0f, 1f))
        scale(sc, sc, pivot = Offset.Zero) {
            for ((i, vp) in VIco.LOGO.withIndex()) {
                // The last path is the screen, the first four are arrows.
                if (i < VIco.LOGO.size - 1) rotate(deg, pivot = Offset(24f, 24f)) { drawVPath(vp, paths[i], color) }
                else drawVPath(vp, paths[i], color)
            }
        }
    }
}

private fun DrawScope.drawVPath(vp: VPath, p: Path, color: Color) {
    if (vp.fill) drawPath(p, color, style = Fill)
    vp.strokeW?.let { strokePath(p, color, it) }
}

/** Six: one turn during animation. */
@Composable
fun GearIcon(sizePx: Int, color: Color, progress: () -> Float) {
    SvgIcon(Ico.GEAR, sizePx, color, stroke = 2f,
        modifier = Modifier.graphicsLayer { rotationZ = 360f * PressEasing.SPIN.transform(progress().coerceIn(0f, 1f)) })
}

/**
 * “Card View”: new tile falls on top (-7 units, manifests itself), the former top drops to the bottom place (-5 -> 0),
 * The former lower one goes down (+6) and goes out.
 */
@Composable
fun LayersIcon(sizePx: Int, color: Color, progress: () -> Float) {
    val m = LocalMetrics.current
    val top = remember { parse(Ico.LAYERS[0]) }
    val low = remember { parse(Ico.LAYERS[1]) }
    Canvas(Modifier.size(m.dp(sizePx))) {
        val sc = size.minDimension / 24f
        val p = progress().coerceIn(0f, 1f)
        val e = PressEasing.DROP.transform(p)
        scale(sc, sc, pivot = Offset.Zero) {
            translate(top = -7f * (1f - e)) { strokePath(top, color.copy(alpha = color.alpha * e), 2f) }
            translate(top = -5f * (1f - e)) { strokePath(low, color, 2f) }
            if (p < 1f) translate(top = 6f * e) { strokePath(low, color.copy(alpha = color.alpha * (1f - e)), 2f) }
        }
    }
}
