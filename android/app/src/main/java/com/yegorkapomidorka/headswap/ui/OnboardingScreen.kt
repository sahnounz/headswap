package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.BridgeMode
import com.yegorkapomidorka.headswap.aa.CarPreset
import com.yegorkapomidorka.headswap.dex.PhoneActivation

/**
 * onboarding (Onboarding.dc.html) and readiness checklist. The first step is the choice of mode, then the path depends on it
 * (OnboardingPath): Android Auto - former ten steps from Readiness.STEPS, its launcher - developer mode, activation and common steps.
 * At the first start, already completed steps (defined automatically or manually marked) are skipped, when repeated
 * All passes are shown. At the end is a checklist with the status of each item; the same checklist opens from Settings.
 */
class OnboardingScreen(host: MainActivity, private val showAll: Boolean, startAtChecklist: Boolean, startAt: StepId? = null) : Screen(host) {
    private val root = ctx.vbox()
    override val view: View get() = root
    private val ui = UiPrefs(ctx)
    private val aa = AaPrefs(ctx)
    private val desktopOk = BridgeMode.desktopSupported(Build.VERSION.SDK_INT)

    /** Path mode: saved in settings, when debugging by launcher - launcher. */
    private var chosen = OnboardingPath.modeFor(BridgeMode.effective(aa.bridgeMode, Build.VERSION.SDK_INT), startAt)
    private var queue: List<StepInfo>
    private var pos = 0

    /** The activation block lives between step redraws: the attempt and the ports entered are not lost. */
    private var activation: PhoneActivationBlock? = null
    private var renderedReady = false
    private var renderedBusy = false
    private var renderedLabel = ""
    private var checklist = false

    /** The step is open from the checklist: after it we return to the checklist. */
    private var single: StepInfo? = null
    private var shownDone = false

    /** A machine marked in the selection step, but not yet applied. */
    private var carChoice: CarPreset? = null

    init {
        queue = buildQueue(startAt)
        checklist = startAtChecklist || queue.isEmpty()
        if (startAt != null) pos = queue.indexOfFirst { it.id == startAt }.coerceAtLeast(0)
        render()
    }

    private fun buildQueue(keep: StepId?): List<StepInfo> =
        OnboardingPath.queue(chosen, desktopOk, showAll, { Readiness.isDone(ctx, it) }, keep).map { Readiness.info(it) }

    /** Checklist lines: steps of the selected path without selecting a mode. */
    private fun checklistSteps(): List<StepInfo> = OnboardingPath.full(chosen, false).map { Readiness.info(it) }

    override fun tick() {
        val block = activation ?: return
        if (checklist || current()?.id != StepId.ACTIVATE) return
        block.refresh()
        if (block.ready && !renderedReady) { next(); return }      // Activation is over while the step is open: go ahead ourselves
        if (block.ready != renderedReady || PhoneActivation.busy != renderedBusy || block.activateLabel != renderedLabel) render()
    }

    override fun onShow() {
        // back from system settings: status could change
        if (checklist) render() else if (current() != null && Readiness.isDone(ctx, current()!!.id) != shownDone) render()
    }

    override fun onPermissionResult() {
        val s = current() ?: return
        if (!checklist && s.id == StepId.ACTIVATE) { activation?.onPermissionResult(); return }      // render() would have lost its resolution expectation
        if (!checklist && Readiness.isDone(ctx, s.id) && (s.id == StepId.NOTIF || s.id == StepId.MIC)) next() else render()
    }

    override fun onBack(): Boolean {
        when {
            checklist -> { ui.onboardingDone = true; return false }
            single != null -> { single = null; checklist = true; render() }
            pos > 0 -> { pos--; render() }
            else -> { ui.onboardingDone = true; return false }      // came out of onboarding themselves: no more imposing
        }
        return true
    }

    private fun current(): StepInfo? = if (checklist) null else (single ?: queue.getOrNull(pos))

