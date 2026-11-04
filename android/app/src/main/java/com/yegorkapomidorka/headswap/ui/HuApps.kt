package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R

/**
 * The foreign applications that the Android Auto shows on the "dev-head unit" (Headswap is represented as the Desktop Head Unit,
 * in AA included "Unknown sources"). Installed find themselves (HuApps.detect), catalog - only recommendations
 * with reference to releases. We do not download or put anything ourselves.
 * The directory package must also be in the `<queries>` manifest, otherwise it is not visible on the API 30+ (the rest find `<intent>` there).
 */
class HuApp(
    val name: String,
    val pkg: String,
    val url: String,
    val descRes: Int,
    /** String-requirement ("Need Android 15+") or null. */
    val reqRes: Int? = null,
    /** Minimum SDK_INT phone; 0 - unlimited. */
    val minSdk: Int = 0,
)

/** What to show about the application: whether it is worth it and will go on this phone. */
data class HuAppStatus(val installed: Boolean, val unsupported: Boolean)

/** Candidate of queries to the PackageManager: package, whether it is system and who supplied it (null - adb, browser, batch installer). */
data class AppCandidate(val pkg: String, val isSystem: Boolean, val installer: String?)

/** The result of the selection: packages for "Set on the phone" (detection order) and catalog entries for "Recommended". */
class AppsSelection(val installed: List<String>, val recommended: List<HuApp>)

object HuApps {
    /** Applications from Play Android Auto show and so, they are not listed. */
    const val PLAY_INSTALLER = "com.android.vending"

    private const val CATEGORY_PROJECTION = "com.google.android.gms.car.category.CATEGORY_PROJECTION"
    private const val CATEGORY_CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"

    val CATALOG: List<HuApp> = listOf(
        HuApp(
            "Fermata Auto", "me.aap.fermata.auto.dear.google.why",
            "https://github.com/AndreyPavlenko/Fermata/releases", R.string.apps_fermata_desc,
        ),
        HuApp(
            "ParkedTube", "dev.codex.aaparkedyoutube",
            "https://github.com/gadmin2151/ParkedTube/releases", R.string.apps_parkedtube_desc,
            R.string.apps_aabrowser_req, minSdk = 35,
        ),
        HuApp(
            "AABrowser", "com.kododake.aabrowser",
            "https://github.com/kododake/AABrowser/releases", R.string.apps_aabrowser_desc,
            R.string.apps_aabrowser_req, minSdk = 35,
        ),
    )

    /** Pure status logic: Installation check and SDK_INT come from the outside (in tests - fakes). */
    fun status(app: HuApp, isInstalled: (String) -> Boolean, sdkInt: Int): HuAppStatus =
        HuAppStatus(isInstalled(app.pkg), sdkInt < app.minSdk)

    /**
     * Pure selection logic, without Android. In "Installed" gets a candidate who is not ourselves, not system and not from Play;
     * plus everything from the catalog that is on the phone (even if requests are not found). In "Recommend" - catalog, which is not.
     */
    fun select(
        candidates: List<AppCandidate>,
        selfPkg: String,
        catalog: List<HuApp>,
        isInstalled: (String) -> Boolean,
    ): AppsSelection {
        val installed = LinkedHashSet<String>()
        for (c in candidates) {
            if (c.pkg == selfPkg || c.isSystem || c.installer == PLAY_INSTALLER) continue
            installed.add(c.pkg)
        }
        for (app in catalog) if (isInstalled(app.pkg)) installed.add(app.pkg)
        return AppsSelection(installed.toList(), catalog.filter { it.pkg !in installed })
    }

    /** We are looking for applications for AA on the phone: player services (CATEGORY_PROJECTION) and full-screen activity (CAR_LAUNCHER). */
    fun detect(ctx: Context): AppsSelection =
        select(scan(ctx.packageManager), ctx.packageName, CATALOG) { isInstalled(ctx, it) }

    private fun scan(pm: PackageManager): List<AppCandidate> {
        val projection = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_PROJECTION)
        val carLauncher = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_CAR_LAUNCHER)
        val infos = ArrayList<ApplicationInfo>()
        try {
            val services = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(projection, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentServices(projection, 0)
            }
            services.mapNotNullTo(infos) { it.serviceInfo?.applicationInfo }
            val activities = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(carLauncher, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentActivities(carLauncher, 0)
            }
            activities.mapNotNullTo(infos) { it.activityInfo?.applicationInfo }
        } catch (e: Exception) {
            AppLog.e("Поиск приложений для AA не удался: ${e.message}")
        }
        return infos.distinctBy { it.packageName }.map {
            val system = it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            AppCandidate(it.packageName, system, installerOf(pm, it.packageName))
        }
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
    } catch (_: Exception) {
        null
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun status(ctx: Context, app: HuApp): HuAppStatus = status(app, { isInstalled(ctx, it) }, Build.VERSION.SDK_INT)

    /** Intent startup or null: application may not have its own icon (then the button "Open" is not shown). */
    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun download(ctx: Context, app: HuApp) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("${app.name}: браузер не открылся: ${e.message}")
        }
    }

    fun open(ctx: Context, pkg: String) {
        try {
            launchIntent(ctx, pkg)?.let { ctx.startActivity(it) }
        } catch (e: Exception) {
            AppLog.e("$pkg: не запустился: ${e.message}")
        }
    }
}
