package app.retrolauncher.catalogue.am

/**
 * Parses a player's `amStartArguments` (one `am start` switch per line) into an [AmCommand].
 *
 * This is pure Kotlin with no Android dependencies, so it can be unit-tested on the JVM.
 * Placeholders such as `{file.path}` are kept as written. [AmCommand.resolve] fills them in for
 * a specific game, and [app.retrolauncher.catalogue.android.toIntent] then builds the Intent.
 *
 * Some catalogue entries are hand-written raw `am` lines, so unknown or malformed switches are
 * reported in [AmCommand.warnings] and skipped instead of throwing.
 */
object AmStartArguments {

    fun parse(arguments: String): AmCommand {
        var packageName: String? = null
        var className: String? = null
        var action: String? = null
        val categories = mutableListOf<String>()
        var data: String? = null
        var mimeType: String? = null
        val extras = mutableListOf<RawExtra>()
        val flags = linkedSetOf<LaunchFlag>()
        var rawFlags = 0
        val warnings = mutableListOf<String>()

        for ((index, rawLine) in arguments.lines().withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val where = "line ${index + 1} \"$line\""

            // Split only at the first two spaces: "-e KEY value with spaces".
            val parts = line.split(' ', limit = 3)
            val switch = parts[0]
            val first = parts.getOrNull(1)
            val firstAndRest = line.substringAfter(' ', missingDelimiterValue = "").takeIf { ' ' in line }

            fun requireValue(): String? {
                if (firstAndRest.isNullOrEmpty()) warnings += "$where: $switch needs a value"
                return firstAndRest?.takeIf { it.isNotEmpty() }
            }

            val extraType = EXTRA_SWITCHES[switch]
            val flag = LaunchFlag.bySwitch[switch]
            when {
                switch == "-n" -> requireValue()?.let { value ->
                    val pkg = value.substringBefore('/', "")
                    val cls = value.substringAfter('/', "")
                    if (pkg.isEmpty() || cls.isEmpty()) {
                        warnings += "$where: -n expects package/activity"
                    } else {
                        packageName = pkg
                        // A leading '.' means the class name is relative to the package.
                        className = if (cls.startsWith('.')) pkg + cls else cls
                    }
                }

                switch == "-a" -> requireValue()?.let { action = it }
                switch == "-c" -> requireValue()?.let { categories += it }
                switch == "-d" -> requireValue()?.let { data = it }
                switch == "-t" -> requireValue()?.let { mimeType = it }

                switch == "-f" -> requireValue()?.let { value ->
                    val parsed = if (value.startsWith("0x", ignoreCase = true)) {
                        value.substring(2).toLongOrNull(16)?.toInt()
                    } else {
                        value.toIntOrNull()
                    }
                    if (parsed == null) warnings += "$where: -f expects an integer" else rawFlags = rawFlags or parsed
                }

                switch == "--esn" -> {
                    if (first.isNullOrEmpty()) warnings += "$where: --esn needs a key"
                    else extras += RawExtra(ExtraType.NULL, first, "")
                }

                extraType != null -> {
                    if (first.isNullOrEmpty()) {
                        warnings += "$where: $switch needs a key"
                    } else {
                        // The value may be empty ("-e KEY") and may contain spaces.
                        val value = parts.getOrNull(2).orEmpty()
                        if (value.isEmpty() && extraType != ExtraType.STRING) {
                            warnings += "$where: $switch $first has no value"
                        } else {
                            extras += RawExtra(extraType, first, value)
                        }
                    }
                }

                flag != null -> {
                    if (first != null) warnings += "$where: $switch takes no value; ignoring \"$firstAndRest\""
                    flags += flag
                }

                else -> warnings += "$where: unknown switch $switch, skipped"
            }
        }

        return AmCommand(
            packageName = packageName,
            className = className,
            action = action,
            categories = categories,
            data = data,
            mimeType = mimeType,
            extras = extras,
            flags = flags,
            rawFlags = rawFlags,
            warnings = warnings,
        )
    }

    private val EXTRA_SWITCHES = mapOf(
        "-e" to ExtraType.STRING,
        "--es" to ExtraType.STRING,
        "--ez" to ExtraType.BOOL,
        "--ei" to ExtraType.INT,
        "--el" to ExtraType.LONG,
        "--ef" to ExtraType.FLOAT,
        "--eu" to ExtraType.URI,
        "--esa" to ExtraType.STRING_ARRAY,
        "--eia" to ExtraType.INT_ARRAY,
        "--ela" to ExtraType.LONG_ARRAY,
    )
}

enum class ExtraType { STRING, BOOL, INT, LONG, FLOAT, URI, STRING_ARRAY, INT_ARRAY, LONG_ARRAY, NULL }

/** An extra as written, possibly still containing placeholders. */
data class RawExtra(val type: ExtraType, val key: String, val value: String)

/**
 * Intent flags from `--activity-*` switches. This module has no Android dependency, so it
 * uses its own enum; [app.retrolauncher.catalogue.android.toIntent] maps it to `Intent.FLAG_*`.
 */
enum class LaunchFlag(val switch: String) {
    NEW_TASK("--activity-new-task"),
    CLEAR_TASK("--activity-clear-task"),
    CLEAR_TOP("--activity-clear-top"),
    SINGLE_TOP("--activity-single-top"),
    NO_HISTORY("--activity-no-history"),
    REORDER_TO_FRONT("--activity-reorder-to-front"),
    MULTIPLE_TASK("--activity-multiple-task"),
    EXCLUDE_FROM_RECENTS("--activity-exclude-from-recents"),
    NO_ANIMATION("--activity-no-animation"),
    ;

    companion object {
        val bySwitch: Map<String, LaunchFlag> = entries.associateBy { it.switch }
    }
}

/** A parsed `am start` command, with placeholders not yet filled in. */
data class AmCommand(
    val packageName: String?,
    val className: String?,
    val action: String?,
    val categories: List<String>,
    val data: String?,
    val mimeType: String?,
    val extras: List<RawExtra>,
    val flags: Set<LaunchFlag>,
    val rawFlags: Int,
    /** Problems found while parsing. Log them; the command is still usable. */
    val warnings: List<String>,
) {
    private val allValues: List<String>
        get() = listOfNotNull(data, mimeType) + extras.map { it.value }

    /** Every placeholder used, such as `file.path` or `tags.app_id`. */
    val placeholders: Set<String> by lazy {
        allValues.flatMap { v -> Placeholders.PATTERN.findAll(v).map { it.groupValues[1] } }.toSet()
    }

    /** Needs a real filesystem path (RetroArch, My Boy!, ...). */
    val needsFilePath: Boolean get() = "file.path" in placeholders

    /** Needs a `content://` URI with a read grant (Pizza Boy, Azahar, ...). */
    val needsFileUri: Boolean get() = "file.uri" in placeholders

    /** Per-game tag names this command needs, such as `app_id`. */
    val tagNames: Set<String>
        get() = placeholders.filter { it.startsWith("tags.") }.map { it.removePrefix("tags.") }.toSet()

    /** Fills in placeholders for [game] and converts typed extras. */
    fun resolve(game: GameFile): ResolveResult = Resolver.resolve(this, game)
}
