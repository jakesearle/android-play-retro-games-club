package app.retrolauncher.club.patch

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.Inflater

/** A patch file in an archive, before it's read. */
data class PatchEntry(val name: String, val format: PatchFormat)

/**
 * Where a hack's patches are read from: a zip read piece by piece over HTTP, so only the patch
 * that's needed is downloaded, or a whole archive downloaded first (for .7z, or if the server
 * won't send parts of a file).
 */
interface PatchSource : AutoCloseable {
    val entries: List<PatchEntry>
    fun read(entry: PatchEntry): ByteArray
    override fun close() {}
}

/** A downloaded archive (.zip or .7z), deleted when closed. */
class LocalArchive(private val file: File) : PatchSource {
    private val files = RaPatchesRepository.patches(file).associateBy { it.name }
    override val entries = files.values.map { PatchEntry(it.name, it.format) }
    override fun read(entry: PatchEntry): ByteArray = files.getValue(entry.name).bytes
    override fun close() { file.delete() }
}

/**
 * A zip on a web server that sends byte ranges: the central directory (at the end of the file,
 * found through the end-of-central-directory record) lists the files, and each patch's bytes are
 * fetched on their own. [range] fetches bytes [start, end) of the file; a negative start means
 * the last -start bytes. Zip64 archives aren't handled (RAPatches' are far smaller than 4 GB).
 */
class RemoteZip(private val range: (start: Long, end: Long) -> ByteArray) : PatchSource {
    private data class Item(val entry: PatchEntry, val method: Int, val compressedSize: Long, val size: Long, val localOffset: Long)
    private val items: List<Item>
    override val entries: List<PatchEntry>

    init {
        // The end record is the last 22 bytes plus a comment of up to 64 KB.
        val tail = range(-(22L + 65535), 0)
        var eocd = -1
        for (i in tail.size - 22 downTo 0) if (le32(tail, i) == 0x06054b50L) { eocd = i; break }
        if (eocd < 0) throw IOException("Not a zip file")
        val count = le16(tail, eocd + 10)
        val cdSize = le32(tail, eocd + 12)
        val cdOffset = le32(tail, eocd + 16)
        if (cdOffset == 0xFFFFFFFFL || count == 0xFFFF) throw IOException("Zip64 archives aren't supported")
        val cd = range(cdOffset, cdOffset + cdSize)
        val found = mutableListOf<Item>()
        var at = 0
        repeat(count) {
            if (le32(cd, at) != 0x02014b50L) throw IOException("Corrupt zip directory")
            val method = le16(cd, at + 10)
            val compressed = le32(cd, at + 20)
            val size = le32(cd, at + 24)
            val nameLength = le16(cd, at + 28)
            val extraLength = le16(cd, at + 30)
            val commentLength = le16(cd, at + 32)
            val local = le32(cd, at + 42)
            val name = String(cd, at + 46, nameLength, Charsets.UTF_8)
            val format = PatchFormat.of(name)
            if (format != null && !name.endsWith("/")) {
                found += Item(PatchEntry(name.substringAfterLast('/'), format), method, compressed, size, local)
            }
            at += 46 + nameLength + extraLength + commentLength
        }
        items = found
        entries = found.map { it.entry }
    }

    override fun read(entry: PatchEntry): ByteArray {
        val item = items.first { it.entry == entry }
        // The local header has its own name and extra lengths; read it, then the data after it.
        val header = range(item.localOffset, item.localOffset + 30)
        if (le32(header, 0) != 0x04034b50L) throw IOException("Corrupt zip entry")
        val dataStart = item.localOffset + 30 + le16(header, 26) + le16(header, 28)
        val stored = range(dataStart, dataStart + item.compressedSize)
        return when (item.method) {
            0 -> stored
            8 -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(stored)
                    val out = ByteArray(item.size.toInt())
                    var got = 0
                    while (got < out.size && !inflater.finished()) {
                        val n = inflater.inflate(out, got, out.size - got)
                        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        got += n
                    }
                    if (got != out.size) throw IOException("Zip entry is short")
                    out
                } finally {
                    inflater.end()
                }
            }
            else -> throw IOException("Zip compression ${item.method} isn't supported")
        }
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int) = (le16(b, i).toLong() or (le16(b, i + 2).toLong() shl 16))

    companion object {
        /** Tries [file]'s bytes as if served over HTTP (for tests and local archives). */
        fun of(bytes: ByteArray) = RemoteZip { start, end ->
            val s = if (start < 0) maxOf(0, bytes.size + start.toInt()) else start.toInt()
            val e = if (start < 0) bytes.size else minOf(end.toInt(), bytes.size)
            ByteArrayInputStream(bytes, s, e - s).readBytes()
        }
    }
}

/**
 * Orders a hack's patches for trying: those named like a file RetroAchievements supports for
 * it first, then the rest; each group newest version first.
 */
object PatchOrder {
    private fun key(name: String) = name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "")

    fun order(entries: List<PatchEntry>, supportedNames: List<String>): List<PatchEntry> {
        val wanted = supportedNames.map(::key)
        val byNewest = entries.sortedWith(compareByDescending<PatchEntry, String>(NaturalOrder) { it.name })
        val named = byNewest.filter { key(it.name) in wanted }
        return named + (byNewest - named.toSet())
    }
}
