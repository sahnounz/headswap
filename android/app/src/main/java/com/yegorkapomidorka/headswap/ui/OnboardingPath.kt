package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.aa.BridgeMode

/** What are the steps of onboarding for the selected mode and in what order. Without Android:, it is checked by tests. */
object OnboardingPath {
    /** Android Auto: former ten steps. */
    val AA = listOf(
        StepId.AA_DEV, StepId.AA_UNKNOWN, StepId.AUTOSTART, StepId.NOTIF, StepId.BACKGROUND,
        StepId.MIC, StepId.BLUETOOTH, StepId.LOCATION, StepId.FIRST, StepId.CAR,
    )

    /**
     * Its launcher: developer mode and activation, then general steps without server head unit Android Auto. Notifications to
     * activation: the pairing code is entered in the notification, otherwise the permission request pops up in the middle of the activation step.
     */
    val LAUNCHER = listOf(
        StepId.DEVMODE, StepId.NOTIF, StepId.ACTIVATE, StepId.BACKGROUND,
        StepId.MIC, StepId.BLUETOOTH, StepId.LOCATION, StepId.FIRST, StepId.CAR,
    )

    /** Steps that are only in the way of launcher. */
    private val LAUNCHER_ONLY = setOf(StepId.DEVMODE, StepId.ACTIVATE)

    /** Path as a whole; select mode first if the "launcher" mode is generally available (Android 13+). */
    fun full(mode: BridgeMode, desktopSupported: Boolean): List<StepId> =
        (if (desktopSupported) listOf(StepId.MODE) else emptyList()) + (if (mode == BridgeMode.DESKTOP) LAUNCHER else AA)

    /** Debugging (-es step devmode|activate): step launcher opened and saved. Android AutoWe do not touch the settings themselves. */
    fun modeFor(stored: BridgeMode, startAt: StepId?): BridgeMode =
        if (startAt in LAUNCHER_ONLY) BridgeMode.DESKTOP else stored

    /**
     * Display queue: when the first pass, the completed steps are skipped (developer mode is already included, activation is already available,
     * mode selection is already done), when re-shown all. [keep] remains in line, even if executed.
     */
    fun queue(mode: BridgeMode, desktopSupported: Boolean, showAll: Boolean, isDone: (StepId) -> Boolean, keep: StepId? = null): List<StepId> =
        full(mode, desktopSupported).filter { showAll || it == keep || !isDone(it) }

    /** "n step from N" for step [id] in list [ids]; not listed - first. */
    fun progress(ids: List<StepId>, id: StepId): Pair<Int, Int> = (ids.indexOf(id) + 1).coerceAtLeast(1) to ids.size
}
