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
 * One-time window "Your launcher on the magnetol" for those who have upgraded to 4.0: new mode, Android Auto by default.
 * Beginners choose onboarding mode, they do not need a window. When to show - see [UpgradeNotice.onLaunch].
 */
object Upgrade40Notice {
    fun show(act: MainActivity) {
        AppLog.i("окно «Свой лаунчер на магнитоле» (4.0): показываю")
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
            c.text(c.getString(R.string.u40_lead), 15f, 500, c.clr(R.color.onpric)).apply {
                background = c.shape(c.clr(R.color.pric), 16)
                pad(c, 14, 12, 14, 12)
                lineHeight = c.dp(20)
            },
            lp(MATCH, WRAP),
        )
        body.addView(
            section(c, R.string.u40_pros, R.color.ok, R.color.okc, R.drawable.ic_plus, listOf(R.string.u40_pro_1, R.string.u40_pro_2, R.string.u40_pro_3)),
            lp(MATCH, WRAP),
        )
        body.addView(
            section(c, R.string.u40_needs, R.color.warn, R.color.warnc, R.drawable.ic_minus, listOf(R.string.u40_need_1, R.string.u40_need_2, R.string.u40_need_3)),
            lp(MATCH, WRAP),
        )
        body.addView(whereBlock(c), lp(MATCH, WRAP))

        // Scrolls all the content, the buttons remain at the bottom of the panel, out of scrolling.
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        panel.addView(scroll, lp(MATCH, 0, 1f))
        panel.addView(
            c.primaryButton(c.getString(R.string.u40_ok), 0, 52, 17f).apply { setOnClickListener { dialog.dismiss() } },
            lp(MATCH, c.dp(52)).margins(c, 0, 16, 0, 0),
        )
        panel.addView(
            c.linkButton(c.getString(R.string.u40_try), 44, 15f).apply {
                setOnClickListener {
                    dialog.dismiss()
                    // same as `--es cmd onboarding --es step mode`: onboarding immediately with mode selection
                    act.showOnboarding(rerun = true, startAt = StepId.MODE)
                }
            },
            lp(MATCH, c.dp(44)).margins(c, 0, 4, 0, 0),
        )

        return FrameLayout(c).apply {
            pad(c, 16, 24, 16, 24)
            addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
        }
    }

    /** Cover «4.0» and header. */
    private fun titleRow(c: Context): View = c.hbox(12).apply {
        addView(
            FrameLayout(c).apply {
                background = c.shape(c.clr(R.color.pri), 12)
                addView(
                    c.text("4.0", 20f, 700, c.clr(R.color.onpri)).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER),
                )
            },
            lp(c.dp(48), c.dp(48)),
        )
        addView(
            c.text(c.getString(R.string.u40_title), 20f, 600).apply {
                letterSpacing = -0.015f
                lineHeight = c.dp(26)
            },
            lp(0, WRAP, 1f),
        )
    }

    /** Block with header section and items in circles with icon; color of header is the same as that of circles. */
    private fun section(c: Context, head: Int, fg: Int, bg: Int, icon: Int, items: List<Int>): View {
        val box = c.vbox(6)
        box.addView(heading(c, head, fg), lp(MATCH, WRAP))
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

    /** The path in the settings and the mode switch picture. */
    private fun whereBlock(c: Context): View {
        val box = c.vbox(6)
        box.addView(c.text(c.getString(R.string.u40_where), 12f, 400, c.clr(R.color.on2)), lp(MATCH, WRAP))
        box.addView(modeSwitchPicture(c), lp(MATCH, c.dp(44)))
        return box
    }

    /** Picture: "Mode" switch from settings (Segmented) with selected Android Auto. Only for view, no taps. */
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
        addView(half("✓ Android Auto", true), lp(0, MATCH, 1f))
        addView(half(c.getString(R.string.bm_own_launcher), false), lp(0, MATCH, 1f))
    }
}
