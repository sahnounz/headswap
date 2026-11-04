package com.yegorkapomidorka.headswap.launcher

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.absoluteOffset
import com.yegorkapomidorka.headswap.R
import kotlin.math.roundToInt


/** Package/class key to run the package: first entry from the launcher application directory. */
private fun keyFor(pkg: String?): String? =
    pkg?.let { p -> LauncherModel.catalog.firstOrNull { it.packageName == p }?.key }

/**
 * Live windows of other people's applications on top of the content. Lie at the root, not inside the screens: TextureView should survive
 * leaving for the main one, otherwise the texture will die under the working application.
 */
@Composable
fun EmbedLayers() {
    val model = LauncherModel
    val area = model.area
    val areaRect = model.areaRect
    val cardRect = model.navCardRect
    val navKey = keyFor(model.navigatorPkg)

    // Navigator: card size, on the entire screen to the right of the rail - in "Navigator" mode
    val navTarget = if (area == Area.Navigator) areaRect else cardRect
    LaunchedEffect(navKey, navTarget?.width, navTarget?.height) {
        EmbedHost.want(EmbedHost.nav, navKey, navTarget?.width?.roundToInt() ?: 0, navTarget?.height?.roundToInt() ?: 0)
    }
    // Application: one window for all applications, changing the application = launch to the same display
    val appKey = model.appKey
    LaunchedEffect(appKey, areaRect?.width, areaRect?.height) {
        EmbedHost.want(EmbedHost.app, appKey, areaRect?.width?.roundToInt() ?: 0, areaRect?.height?.roundToInt() ?: 0)
    }
    LaunchedEffect(area) {
        when (area) {
            Area.Navigator -> EmbedHost.refresh(EmbedHost.nav)
            is Area.App -> EmbedHost.refresh(EmbedHost.app)
            else -> Unit
        }
    }

    val navShown = navKey != null && (area == Area.Navigator || (area == Area.Home && model.cardView == CardView.NAVIGATOR))
    // signature of the navigator window in the corner of the card (v2 layout); no full screen required
    val navPkg = model.navigatorPkg
    val navTag = if (area != Area.Navigator && navPkg != null) remember(navPkg) { LauncherModel.packageLabel(navPkg) } else null
    EmbedLayer(EmbedHost.nav, navTarget, shown = navShown, interactive = area == Area.Navigator,
        rounded = area != Area.Navigator, background = LocalPalette.current.mapBg,
        starting = stringResource(R.string.l_nav_starting), onCardTap = { LauncherActions.openNavigator() }, tag = navTag,
        cardViewButton = area == Area.Home && LocalMetrics.current.spec.strip)
    if (appKey != null) {
        val label = model.starting ?: ""
        EmbedLayer(EmbedHost.app, areaRect, shown = area is Area.App, interactive = true, rounded = false, background = LocalPalette.current.bgSolid,
            starting = stringResource(R.string.l_starting, label), onCardTap = null)
    }
}

private val OFFSCREEN = IntOffset(-100_000, -100_000)

