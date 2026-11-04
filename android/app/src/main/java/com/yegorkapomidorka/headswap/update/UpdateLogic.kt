package com.yegorkapomidorka.headswap.update

/**
 * Pure update logic without Android: releases of GitHub, version comparison, APK selection when to check.
 * Release: vX.Y.Z tag, headswap-X.Y.Z.apk file; "last" defines GitHub itself (not draft and not pre-release).
 */
object UpdateLogic {
    const val REPO = "YegorkaPomidorka/headswap"
    const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    /** Backup path if API did not respond to 200 (60 request per hour limit on IP): redirect to tag page. */
    const val PAGE_LATEST = "https://github.com/$REPO/releases/latest"

    /** Autocheck no more than once in 6 hours; after failure repeat in an hour. */
    const val CHECK_INTERVAL_MS = 6 * 3600_000L
    const val RETRY_MS = 3600_000L

    private const val MAX_NOTES = 1500

    /** Found release; size = -1 and sha256 = null if only tag (backup path) is known. */
    data class Release(
        val version: String,
        val tag: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String?,
        val notes: String,
        val page: String,
    )

    data class Asset(val name: String, val url: String, val size: Long, val digest: String?)

    /** "v1.1.10", "1.1.10", "1.1.10-cx5test1" -> [1, 1, 10]; no digits at the beginning - null. */
    fun parseVersion(s: String): List<Int>? {
        val m = Regex("""^[vV]?(\d+(?:\.\d+)*)""").find(s.trim()) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** remote is newer than local; missing parts = 0 (1.2 == 1.2.0), the test build suffix is not taken into account. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun versionFromTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** APK release: headswap-< version>.apk, otherwise the only .apk among the files. */
    fun pickApk(assets: List<Asset>, version: String): Asset? =
        assets.firstOrNull { it.name.equals("headswap-$version.apk", ignoreCase = true) }
            ?: assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.singleOrNull()

    /** digest release file GitHub "sha256:<hex>" -> hex in lowercase; other algorithms - null. */
    fun sha256(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        return d.substring(7).lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Location redirect .../releases/tag/v1.1.10 -> "v1.1.10". */
    fun tagFromLocation(location: String?): String? =
        location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.substringBefore('#')?.takeIf { it.isNotBlank() }

    fun assetUrl(tag: String, version: String) = "https://github.com/$REPO/releases/download/$tag/headswap-$version.apk"

    fun tagPage(tag: String) = "https://github.com/$REPO/releases/tag/$tag"

    /** Description of the release for the dialogue: without \ r and extra empty lines, not longer MAX_NOTES. */
    fun notes(body: String?): String {
        val t = (body ?: "").replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (t.length > MAX_NOTES) t.take(MAX_NOTES).trimEnd() + "…" else t
    }

    /** Is it time for autocheck: included and from the past passed [CHECK_INTERVAL_MS] (the clock moved back - also time). */
    fun shouldCheck(enabled: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
        enabled && (nowMs - lastCheckMs >= CHECK_INTERVAL_MS || nowMs < lastCheckMs)
}
