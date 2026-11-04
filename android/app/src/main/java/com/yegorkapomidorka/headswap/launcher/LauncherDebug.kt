package com.yegorkapomidorka.headswap.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.dex.DesktopPick
import com.yegorkapomidorka.headswap.dex.DexPrefs

/** Stand without machine: screen, settings section, theme and side of the launcher card from adb (MainActivity --es cmd launcher). */
internal object LauncherDebug {
    fun apply(ctx: Context, intent: Intent) {
        LauncherModel.init(ctx)
        val model = LauncherModel
        Handler(Looper.getMainLooper()).post {
            intent.getStringExtra("theme")?.let { t -> ThemeChoice.entries.firstOrNull { it.name.equals(t, true) }?.let(model::chooseTheme) }
            intent.getStringExtra("map")?.let { model.chooseMapLeft(it == "left") }
            intent.getStringExtra("page")?.let { p -> SettingsPage.entries.firstOrNull { it.name.equals(p, true) }?.let { model.settingsPage = it } }
            // launcher on head unit "package / class" (DexPrefs); "own" - our. Applied in the same way as from the settings:
            // showDesktop -> LauncherActivity.onNewIntent rereads the selection
            intent.getStringExtra("desktop")?.let { d ->
                if (d == "own" || DesktopPick.isComponent(d)) {
                    DexPrefs(ctx).desktopComponent = d.takeUnless { it == "own" }
                    BridgeService.requestDesktopHome(ctx)
                }
            }
            when (intent.getStringExtra("area")) {
                "home" -> LauncherActions.home()
                "settings" -> model.go(Area.Settings)
            }
            AppLog.i("LAUNCHER debug: экран=${model.area} раздел=${model.settingsPage} тема=${model.theme} карта слева=${model.mapLeft}", false)
        }
    }
}