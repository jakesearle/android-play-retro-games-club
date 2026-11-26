package app.retrolauncher.launch

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A newer release on GitHub than the one installed.
 *
 * @param url the release's page.
 * @param apkUrl the APK attached to the release, which [AppUpdater] installs; null if it has none.
 * @param apkSize the APK's size in bytes, or 0 if unknown.
 * @param notes the release's description (Markdown, as written on GitHub), maybe empty.
 */
data class AppUpdate(
    val version: String,
    val url: String,
    val apkUrl: String? = null,
    val apkSize: Long = 0,
    val notes: String = "",
)

/** What [UpdateCheck] keeps from GitHub's latest-release answer. */
internal data class Release(val tag: String, val url: String, val apkUrl: String?, val apkSize: Long, val notes: String)

/**
 * Asks GitHub for this app's latest release, at most once a day (the answer is kept between
 * checks), and says whether it's newer than [current]. Offline, the last answer is used.
 */
class UpdateCheck(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (String) -> String = ::httpGet,
) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun check(current: String): AppUpdate? {
        // An answer saved before the APK was kept (FORMAT) is asked for again.
        if (clock() - prefs.getLong(CHECKED, 0) > MAX_AGE_MS || prefs.getInt(FORMAT_KEY, 0) < FORMAT) {
            try {
                parseRelease(fetch(LATEST))?.let { r ->
                    prefs.edit {
                        putString(TAG, r.tag); putString(URL_KEY, r.url)
                        putString(APK_URL, r.apkUrl); putLong(APK_SIZE, r.apkSize); putString(NOTES, r.notes)
                        putLong(CHECKED, clock()); putInt(FORMAT_KEY, FORMAT)
                    }
                }
            } catch (_: Exception) {
                // Offline or rate-limited: keep the last answer.
            }
        }
        val tag = prefs.getString(TAG, null) ?: return null
        val url = prefs.getString(URL_KEY, null) ?: return null
        val version = tag.removePrefix("v")
        if (!isNewer(version, current)) return null
        return AppUpdate(version, url, prefs.getString(APK_URL, null), prefs.getLong(APK_SIZE, 0), prefs.getString(NOTES, null).orEmpty())
    }

    companion object {
        const val REPO = "https://github.com/jakesearle/android-play-retro-games-club"
        private const val LATEST = "https://api.github.com/repos/jakesearle/android-play-retro-games-club/releases/latest"
        /** Obtainium's link that adds this app (or opens it, if already added) so it can update it. */
        const val OBTAINIUM_LINK = "obtainium://add/$REPO"
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val CHECKED = "checked"
        private const val TAG = "tag"
        private const val URL_KEY = "url"
        private const val APK_URL = "apk_url"
        private const val APK_SIZE = "apk_size"
        private const val NOTES = "notes"
        private const val FORMAT_KEY = "format"
        private const val FORMAT = 2

        /**
         * GitHub's latest-release JSON: its tag, page, release notes and the first `.apk` attached
         * (releases attach one, `prgc-launcher-0.2.0.apk`). Null without a tag or page.
         */
        internal fun parseRelease(json: String): Release? {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: return null
            fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val tag = o.text("tag_name") ?: return null
            val url = o.text("html_url") ?: return null
            val apk = (o["assets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .firstOrNull { it.text("name")?.endsWith(".apk", ignoreCase = true) == true && it.text("browser_download_url") != null }
            return Release(
                tag = tag,
                url = url,
                apkUrl = apk?.text("browser_download_url"),
                apkSize = (apk?.get("size") as? JsonPrimitive)?.longOrNull ?: 0,
                notes = o.text("body").orEmpty().trim(),
            )
        }

        /** Whether version [a] ("0.10.1") is newer than [b] ("0.9.3"), comparing numbers part by part. */
        fun isNewer(a: String, b: String): Boolean {
            fun parts(v: String) = v.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val x = parts(a); val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
                if (p != q) return p > q
            }
            return false
        }

        private fun httpGet(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", "PRGC-Launcher")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                return c.inputStream.use { it.readBytes().decodeToString() }
            } finally {
                c.disconnect()
            }
        }
    }
}
