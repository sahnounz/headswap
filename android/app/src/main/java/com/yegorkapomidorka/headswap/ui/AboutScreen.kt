package com.yegorkapomidorka.headswap.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.about.About
import com.yegorkapomidorka.headswap.about.OpenSource

/** "About the application": authors (name and nickname in Telegram, without reference) and third-party open licenses (tap opens full text). Data - [About]. */
class AboutScreen(host: MainActivity) : Screen(host) {
    override val view: View

    init {
        val c = ctx
        val body = c.vbox(6).apply { pad(c, 16, 4, 16, 24) }

        body.addView(c.sectionTitle(c.getString(R.string.about_authors)))
        val authors = c.card()
        for ((i, a) in About.authors.withIndex()) {
            if (i > 0) authors.addView(c.divider())
            authors.addView(row(c.getString(a.nameRes), a.handle, null, null))
        }
        body.addView(authors, lp(MATCH, WRAP))

        body.addView(c.sectionTitle(c.getString(R.string.about_opensource)))
        val licenses = c.card()
        for ((i, lic) in About.licenses.withIndex()) {
            if (i > 0) licenses.addView(c.divider())
            licenses.addView(row(lic.title, "${lic.license} · ${lic.holder}", R.drawable.ic_chevron) { showLicense(lic) })
        }
        body.addView(licenses, lp(MATCH, WRAP))

        view = scaffold(c, c.getString(R.string.about_title), { host.back() }, body)
    }

    /** Card line: title and signature on the left, icon on the right; click on the entire line. Without [onClick], the line is not pressed. */
    private fun row(title: String, sub: String, iconRes: Int?, onClick: (() -> Unit)?): View {
        val c = ctx
        return c.hbox(14).apply {
            pad(c, 16, 12, 14, 12)
            minimumHeight = c.dp(64)
            if (onClick != null) {
                background = c.rippleBg(null, 0)
                isClickable = true
                setOnClickListener { onClick() }
            }
            addView(c.vbox(2).apply {
                addView(c.text(title, 16f, 500))
                addView(c.text(sub, 13f, 400, c.clr(R.color.on2)))
            }, lp(0, WRAP, 1f))
            if (iconRes != null) addView(c.icon(iconRes, 20, c.clr(R.color.on2)))
        }
    }

    /** Full license text in the scrolling window; Project Page opens the project in the browser. */
    private fun showLicense(lic: OpenSource) {
        val c = ctx
        val text = About.reflow(About.readText(c, lic.asset)).ifEmpty { c.getString(R.string.about_license_unavailable) }
        val content = c.vbox(8).apply {
            pad(c, 20, 8, 20, 8)
            addView(c.text("${lic.license} · ${lic.holder}", 14f, 600))
            addView(c.text(lic.url, 12f, 400, c.clr(R.color.on2), monospace = true).apply { setTextIsSelectable(true) })
            addView(c.text(text, 12f, 400, c.clr(R.color.on2), monospace = true).apply {
                lineHeight = c.dp(18)
                setTextIsSelectable(true)
            })
        }
        AlertDialog.Builder(c, R.style.Theme_Headswap_Dialog)
            .setTitle(lic.title)
            .setView(ScrollView(c).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.about_open_project) { _, _ -> openUrl(lic.url) }
            .show()
    }

    private fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            AppLog.e("о приложении: ссылка не открылась: ${e.message}")
        }
    }
}
