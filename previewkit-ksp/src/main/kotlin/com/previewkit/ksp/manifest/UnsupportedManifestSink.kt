package com.previewkit.ksp.manifest

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

private const val MANIFEST_PACKAGE_NAME = "previewkit.manifest"
private const val MANIFEST_FILE_NAME = "unsupported-parameters"
private const val MANIFEST_EXTENSION = "txt"
private const val MANIFEST_HEADER = "previewkit-unsupported-v1"

private const val KIND_UNSUPPORTED_PARAMETER = "unsupported_parameter"
private const val KIND_PRIVATE_OR_LOCAL_FUNCTION = "private_or_local_function"

private const val FIELD_SEPARATOR = '\t'
private const val LINE_SEPARATOR = '\n'
private const val PERCENT_CHAR = '%'
private const val TAB_CHAR = '\t'
private const val LF_CHAR = '\n'
private const val CR_CHAR = '\r'

private const val ENCODED_PERCENT = "%25"
private const val ENCODED_TAB = "%09"
private const val ENCODED_LF = "%0A"
private const val ENCODED_CR = "%0D"

internal data class UnsupportedManifestRecord(
    val kind: String,
    val sourceFilePath: String,
    val packageName: String,
    val functionName: String,
    val paramName: String,
    val typeFqn: String,
    val reason: String,
)

internal class UnsupportedManifestSink {

    private val records = mutableListOf<UnsupportedManifestRecord>()

    internal fun recordUnsupportedParameter(
        sourceFilePath: String,
        packageName: String,
        functionName: String,
        paramName: String,
        typeFqn: String,
        reason: String,
    ) {
        records += UnsupportedManifestRecord(
            kind = KIND_UNSUPPORTED_PARAMETER,
            sourceFilePath = sourceFilePath,
            packageName = packageName,
            functionName = functionName,
            paramName = paramName,
            typeFqn = typeFqn,
            reason = reason,
        )
    }

    internal fun recordPrivateOrLocalFunction(
        sourceFilePath: String,
        packageName: String,
        functionName: String,
        reason: String,
    ) {
        records += UnsupportedManifestRecord(
            kind = KIND_PRIVATE_OR_LOCAL_FUNCTION,
            sourceFilePath = sourceFilePath,
            packageName = packageName,
            functionName = functionName,
            paramName = "",
            typeFqn = "",
            reason = reason,
        )
    }

    internal fun writeTo(codeGenerator: CodeGenerator) {
        val outputStream = codeGenerator.createNewFile(
            dependencies = Dependencies.ALL_FILES,
            packageName = MANIFEST_PACKAGE_NAME,
            fileName = MANIFEST_FILE_NAME,
            extensionName = MANIFEST_EXTENSION,
        )
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(MANIFEST_HEADER).append(LINE_SEPARATOR)
            for (record in records) {
                writer.append(encodeRecordLine(record)).append(LINE_SEPARATOR)
            }
        }
    }
}

private fun encodeRecordLine(record: UnsupportedManifestRecord): String {
    val fields = listOf(
        record.kind,
        record.sourceFilePath,
        record.packageName,
        record.functionName,
        record.paramName,
        record.typeFqn,
        record.reason,
    )
    return fields.joinToString(separator = FIELD_SEPARATOR.toString()) { field -> percentEncode(field) }
}

private fun percentEncode(value: String): String {
    val builder = StringBuilder(value.length)
    for (char in value) {
        when (char) {
            PERCENT_CHAR -> builder.append(ENCODED_PERCENT)
            TAB_CHAR -> builder.append(ENCODED_TAB)
            LF_CHAR -> builder.append(ENCODED_LF)
            CR_CHAR -> builder.append(ENCODED_CR)
            else -> builder.append(char)
        }
    }
    return builder.toString()
}
