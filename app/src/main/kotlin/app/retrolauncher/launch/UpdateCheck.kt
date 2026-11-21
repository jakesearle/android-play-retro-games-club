package app.retrolauncher.launch

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A newer release on GitHub than the one installed. */
data class AppUpdate(val version: String, val url: String)

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
        if (clock() - prefs.getLong(CHECKED, 0) > MAX_AGE_MS) {
            try {
                val o = Json.parseToJsonElement(fetch(LATEST)) as JsonObject
                val tag = (o["tag_name"] as? JsonPrimitive)?.content
                val url = (o["html_url"] as? JsonPrimitive)?.content
                if (tag != null && url != null) prefs.edit { putString(TAG, tag); putString(URL_KEY, url); putLong(CHECKED, clock()) }
            } catch (_: Exception) {
                // Offline or rate-limited: keep the last answer.
            }
        }
        val tag = prefs.getString(TAG, null) ?: return null
        val url = prefs.getString(URL_KEY, null) ?: return null
        val version = tag.removePrefix("v")
        return if (isNewer(version, current)) AppUpdate(version, url) else null
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
