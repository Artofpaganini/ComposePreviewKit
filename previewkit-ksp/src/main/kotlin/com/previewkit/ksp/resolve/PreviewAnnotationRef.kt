package com.previewkit.ksp.resolve

internal data class PreviewAnnotationRef(
    val fqn: String,
    val simpleName: String,
    val isSurface: Boolean,
    val boxFqn: String,
)
