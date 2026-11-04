package com.yegorkapomidorka.headswap.diag

import android.content.Context
import com.yegorkapomidorka.headswap.AppLog

/**
 * "Helped / Did not help" marks under the advice of diagnosis. Stored on the phone (id reasons -> "helped|not_helped|ms time")
 * and written in a line in the application log: with the sent log, the developer sees what tips work.
 */
object DiagFeedback {
    private const val PREFS = "diag_feedback"

    fun record(ctx: Context, id: String, helped: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(id, "${if (helped) "helped" else "not_helped"}|${System.currentTimeMillis()}")
            .apply()
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        AppLog.i("DIAG отзыв: $id = ${if (helped) "помогло" else "не помогло"} (версия $ver)")
    }
}
