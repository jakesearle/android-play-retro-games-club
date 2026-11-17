package app.retrolauncher.catalogue.am

/**
 * The game being launched. Supply whatever is available:
 * - [path]: a real filesystem path (raw-path ROM folders with all-files access).
 * - [uri]: a `content://` URI (folders picked with the system file picker, or a FileProvider).
 * - [mime]: used for `{file.mime}`. Falls back to `application/octet-stream`.
 * - [tags]: per-game values for `{tags.<name>}`, such as a Steam `app_id`.
 */
data class GameFile(
    val path: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val tags: Map<String, String> = emptyMap(),
)

sealed interface ResolveResult {
    data class Success(val spec: LaunchSpec) : ResolveResult

    /** The game can't be launched with this player, for example a path is needed but only a URI is known. */
    data class Failure(val problems: List<String>) : ResolveResult
}

/** Everything needed to build the launch Intent, with no Android types. */
data class LaunchSpec(
    val packageName: String?,
    val className: String?,
    val action: String?,
    val categories: List<String>,
    val data: String?,
    val mimeType: String?,
    val extras: List<Extra>,
    val flags: Set<LaunchFlag>,
    val rawFlags: Int,
    /** `content://` URIs the emulator needs a read grant for, through the flag plus ClipData. */
    val grantUris: List<String>,
)

sealed interface Extra {
    val key: String

    data class StringValue(override val key: String, val value: String) : Extra
    data class BoolValue(override val key: String, val value: Boolean) : Extra
    data class IntValue(override val key: String, val value: Int) : Extra
    data class LongValue(override val key: String, val value: Long) : Extra
    data class FloatValue(override val key: String, val value: Float) : Extra
    data class UriValue(override val key: String, val value: String) : Extra
    data class StringArrayValue(override val key: String, val value: List<String>) : Extra
    data class IntArrayValue(override val key: String, val value: List<Int>) : Extra
    data class LongArrayValue(override val key: String, val value: List<Long>) : Extra
    data class NullValue(override val key: String) : Extra
}

internal object Placeholders {
    /** `{file.path}`, `{file.uri}`, `{file.mime}`, `{tags.app_id}`, ... */
    val PATTERN = Regex("""\{((?:file|tags)\.[^{}\s]+)\}""")
    const val DEFAULT_MIME = "application/octet-stream"
}

internal object Resolver {

    fun resolve(command: AmCommand, game: GameFile): ResolveResult {
        val problems = linkedSetOf<String>()
        var usedUri = false

        fun fill(value: String): String = Placeholders.PATTERN.replace(value) { match ->
            when (val name = match.groupValues[1]) {
                "file.path" -> game.path ?: run { problems += "needs a file path"; match.value }
                "file.uri" -> {
                    usedUri = true
                    game.uri ?: run { problems += "needs a content:// URI"; match.value }
                }
                "file.mime" -> game.mime ?: Placeholders.DEFAULT_MIME
                else -> {
                    val tag = name.removePrefix("tags.")
                    if (name.startsWith("tags.") && game.tags.containsKey(tag)) {
                        game.tags.getValue(tag)
                    } else {
                        problems += if (name.startsWith("tags.")) "needs game tag \"$tag\"" else "unknown placeholder {$name}"
                        match.value
                    }
                }
            }
        }

        val data = command.data?.let(::fill)
        val mime = command.mimeType?.let(::fill)
        val extras = command.extras.mapNotNull { raw ->
            val before = problems.size
            val value = fill(raw.value)
            // Don't also report "isn't an int" for a placeholder that couldn't be filled in.
            if (problems.size > before) null else convert(raw, value) { problems += "${raw.key}: $it" }
        }

        if (problems.isNotEmpty()) return ResolveResult.Failure(problems.toList())

        val grants = if (usedUri && game.uri != null && game.uri.startsWith("content://")) listOf(game.uri) else emptyList()

        return ResolveResult.Success(
            LaunchSpec(
                packageName = command.packageName,
                className = command.className,
                action = command.action,
                categories = command.categories,
                data = data,
                mimeType = mime,
                extras = extras,
                // `am start` always launches into a new task; CLEAR_TASK needs NEW_TASK to work.
                flags = command.flags + LaunchFlag.NEW_TASK,
                rawFlags = command.rawFlags,
                grantUris = grants,
            )
        )
    }

    private fun convert(raw: RawExtra, value: String, problem: (String) -> Unit): Extra? {
        val key = raw.key
        return when (raw.type) {
            ExtraType.STRING -> Extra.StringValue(key, value)
            ExtraType.URI -> Extra.UriValue(key, value)
            ExtraType.NULL -> Extra.NullValue(key)
            ExtraType.BOOL -> parseBool(value)?.let { Extra.BoolValue(key, it) }
                ?: run { problem("\"$value\" isn't a boolean"); null }
            ExtraType.INT -> value.toIntOrNull()?.let { Extra.IntValue(key, it) }
                ?: run { problem("\"$value\" isn't an int"); null }
            ExtraType.LONG -> value.toLongOrNull()?.let { Extra.LongValue(key, it) }
                ?: run { problem("\"$value\" isn't a long"); null }
            ExtraType.FLOAT -> value.toFloatOrNull()?.let { Extra.FloatValue(key, it) }
                ?: run { problem("\"$value\" isn't a float"); null }
            ExtraType.STRING_ARRAY -> Extra.StringArrayValue(key, splitArray(value))
            ExtraType.INT_ARRAY -> splitArray(value).map { it.trim().toIntOrNull() }
                .takeIf { null !in it }?.let { Extra.IntArrayValue(key, it.filterNotNull()) }
                ?: run { problem("\"$value\" isn't a list of ints"); null }
            ExtraType.LONG_ARRAY -> splitArray(value).map { it.trim().toLongOrNull() }
                .takeIf { null !in it }?.let { Extra.LongArrayValue(key, it.filterNotNull()) }
                ?: run { problem("\"$value\" isn't a list of longs"); null }
        }
    }

    /** Matches `am`: true/t/false/f, or an integer (non-zero means true). */
    private fun parseBool(value: String): Boolean? = when (value.lowercase()) {
        "true", "t" -> true
        "false", "f" -> false
        else -> value.toIntOrNull()?.let { it != 0 }
    }

    /** Comma-separated, as in `am`. `\,` is a literal comma. */
    private fun splitArray(value: String): List<String> =
        value.split(Regex("""(?<!\\),""")).map { it.replace("\\,", ",") }
}
