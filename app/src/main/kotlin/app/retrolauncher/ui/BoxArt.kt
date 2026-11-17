package app.retrolauncher.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Box art from retroachievements.org, cached on disk by its file name (`/Images/<id>.png`
 * is stable), so it's downloaded once and shown offline afterwards.
 */
@Composable
fun rememberBoxArt(url: String): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) { load(File(context.cacheDir, "images"), url) }
    }
}

private fun load(dir: File, url: String): ImageBitmap? {
    val name = url.substringAfterLast('/')
    if (url.isEmpty() || name.isEmpty() || ".." in name || name.length > 64) return null
    val file = File(dir, name)
    if (!file.exists() || file.length() == 0L) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (c.responseCode !in 200..299) return null
                val bytes = c.inputStream.use { it.readBytes() }
                if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return null
                dir.mkdirs()
                val tmp = File(dir, "$name.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(file)
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            return null
        }
    }
    return BitmapFactory.decodeFile(file.path)?.asImageBitmap()
}
