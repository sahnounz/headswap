package com.yegorkapomidorka.headswap.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R

/**
 * A one-time “Direct passthrough Video” window for those who have upgraded to 3.0: direct passthrough and the “Auto” frequency is now default.
 * Beginners will see the mode in onboarding and settings, they do not need a window. When to show - see [UpgradeNotice.onLaunch].
 */
object Upgrade30Notice {
    fun show(act: MainActivity) {
        AppLog.i("окно «Прямой проброс видео» (3.0): показываю")
        val dialog = Dialog(act, R.style.Theme_Headswap_Dialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content(act, dialog), FrameLayout.LayoutParams(MATCH, WRAP))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
        }
        dialog.show()
    }

    /** Fields around the panel: on the sides of the 16 dp, at the top and bottom of the 24 dp, so that on the short screen the panel does not stick to the edges. */
    private fun content(act: MainActivity, dialog: Dialog): View {
        val c: Context = act
        val panel = object : LinearLayout(c) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = c.dp(480)
                val w = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(
                    if (w > max) MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY) else widthMeasureSpec,
                    heightMeasureSpec,
                )
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = c.shape(c.clr(R.color.surf), 28)
            pad(c, 20, 20, 20, 12)
        }

        val body = c.vbox(12)
        body.addView(titleRow(c))
        body.addView(
            c.text(c.getString(R.string.u30_lead), 15f, 500, c.clr(R.color.onpric)).apply {
                background = c.shape(c.clr(R.color.pric), 16)
                pad(c, 14, 12, 14, 12)
                lineHeight = c.dp(20)
            },
            lp(MATCH, WRAP),
        )
        body.addView(
            section(c, R.string.u30_pros, R.color.ok, R.color.okc, R.drawable.ic_plus, listOf(R.string.u30_pro_1, R.string.u30_pro_2, R.string.u30_pro_3), R.color.ok),
            lp(MATCH, WRAP),
        )
        body.addView(
            section(c, R.string.u30_changed, R.color.on2, R.color.surf2, R.drawable.ic_arrow_right, listOf(R.string.u30_chg_1, R.string.u30_chg_2), R.color.on2),
            lp(MATCH, WRAP),
        )
        body.addView(warnSection(c), lp(MATCH, WRAP))

        // Scrolls all the content, the buttons remain at the bottom of the panel, out of scrolling.
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        panel.addView(scroll, lp(MATCH, 0, 1f))
        panel.addView(
            c.primaryButton(c.getString(R.string.u30_ok), 0, 52, 17f).apply { setOnClickListener { dialog.dismiss() } },
            lp(MATCH, c.dp(52)).margins(c, 0, 16, 0, 0),
        )
        panel.addView(
            c.linkButton(c.getString(R.string.u30_open), 44, 15f).apply {
                setOnClickListener {
                    dialog.dismiss()
                    act.showSettings()
                    act.showSettingsSection(SettingsSection.VIDEO)
                }
            },
            lp(MATCH, c.dp(44)).margins(c, 0, 4, 0, 0),
        )

        return FrameLayout(c).apply {
            pad(c, 16, 24, 16, 24)
            addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
        }
    }

    /** Cover «3.0» and header. */
    private fun titleRow(c: Context): View = c.hbox(12).apply {
        addView(
            FrameLayout(c).apply {
                background = c.shape(c.clr(R.color.pri), 12)
                addView(
                    c.text("3.0", 20f, 700, c.clr(R.color.onpri)).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER),
                )
            },
            lp(c.dp(48), c.dp(48)),
        )
        addView(
            c.text(c.getString(R.string.u30_title), 20f, 600).apply {
                letterSpacing = -0.015f
                lineHeight = c.dp(26)
            },
            lp(0, WRAP, 1f),
        )
    }

    /** Block with header section and items in circles with an icon. */
    private fun section(c: Context, head: Int, fg: Int, bg: Int, icon: Int, items: List<Int>, headColor: Int): View {
        val box = c.vbox(6)
        box.addView(heading(c, head, headColor), lp(MATCH, WRAP))
        for (res in items) box.addView(item(c, c.getText(res), fg, bg, icon), lp(MATCH, WRAP))
        return box
    }

    private fun heading(c: Context, res: Int, color: Int) =
        c.text(c.getString(res).uppercase(c.resources.configuration.locales[0]), 13f, 600, c.clr(color)).apply {
            letterSpacing = 0.02f
        }

    private fun item(c: Context, text: CharSequence, fg: Int, bg: Int, icon: Int): View = c.hbox(10).apply {
        addView(
            FrameLayout(c).apply {
                background = c.oval(c.clr(bg))
                addView(c.icon(icon, 12, c.clr(fg)), FrameLayout.LayoutParams(c.dp(12), c.dp(12), Gravity.CENTER))
            },
            lp(c.dp(20), c.dp(20)),
        )
        addView(c.text(text, 14f).apply { lineHeight = c.dp(19) }, lp(0, WRAP, 1f))
    }

    /** “If something is wrong”: text, path in settings and a picture of the mode switch. */
    private fun warnSection(c: Context): View {
        val box = c.vbox(6)
        box.addView(heading(c, R.string.u30_warn, R.color.warn), lp(MATCH, WRAP))
        box.addView(c.text(c.getText(R.string.u30_warn_text), 14f).apply { lineHeight = c.dp(19) }, lp(MATCH, WRAP))
        box.addView(
            c.text(c.getString(R.string.u30_where), 12f, 400, c.clr(R.color.on2)),
            lp(MATCH, WRAP).margins(c, 0, 4, 0, 0),
        )
        box.addView(modeSwitchPicture(c), lp(MATCH, c.dp(44)))
        return box
    }

    /** Picture: Switch "Mode" from settings (Segmented) with selected transcoding. Only for view, no presses. */
    private fun modeSwitchPicture(c: Context): View = c.hbox().apply {
        background = c.shape(0, 22, c.clr(R.color.line), 1.5f)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val half = { label: String, selected: Boolean ->
            c.text(label, 14f, 600, c.clr(if (selected) R.color.onpric else R.color.on)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                pad(c, 6, 0, 6, 0)
                if (selected) background = c.shape(c.clr(R.color.pric), 0)
            }
        }
        addView(half("✓ ${c.getString(R.string.set_transcode_title)}", true), lp(0, MATCH, 1f))
        addView(half(c.getString(R.string.set_direct_title), false), lp(0, MATCH, 1f))
    }
}
