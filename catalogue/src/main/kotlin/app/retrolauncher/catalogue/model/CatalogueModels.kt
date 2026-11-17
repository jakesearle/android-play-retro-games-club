package app.retrolauncher.catalogue.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Data classes for the Cocoon emulator catalogue (`android.json`).
 *
 * The format is defined by `tools/platforms.py` in KansoMirai/CocoonFE. Every optional field
 * has a default, and unknown keys are ignored, so new upstream fields don't break parsing.
 */

/** The small file checked first: `manifest.json`. */
@Serializable
data class CatalogueManifest(
    val format: Int,
    val version: Int,
    val file: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class CatalogueData(
    val format: Int,
    val platforms: List<Platform> = emptyList(),
)

@Serializable
data class Platform(
    val id: String,
    val name: String,
    /** Python-flavoured regex, matched against the file name. Usually just excludes dot-files. */
    val acceptedFilenameRegex: String? = null,
    val description: String? = null,
    val retroAchievementsConsoleIds: List<Int> = emptyList(),
    /** Old platform IDs. */
    val was: List<String> = emptyList(),
    val extra: PlatformExtra? = null,
    val cocoon: CocoonInfo = CocoonInfo(),
    /** Scraper IDs. Kept loose because upstream passes the YAML through unchanged. */
    val scrape: JsonObject? = null,
    val players: List<Player> = emptyList(),
) {
    val folderAliases: List<String> get() = extra?.folderAliases.orEmpty()

    fun scrapeInt(key: String): Int? = (scrape?.get(key) as? JsonPrimitive)?.intOrNull
    fun scrapeString(key: String): String? =
        (scrape?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    val screenscraperId: Int? get() = scrapeInt("screenscraper")
    val igdbId: Int? get() = scrapeInt("igdb")
    val theGamesDbId: Int? get() = scrapeInt("thegamesdb")
    val launchboxName: String? get() = scrapeString("launchbox")
    val libretroName: String? get() = scrapeString("libretro")
}

@Serializable
data class PlatformExtra(
    val folderAliases: List<String> = emptyList(),
    /** Windows-only storefront config. Not modelled yet. */
    val pcStorefronts: JsonObject? = null,
)

@Serializable
data class CocoonInfo(
    val icon: String? = null,
    /** Either a named colour (`"wisteria"`) or a two-colour gradient (`["#86A7FD", "#..."]`). */
    val folder: JsonElement? = null,
) {
    val folderColourName: String?
        get() = (folder as? JsonPrimitive)?.takeIf { it.isString }?.content

    val folderGradient: List<String>?
        get() = (folder as? JsonArray)?.map { it.jsonPrimitive.content }
}

@Serializable
data class Player(
    val id: String,
    val name: String,
    /** Null means the player accepts any file the platform accepts. */
    val acceptedFilenameRegex: String? = null,
    /** Newline-separated `am start` arguments; see [app.retrolauncher.catalogue.am.AmStartArguments]. */
    val amStartArguments: String,
    /** Old player IDs. Use them to migrate saved per-game choices. */
    val was: List<String> = emptyList(),
    val extra: PlayerExtra? = null,
) {
    val cantResume: Boolean get() = extra?.cantResume == true
}

@Serializable
data class PlayerExtra(
    val cantResume: Boolean = false,
)

/** The JSON configuration used for the catalogue files. */
val CatalogueJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
