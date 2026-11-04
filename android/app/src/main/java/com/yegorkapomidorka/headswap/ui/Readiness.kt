package com.yegorkapomidorka.headswap.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.dex.DelegatedDesktopAccess

/** All you need to include before the trip: order, texts, auto-definition "already done". Common to onboarding and checklist. */
enum class StepId(val key: String) {
    /** The first step: Android Auto or your launcher. Next path depends on the choice (OnboardingPath). */
    MODE("mode"),

    /** Only for its launcher: developer mode Android (need "Debug Wi-Fi"). */
    DEVMODE("devmode"),

    /** Only for its launcher: activation of Headswap via "Debugging by Wi-Fi" (PhoneActivationBlock). */
    ACTIVATE("activate"),
    AA_DEV("aa_dev"),
    AA_UNKNOWN("aa_unknown"),
    AUTOSTART("autostart"),
    NOTIF("notif"),
    BACKGROUND("background"),
    MIC("mic"),
    BLUETOOTH("bluetooth"),
    LOCATION("location"),
    FIRST("first"),

    /** Machine selection: Community-proven settings (CarPreset). Last step; experienced skip. */
    CAR("car"),
}

/**
 * Step Texts - id resources (hint/extra may be missing). Step body - either numbered list (string-array [list]),
 * or paragraph ([body], the step of choosing a machine). In lines turns UI: ctx.getString, list - through [listItems].
 */
class StepInfo(
    val id: StepId,
    val title: Int,
    val list: Int?,
    val body: Int?,
    val hint: Int?,
    val primary: Int,
    val extra: Int?,
    val skip: Int,
    /** A short name for the checklist. */
    val short: Int,
) {
    /** List items; in the background step, the items about "Autostart" (3-4) are only on Xiaomi. */
    fun listItems(ctx: Context, xiaomi: Boolean = Readiness.isXiaomi()): List<String> {
        val all = list?.let { ctx.resources.getStringArray(it).toList() } ?: return emptyList()
        return if (id == StepId.BACKGROUND && !xiaomi) all.take(2) else all
    }
}

object Readiness {
    val STEPS: List<StepInfo> = listOf(
        StepInfo(
            StepId.AA_DEV, R.string.step_aa_dev_title, R.array.step_aa_dev_list, null, null,
            R.string.step_aa_dev_primary, null, R.string.onb_already_done, R.string.step_aa_dev_short,
        ),
        StepInfo(
            StepId.AA_UNKNOWN, R.string.step_aa_unknown_title, R.array.step_aa_unknown_list, null, R.string.step_aa_unknown_hint,
            R.string.step_aa_unknown_primary, null, R.string.onb_already_done, R.string.step_aa_unknown_short,
        ),
        StepInfo(
            StepId.AUTOSTART, R.string.step_autostart_title, R.array.step_autostart_list, null, R.string.step_autostart_hint,
            R.string.step_autostart_primary, null, R.string.onb_got_it, R.string.step_autostart_short,
        ),
        StepInfo(
            StepId.NOTIF, R.string.step_notif_title, R.array.step_notif_list, null, R.string.step_notif_hint,
            R.string.onb_allow, null, R.string.onb_later, R.string.step_notif_short,
        ),
        StepInfo(
            StepId.BACKGROUND, R.string.step_background_title, R.array.step_background_list, null, null,
            R.string.step_background_primary, R.string.step_background_extra, R.string.onb_already_done, R.string.step_background_short,
        ),
        StepInfo(
            StepId.MIC, R.string.step_mic_title, R.array.step_mic_list, null, R.string.step_mic_hint,
            R.string.onb_allow, null, R.string.onb_later, R.string.step_mic_short,
        ),
        StepInfo(
            StepId.BLUETOOTH, R.string.step_bluetooth_title, R.array.step_bluetooth_list, null, R.string.step_bluetooth_hint,
            R.string.step_bluetooth_primary, null, R.string.onb_already_done, R.string.step_bluetooth_short,
        ),
        StepInfo(
            StepId.LOCATION, R.string.step_location_title, R.array.step_location_list, null, R.string.step_location_hint,
            R.string.step_location_primary, null, R.string.onb_already_done, R.string.step_location_short,
        ),
        StepInfo(
            StepId.FIRST, R.string.step_first_title, R.array.step_first_list, null, R.string.step_first_hint,
            R.string.step_first_primary, null, R.string.onb_back, R.string.step_first_short,
        ),
        StepInfo(
            StepId.CAR, R.string.step_car_title, null, R.string.step_car_body, null,
            R.string.car_apply, null, R.string.onb_skip, R.string.step_car_short,
        ),
    )

