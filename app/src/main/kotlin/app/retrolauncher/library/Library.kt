package app.retrolauncher.library

/** A folder of ROMs added by the user. */
sealed interface RomSource {
    /** Stable key used for persistence and de-duplication. */
    val key: String
    val label: String

    /** A folder picked with the system file picker (`ACTION_OPEN_DOCUMENT_TREE`). */
    data class Saf(val treeUri: String) : RomSource {
        override val key get() = "saf:$treeUri"
        override val label get() = SafPaths.treeUriToPath(treeUri) ?: treeUri
    }

    /** A raw filesystem path. Needs MANAGE_EXTERNAL_STORAGE. */
    data class RawPath(val path: String) : RomSource {
        override val key get() = "path:$path"
        override val label get() = path
    }

    companion object {
        fun fromKey(key: String): RomSource? = when {
            key.startsWith("saf:") -> Saf(key.removePrefix("saf:"))
            key.startsWith("path:") -> RawPath(key.removePrefix("path:"))
            else -> null
        }
    }
}

/**
 * One ROM file.
 *
 * @param key stable identity for per-game settings: the real path when known, else the URI.
 * @param path real filesystem path, when one is known (raw folders, or derived from a SAF document ID).
 * @param uri `content://` URI, when known (SAF documents). Raw-path games get one from a FileProvider at launch.
 */
data class Game(
    val key: String,
    val platformId: String,
    val fileName: String,
    val title: String,
    val path: String?,
    val uri: String?,
    /** Size in bytes and last-modified time (ms), for the hash cache and saved ROM links. */
    val size: Long = 0,
    val lastModified: Long = 0,
)

object GameTitles {
    private val TAGS = Regex("""\s*[(\[][^)\]]*[)\]]""")

    /** `Pokemon - Emerald Version (USA, Europe) [!].gba` → `Pokemon - Emerald Version`. */
    fun clean(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        return base.replace(TAGS, "").trim().ifEmpty { base }
    }
}

/**
 * Converts between SAF document IDs and real paths. Android doesn't promise this mapping,
 * but `com.android.externalstorage.documents` has used `primary:` for internal storage and
 * `XXXX-XXXX:` for SD cards for years, including on Android 13. Path-based emulators (RetroArch,
 * My Boy!) can then still launch games from folders picked with the file picker.
 */
object SafPaths {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** `primary:ROMs/gba/x.gba` → `/storage/emulated/0/ROMs/gba/x.gba`; `1234-ABCD:gba` → `/storage/1234-ABCD/gba`. */
    fun documentIdToPath(documentId: String): String? {
        val volume = documentId.substringBefore(':', "")
        if (volume.isEmpty()) return null
        val relative = documentId.substringAfter(':')
        val root = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "/storage/$volume"
            else -> return null // home:, raw:, msd:, ... can't be mapped reliably.
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /** Best-effort path for a tree URI, for display and path derivation. */
    fun treeUriToPath(treeUri: String): String? {
        val authority = treeUri.removePrefix("content://").substringBefore('/')
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val encodedId = treeUri.substringAfter("/tree/", "").substringBefore('/')
        if (encodedId.isEmpty()) return null
        return documentIdToPath(java.net.URLDecoder.decode(encodedId, "UTF-8"))
    }

    fun isExternalStorage(authority: String?) = authority == EXTERNAL_STORAGE_AUTHORITY
}

