package app.retrolauncher.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.am.GameFile
import app.retrolauncher.catalogue.am.ResolveResult
import app.retrolauncher.catalogue.android.toIntent
import app.retrolauncher.catalogue.model.Player
import app.retrolauncher.library.Game
import java.io.File

/** Starts games in emulators. */
class GameLauncher(private val context: Context) {
    private val retroArch = RetroArchSession(context)

    /** A launch ready to start: the intent, and what to call the game and emulator in messages. */
    class Launch(val intent: Intent, val title: String, val emulator: String, val isRetroArch: Boolean)

    /** Builds the launch for [game] with [player], or returns a message saying why it can't. */
    fun prepare(catalogue: Catalogue, game: Game, player: Player): Pair<Launch?, String?> {
        val command = catalogue.command(player)
        command.warnings.forEach { Log.w(TAG, "${player.id}: $it") }

        val gameFile = GameFile(
            path = game.path,
            uri = game.uri ?: if (command.needsFileUri) game.path?.let(::fileProviderUri) else null,
            mime = mimeFor(game.fileName),
        )
        val spec = when (val result = command.resolve(gameFile)) {
            is ResolveResult.Success -> result.spec
            is ResolveResult.Failure -> return null to "${player.label} can't launch this game: ${result.problems.joinToString()}"
        }
        val intent = spec.toIntent(grantWrite = true)
        val isRetroArch = spec.packageName?.startsWith("com.retroarch") == true
        if (isRetroArch) keepRunningRetroArch(intent)
        Log.i(TAG, "Prepared ${game.fileName} with ${player.id}: $intent extras=${intent.extras}")
        return Launch(intent, shortTitle(game.fileName), player.label, isRetroArch) to null
    }

    /** "Mega Man (USA) (Rev 1).zip" → "Mega Man": the file name without extension or (…) tags. */
    private fun shortTitle(fileName: String): String {
        val name = fileName.substringBeforeLast('.')
        return name.replace(Regex("""\s*\([^)]*\)"""), "").trim().ifEmpty { name }
    }

    /** The game RetroArch would drop, unsaved, if [launch] started now; null if none. */
    fun wouldClose(launch: Launch): RetroArchSession.Conflict? =
        if (launch.isRetroArch) retroArch.wouldClose(launch.intent) else null

    /** Starts [launch]. Returns null on success, or a message to show the user. */
    fun start(launch: Launch): String? {
        Log.i(TAG, "Launching ${launch.title}: ${launch.intent}")
        return try {
            context.startActivity(launch.intent)
            if (launch.isRetroArch) retroArch.record(launch.title, launch.intent)
            null
        } catch (e: ActivityNotFoundException) {
            "${launch.emulator} isn't installed, or its launch activity has changed."
        } catch (e: SecurityException) {
            Log.w(TAG, "Launch refused", e)
            "${launch.emulator} refused the launch: ${e.message}"
        }
    }

    /** Brings back the game RetroArch has open: the same intent again, which it resumes. */
    fun resume(open: RetroArchSession.OpenGame): String? =
        start(Launch(open.intent, open.title, "RetroArch", isRetroArch = true))

    fun hasUsageAccess() = retroArch.hasUsageAccess()
    fun usageAccessIntent() = retroArch.usageAccessIntent()

    /**
     * Drops the catalogue's --activity-clear-task and --activity-clear-top for RetroArch. If the
     * player pressed Home mid-game, RetroArch is still running; clearing the task makes Android
     * destroy and recreate its activity inside that process, which never finishes shutting down,
     * and RetroArch hangs or crashes. Without the flags, its single-instance activity gets the
     * intent in onNewIntent instead: the same game resumes, and a different game makes it exit
     * (newer builds restart with the new game).
     */
    private fun keepRunningRetroArch(intent: Intent) {
        intent.flags = (intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK) and
            (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).inv()
    }

    private fun fileProviderUri(path: String): String? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path)).toString()
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "No FileProvider URI for $path", e)
        null
    }

    private fun mimeFor(fileName: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())

    private companion object {
        const val TAG = "GameLauncher"
    }
}
