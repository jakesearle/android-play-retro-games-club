package app.retrolauncher.support

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A bug report people can send without adb (Discord users can't run it): the app's version and
 * the device, a status summary, and the app's own recent log lines, as a text file shared
 * through Android's share sheet. Apps can read only their own log lines, so nothing from other
 * apps is included, and anything that looks like a key is removed.
 */
object BugReport {
    /** Writes the report to the app's cache and returns the file. Blocking: reads logcat. */
    fun write(context: Context, status: String): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest is kept
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "prgc-report-$stamp.txt")
        file.writeText(redact(text(context, status, logcat())))
        return file
    }

    /** Opens the share sheet for [file]. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Play Retro Games Club app report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share the report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun text(context: Context, status: String, log: String): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return buildString {
            appendLine("Play Retro Games Club app report")
            appendLine("App: ${context.packageName} $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Written: ${LocalDateTime.now()}")
            appendLine()
            appendLine("── Status ──")
            appendLine(status.trimEnd())
            appendLine()
            appendLine("── Recent log ──")
            append(log.ifBlank { "(no log lines)" })
        }
    }

    /** This app's recent log lines (the last [LINES]). */
    private fun logcat(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "$LINES", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
    } catch (e: Exception) {
        "(couldn't read the log: ${e.javaClass.simpleName})"
    }

    /**
     * Removes anything key-like: RetroAchievements' Web API key travels as `y=` in URLs (which
     * the app never logs, but a library might), and tokens or keys in "name=value" form.
     */
    internal fun redact(text: String): String = text
        .replace(Regex("""([?&](?:y|key|token|t)=)[^&\s"]+""", RegexOption.IGNORE_CASE), "$1[removed]")
        .replace(Regex("""((?:api[_-]?key|token|password)\s*[=:]\s*)\S+""", RegexOption.IGNORE_CASE), "$1[removed]")

    private const val LINES = 1500
}
