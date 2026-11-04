package com.yegorkapomidorka.headswap.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** launcher: root background on the topic, rail on the left (always) and content area. */
@Composable
fun LauncherRoot(connected: Boolean) {
    val density = LocalDensity.current
    val model = LauncherModel
    LaunchedEffect(Unit) {
        while (true) {
            model.status = withContext(Dispatchers.Default) { StatusReader.read(model.context) }
            // headlight mode from head unit for the theme "Auto"
            model.huTheme = CarLifeState.huTheme
            delay(1000)
        }
    }
    // call status and log: on main thread (PhoneStateListener takes Looper stream creation)
    LaunchedEffect(Unit) {
        while (true) {
            PhoneController.tick(model.context)
            delay(1000)
        }
    }
    // “Turn on music automatically”: once on your desktop (new display = new session), not for every recomposition
    LaunchedEffect(connected) {
        if (connected) {
            delay(2000) // First Survey of MusicController Sessions
            LauncherActions.autoplay()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val spec = remember(w, h) { LauncherSpecs.forSize(w, h) }
        val metrics = remember(spec, density) { LauncherMetrics(spec, density) }
        val pal = if (model.lightTheme) Palettes.LIGHT else Palettes.DARK
        CompositionLocalProvider(LocalMetrics provides metrics, LocalAccent provides Pal.GOLD, LocalPalette provides pal) {
            Box(Modifier.fillMaxSize().launcherBackground(pal)) {
                if (!connected) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Txt(stringResource(R.string.l_connecting), spec.f1, color = Pal.SEC)
                    }
                } else if (model.foreign != null) {
                    // alien launcher: only its window and our navigation panel, no rail and overlay call
                    ForeignRoot()
                } else {
                    // rail always on the left; "Map on the left" rearranges only the main rows (HomeScreen)
                    Row(Modifier.fillMaxSize()) {
                        Rail()
                        Box(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { model.areaRect = it.boundsInRoot() }) {
                            AreaContent()
                        }
                    }
                    run {
                        EmbedLayers()
                        // incoming call on top of someone else's application or screen without a "Phone" card
                        if (PhoneController.callState == CallState.RINGING && (model.area != Area.Home || spec.strip)) {
                            // as the lower stripe of the main one: nailed to the bottom, rounding only from above
                            val r = metrics.dp(spec.radius)
                            Box(Modifier.fillMaxSize().padding(end = metrics.dp(spec.m)), contentAlignment = Alignment.BottomEnd) {
                                PhoneCard(Modifier.width(metrics.dp(spec.phone)).height(metrics.dp(spec.bottomH)),
                                    RoundedCornerShape(topStart = r, topEnd = r))
                            }
                        }
                        RemoveConfirm()
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaContent() {
    when (LauncherModel.area) {
        Area.Home -> HomeScreen()
        Area.AllApps -> AllAppsScreen()
        Area.Picker -> PickerScreen()
        Area.Settings -> LauncherSettingsScreen()
        Area.Calls -> CallsScreen()
        Area.Players -> PlayersScreen()
        Area.Navigator, is Area.App -> EmbedAreaBackdrop()
    }
}


/** The railing is at full height (S-shapedly distributed) and under it a separate golden navigation unit. Model v2. */
@Composable
private fun Rail() {
    val m = LocalMetrics.current
    val s = m.spec
    val accent = LocalAccent.current
    val model = LauncherModel
    val status = model.status
    val canBack = BackNav.enabled(model.area)
    Column(Modifier.width(m.dp(s.rail)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(m.dp(s.g))) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(bottomEnd = m.dp(s.radius))).background(Pal.SURFACE)
                .padding(top = m.dp(s.g), bottom = m.dp(s.g8)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(status.time, s.f1, color = Pal.RAIL, weight = FontWeight.SemiBold, mono = true)
            // “Back” is always visible: white everywhere except the main one, where there is nowhere to go (gray, without animation)
            val back = rememberPressAnim(600)
            RoundButton(s.rb, stringResource(R.string.l_back), onTap = { LauncherActions.embeddedBack() }, enabled = canBack,
                onPress = back::play) {
                BackIcon(s.backIc, if (canBack) Pal.RAIL else LocalPalette.current.backOff) { back.progress }
            }
            // logo: on the main - "All applications", on any other screen - home
            val logo = rememberPressAnim(1000)
            val home = model.area == Area.Home
            RoundButton(s.rb, stringResource(if (home) R.string.l_all_apps else R.string.l_home), onTap = { LauncherActions.logo() },
                onPress = logo::play) {
                Box(Modifier.matchParentSize().drawBehind {
                    // as CSS circle farthest-corner: radius 0.707 sides, feet 0 / 40% / 70%
                    drawCircle(Brush.radialGradient(
                        0f to accent.copy(alpha = 0.6f), 0.4f to accent.copy(alpha = 0.25f), 0.7f to accent.copy(alpha = 0f),
                        center = center, radius = size.minDimension * 0.7071f))
                }, contentAlignment = Alignment.Center) {
                    LogoIcon(s.logoIn, Pal.RAIL) { logo.progress }
                }
            }
            // on the settings screen, the gear is highlighted: current screen
            val gear = rememberPressAnim(900)
            val inSettings = model.area == Area.Settings
            RoundButton(s.rb, stringResource(R.string.l_settings), onTap = { LauncherActions.openSettings() }, onPress = gear::play,
                background = if (inSettings) accent.copy(alpha = 0.32f) else Color.Transparent) {
                GearIcon(s.ric, if (inSettings) Pal.RAIL else Pal.SEC2) { gear.progress }
            }
            // in the ribbon "View card" - a glass button on the map
            if (!s.strip) CardViewButton(s.rb, s.ric, Color.Transparent, Pal.SEC2)
        }
        // Navigation arrow: 2 turn and stop in random other side of the world
        val spin = rememberNavSpin()
        Pressable(
            Modifier.width(m.dp(s.rail)).height(m.dp(s.navH))
                .clip(RoundedCornerShape(topEnd = m.dp(s.radius))).background(accent),
            description = stringResource(R.string.l_navigation), onTap = { LauncherActions.openNavigator() },
            onPress = spin::play,
        ) { pressed ->
            if (pressed) Box(Modifier.matchParentSize().background(Color(0x33000000)))
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                SvgIcon(Ico.NAV, s.icBig, Pal.onAccent(accent), fill = true,
                    modifier = Modifier.graphicsLayer { rotationZ = spin.degrees })
            }
        }
    }
}

/** “View of the navigation card”: transparent on the rail, glass on the map in the ribbon. New tile falls on DOWN. */
@Composable
fun CardViewButton(sizePx: Int, iconPx: Int, background: Color, color: Color, modifier: Modifier = Modifier) {
    val anim = rememberPressAnim(700)
    RoundButton(sizePx, stringResource(R.string.l_card_view), onTap = { LauncherActions.cycleCardView() }, modifier = modifier,
        background = background, onPress = anim::play) {
        LayersIcon(iconPx, color) { anim.progress }
    }
}

/** Status bar on the right top: date, Bluetooth, Wi-Fi, network, media volume. Nothing pressed, no weather. */
@Composable
fun StatusBar(title: String? = null, showNet: Boolean = true) {
    val m = LocalMetrics.current
    val s = m.spec
    val st = LauncherModel.status
    Row(
        Modifier.fillMaxWidth().height(m.dp(s.statusH)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.dp(s.g), Alignment.End),
    ) {
        // header screen at left (settings), status at right
        if (title != null) Txt(title, s.fs, Modifier.weight(1f), color = Pal.TEXT, weight = FontWeight.SemiBold)
        Ltr {
            Txt(st.date, s.fs, color = Pal.SEC2)
            SvgIcon(Ico.BT, s.fs, if (st.bluetooth) Pal.SEC2 else Pal.SEC2.copy(alpha = 0.3f))
            SvgIcon(Ico.WIFI, s.fs, if (st.wifi) Pal.SEC2 else Pal.SEC2.copy(alpha = 0.3f))
            // network with signal level - here, next to Wi-Fi, not in rail
            if (showNet && st.signal >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                VectorIcon(VIco.signal(st.signal), 24f, s.fs, Pal.SEC2)
                Txt(st.net, s.fs, color = Pal.SEC2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.dp(s.g4))) {
                SvgIcon(Ico.VOLUME, s.fs, Pal.SEC2)
                Txt(st.volume.toString(), s.fs, color = Pal.SEC2)
            }
        }
    }
}
