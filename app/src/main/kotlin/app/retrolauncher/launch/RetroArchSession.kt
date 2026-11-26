package app.retrolauncher.launch

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.edit
import java.net.URISyntaxException

/**
 * The game this app last started in RetroArch, and whether RetroArch may still have it open.
 *
 * RetroArch can't switch games cleanly: given a different game while one is open, it exits on
 * the spot, without writing the open game's auto save state, so progress since its last save is
 * lost. The launcher asks first (see [ClubViewModel.play]); this tells it when to.
 *
 * Android doesn't show other apps' processes, but with Usage Access it does show their activity
 * events. Pressing Home mid-game leaves ACTIVITY_PAUSED then ACTIVITY_STOPPED; quitting, or
 * RetroArch exiting, leaves ACTIVITY_STOPPED alone, as the process is gone before it pauses.
 * One blind spot: Android ending a backgrounded RetroArch to free memory leaves no event, so it
 * still looks open.
 */
class RetroArchSession(private val context: Context) {
    private val prefs = context.getSharedPreferences("retroarch_session", Context.MODE_PRIVATE)

    /** A game RetroArch may have open: its title, and the intent that started it (to resume it). */
    data class OpenGame(val title: String, val intent: Intent, val rom: String?, val core: String?, val since: Long)

    fun record(title: String, intent: Intent) = prefs.edit {
        putString(TITLE, title)
        putString(INTENT, intent.toUri(Intent.URI_INTENT_SCHEME))
        putLong(SINCE, System.currentTimeMillis())
    }

    fun last(): OpenGame? {
        val title = prefs.getString(TITLE, null) ?: return null
        val intent = try {
            Intent.parseUri(prefs.getString(INTENT, null) ?: return null, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            return null
        }
        return OpenGame(title, intent, intent.getStringExtra(ROM), intent.getStringExtra(LIBRETRO), prefs.getLong(SINCE, 0))
    }

    /**
     * Whether launching [intent] would make RetroArch drop an open game: the game RetroArch may
     * still have open, or null if it's closed, or it's the same game (which just resumes).
     */
    fun wouldClose(intent: Intent): Conflict? {
        val open = last() ?: return null
        val pkg = intent.component?.packageName ?: return null
        if (open.intent.component?.packageName != pkg) return null
        // RetroArch compares the ROM and core extras: if both match, it resumes.
        if (open.rom == intent.getStringExtra(ROM) && open.core == intent.getStringExtra(LIBRETRO)) return null
        return when (isRunning(pkg, open.since)) {
            false -> null
            true -> Conflict(open, certain = true)
            null -> Conflict(open, certain = false)
        }
    }

    /** RetroArch may have [open] open; [certain] is false without Usage Access, when it can't tell. */
    data class Conflict(val open: OpenGame, val certain: Boolean)

    /** True or false from RetroArch's activity events since [since], or null without Usage Access. */
    private fun isRunning(pkg: String, since: Long): Boolean? {
        if (!hasUsageAccess()) return null
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = usage.queryEvents(since - 1_000, System.currentTimeMillis())
        val event = UsageEvents.Event()
        var last: Int? = null
        var beforeLast: Int? = null
        while (events.getNextEvent(event)) {
            if (event.packageName != pkg) continue
            val type = event.eventType
            if (type != UsageEvents.Event.ACTIVITY_RESUMED && type != UsageEvents.Event.ACTIVITY_PAUSED &&
                type != UsageEvents.Event.ACTIVITY_STOPPED
            ) continue
            beforeLast = last
            last = type
        }
        Log.i(TAG, "$pkg events since launch: …$beforeLast, $last")
        return when (last) {
            null -> false // never came up, or the events have aged out
            UsageEvents.Event.ACTIVITY_STOPPED -> beforeLast == UsageEvents.Event.ACTIVITY_PAUSED
            else -> true // paused or still resumed
        }
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Android's Usage Access settings, where the player can let this app see RetroArch's events:
     * this app's own page where Settings supports it (Android 10+), else the list of apps.
     */
    fun usageAccessIntent(): Intent {
        val perApp = Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
        val intent = if (perApp.resolveActivity(context.packageManager) != null) perApp
        else Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val TAG = "RetroArchSession"
        const val TITLE = "title"
        const val INTENT = "intent"
        const val SINCE = "since"
        const val ROM = "ROM"
        const val LIBRETRO = "LIBRETRO"
    }
}
