package app.retrolauncher.launch

import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.model.Platform
import app.retrolauncher.club.PlatformConsoles
import app.retrolauncher.club.RaConsoles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * One app from the [Obtainium Emulation Pack](https://github.com/RJNY/Obtainium-Emulation-Pack)
 * (Unlicense). [config] is the app's Obtainium configuration exactly as the pack exports it;
 * Obtainium's `obtainium://app/<config>` link adds it, as the pack's README links do.
 */
data class PackApp(
    val name: String,
    /** Obtainium's ID: usually the Android package, but a number for some sources (RetroArch). */
    val id: String,
    /** Where the app comes from: its GitHub repository or download page. */
    val url: String,
    /** The pack's one-line description, e.g. "PlayStation 1 emulator". */
    val about: String?,
    val categories: List<String>,
    val config: JsonObject,
) {
    /** The Android package this installs, or null if unknown. */
    val packageName: String?
        get() = when {
            id.contains('.') -> id
            // RetroArch's buildbot source has a numeric Obtainium ID; the pack ships the AArch64 build.
            url.contains("buildbot.libretro.com") -> "com.retroarch.aarch64"
            else -> null
        }

    /** Obtainium's link that adds this app (Obtainium installs it and keeps it updated). */
    val obtainiumLink: String
        get() = "obtainium://app/" + URLEncoder.encode(config.toString(), "UTF-8").replace("+", "%20")
}

object EmulatorPack {
    const val URL = "https://raw.githubusercontent.com/RJNY/Obtainium-Emulation-Pack/main/obtainium-emulation-pack-latest.json"

    /** Parses the pack's exported JSON (`{apps: [...]}`); unreadable entries are skipped. */
    fun parse(json: String): List<PackApp> {
        val apps = Json.parseToJsonElement(json).jsonObject["apps"] as? JsonArray ?: return emptyList()
        return apps.mapNotNull { element ->
            val app = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (app[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val about = str("additionalSettings")?.let { settings ->
                runCatching { (Json.parseToJsonElement(settings).jsonObject["about"] as? JsonPrimitive)?.content }.getOrNull()
            }
            PackApp(
                name = str("name") ?: return@mapNotNull null,
                id = str("id") ?: return@mapNotNull null,
                url = str("url").orEmpty(),
                about = about?.takeIf { it.isNotBlank() },
                categories = (app["categories"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                config = app,
            )
        }
    }

    /**
     * Joey64 isn't in the pack yet; this is the same kind of Obtainium config, tracking its
     * GitHub releases, so it can be suggested (and preferred) for N64 like the pack's apps.
     */
    val JOEY64 = PackApp(
        name = "Joey64",
        id = "com.joeysretro.joey64",
        url = "https://github.com/JoeysRetroHandhelds/joey64",
        about = "Nintendo 64 emulator for Android handhelds",
        categories = listOf("Emulator"),
        config = Json.parseToJsonElement(
            """{"id":"com.joeysretro.joey64","url":"https://github.com/JoeysRetroHandhelds/joey64",""" +
                """"author":"JoeysRetroHandhelds","name":"Joey64","preferredApkIndex":0,""" +
                """"additionalSettings":"{\"includePrereleases\":false,\"fallbackToOlderReleases\":true,\"about\":\"Nintendo 64 emulator for Android handhelds\"}",""" +
                """"categories":["Emulator"],"allowIdChange":false,"overrideSource":"GitHub"}"""
        ).jsonObject,
    )
}

/** An emulator to suggest for a platform. */
data class Recommendation(
    val app: PackApp,
    /** It supports RetroAchievements, which the club's games are played with. */
    val achievements: Boolean,
)

object Recommendations {
    private const val RETROARCH = "com.retroarch.aarch64"

    /** Standalone emulators RetroAchievements lists as supported (docs/EMULATORS.md), plus RetroArch. */
    private val ACHIEVEMENTS = setOf(
        RETROARCH, "com.github.stenzek.duckstation", "org.ppsspp.ppsspp",
        "org.dolphinemu.dolphinemu", "com.flycast.emulator", "me.magnum.melonds", "com.armsx2",
    )

    /**
     * Consoles where RetroAchievements supports no RetroArch core, only standalone emulators
     * (docs/EMULATORS.md): GameCube, Wii, PS2, Apple II, Arcadia 2001, Interton VC 4000,
     * Elektor and Jaguar CD. RetroArch can still play some of them, but without achievements.
     */
    private val RETROARCH_WITHOUT_ACHIEVEMENTS = setOf(
        RaConsoles.GAMECUBE, RaConsoles.WII, RaConsoles.PLAYSTATION_2, RaConsoles.APPLE_II,
        RaConsoles.ARCADIA_2001, RaConsoles.INTERTON_VC_4000, RaConsoles.ELEKTOR_TV_GAMES_COMPUTER, RaConsoles.ATARI_JAGUAR_CD,
    )

    /** Whether [packageName] earns RetroAchievements on [platform]. */
    fun achievements(packageName: String?, platform: Platform): Boolean = when {
        packageName !in ACHIEVEMENTS -> false
        packageName == RETROARCH -> PlatformConsoles.of(platform).none { it in RETROARCH_WITHOUT_ACHIEVEMENTS }
        else -> true
    }

    /**
     * Pack apps that can play [platform], judged by the catalogue (which knows how to launch
     * each package for each platform), best first: apps preferred for the platform (Joey64 for
     * N64), then ones that support RetroAchievements, then the rest, each in the pack's order.
     * One per package, at most [limit].
     */
    fun forPlatform(catalogue: Catalogue, platform: Platform, pack: List<PackApp>, limit: Int = 4): List<Recommendation> {
        val launchable = platform.players.mapNotNull { catalogue.packageName(it) }.toSet()
        val preferred = ExtraPlayers.preferredFor(platform.id)
        val candidates = (listOf(EmulatorPack.JOEY64) + pack)
            .filter { "Emulator" in it.categories || "PC Emulation" in it.categories }
            .filter { it.packageName in launchable }
            .distinctBy { it.packageName }
        return candidates
            .sortedWith(
                compareBy<PackApp>(
                    { preferred.indexOf(it.packageName).let { i -> if (i < 0) preferred.size else i } },
                    { if (achievements(it.packageName, platform)) 0 else 1 },
                )
            )
            .map { Recommendation(it, achievements(it.packageName, platform)) }
            .take(limit)
    }
}

/**
 * Downloads the pack at runtime and keeps it a week, like the emulator catalogue. Offline, an
 * older copy is used; null only when there's never been one.
 */
class EmulatorPackRepository(
    private val dir: File,
    private val download: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file get() = File(dir, "emulator-pack.json")
    private var cached: List<PackApp>? = null

    @Synchronized
    fun load(): List<PackApp>? {
        val stale = !file.exists() || clock() - file.lastModified() > MAX_AGE_MS
        if (stale) {
            try {
                val body = download(EmulatorPack.URL)
                if (EmulatorPack.parse(body).isNotEmpty()) {
                    dir.mkdirs()
                    val tmp = File(dir, "emulator-pack.json.tmp")
                    tmp.writeText(body)
                    tmp.renameTo(file)
                    cached = null
                }
            } catch (_: IOException) {
                // Offline: an older copy is fine.
            }
        }
        cached?.let { return it }
        return try {
            EmulatorPack.parse(file.readText()).takeIf { it.isNotEmpty() }?.also { cached = it }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
