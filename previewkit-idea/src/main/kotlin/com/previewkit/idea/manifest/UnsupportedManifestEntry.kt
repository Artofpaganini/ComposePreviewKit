package com.previewkit.idea.manifest

internal data class UnsupportedManifestEntry(
    val kind: UnsupportedManifestKind,
    val sourceFilePath: String,
    val packageName: String,
    val functionName: String,
    val paramName: String,
    val typeFqn: String,
    val reason: String,
)
