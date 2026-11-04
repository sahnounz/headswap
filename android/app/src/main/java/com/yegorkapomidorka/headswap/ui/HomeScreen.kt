package com.yegorkapomidorka.headswap.ui

import android.animation.ObjectAnimator
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppTheme
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaServerCheck
import com.yegorkapomidorka.headswap.aa.BridgeMode
import com.yegorkapomidorka.headswap.update.Updater

/**
 * The main screen "Ready for a trip" (Main.dc.html): six states, stages, buttons; on top of the update board.
 * In the "launcher" mode, the same skeleton with other stages, texts and buttons (DesktopHomeLogic).
 */
class HomeScreen(host: MainActivity) : Screen(host) {
    private val root = ctx.vbox()
    override val view: View get() = root

    private val updBox = ctx.vbox()
    private var updKey: String? = null
    private val heroBox = ctx.vbox(14)
    private val stagesBox = ctx.vbox()
    private val footer = ctx.vbox(4)
    private var lastState: HomeState? = null
    private var lastDesk: DesktopHomeState? = null
    private var desktopMode = false
    /** Cover «Your launcher» to the right of the name; visible only in DESKTOP. mode */
    private val modeChip: TextView = ctx.text(ctx.getString(R.string.bm_own_launcher), 12f, 600, ctx.clr(R.color.on2)).apply {
        background = ctx.shape(ctx.clr(R.color.surf2), 8)
        pad(ctx, 8, 3, 8, 3)
        visibility = View.GONE
    }
    private var activation: PhoneActivationBlock? = null
    private var lastWireless: String? = null
    private val rings = ArrayList<ObjectAnimator>()
    private var graceLeftText: TextView? = null
    private var graceFill: View? = null
    private var graceRest: View? = null

    init {
        val c = ctx
        val header = c.hbox().apply {
            pad(c, 20, 8, 8, 0)
            val chip = FrameLayout(c).apply {
                background = c.shape(c.clr(R.color.pri), 10)
                addView(c.icon(R.drawable.ic_logo, 22, c.clr(R.color.onpri)), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            }
            addView(chip, lp(c.dp(32), c.dp(32)))
            addView(c.text("Headswap", 22f, 600).apply { letterSpacing = -0.01f }, lp(WRAP, WRAP).margins(c, 10))
            addView(modeChip, lp(WRAP, WRAP).margins(c, 8))
            addView(View(c), lp(0, 1, 1f))
            addView(c.iconButton(R.drawable.ic_settings, c.clr(R.color.on2), c.getString(R.string.home_settings)).apply {
                setOnClickListener { host.showSettings() }
            })
        }
        root.addView(header, c.lpDp(MATCH, 64))

        val body = c.vbox(12).apply { pad(c, 16, 8, 16, 8) }
        body.addView(updBox, lp(MATCH, WRAP))
        body.addView(heroBox, lp(MATCH, WRAP))
        body.addView(c.card(24).apply { pad(c, 0, 6, 0, 6); addView(stagesBox, lp(MATCH, WRAP)) }, lp(MATCH, WRAP))
        root.addView(ScrollView(c).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        }, lp(MATCH, 0, 1f))

        footer.pad(c, 16, 16, 16, 24)
        root.addView(footer, lp(MATCH, WRAP))
    }

    override fun onShow() {
        lastState = null
        lastDesk = null
        updKey = null
        syncMode()
        // open the screen: check the server head unit in a short session (in the mode "Your launcher" port 5277 is not needed)
        if (!desktopMode) AaServerCheck.wantAuto()
        tick()
    }

    /** The mode that really applies (on the Android below the 13 "Your launcher" is the Android Auto); a cap cap on it. */
    private fun syncMode() {
        desktopMode = BridgeMode.effective(AaPrefs(ctx).bridgeMode, Build.VERSION.SDK_INT) == BridgeMode.DESKTOP
        modeChip.visibility = if (desktopMode) View.VISIBLE else View.GONE
    }

    override fun onPermissionResult() {
        activation?.onPermissionResult()
    }

    /** Wireless line in the status card: only when mode is enabled and USB-session is not. */
    private fun wirelessLine(): String? {
        val w = CarLifeState.wireless
        // during a wireless session, the line would repeat the subtitle; after the break, you need ("I'm waiting for the machine again")
        if (CarLifeState.wirelessSession) return null
        return if (w != null) ctx.getString(R.string.home_wireless, w) else null
    }

    override fun onHide() {
        rings.forEach { it.cancel() }
        rings.clear()
    }

