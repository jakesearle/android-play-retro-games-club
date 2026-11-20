package app.retrolauncher.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException

/**
 * Saves a new ROM (a patched romhack) next to another one: in that folder's `Hacks` subfolder
 * if it has one (any capitalisation), otherwise beside it. Works for folders picked with the
 * system picker (document URIs, which need the write grant they were picked with) and raw paths.
 */
class RomWriter(private val resolver: ContentResolver) {
    /** The saved ROM, and whether it went into a Hacks folder. */
    data class Saved(val game: Game, val inHacks: Boolean)

    @Throws(IOException::class)
    fun saveBeside(base: Game, name: String, content: File): Saved {
        base.uri?.let { return saveWithDocuments(base, Uri.parse(it), name, content) }
        val path = base.path ?: throw IOException("Don't know where ${base.fileName} is")
        val folder = File(path).parentFile ?: throw IOException("No folder for ${base.fileName}")
        val hacks = folder.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(HACKS, ignoreCase = true) }
        val dest = File(hacks ?: folder, name)
        content.copyTo(dest, overwrite = true)
        return Saved(Game(dest.path, base.platformId, name, GameTitles.clean(name), dest.path, null, dest.length(), dest.lastModified()), hacks != null)
    }

    private fun saveWithDocuments(base: Game, uri: Uri, name: String, content: File): Saved {
        if (!DocumentsContract.isTreeUri(uri)) throw IOException("Can't write next to ${base.fileName}")
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
        val docId = DocumentsContract.getDocumentId(uri)
        val parentId = if ('/' in docId) docId.substringBeforeLast('/') else docId.substringBefore(':') + ":"
        val hacksId = children(tree, parentId).firstOrNull { it.dir && it.name.equals(HACKS, ignoreCase = true) }?.id
        val folderId = hacksId ?: parentId
        // Replace a file of the same name rather than making "name (1)".
        val existing = children(tree, folderId).firstOrNull { !it.dir && it.name == name }?.id
        val dest = existing?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, folderId), "application/octet-stream", name)
            ?: throw IOException("Couldn't create $name")
        (resolver.openOutputStream(dest, "wt") ?: throw IOException("Couldn't write $name")).use { out -> content.inputStream().use { it.copyTo(out) } }
        val newId = DocumentsContract.getDocumentId(dest)
        val path = if (SafPaths.isExternalStorage(dest.authority)) SafPaths.documentIdToPath(newId) else null
        val (size, modified) = stat(dest) ?: (content.length() to System.currentTimeMillis())
        return Saved(Game(path ?: dest.toString(), base.platformId, name, GameTitles.clean(name), path, dest.toString(), size, modified), hacksId != null)
    }

    private data class Child(val id: String, val name: String, val dir: Boolean)

    private fun children(tree: Uri, parentId: String): List<Child> {
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), columns, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Child(c.getString(0), c.getString(1) ?: "", c.getString(2) == Document.MIME_TYPE_DIR)) }
        }.orEmpty()
    }

    private fun stat(uri: Uri): Pair<Long, Long>? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
        }
    }.getOrNull()

    private companion object {
        const val HACKS = "Hacks"
    }
}
