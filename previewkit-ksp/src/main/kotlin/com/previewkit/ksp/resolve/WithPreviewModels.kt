package com.previewkit.ksp.resolve

import com.google.devtools.ksp.symbol.KSFunctionDeclaration

internal data class WithPreviewTarget(
    val function: KSFunctionDeclaration,
    val variant: Int,
)

