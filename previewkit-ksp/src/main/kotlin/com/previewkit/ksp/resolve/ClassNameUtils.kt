package com.previewkit.ksp.resolve

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.squareup.kotlinpoet.ClassName

internal fun buildFunctionName(declaration: KSClassDeclaration): String =
    "preview${buildCompoundSimpleName(declaration)}"

internal fun buildCompoundSimpleName(declaration: KSClassDeclaration): String =
    buildNameParts(declaration).joinToString(separator = "")

@Suppress("SpreadOperator")
internal fun buildClassName(declaration: KSClassDeclaration): ClassName {
    val packageName = declaration.packageName.asString()
    val parts = buildNameParts(declaration)
    return ClassName(packageName, parts.first(), *parts.drop(1).toTypedArray())
}

private fun buildNameParts(declaration: KSClassDeclaration): List<String> {
    return buildList {
        var current: KSDeclaration? = declaration
        while (current is KSClassDeclaration) {
            add(0, current.simpleName.asString())
            current = current.parentDeclaration
        }
    }
}

internal fun declarationFqn(declaration: KSDeclaration): String? {
    declaration.qualifiedName?.asString()?.let { return it }
    if (declaration !is KSClassDeclaration) return null
    val parts = buildNameParts(declaration)
    if (parts.isEmpty()) return null
    val packageName = declaration.packageName.asString()
    return when {
        packageName.isEmpty() -> parts.joinToString(separator = ".")
        else -> "$packageName.${parts.joinToString(separator = ".")}"
    }
}
