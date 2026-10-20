package com.previewkit.ksp.resolve

import com.google.devtools.ksp.symbol.KSClassDeclaration

internal sealed interface ModelToGenerate {
    val declaration: KSClassDeclaration
    val isAnnotated: Boolean

    data class DataClassModel(
        override val declaration: KSClassDeclaration,
        override val isAnnotated: Boolean,
    ) : ModelToGenerate

    data class SealedModel(
        override val declaration: KSClassDeclaration,
        override val isAnnotated: Boolean,
        val concretes: List<KSClassDeclaration>,
    ) : ModelToGenerate

    data class EnumModel(
        override val declaration: KSClassDeclaration,
        override val isAnnotated: Boolean,
    ) : ModelToGenerate

    data class ValueClassModel(
        override val declaration: KSClassDeclaration,
        override val isAnnotated: Boolean,
    ) : ModelToGenerate

    data class ObjectModel(
        override val declaration: KSClassDeclaration,
        override val isAnnotated: Boolean,
    ) : ModelToGenerate
}
