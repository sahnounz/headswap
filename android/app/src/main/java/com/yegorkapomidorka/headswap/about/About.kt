package com.yegorkapomidorka.headswap.about

import android.content.Context
import com.yegorkapomidorka.headswap.R

/** Author for the screen "About application": name by language, nickname and page in Telegram. */
data class Author(val nameRes: Int, val handle: String)

/** Project with open license: copyright holder, license, project page and full text file in assets/licenses. */
data class OpenSource(
    val name: String,
    val version: String?,
    val holder: String,
    val license: String,
    val url: String,
    val asset: String,
) {
    /** "Kadb 2.1.5" or just a name if version is not needed. */
    val title: String get() = if (version == null) name else "$name $version"
}

/** Authors and third-party licenses: one source for the head unit screen (launcher) and phone screen. */
object About {
    val authors = listOf(
        Author(R.string.about_author_yegor, "@YegorkaPomidorka"),
        Author(R.string.about_author_ildar, "@sadykovildar"),
    )

    private const val GPL = "GPL-3.0.txt"
    private const val APACHE = "Apache-2.0.txt"

    /** Versions are the ones that really fall into the APK (gradlew :app:dependencies, releaseRuntimeClasspath). */
    val licenses = listOf(
        OpenSource("Headswap", null, "Yegor, Ildar Sadykov", "GPL-3.0", "https://github.com/YegorkaPomidorka/headswap", GPL),
        OpenSource("headswap_desk", null, "Ildar Sadykov", "GPL-3.0", "https://github.com/SadykovIV/headswap_desk", GPL),
        OpenSource("Kadb", "2.1.5", "Flyfish Studio and contributors", "Apache-2.0", "https://github.com/flyfishxu/Kadb", APACHE),
        OpenSource("spake2-java", "1.1.1", "Muntashir Al-Islam", "GPL-3.0", "https://github.com/Flyfish233/spake2-java", GPL),
        OpenSource("curve25519-elisabeth, ed25519-elisabeth", "0.1.0", "Jack Grigg", "MIT, BSD-3-Clause",
            "https://github.com/cryptography-cafe/curve25519-elisabeth", "MIT-Elisabeth.txt"),
        OpenSource("Bouncy Castle", "1.86", "The Legion of the Bouncy Castle Inc.", "MIT", "https://www.bouncycastle.org", "MIT-BouncyCastle.txt"),
        OpenSource("Okio", "3.18.2", "Square, Inc.", "Apache-2.0", "https://github.com/square/okio", APACHE),
        OpenSource("AndroidHiddenApiBypass", "6.1", "LSPosed", "Apache-2.0", "https://github.com/LSPosed/AndroidHiddenApiBypass", APACHE),
        OpenSource("Jetpack Compose, AndroidX", "1.9.4", "The Android Open Source Project", "Apache-2.0",
            "https://developer.android.com/jetpack/androidx", APACHE),
        OpenSource("kotlinx.coroutines, Kotlin", "1.11.0", "JetBrains", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines", APACHE),
        OpenSource("JSpecify", "1.0.0", "JSpecify contributors", "Apache-2.0", "https://jspecify.dev", APACHE),
        OpenSource("Golos Text", null, "The Golos Text Project Authors", "SIL OFL 1.1", "https://github.com/googlefonts/golos-text", "OFL-GolosText.txt"),
        OpenSource("JetBrains Mono", null, "The JetBrains Mono Project Authors", "SIL OFL 1.1", "https://github.com/JetBrains/JetBrainsMono",
            "OFL-JetBrainsMono.txt"),
    )

    /** Full text of the license from assets/licenses; not read - empty line (the screen will show only a link to the project). */
    fun readText(ctx: Context, asset: String): String =
        try {
            ctx.assets.open("licenses/$asset").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            ""
        }

    private val RULE = Regex("^[-=_*]{4,}$")
    private val LIST_MARK = Regex("""^\s*(\(?[0-9A-Za-z]{1,2}[.)]|[0-9]+\.)\s""")

    /**
     * License texts are typed in 80 columns: on a narrow screen, lines are torn as if by chance. We glue together the lines of one paragraph,
     * paragraphs (empty line), list items and indents header do not touch, lines of hyphens are rolled.
     */
    fun reflow(raw: String): String {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var prevIndented = false
        fun flush() {
            if (cur.isNotEmpty()) out += cur.toString()
            cur.setLength(0)
        }
        for (line in raw.replace("\r\n", "\n").split('\n')) {
            val t = line.trim()
            val indented = line.length - line.trimStart().length >= 8
            when {
                t.isEmpty() -> {
                    flush()
                    if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""
                    prevIndented = false
                }
                RULE.matches(t) -> {
                    flush()
                    out += "———"
                    prevIndented = false
                }
                else -> {
                    if (cur.isNotEmpty() && (LIST_MARK.containsMatchIn(line) || (indented && !prevIndented))) flush()
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(t)
                    prevIndented = indented
                }
            }
        }
        flush()
        return out.joinToString("\n").trim()
    }
}
