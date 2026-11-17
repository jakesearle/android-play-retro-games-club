package app.retrolauncher.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.retrolauncher.catalogue.Catalogue
import app.retrolauncher.catalogue.model.Platform
import java.io.File

/**
 * Finds games in the user's ROM folders.
 *
 * A source is either a single platform folder (`.../gba`) or a root whose subfolders are
 * platform folders (`.../ROMs/gba`, `.../ROMs/snes`). Folder names are matched to platforms by
 * ID, `folderAliases` and old IDs. Files in a platform folder are kept when the platform's regex
 * accepts them and at least one of its players does. That filters out art, saves and readmes.
 * Subfolders of a platform folder are searched too, up to [MAX_DEPTH] levels.
 */
class LibraryScanner(private val resolver: ContentResolver) {

    private data class Entry(
        val name: String,
        val isDir: Boolean,
        val path: String?,
        val uri: String?,
        val docId: String?,
        val size: Long = 0,
        val modified: Long = 0,
    )

    /** Returns games grouped by platform ID, each list sorted by title. */
    fun scan(sources: List<RomSource>, catalogue: Catalogue): Map<String, List<Game>> {
        val games = LinkedHashMap<String, Game>()
        for (source in sources) {
            try {
                when (source) {
                    is RomSource.RawPath -> scanRaw(File(source.path), catalogue, games)
                    is RomSource.Saf -> scanSaf(Uri.parse(source.treeUri), catalogue, games)
                }
            } catch (e: Exception) {
                // A removed SD card or revoked permission shouldn't break the other folders.
                Log.w(TAG, "Couldn't scan ${source.label}", e)
            }
        }
        return games.values
            .groupBy { it.platformId }
            .mapValues { (_, list) -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }
    }

    private fun scanRaw(root: File, catalogue: Catalogue, out: MutableMap<String, Game>) {
        fun list(dir: File) = dir.listFiles().orEmpty().map {
            Entry(it.name, it.isDirectory, it.absolutePath, null, null, it.length(), it.lastModified())
        }
        scanTree(root.name, list(root), catalogue, out) { entry -> list(File(entry.path!!)) }
    }

    private fun scanSaf(treeUri: Uri, catalogue: Catalogue, out: MutableMap<String, Game>) {
        val external = SafPaths.isExternalStorage(treeUri.authority)
        fun list(docId: String): List<Entry> {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            return resolver.query(children, projection, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        add(
                            Entry(
                                name = name,
                                isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                                path = if (external) SafPaths.documentIdToPath(id) else null,
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                                docId = id,
                                size = if (c.isNull(3)) 0 else c.getLong(3),
                                modified = if (c.isNull(4)) 0 else c.getLong(4),
                            )
                        )
                    }
                }
            }.orEmpty()
        }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootId.substringAfter(':').substringAfterLast('/')
        scanTree(rootName, list(rootId), catalogue, out) { entry -> list(entry.docId!!) }
    }

    private fun scanTree(
        rootName: String,
        rootEntries: List<Entry>,
        catalogue: Catalogue,
        out: MutableMap<String, Game>,
        listDir: (Entry) -> List<Entry>,
    ) {
        val rootPlatform = FolderAliases.platformFor(catalogue, rootName)
        if (rootPlatform != null) {
            addGames(rootPlatform, rootEntries, catalogue, out, listDir, depth = 0)
            return
        }
        for (dir in rootEntries.filter { it.isDir }) {
            val platform = FolderAliases.platformFor(catalogue, dir.name) ?: continue
            addGames(platform, listDir(dir), catalogue, out, listDir, depth = 0)
        }
    }

    /** Adds a platform folder's games, including subfolders (however the player sorts them). */
    private fun addGames(
        platform: Platform,
        entries: List<Entry>,
        catalogue: Catalogue,
        out: MutableMap<String, Game>,
        listDir: (Entry) -> List<Entry>,
        depth: Int,
    ) {
        for (e in entries) {
            if (e.name.startsWith('.')) continue
            if (e.isDir) {
                if (depth < MAX_DEPTH) addGames(platform, listDir(e), catalogue, out, listDir, depth + 1)
                continue
            }
            if (!catalogue.platformAccepts(platform, e.name)) continue
            if (platform.players.isNotEmpty() && platform.players.none { catalogue.playerAccepts(it, e.name) }) continue
            val key = e.path ?: e.uri ?: continue
            out.putIfAbsent(
                key,
                Game(key, platform.id, e.name, GameTitles.clean(e.name), e.path, e.uri, e.size, e.modified),
            )
        }
    }

    private companion object {
        const val TAG = "LibraryScanner"
        const val MAX_DEPTH = 6
    }
}
