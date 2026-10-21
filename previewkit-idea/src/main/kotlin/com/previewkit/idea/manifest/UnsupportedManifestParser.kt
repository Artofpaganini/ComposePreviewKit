package com.previewkit.idea.manifest

internal object UnsupportedManifestParser {

    private const val HEADER: String = "previewkit-unsupported-v1"
    private const val FIELD_COUNT: Int = 7
    private const val FIELD_SEPARATOR: Char = '\t'
    private const val PERCENT_CHAR: Char = '%'
    private const val ESCAPE_LENGTH: Int = 3

    private const val KIND_INDEX: Int = 0
    private const val SOURCE_FILE_PATH_INDEX: Int = 1
    private const val PACKAGE_NAME_INDEX: Int = 2
    private const val FUNCTION_NAME_INDEX: Int = 3
    private const val PARAM_NAME_INDEX: Int = 4
    private const val TYPE_FQN_INDEX: Int = 5
    private const val REASON_INDEX: Int = 6

    private const val RAW_KIND_UNSUPPORTED_PARAMETER: String = "unsupported_parameter"
    private const val RAW_KIND_PRIVATE_OR_LOCAL_FUNCTION: String = "private_or_local_function"

    private val ESCAPED_CHARS: Map<String, Char> = mapOf(
        "25" to '%',
        "09" to '\t',
        "0A" to '\n',
        "0D" to '\r',
    )

    fun parse(content: String): List<UnsupportedManifestEntry> {
        val lines = content.lines()
        val header = lines.firstOrNull() ?: return emptyList()
        if (header != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { line -> parseRecord(line) }
    }

    private fun parseRecord(line: String): UnsupportedManifestEntry? {
        if (line.isBlank()) return null
        val fields = line.split(FIELD_SEPARATOR, limit = FIELD_COUNT)
        if (fields.size != FIELD_COUNT) return null
        val lastField = fields[REASON_INDEX]
        if (lastField.contains(FIELD_SEPARATOR)) return null
        val kind = kindOf(percentDecode(fields[KIND_INDEX])) ?: return null
        return UnsupportedManifestEntry(
            kind = kind,
            sourceFilePath = percentDecode(fields[SOURCE_FILE_PATH_INDEX]),
            packageName = percentDecode(fields[PACKAGE_NAME_INDEX]),
            functionName = percentDecode(fields[FUNCTION_NAME_INDEX]),
            paramName = percentDecode(fields[PARAM_NAME_INDEX]),
            typeFqn = percentDecode(fields[TYPE_FQN_INDEX]),
            reason = percentDecode(lastField),
        )
    }

    private fun kindOf(rawKind: String): UnsupportedManifestKind? {
        return when (rawKind) {
            RAW_KIND_UNSUPPORTED_PARAMETER -> UnsupportedManifestKind.UNSUPPORTED_PARAMETER
            RAW_KIND_PRIVATE_OR_LOCAL_FUNCTION -> UnsupportedManifestKind.PRIVATE_OR_LOCAL_FUNCTION
            else -> null
        }
    }

    private fun percentDecode(value: String): String {
        if (PERCENT_CHAR !in value) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current != PERCENT_CHAR || index + ESCAPE_LENGTH > value.length) {
                builder.append(current)
                index += 1
                continue
            }
            val decoded = ESCAPED_CHARS[value.substring(index + 1, index + ESCAPE_LENGTH)]
            if (decoded == null) {
                builder.append(current)
                index += 1
            } else {
                builder.append(decoded)
                index += ESCAPE_LENGTH
            }
        }
        return builder.toString()
    }
}