    override fun tick() {
        refreshUpdate()
        val wasDesktop = desktopMode
        syncMode()
        if (desktopMode != wasDesktop) { lastState = null; lastDesk = null }
        if (desktopMode) {
            tickDesktop()
            return
        }
        AaServerCheck.autoTick(ctx)
        val st = HeadswapStatus.current(ctx)
        val wl = wirelessLine()
        if (st == lastState && wl == lastWireless) return
        val prev = lastState
        lastState = st
        val wlSame = wl == lastWireless
        lastWireless = wl
        // only the countdown changes: we update it without reassembling the screen
        if (wlSame && prev != null && st.copy(graceLeftS = prev.graceLeftS) == prev && st.phase == Phase.RECONNECTING) {
            updateGrace(st)
            return
        }
        render(st)
    }

    /** X cover: not while connected to the machine; the cross hides it until the next version. */
    private fun refreshUpdate() {
        val c = ctx
        val rel = if (CarLifeState.running) null else Updater.banner(c)
        val key = if (rel == null) "" else "${rel.version}|${Updater.step}|${Updater.progress}"
        if (key == updKey) return
        updKey = key
        updBox.removeAllViews()
        if (rel == null) {
            updBox.visibility = View.GONE
            return
        }
        updBox.visibility = View.VISIBLE
        val sub = when (Updater.step) {
            Updater.Step.DOWNLOADING -> c.getString(R.string.upd_status_downloading, rel.version, Updater.progress)
            Updater.Step.INSTALLING -> c.getString(R.string.upd_status_installing)
            else -> c.getString(R.string.upd_banner_sub)
        }
        val row = c.hbox(14).apply {
            pad(c, 16, 10, 4, 10)
            background = c.rippleBg(c.shape(c.clr(R.color.surf), 20), 20)
            isClickable = true
            setOnClickListener { Updater.start(host) }
        }
        row.addView(c.icon(R.drawable.ic_download, 24, c.clr(R.color.acc)))
        row.addView(c.vbox(2).apply {
            addView(c.text(c.getString(R.string.upd_status_available, rel.version), 16f, 600))
            addView(c.text(sub, 14f, 400, c.clr(R.color.on2)))
        }, lp(0, WRAP, 1f))
        row.addView(c.iconButton(R.drawable.ic_close, c.clr(R.color.on2), c.getString(R.string.upd_later)).apply {
            setOnClickListener {
                Updater.dismiss(c, rel.version)
                refreshUpdate()
            }
        })
        updBox.addView(row, lp(MATCH, WRAP))
    }

    private fun updateGrace(st: HomeState) {
        graceLeftText?.text = ctx.getString(R.string.home_grace_left, st.graceLeftS)
        val total = st.graceTotalS.coerceAtLeast(1)
        val f = (st.graceLeftS.toFloat() / total).coerceIn(0f, 1f)
        graceFill?.layoutParams = lp(0, MATCH, f.coerceAtLeast(0.001f))
        graceRest?.layoutParams = lp(0, MATCH, (1f - f).coerceAtLeast(0.001f))
        (graceFill?.parent as? View)?.requestLayout()
    }

    private fun render(st: HomeState) {
        val c = ctx
        onHide()
        renderHero(st.tone, st.icon, st.title.get(c), st.sub.get(c), lastWireless)
        if (st.showChain) heroBox.addView(chain(), lp(MATCH, WRAP))
        if (st.phase == Phase.RECONNECTING) heroBox.addView(graceBlock(st), lp(MATCH, WRAP))

        renderStages(st.stages)

        footer.removeAllViews()
        // head unit server includes the user himself: and the first start, and after the break button leads to Android Auto settings
        if (st.primary != null) {
            footer.addView(c.primaryButton(c.getString(R.string.action_open_aa), R.drawable.ic_open).apply { setOnClickListener { Actions.startServerFromUi(c) } })
        }
        if (st.outline == OutlineAction.OPEN_AA) {
            footer.addView(c.outlineButton(c.getString(R.string.home_btn_open_aa), R.drawable.ic_open).apply { setOnClickListener { Actions.openAaSettings(c) } })
        }
        if (st.logLink) {
            // “Send log” lives inside self-diagnosis: the main thing is one clear second button
            footer.addView(c.tonalButton(c.getString(R.string.diag_home_btn)).apply { setOnClickListener { host.showDiag() } })
        }
        // AA settings are needed not only for the head unit: server button at the bottom in any state, if it is no longer there (the main one leads there too)
        if (st.primary == null && st.outline != OutlineAction.OPEN_AA) {
            footer.addView(c.outlineButton(c.getString(R.string.home_btn_open_aa), R.drawable.ic_open).apply { setOnClickListener { Actions.openAaSettings(c) } })
        }
        if (st.phase == Phase.RECONNECTING) updateGrace(st)
    }

