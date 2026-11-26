package app.retrolauncher.launch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the app in place from a GitHub release's APK: [download] it, [verify] it is this app,
 * newer and signed with the same key, then [install] it through Android's package installer,
 * which asks the player to confirm. Installing from an app needs the "install unknown apps"
 * permission for this app, which only the player can turn on ([canInstall], [permissionIntent]).
 */
class AppUpdater(private val context: Context) {
    private val dir get() = File(context.cacheDir, "updates")

    /** Whether the player has let this app install apps (Settings › Install unknown apps). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** This app's page in Settings › Install unknown apps. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Downloads [update]'s APK into the cache, reporting (bytes so far, total or 0). An APK already
     * downloaded for this version is reused. Blocking; stops with an [IOException] once
     * [cancelled] says so. Older downloads are deleted.
     */
    @Throws(IOException::class)
    fun download(update: AppUpdate, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val url = update.apkUrl ?: throw IOException("This release has no APK.")
        val target = File(dir, "prgc-launcher-${update.version.filter { it.isLetterOrDigit() || it == '.' }}.apk")
        dir.mkdirs()
        dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        if (target.exists() && (update.apkSize <= 0 || target.length() == update.apkSize)) return target

        val part = File(dir, target.name + ".part")
        // GitHub answers with a redirect to its file host, which HttpURLConnection follows (both https).
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "PRGC-Launcher")
            c.setRequestProperty("Accept", "application/octet-stream")
            if (c.responseCode !in 200..299) throw IOException("GitHub answered HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: update.apkSize
            var done = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("The download was cut short.")
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(target)) throw IOException("Couldn't save the download.")
        return target
    }

    /**
     * Why [apk] can't update this app, in words for the player, or null if it can: it must be
     * this app (same package), a newer build, and signed with the same key (Android refuses an
     * update signed with another key anyway, but only after the player has confirmed).
     */
    fun verify(apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The download isn't a working APK."
        val installed = pm.getPackageInfo(context.packageName, flags)
        return problem(
            archivePackage = archive.packageName,
            ourPackage = context.packageName,
            archiveVersion = versionCode(archive),
            ourVersion = versionCode(installed),
            archiveSigners = signers(archive),
            ourSigners = signers(installed),
        )
    }

    /**
     * Hands [apk] to Android's package installer. The answer comes back to [InstallReceiver]:
     * Android asks the player to confirm (or installs straight away, on Android 12+ when this
     * app installed itself last time), then replaces this app, which closes it.
     */
    @Throws(IOException::class)
    fun install(apk: File) {
        _status.value = InstallStatus.Waiting
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds the result to it.
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            _status.value = InstallStatus.Idle
            throw e as? IOException ?: IOException(e.message ?: "Android couldn't start the install.", e)
        }
    }

    /** Deletes any downloaded APK: once this version is current, they're of no use. */
    fun clean() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Where an [install] has got to, as [InstallReceiver] hears it. */
    sealed interface InstallStatus {
        data object Idle : InstallStatus
        /** Handed to Android, which is asking the player or installing. */
        data object Waiting : InstallStatus
        /** The player said no, or closed Android's prompt. */
        data object Cancelled : InstallStatus
        data class Failed(val message: String) : InstallStatus
    }

    companion object {
        private val _status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
        val status: StateFlow<InstallStatus> = _status

        /** The player has seen the last answer. */
        fun acknowledge() {
            if (_status.value !is InstallStatus.Waiting) _status.value = InstallStatus.Idle
        }

        internal fun report(status: InstallStatus) {
            _status.value = status
        }

        /** [verify]'s rules, without Android, so they can be tested. */
        internal fun problem(
            archivePackage: String?,
            ourPackage: String,
            archiveVersion: Long,
            ourVersion: Long,
            archiveSigners: Set<String>?,
            ourSigners: Set<String>?,
        ): String? = when {
            archivePackage != ourPackage -> "The download is a different app ($archivePackage), so it wasn't installed."
            archiveVersion <= ourVersion -> "The download isn't newer than the version installed."
            // Unknown on some Android versions; the installer still checks.
            archiveSigners.isNullOrEmpty() || ourSigners.isNullOrEmpty() -> null
            archiveSigners != ourSigners ->
                "The download is signed with a different key from the app installed (a debug or self-built copy?), " +
                    "so Android won't install it over this one. Install it from the release page instead."
            else -> null
        }

        @Suppress("DEPRECATION")
        private fun versionCode(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

        /** SHA-256 of each certificate the package is signed with now, or null if Android didn't say. */
        @Suppress("DEPRECATION")
        private fun signers(info: PackageInfo): Set<String>? {
            val certs = if (Build.VERSION.SDK_INT >= 28) {
                val signing = info.signingInfo ?: return null
                if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            } else {
                info.signatures
            } ?: return null
            val sha = MessageDigest.getInstance("SHA-256")
            return certs.map { cert -> sha.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
    }
}

/** Hears back from Android's package installer for [AppUpdater.install]. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "Do you want to update this app?" screen.
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    AppUpdater.report(AppUpdater.InstallStatus.Failed("Android didn't show its install prompt."))
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    AppUpdater.report(AppUpdater.InstallStatus.Failed("Android didn't show its install prompt."))
                }
            }
            // On success Android replaces the app and closes it, so this is rarely heard.
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.report(AppUpdater.InstallStatus.Idle)
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdater.report(AppUpdater.InstallStatus.Cancelled)
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Android won't install it over this copy (it's signed with a different key)."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough storage space to install it."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install."
                    else -> "Android couldn't install it."
                }
                AppUpdater.report(AppUpdater.InstallStatus.Failed(if (detail.isNullOrBlank()) message else "$message ($detail)"))
            }
        }
    }
}