@Composable
internal fun EmbedLayer(
    slot: EmbedSlot, target: Rect?, shown: Boolean, interactive: Boolean, rounded: Boolean, background: Color,
    starting: String, onCardTap: (() -> Unit)?, tag: String? = null, cardViewButton: Boolean = false,
) {
    var last by remember(slot) { mutableStateOf<Rect?>(null) }
    if (target != null) last = target
    val rect = last ?: return
    val m = LocalMetrics.current
    val density = LocalDensity.current
    SideEffect { slot.interactive = shown && interactive }
    val status = slot.status
    if (shown && status == EmbedStatus.READY && slot === EmbedHost.app) {
        LaunchedEffect(Unit) { LauncherModel.launchLock.finish(); LauncherModel.starting = null }
    }
    val shape = RoundedCornerShape(if (rounded) m.dp(m.spec.radius) else m.dp(0))
    val w = with(density) { rect.width.roundToInt().toDp() }
    val h = with(density) { rect.height.roundToInt().toDp() }
    // Hidden layer is removed behind the screen: AndroidView in Compose takes touch within its boundaries even with INVISIBLE.
    // and the hidden card of the navigator jammed the arrows "All applications". TextureView itself lives on.
    Box(
        Modifier.absoluteOffset { if (shown) IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) else OFFSCREEN }
            .size(w, h).clip(shape),
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    EmbedHost.adoptable(slot)?.let { runCatching { setSurfaceTexture(it) } }
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = EmbedHost.textureAvailable(slot, st)
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        // texture belongs to the slot: a child display producer works under it
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = synchronized(slot.lock) { slot.texture !== st }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    setOnTouchListener { v, ev ->
                        if (!slot.interactive) false else { EmbedHost.touch(slot, ev, v.width, v.height); true }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { v -> v.visibility = if (shown) android.view.View.VISIBLE else android.view.View.INVISIBLE },
        )
        if (shown) {
            if (status != EmbedStatus.READY) {
                Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (status == EmbedStatus.FAILED) {
                            Pressable(Modifier.fillMaxSize(), onTap = { EmbedHost.retry(slot) }) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Txt(stringResource(R.string.l_nav_failed), m.spec.f2, color = Pal.SEC2, maxLines = 3, align = TextAlign.Center)
                                }
                            }
                        } else Txt(starting, if (rounded) m.spec.fs else m.spec.f1, color = Pal.SEC2, weight = FontWeight.Medium,
                            maxLines = 2, align = TextAlign.Center)
                    }
                }
            } else if (onCardTap != null && !interactive) {
                // Navigation card: tap opens the window, touch does not go to the application
                Pressable(Modifier.fillMaxSize(), onTap = onCardTap) {}
            }
            if (tag != null && status == EmbedStatus.READY) {
                // signature without touching: tap passes to the card under it
                Box(
                    Modifier.align(Alignment.BottomStart).padding(start = m.dp(m.spec.g8), bottom = m.dp(m.spec.g8))
                        .clip(RoundedCornerShape(m.dp(m.spec.radius))).background(LocalPalette.current.tagBg)
                        .padding(horizontal = m.dp(m.spec.g8), vertical = m.dp(m.spec.b4)),
                ) { Txt(tag, m.spec.tag, color = Pal.SEC2) }
            }
            // tape: "Card view" on the map above the window of the navigator (there is no place for it in the rail)
            if (cardViewButton) MapCardViewButton(Modifier.align(Alignment.TopEnd))
        }
    }
}

/** The card is in place of navigation: a live window of the navigator (draws a layer), the choice of the navigator or a large clock. */
@Composable
fun NavCard(modifier: Modifier) {
    val model = LauncherModel
    val m = LocalMetrics.current
    val s = m.spec
    CardBox(modifier.onGloballyPositioned { if (model.cardView == CardView.NAVIGATOR) model.navCardRect = it.boundsInRoot() }, background = LocalPalette.current.mapBg) {
        // a copy of the button of the tape for the clock and the choice of the navigator: there is no window on top of the navigator, which means that there is no copy of it
        if (s.strip) MapCardViewButton(Modifier.align(Alignment.TopEnd).zIndex(1f))
        when {
            model.cardView == CardView.CLOCK -> ClockFace()
            model.navigatorPkg == null -> Pressable(Modifier.fillMaxSize(), onTap = { LauncherActions.openNavigatorChoice() }) { pressed ->
                if (pressed) Box(Modifier.matchParentSize().background(Pal.SURFACE_PRESSED))
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SvgIcon(Ico.NAV, s.icBig, Pal.SEC, fill = true)
                        val text = if (!model.catalogLoaded || model.installedNavigators.isNotEmpty())
                            stringResource(R.string.l_nav_choose) else stringResource(R.string.l_nav_not_installed)
                        Txt(text, s.f1, color = Pal.SEC2, weight = FontWeight.Medium, align = TextAlign.Center)
                    }
                }
            }
            else -> Unit
        }
    }
}

/** Glass button "View card" in the upper right corner of the map (only 1280x480 tape). */
@Composable
private fun MapCardViewButton(modifier: Modifier) {
    val m = LocalMetrics.current
    val s = m.spec
    CardViewButton(s.pcB, s.pcIc, GlassBg, Pal.WHITE.copy(alpha = 0.75f),
        modifier.padding(top = m.dp(s.g8), end = m.dp(s.g8)).border(m.dp(1), GlassBorder, CircleShape))
}

/** Type of watch: large clock and date. */
@Composable
private fun ClockFace() {
    val m = LocalMetrics.current
    val s = m.spec
    val st = LauncherModel.status
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(st.time, s.t * 2, weight = FontWeight.Bold, mono = true)
            Txt(st.date, s.f1, color = Pal.SEC2)
        }
    }
}

/** The area under the built-in application: the layer is drawn on top, there is only a background. */
@Composable
fun EmbedAreaBackdrop() {
    Box(Modifier.fillMaxSize().background(LocalPalette.current.bgSolid))
}
