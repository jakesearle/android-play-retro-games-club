package app.retrolauncher.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.retrolauncher.club.RomFile
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel

/** Opens and inspects game files, whether raw paths or `content://` documents. */
class RomFiles(private val resolver: ContentResolver) {

    /** Prefers the content URI (always readable with the folder grant), else the raw path. */
    fun open(game: Game): RomFile? {
        val uri = game.uri?.let(Uri::parse)
        val path = game.path
        return when {
            uri != null -> UriRom(game.fileName, uri, path?.let { File(it).parentFile?.name })
            path != null && File(path).canRead() -> PathRom(File(path))
            else -> null
        }
    }

    private inner class UriRom(override val name: String, val uri: Uri, override val parentName: String?) : RomFile {
        override fun open(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())

        override fun openChannel(): SeekableByteChannel? {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
            // Closing the stream's channel closes the descriptor too.
            return ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
        }

        /**
         * A file next to this one in a picked folder: for tree URIs whose document IDs are paths
         * (`primary:ROMs/psx/Game.cue`), the sibling's ID is the same folder plus [name].
         */
        override fun sibling(name: String): RomFile? {
            if (!DocumentsContract.isTreeUri(uri)) return null
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            if ('/' !in docId && ':' !in docId) return null
            val folderId = if ('/' in docId) docId.substringBeforeLast('/') else docId.substringBefore(':') + ":"
            val siblingId = if (folderId.endsWith(":")) folderId + name else "$folderId/$name"
            val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            val siblingUri = DocumentsContract.buildDocumentUriUsingTree(tree, siblingId)
            val exists = runCatching { resolver.query(siblingUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } }
                .getOrNull() == true
            return if (exists) UriRom(name, siblingUri, parentName) else null
        }
    }

    private class PathRom(val file: File) : RomFile {
        override val name: String = file.name
        override val parentName: String? = file.parentFile?.name
        override fun open(): InputStream = file.inputStream()
        override fun openChannel(): SeekableByteChannel = RandomAccessFile(file, "r").channel
        override fun sibling(name: String): RomFile? = File(file.parentFile, name).takeIf { it.isFile && it.canRead() }?.let(::PathRom)
    }

    /** Current size and modified time, or null if the file is gone or unreadable. */
    fun stat(game: Game): Pair<Long, Long>? {
        game.uri?.let { uri ->
            try {
                val columns = arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                resolver.query(Uri.parse(uri), columns, null, null, null)?.use { c ->
                    if (c.moveToFirst()) return (if (c.isNull(0)) 0L else c.getLong(0)) to (if (c.isNull(1)) 0L else c.getLong(1))
                }
            } catch (_: Exception) {
            }
        }
        game.path?.let { File(it) }?.takeIf { it.isFile }?.let { return it.length() to it.lastModified() }
        return null
    }

    /** A Game for a single file the user picked with the document picker. */
    fun gameForDocument(uri: Uri, platformId: String): Game? {
        var name: String? = null
        var size = 0L
        var modified = 0L
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        .takeIf { it >= 0 && !c.isNull(it) }?.let { modified = c.getLong(it) }
                }
            }
        } catch (_: Exception) {
            return null
        }
        val fileName = name ?: return null
        val path = if (SafPaths.isExternalStorage(uri.authority)) {
            runCatching { SafPaths.documentIdToPath(DocumentsContract.getDocumentId(uri)) }.getOrNull()
        } else {
            null
        }
        return Game(path ?: uri.toString(), platformId, fileName, GameTitles.clean(fileName), path, uri.toString(), size, modified)
    }
}
