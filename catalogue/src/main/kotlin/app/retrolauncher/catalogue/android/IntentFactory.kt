package app.retrolauncher.catalogue.android

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.retrolauncher.catalogue.am.Extra
import app.retrolauncher.catalogue.am.LaunchFlag
import app.retrolauncher.catalogue.am.LaunchSpec

/**
 * Builds the launch Intent. This is the only place that touches Android Intent APIs; all the
 * logic is in the pure-Kotlin parser and resolver.
 *
 * @param grantWrite also grant write access to `content://` URIs. Some emulators write saves
 *   next to the ROM.
 */
fun LaunchSpec.toIntent(grantWrite: Boolean = false): Intent {
    val intent = Intent()
    action?.let { intent.action = it }
    if (packageName != null && className != null) intent.setClassName(packageName, className)
    else if (packageName != null) intent.setPackage(packageName)
    categories.forEach(intent::addCategory)

    // setData() clears the type and setType() clears the data, so set them together.
    val dataUri = data?.let(::toUri)
    when {
        dataUri != null && mimeType != null -> intent.setDataAndType(dataUri, mimeType)
        dataUri != null -> intent.data = dataUri
        mimeType != null -> intent.type = mimeType
    }

    for (extra in extras) {
        when (extra) {
            is Extra.StringValue -> intent.putExtra(extra.key, extra.value)
            is Extra.BoolValue -> intent.putExtra(extra.key, extra.value)
            is Extra.IntValue -> intent.putExtra(extra.key, extra.value)
            is Extra.LongValue -> intent.putExtra(extra.key, extra.value)
            is Extra.FloatValue -> intent.putExtra(extra.key, extra.value)
            is Extra.UriValue -> intent.putExtra(extra.key, toUri(extra.value))
            is Extra.StringArrayValue -> intent.putExtra(extra.key, extra.value.toTypedArray())
            is Extra.IntArrayValue -> intent.putExtra(extra.key, extra.value.toIntArray())
            is Extra.LongArrayValue -> intent.putExtra(extra.key, extra.value.toLongArray())
            is Extra.NullValue -> intent.putExtra(extra.key, null as String?)
        }
    }

    var flagBits = rawFlags
    for (flag in flags) flagBits = flagBits or flag.intentFlag
    intent.addFlags(flagBits)

    if (grantUris.isNotEmpty()) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (grantWrite) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // The grant flags only cover URIs in data or ClipData, not URIs passed as string extras
        // (such as Pizza Boy's rom_uri), so put every URI into ClipData as well.
        val uris = grantUris.map(Uri::parse)
        val clip = ClipData.newRawUri("rom", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
    }
    return intent
}

/**
 * A plain path such as `/storage/emulated/0/ROMs/Game #1.gba` would be mangled by
 * `Uri.parse` (the `#` starts a fragment), so build a scheme-less path URI. `getPath()` then
 * returns the original string, which is what path-based emulators such as My Boy! read.
 */
private fun toUri(value: String): Uri =
    if (value.startsWith("/")) Uri.Builder().path(value).build() else Uri.parse(value)

private val LaunchFlag.intentFlag: Int
    get() = when (this) {
        LaunchFlag.NEW_TASK -> Intent.FLAG_ACTIVITY_NEW_TASK
        LaunchFlag.CLEAR_TASK -> Intent.FLAG_ACTIVITY_CLEAR_TASK
        LaunchFlag.CLEAR_TOP -> Intent.FLAG_ACTIVITY_CLEAR_TOP
        LaunchFlag.SINGLE_TOP -> Intent.FLAG_ACTIVITY_SINGLE_TOP
        LaunchFlag.NO_HISTORY -> Intent.FLAG_ACTIVITY_NO_HISTORY
        LaunchFlag.REORDER_TO_FRONT -> Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        LaunchFlag.MULTIPLE_TASK -> Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        LaunchFlag.EXCLUDE_FROM_RECENTS -> Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        LaunchFlag.NO_ANIMATION -> Intent.FLAG_ACTIVITY_NO_ANIMATION
    }

/**
 * Checks whether emulator packages are installed. This needs the `<queries>` entries in the app
 * manifest on Android 11+. Results are cached; call [refresh] after an app is installed or removed.
 */
class InstalledPackages(private val packageManager: PackageManager) {
    private val cache = mutableMapOf<String, Boolean>()

    @Synchronized
    fun isInstalled(packageName: String): Boolean = cache.getOrPut(packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    @Synchronized
    fun refresh() = cache.clear()
}