    /** The launcher: mode and path selection steps in [STEPS] are not available to keep the Android Auto checklist and path unchanged. */
    val EXTRA_STEPS: List<StepInfo> = listOf(
        StepInfo(
            StepId.MODE, R.string.step_mode_title, null, R.string.step_mode_subtitle, null,
            R.string.step_mode_primary, null, R.string.onb_back, R.string.step_mode_short,
        ),
        StepInfo(
            StepId.DEVMODE, R.string.step_devmode_title, R.array.step_devmode_list, null, R.string.step_devmode_hint,
            R.string.step_devmode_primary, null, R.string.onb_already_done, R.string.step_devmode_short,
        ),
        // header and buttons draw PhoneActivationBlock; the block list and prompt takes from here
        StepInfo(
            StepId.ACTIVATE, R.string.pa_title, R.array.step_activate_list, null, R.string.step_activate_hint,
            R.string.pa_button, null, R.string.onb_later, R.string.step_activate_short,
        ),
    )

    fun info(id: StepId): StepInfo = STEPS.firstOrNull { it.id == id } ?: EXTRA_STEPS.first { it.id == id }

    fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    /** true/false - defined automatically; null - cannot be defined (only manual mark "Already done"). */
    fun auto(ctx: Context, id: StepId): Boolean? = when (id) {
        StepId.NOTIF -> ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        StepId.MIC -> ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        StepId.BACKGROUND -> batteryOk(ctx)
        StepId.CAR -> if (AaPrefs(ctx).carPreset != null) true else null      // Pass - manual mark
        StepId.DEVMODE -> devSettingsEnabled(ctx)
        StepId.ACTIVATE -> DelegatedDesktopAccess.isReady(ctx)
        StepId.LOCATION -> try {
            ctx.getSystemService(LocationManager::class.java).isLocationEnabled
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    /** Developer mode is enabled (seven clicks on "Assembly number"). */
    fun devSettingsEnabled(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun batteryOk(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Done: Auto-detection, and where it is not - manual mark. Background on Xiaomi also requires manual mark. */
    fun isDone(ctx: Context, id: StepId): Boolean {
        val ui = UiPrefs(ctx)
        if (id == StepId.BACKGROUND) return batteryOk(ctx) && (!isXiaomi() || ui.isManualDone(id.key))
        return auto(ctx, id) ?: ui.isManualDone(id.key)
    }

    // --- button actions ---

    fun appDetails(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun requestIgnoreBattery(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.e("не открыть запрос исключения из оптимизации батареи", e)
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                appDetails(ctx)
            }
        }
    }

    /** Autostart Xiaomi (Security); failed - application card. */
    fun openXiaomiAutostart(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            AppLog.i("автозапуск Xiaomi не открылся (${e.javaClass.simpleName}), открываю карточку приложения")
            try {
                appDetails(ctx)
            } catch (e2: Exception) {
                AppLog.e("не открыть карточку приложения", e2)
            }
        }
    }

    fun openLocationSettings(ctx: Context) = openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun openBluetoothSettings(ctx: Context) = openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)

    /** "About the phone": there is an "Assembly number". */
    fun openDeviceInfo(ctx: Context) = openSettings(ctx, Settings.ACTION_DEVICE_INFO_SETTINGS)

    private fun openSettings(ctx: Context, action: String) {
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.e("не открыть $action", e)
        }
    }
}
