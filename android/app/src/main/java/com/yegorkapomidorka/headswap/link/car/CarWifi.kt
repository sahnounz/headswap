package com.yegorkapomidorka.headswap.link.car

import android.content.Context

/**
 * Wi-Fi machine access point (head unit distributes it itself): on it the phone enters the machine network through WifiNetworkSpecifier, without losing
 * Internet main network. Separate file prefs `car_wifi`, excluded from backup (res/xml/backup_rules.xml, data_extraction_rules.xml).
 * SSID and the password do not write anywhere except this file and the settings screen: neither in WLog, nor in AppLog, nor in the report.
 */
class CarWifi(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("car_wifi", Context.MODE_PRIVATE)

    val ssid: String? get() = sp.getString("ssid", null)

    /** The WPA2/WPA3 password; an empty string (or null) is an open network. */
    val pass: String get() = sp.getString("pass", null).orEmpty()

    val isSet: Boolean get() = !ssid.isNullOrEmpty()

    /** 6/6 has already been raised in this network: head unit connects through its Wi-Fi, the machine network is requested without waiting. Reset by changing SSID. */
    val proven: Boolean get() = sp.getBoolean("proven", false)

    fun markProven() {
        sp.edit().putBoolean("proven", true).apply()
    }

    /**
     * The protection with which the machine's network entry has already failed ("open"/"wpa2"/"wpa3"), or null. Android considers WPA2 and WPA3 to be one
     * SSID by different networks and gives them different random MAC: head unit sees a new device (Paul 2026-10-19: 4 takes in Known devices).
     */
    val sec: String? get() = sp.getString("sec", null)

    fun rememberSec(label: String) {
        if (label != sec) sp.edit().putString("sec", label).apply()
    }

    fun save(ssid: String, pass: String) {
        val ed = sp.edit().putString("ssid", ssid).putString("pass", pass)
        if (ssid != this.ssid) ed.remove("proven")
        if (ssid != this.ssid || pass != this.pass) ed.remove("sec")
        ed.apply()
    }

    fun clear() {
        sp.edit().clear().apply()
    }
}
