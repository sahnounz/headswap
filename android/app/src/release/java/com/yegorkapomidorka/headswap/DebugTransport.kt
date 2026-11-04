package com.yegorkapomidorka.headswap

import android.content.Intent
import android.os.ParcelFileDescriptor

/** B release TCP-no transport: the bridge works only with USB-accessory. */
object DebugTransport {
    fun port(intent: Intent?): Int? = null

    fun tcp6(intent: Intent?): Boolean = false

    fun wifiB(intent: Intent?): Boolean = false

    fun tcp6Bind(intent: Intent?): String = "127.0.0.1"

    fun connect(port: Int): ParcelFileDescriptor? = null
}