    private fun tickDesktop() {
        val st = DesktopHomeStatus.current(ctx)
        if (st == lastDesk) return
        lastDesk = st
        renderDesktop(st)
    }

    /** “Your launcher”: the hero card and stages are the same as the Android Auto, the buttons at the bottom are your own. */
    private fun renderDesktop(st: DesktopHomeState) {
        val c = ctx
        onHide()
        renderHero(st.tone, st.icon, st.title.get(c), st.sub.get(c), null)
        renderStages(st.stages)
        footer.removeAllViews()
        for (b in st.buttons) {
            footer.addView(
                when (b) {
                    DesktopButton.ACTIVATE -> c.primaryButton(c.getString(R.string.home_desk_btn_activate), R.drawable.ic_key).apply { setOnClickListener { activate() } }
                    DesktopButton.STOP -> c.outlineButton(c.getString(R.string.home_desk_btn_stop), R.drawable.ic_stop).apply { setOnClickListener { Actions.stopBridge(c) } }
                    DesktopButton.DIAG -> c.tonalButton(c.getString(R.string.diag_home_btn)).apply { setOnClickListener { host.showDiag() } }
                },
            )
        }
    }

    /** The same thread as the ACTIVATE onboarding step and block in "Mode": PhoneActivationBlock without showing (embedded), button here. */
    private fun activate() {
        val block = activation ?: PhoneActivationBlock(host, embedded = true).also { activation = it }
        block.activateNow()
    }

