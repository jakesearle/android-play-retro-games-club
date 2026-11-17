package app.retrolauncher.launch

import android.content.ActivityNotFoundException
import android.content.Context
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

/** Starts a game in an emulator. Returns null on success, or a message to show the user. */
class GameLauncher(private val context: Context) {

    fun launch(catalogue: Catalogue, game: Game, player: Player): String? {
        val command = catalogue.command(player)
        command.warnings.forEach { Log.w(TAG, "${player.id}: $it") }

        val gameFile = GameFile(
            path = game.path,
            uri = game.uri ?: if (command.needsFileUri) game.path?.let(::fileProviderUri) else null,
            mime = mimeFor(game.fileName),
        )
        val spec = when (val result = command.resolve(gameFile)) {
            is ResolveResult.Success -> result.spec
            is ResolveResult.Failure -> return "${player.name} can't launch this game: ${result.problems.joinToString()}"
        }
        val intent = spec.toIntent(grantWrite = true)
        Log.i(TAG, "Launching ${game.fileName} with ${player.id}: $intent extras=${intent.extras}")
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "${player.name} isn't installed, or its launch activity has changed."
        } catch (e: SecurityException) {
            Log.w(TAG, "Launch refused", e)
            "${player.name} refused the launch: ${e.message}"
        }
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
