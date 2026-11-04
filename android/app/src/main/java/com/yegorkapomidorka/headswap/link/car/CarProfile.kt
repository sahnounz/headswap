package com.yegorkapomidorka.headswap.link.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yegorkapomidorka.headswap.BuildConfig

/**
 * Machine selected by user: Bluetooth-head unit device and binding CompanionDeviceManager. Lives in separate
 * The prefs. Trigger file (ACL, [CarAclReceiver]) is only enabled while the machine is selected and assembly with a wireless beta.
 */
class CarProfile(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("car_profile", Context.MODE_PRIVATE)

    /** MAC as came from the system (register different, compare via [CarMac.same]). */
    val btMac: String? get() = sp.getString("btMac", null)

    val btName: String? get() = sp.getString("btName", null)

    /** id binding CDM (API 33+); -1 - not (on API 29-32 id is not issued, binding on MAC). */
    val cdmAssociationId: Int get() = sp.getInt("cdmAssociationId", -1)

    val isSet: Boolean get() = btMac != null

    /**
     * How this machine connects to wireless connection. Tied to [btMac]: other machine or reset - again UNKNOWN.
     * Without the selected machine is not written.
     */
    var connMode: CarConnMode
        get() = if (!isSet) CarConnMode.UNKNOWN else CarConnMode.entries.firstOrNull { it.name == sp.getString("connMode", null) } ?: CarConnMode.UNKNOWN
        set(v) {
            if (isSet) sp.edit().putString("connMode", v.name).apply()
        }

    fun save(mac: String, name: String?, associationId: Int) {
        val ed = sp.edit()
        if (!CarMac.same(btMac, mac)) ed.remove("connMode")
        ed.putString("btMac", mac).putString("btName", name).putInt("cdmAssociationId", associationId).apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }

    companion object {
        /** The ACL. receiver is turned on/off only if the machine is selected and the assembly with a wireless beta. */
        fun enableTrigger(ctx: Context, on: Boolean) {
            val enable = on && BuildConfig.WIRELESS_BETA && CarProfile(ctx).isSet
            ctx.packageManager.setComponentEnabledSetting(
                component(ctx),
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        /** Status of receiver for report. DEFAULT = as in manifest (enabled=false), i.e. disabled. */
        fun triggerEnabled(ctx: Context): Boolean =
            ctx.packageManager.getComponentEnabledSetting(component(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        private fun component(ctx: Context) = ComponentName(ctx, CarAclReceiver::class.java)
    }
}
