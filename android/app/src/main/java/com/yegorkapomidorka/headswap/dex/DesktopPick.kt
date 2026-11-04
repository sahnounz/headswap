package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.desktop.DesktopAppCatalog
import com.yegorkapomidorka.headswap.launcher.AppRef

/** Third-party launcher instead of our full-screen head unit: what to offer in the phone settings and how to disassemble the saved selection. */
internal object DesktopPick {
    /**
     * launcher for selection: exported input under LAUNCHER (catalog, our package there is already filtered), which itself is under
     * HOME ([homeKeys] - "package/class"). HOME-stub with separate LAUNCHER-input (FallbackHome "Settings") - not launcher.
     */
    fun options(catalog: List<DesktopAppCatalog.Entry>, homeKeys: Set<String>): List<DesktopAppCatalog.Entry> =
        catalog.filter { it.key in homeKeys }.distinctBy { it.packageName }

    /** A "package/class" line with both parts. */
    fun isComponent(value: String?): Boolean = value != null && value.indexOf('/').let { it > 0 && it < value.length - 1 }

    /** Selected package/class component, while its package is among the options (activity could change); otherwise null - Headswap. */
    fun selected(component: String?, options: List<DesktopAppCatalog.Entry>): DesktopAppCatalog.Entry? {
        val pkg = AppRef.parse(component ?: return null)?.pkg ?: return null
        return options.firstOrNull { it.packageName == pkg }
    }

    /** Installed by launcher, except our package (in smoke-assembly it is with suffix); requests to PackageManager, not with main thread. */
    fun installed(context: Context): List<DesktopAppCatalog.Entry> {
        val pm = context.packageManager
        val found = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).mapNotNull { info ->
                val a = info.activityInfo ?: return@mapNotNull null
                DesktopAppCatalog.Entry(a.packageName, a.name,
                    runCatching { info.loadLabel(pm).toString() }.getOrDefault(a.packageName),
                    enabled = a.enabled && a.applicationInfo.enabled, exported = a.exported)
            }
        }.getOrElse { AppLog.i("DESKTOP лаунчеры: LAUNCHER ${it.javaClass.simpleName}", false); emptyList() }
        val homes = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapNotNullTo(HashSet()) { info -> info.activityInfo?.let { "${it.packageName}/${it.name}" } }
        }.getOrElse { AppLog.i("DESKTOP лаунчеры: HOME ${it.javaClass.simpleName}", false); emptySet() }
        return options(DesktopAppCatalog.select(found, context.packageName), homes)
    }
}
