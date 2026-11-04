package com.yegorkapomidorka.headswap

import android.app.Application
import com.yegorkapomidorka.headswap.dex.ActivationToken

/** Start process: the activation token from the PC must exist before the first command ManualAccessLauncher, there is no screen for it anymore. */
class HeadswapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // current() creates token via synchronous commit(): not on main thread
        Thread({
            try {
                ActivationToken(this).current()
            } catch (e: Exception) {
                AppLog.e("токен активации не создан", e)
            }
        }, "activation-token").start()
    }
}