    /** Hero card: tone, icon in a mug, header in one line, subtitle and optional line [note]. */
    private fun renderHero(tone: Tone, icon: HeroIcon, title: String, sub: String, note: String?) {
        val c = ctx
        val night = c.isNight
        val (bgRes, fgRes, onFgRes, textRes) = when (tone) {
            Tone.PRI -> listOf(R.color.pric, R.color.pri, R.color.onpri, if (night) R.color.on else R.color.acc)
            Tone.OK -> listOf(R.color.okc, R.color.ok, R.color.onok, if (night) R.color.on else R.color.ok)
            Tone.WARN -> listOf(R.color.warnc, R.color.warn, R.color.warnc, if (night) R.color.on else R.color.warn)
            Tone.ERR -> listOf(R.color.errc, R.color.err, R.color.onerr, if (night) R.color.on else R.color.err)
        }
        heroBox.removeAllViews()
        heroBox.background = c.shape(c.clr(bgRes), 28)
        heroBox.pad(c, 20, 20, 20, 20)
        val iconRes = when (icon) {
            HeroIcon.POWER -> R.drawable.ic_power
            HeroIcon.PLUG -> R.drawable.ic_plug
            HeroIcon.CAR -> R.drawable.ic_car
            HeroIcon.CAR_USB -> R.drawable.ic_car_usb
            HeroIcon.CAR_WIFI -> R.drawable.ic_car_wifi
            HeroIcon.REFRESH -> R.drawable.ic_refresh
            HeroIcon.ALERT -> R.drawable.ic_alert
            HeroIcon.KEY -> R.drawable.ic_key
        }
        // left icon in the circle, right header in one line; subtitle is lower on the whole width
        heroBox.addView(c.hbox(12).apply {
            addView(FrameLayout(c).apply {
                background = c.oval(c.clr(fgRes))
                addView(c.icon(iconRes, 24, c.clr(onFgRes)), FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.CENTER))
            }, lp(c.dp(44), c.dp(44)))
            addView(c.text(title, 20f, 600, c.clr(textRes)).apply {
                letterSpacing = -0.015f
                lineHeight = c.dp(26)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP))
        heroBox.addView(c.vbox(6).apply {
            addView(c.text(sub, 16f, 400).apply { lineHeight = c.dp(22) })
            note?.let { addView(c.text(it, 14f, 400, c.clr(R.color.on2))) }
        }, lp(MATCH, WRAP))
    }

    private fun renderStages(stages: List<Pair<UiText, Pair<StageState, UiText>>>) {
        val c = ctx
        stagesBox.removeAllViews()
        for ((label, sm) in stages) stagesBox.addView(stageRow(label.get(c), sm.first, sm.second.get(c)), c.lpDp(MATCH, 52))
    }

    /** How to run head unit: server three numbered items on the surf (Main.dc.html, isOff) plate. */
    private fun chain(): View {
        val c = ctx
        val box = c.vbox().apply {
            background = c.shape(c.clr(R.color.surf), 16)
            pad(c, 0, 4, 0, 4)
            contentDescription = c.getString(R.string.home_chain_label)
        }
        listOf(R.string.home_chain_1, R.string.home_chain_2, R.string.home_chain_3).forEachIndexed { i, res ->
            if (i > 0) box.addView(c.divider())
            box.addView(
                c.hbox(12).apply {
                    pad(c, 14, 12, 14, 12)
                    addView(
                        FrameLayout(c).apply {
                            background = c.oval(c.clr(R.color.pri))
                            addView(c.text("${i + 1}", 13f, 700, c.clr(R.color.onpri)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                        },
                        lp(c.dp(24), c.dp(24)),
                    )
                    addView(c.text(boldText(res), 15f, 400).apply { lineHeight = c.dp(20) }, lp(0, WRAP, 1f))
                },
                lp(MATCH, WRAP),
            )
        }
        return box
    }

    /** Resource line with <b>: bold draw application font (Golos 700) and not synthetic BOLD. */
    private fun boldText(res: Int): CharSequence {
        val c = ctx
        val out = SpannableString(c.getText(res))
        for (sp in out.getSpans(0, out.length, StyleSpan::class.java)) {
            if (sp.style == Typeface.BOLD) {
                out.setSpan(TypefaceSpan(c.sans(700)), out.getSpanStart(sp), out.getSpanEnd(sp), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.removeSpan(sp)
            }
        }
        return out
    }

    private fun graceBlock(st: HomeState): View {
        val c = ctx
        val box = c.vbox(8)
        val bar = c.hbox().apply { background = c.shape(c.clr(R.color.surf), 4); clipToOutline = true }
        val fill = View(c).apply { background = c.shape(c.clr(R.color.warn), 4) }
        val rest = View(c)
        bar.addView(fill, lp(0, MATCH, 1f))
        bar.addView(rest, lp(0, MATCH, 0.001f))
        graceFill = fill
        graceRest = rest
        box.addView(bar, c.lpDp(MATCH, 8))
        val row = c.hbox()
        row.addView(c.text(c.getString(R.string.home_grace_waiting), 14f, 400, c.clr(R.color.on2)), lp(0, WRAP, 1f))
        val left = c.text(c.getString(R.string.home_grace_left, st.graceLeftS), 14f, 600)
        graceLeftText = left
        row.addView(left)
        box.addView(row, lp(MATCH, WRAP))
        return box
    }

    private fun stageRow(label: String, state: StageState, meta: String): View {
        val c = ctx
        val row = c.hbox(14).apply { pad(c, 20, 0, 20, 0) }
        row.addView(stageIcon(state), lp(c.dp(28), c.dp(28)))
        row.addView(c.text(label, 16f, 500, c.clr(if (state == StageState.PEND) R.color.on2 else R.color.on)), lp(0, WRAP, 1f))
        val metaColor = when (state) {
            StageState.ERR -> R.color.err
            StageState.PAUSE -> R.color.warn
            StageState.TODO, StageState.ACTIVE -> R.color.acc
            else -> R.color.on2
        }
        row.addView(c.text(meta, 14f, 500, c.clr(metaColor)))
        return row
    }

    private fun stageIcon(state: StageState): View {
        val c = ctx
        val box = FrameLayout(c)
        fun glyph(res: Int, tint: Int) = box.addView(c.icon(res, 16, tint), FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
        when (state) {
            StageState.DONE -> { box.background = c.oval(c.clr(R.color.ok)); glyph(R.drawable.ic_check, c.clr(R.color.onok)) }
            StageState.ACTIVE -> {
                val ring = ImageView(c).apply { setImageResource(AppTheme.res(R.drawable.ic_ring_active)) }
                box.addView(ring, FrameLayout.LayoutParams(c.dp(28), c.dp(28)))
                box.addView(View(c).apply { background = c.oval(c.clr(R.color.pri)) }, FrameLayout.LayoutParams(c.dp(8), c.dp(8), Gravity.CENTER))
                rings.add(ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
                    duration = 1400
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                })
            }
            StageState.PAUSE -> { box.background = c.oval(c.clr(R.color.warnc)); glyph(R.drawable.ic_pause, c.clr(R.color.warn)) }
            StageState.ERR -> { box.background = c.oval(c.clr(R.color.err)); glyph(R.drawable.ic_close, c.clr(R.color.onerr)) }
            StageState.TODO -> {
                box.background = c.oval(c.clr(R.color.pri))
                box.addView(c.text("!", 16f, 700, c.clr(R.color.onpri)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            StageState.PEND -> box.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(c.dp(2), c.clr(R.color.line))
            }
        }
        return box
    }
}