    private fun next() {
        if (single != null) {
            single = null
            checklist = true
        } else if (pos + 1 < queue.size) {
            pos++
        } else {
            checklist = true
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        val s = if (checklist) null else current()
        when {
            checklist -> renderChecklist()
            s == null -> return
            s.id == StepId.CAR -> renderCar(s)
            s.id == StepId.MODE -> renderMode(s)
            s.id == StepId.ACTIVATE -> renderActivate(s)
            else -> renderStep(s)
        }
    }

    /** n Step from N with points. */
    private fun renderProgress(s: StepInfo) {
        val c = ctx
        // from the checklist number is considered to be on the full path, otherwise in turn (the steps performed in it have already been missed)
        val ids = if (single != null) OnboardingPath.full(chosen, desktopOk) else queue.map { it.id }
        val (n, total) = OnboardingPath.progress(ids, s.id)
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text(c.getString(R.string.onb_step_progress, n, total), 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
            val dots = c.hbox(6)
            for (i in 1..total) {
                dots.addView(View(c).apply { background = c.shape(c.clr(if (i <= n) R.color.pri else R.color.line), 3) }, lp(c.dp(if (total > 8) 14 else if (total > 6) 18 else 28), c.dp(6)))
            }
            addView(dots)
        }
        root.addView(top, c.lpDp(MATCH, 48))
    }

    private fun renderStep(s: StepInfo) {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        shownDone = done
        renderProgress(s)
        renderTitle(s)

        // scrolls only picture and text, header remains in place
        val body = c.vbox().apply {
            // with a hint and on the steps with a window Android Auto layout is more compact: the text is completely visible without scrolling
            val compact = s.hint != null || s.id == StepId.AA_DEV || s.id == StepId.AA_UNKNOWN
            addView(c.stepMockCard(Mocks.build(c, s.id), compact), lp(MATCH, WRAP).margins(c, 0, 4, 0, 0))
            val text = c.vbox(10).apply { pad(c, 0, 18, 0, 36) }     // bottom indent more edge dimming: the end of the text does not sink into fade
            if (done) text.addView(c.doneChip(c.getString(R.string.onb_done_chip)), lp(WRAP, WRAP))
            text.addView(c.numberedList(s.listItems(c)), lp(MATCH, WRAP))
            // In the launcher path, notifications are required primarily for the activation pairing code.
            val hint = if (s.id == StepId.NOTIF && chosen == BridgeMode.DESKTOP) R.string.step_notif_hint_launcher else s.hint
            hint?.let { text.addView(c.stepHint(c.getString(it)), lp(MATCH, WRAP)) }
            addView(text, lp(MATCH, WRAP))
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        val buttons = c.vbox(4)
        val primaryLabel = if (done && s.id != StepId.FIRST) c.getString(R.string.onb_next) else c.getString(s.primary)
        buttons.addView(c.primaryButton(primaryLabel).apply { setOnClickListener { onPrimary(s, done) } })
        val showExtra = s.extra != null && !done && (s.id != StepId.BACKGROUND || Readiness.isXiaomi())
        if (showExtra) {
            buttons.addView(c.containerButton(c.getString(s.extra!!)).apply { setOnClickListener { onExtra(s) } })
        }
        val skipLabel = if (done && s.id != StepId.FIRST) c.getString(R.string.onb_back) else c.getString(s.skip)
        buttons.addView(c.linkButton(skipLabel, 48).apply { setOnClickListener { onSkip(s, done) } })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /** First step: Android Auto or your launcher. The choice is written in the same bridge mode as the switch in "Mode". */
    private fun renderMode(s: StepInfo) {
        val c = ctx
        shownDone = Readiness.isDone(c, s.id)
        renderProgress(s)
        renderTitle(s)
        val launcher = chosen == BridgeMode.DESKTOP
        val body = c.vbox(12).apply { pad(c, 0, 4, 0, 24) }
        body.addView(c.text(c.getString(s.body!!), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        body.addView(
            c.modeCard(R.drawable.ic_car, c.getString(R.string.bm_aa), c.getString(R.string.step_mode_aa_body), emptyList(), !launcher) { pickMode(BridgeMode.ANDROID_AUTO) },
            lp(MATCH, WRAP).margins(c, 0, 4, 0, 0),
        )
        body.addView(
            c.modeCard(R.drawable.ic_plug, c.getString(R.string.bm_own_launcher), c.getString(R.string.step_mode_own_body), listOf("Headswap", "CarDash"), launcher) { pickMode(BridgeMode.DESKTOP) },
            lp(MATCH, WRAP),
        )
        if (launcher) {
            body.addView(
                c.modeWarning(c.getString(R.string.step_mode_warn_title), c.getString(R.string.step_mode_warn_body), c.getString(R.string.step_mode_warn_risk)),
                lp(MATCH, WRAP),
            )
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))
        root.addView(c.primaryButton(c.getString(s.primary)).apply {
            setOnClickListener {
                ui.setManualDone(s.id.key, true)
                AppLog.i("онбординг, режим: ${chosen.key}")
                next()
            }
        })
    }

    private fun pickMode(m: BridgeMode) {
        if (aa.bridgeMode != m) {
            aa.bridgeMode = m
            AppLog.i("настройка: режим моста -> ${m.key} (онбординг)")
        }
        if (chosen != m) {
            chosen = m
            queue = buildQueue(StepId.MODE)      // queue tail depends on the mode
            pos = queue.indexOfFirst { it.id == StepId.MODE }.coerceAtLeast(0)
        }
        render()
    }

    /**
     * Activation step launcher: status, layout, points and manual input fields leads PhoneActivationBlock (the same flow as in the "mode"),
     * header and buttons - like the rest of the steps. Below "Activate" (during the "Cancel" attempt) and "Enter port and code manually".
     * When the activation has passed while the step is open, onboarding goes on itself (tick); if the step is open already activated - "Next".
     */
    private fun renderActivate(s: StepInfo) {
        val c = ctx
        val block = activation ?: PhoneActivationBlock(host, c.resources.getStringArray(s.list!!).toList(), s.hint!!, embedded = true).also { activation = it }
        block.refresh()
        val ready = block.ready
        val busy = PhoneActivation.busy
        shownDone = Readiness.isDone(c, s.id)
        renderedReady = ready
        renderedBusy = busy
        renderedLabel = block.activateLabel
        renderProgress(s)
        (block.titleView.parent as? ViewGroup)?.removeView(block.titleView)
        root.addView(block.titleView, lp(MATCH, WRAP).margins(c, 0, 4, 0, 16))
        (block.view.parent as? ViewGroup)?.removeView(block.view)
        val body = c.vbox().apply {
            pad(c, 0, 4, 0, 36)
            addView(block.view, lp(MATCH, WRAP))
        }
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))
        val buttons = c.vbox(4)
        when {
            ready -> {
                buttons.addView(c.primaryButton(c.getString(R.string.onb_next)).apply { setOnClickListener { next() } })
                buttons.addView(c.linkButton(c.getString(R.string.onb_back), 48).apply { setOnClickListener { stepBack() } })
            }
            busy -> buttons.addView(c.primaryButton(c.getString(R.string.pa_cancel)).apply { setOnClickListener { block.cancelNow() } })
            else -> {
                buttons.addView(c.primaryButton(block.activateLabel).apply { setOnClickListener { block.activateNow() } })
                buttons.addView(c.containerButton(c.getString(R.string.pa_manual_toggle)).apply { setOnClickListener { block.toggleManual() } })
            }
        }
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /**
     * The last step: what kind of machine you have. Flashlight only shows the settings of the machine, "Apply" writes them (AaPrefs.applyCar),
     * “Skip” (for the experienced) does not touch anything and puts a manual step mark.
     */
    private fun renderCar(s: StepInfo) {
        val c = ctx
        shownDone = Readiness.isDone(c, s.id)
        renderProgress(s)
        renderTitle(s)
        val aa = AaPrefs(c)
        if (carChoice == null) carChoice = aa.carPreset

        val applyBtn = c.primaryButton(c.getString(s.primary))
        fun refresh() {
            applyBtn.isEnabled = carChoice != null
            applyBtn.alpha = if (carChoice != null) 1f else 0.45f
        }
        val chooser = CarChooser(c, withOther = false) { p ->
            carChoice = p
            refresh()
        }
        carChoice?.let { chooser.show(it) }

        val body = c.vbox(12).apply { pad(c, 0, 4, 0, 24) }
        body.addView(c.text(c.getString(s.body!!), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        body.addView(chooser.view, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(body), lp(MATCH, 0, 1f))

        applyBtn.setOnClickListener {
            val p = carChoice ?: return@setOnClickListener
            aa.applyCar(p)
            AppLog.i("онбординг, мой автомобиль: ${p.name}, применены ${p.settings}")
            next()
        }
        refresh()
        val buttons = c.vbox(4)
        buttons.addView(applyBtn)
        buttons.addView(c.linkButton(c.getString(s.skip), 48).apply {
            setOnClickListener {
                AppLog.i("онбординг, мой автомобиль: пропущен")
                ui.setManualDone(s.id.key, true)
                next()
            }
        })
        root.addView(buttons, lp(MATCH, WRAP))
    }

    /** Scrolling with edge dimming: you can see that the text continues. */
    private fun fadingScroll(content: View) = ScrollView(ctx).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(24))
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true     // the permanent stripe lies on the right edge of the illustration card; the tail of the text is already visible by darkening
        addView(content)
    }

    /** header steps under the line "Step n from N": out of scroll. */
    private fun renderTitle(s: StepInfo) {
        val c = ctx
        root.addView(
            c.text(c.getString(s.title), 24f, 600).apply { letterSpacing = -0.01f; lineHeight = c.dp(30) },
            lp(MATCH, WRAP).margins(c, 0, 4, 0, 16),
        )
    }

    private fun onPrimary(s: StepInfo, done: Boolean) {
        val c = ctx
        if (done && s.id != StepId.FIRST) { next(); return }
        when (s.id) {
            StepId.AA_DEV, StepId.AA_UNKNOWN -> Actions.openAaSettings(c)
            StepId.AUTOSTART -> Actions.startServerFromUi(c)
            StepId.NOTIF -> requestNotifications()
            StepId.BACKGROUND -> if (!Readiness.batteryOk(c)) Readiness.requestIgnoreBattery(c) else Readiness.openXiaomiAutostart(c)
            StepId.MIC -> requestMic()
            StepId.BLUETOOTH -> Readiness.openBluetoothSettings(c)
            StepId.LOCATION -> Readiness.openLocationSettings(c)
            StepId.FIRST -> { ui.setManualDone(s.id.key, true); next() }
            StepId.CAR -> next()      // their buttons in renderCar
            StepId.DEVMODE -> Readiness.openDeviceInfo(c)
            StepId.MODE, StepId.ACTIVATE -> {}      // your buttons in renderMode / renderActivate
        }
    }

    private fun onExtra(s: StepInfo) {
        try {
            when (s.id) {
                StepId.BACKGROUND -> Readiness.openXiaomiAutostart(ctx)
                else -> {}
            }
        } catch (e: Exception) {
            AppLog.e("шаг ${s.id}: не открыть системный экран", e)
        }
    }

    private fun onSkip(s: StepInfo, done: Boolean) {
        if (done && s.id != StepId.FIRST) { stepBack(); return }
        when (s.id) {
            StepId.FIRST -> stepBack()
            StepId.AA_DEV, StepId.AA_UNKNOWN, StepId.AUTOSTART, StepId.BACKGROUND, StepId.BLUETOOTH, StepId.LOCATION, StepId.DEVMODE -> { ui.setManualDone(s.id.key, true); next() }
            else -> next()
        }
    }

    private fun stepBack() {
        if (!onBack()) host.back()
    }

    private fun requestNotifications() {
        val c = ctx
        val p = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 && firstAsk(p)) {
            markAsked(p)
            host.requestPermissions(arrayOf(p), 1)
            return
        }
        // system dialogue already was (or SDK < 33): include in the notification settings of the application
        c.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun requestMic() {
        val c = ctx
        if (!firstAsk(Manifest.permission.RECORD_AUDIO) && !host.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            Readiness.appDetails(c)     // refused permanently: only in the application card can be allowed
            return
        }
        markAsked(Manifest.permission.RECORD_AUDIO)
        host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    private fun firstAsk(p: String) = !ui.isManualDone("asked_$p")

    private fun markAsked(p: String) = ui.setManualDone("asked_$p", true)

    // --- Checklist ---

    private fun renderChecklist() {
        val c = ctx
        root.pad(c, 20, 20, 20, 24)
        val top = c.hbox().apply {
            addView(c.text(c.getString(R.string.onb_checklist_title), 14f, 600, c.clr(R.color.on2)), lp(0, WRAP, 1f))
        }
        root.addView(top, c.lpDp(MATCH, 48))

        val list = c.vbox(12).apply { pad(c, 0, 8, 0, 8) }
        val steps = checklistSteps()
        val doneCount = steps.count { Readiness.isDone(c, it.id) }
        val heading = if (doneCount == steps.size) c.getString(R.string.onb_all_ready) else c.getString(R.string.onb_ready_count, doneCount, steps.size)
        list.addView(c.text(heading, 28f, 600).apply {
            letterSpacing = -0.01f
            lineHeight = c.dp(34)
        })
        list.addView(c.text(c.getString(R.string.onb_checklist_hint), 17f, 400, c.clr(R.color.on2)).apply { lineHeight = c.dp(24) })
        val card = c.card(24).apply { pad(c, 0, 6, 0, 6) }
        for (s in steps) card.addView(checkRow(s), c.lpDp(MATCH, 60))
        list.addView(card, lp(MATCH, WRAP).margins(c, 0, 8, 0, 0))
        root.addView(fadingScroll(list), lp(MATCH, 0, 1f))

        root.addView(c.primaryButton(c.getString(R.string.onb_done)).apply {
            setOnClickListener {
                ui.onboardingDone = true
                host.back()
            }
        })
    }

    private fun checkRow(s: StepInfo): View {
        val c = ctx
        val done = Readiness.isDone(c, s.id)
        val auto = Readiness.auto(c, s.id)
        val statusText: String
        val statusColor: Int
        when {
            done -> { statusText = c.getString(if (auto == null) R.string.onb_status_marked else R.string.onb_status_ready); statusColor = R.color.ok }
            auto == null -> { statusText = c.getString(R.string.onb_status_unmarked); statusColor = R.color.on2 }
            else -> { statusText = c.getString(R.string.onb_status_off); statusColor = R.color.warn }
        }
        return c.hbox(14).apply {
            pad(c, 20, 0, 16, 0)
            background = c.rippleBg(null, 0)
            isClickable = true
            setOnClickListener {
                single = s
                checklist = false
                render()
            }
            val mark = FrameLayout(c)
            when {
                done -> {
                    mark.background = c.oval(c.clr(R.color.ok))
                    mark.addView(c.icon(R.drawable.ic_check, 16, c.clr(R.color.onok)), FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
                }
                auto == null -> mark.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0)
                    setStroke(c.dp(2), c.clr(R.color.line))
                }
                else -> {
                    mark.background = c.oval(c.clr(R.color.warn))
                    mark.addView(c.text("!", 16f, 700, c.clr(R.color.warnc)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
                }
            }
            addView(mark, lp(c.dp(28), c.dp(28)))
            addView(c.vbox(1).apply {
                addView(c.text(c.getString(s.short), 16f, 500))
                addView(c.text(statusText, 13f, 500, c.clr(statusColor)))
            }, lp(0, WRAP, 1f))
            addView(c.icon(R.drawable.ic_chevron, 20, c.clr(R.color.on2)))
        }
    }
}
