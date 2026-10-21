package com.previewkit.idea.util

import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

internal object WithPreviewMatcher {

    const val ANNOTATION_FQN: String = "com.previewkit.annotation.WithPreview"
    private const val ANNOTATION_SHORT_NAME: String = "WithPreview"

    const val COMPOSABLE_FQN: String = "androidx.compose.runtime.Composable"
    private const val COMPOSABLE_SHORT_NAME: String = "Composable"

    fun annotationShortNames(file: KtFile): Set<String> =
        usableShortNames(file, ANNOTATION_FQN, ANNOTATION_SHORT_NAME)

    fun composableShortNames(file: KtFile): Set<String> =
        usableShortNames(file, COMPOSABLE_FQN, COMPOSABLE_SHORT_NAME)

    fun annotationEntry(function: KtNamedFunction, shortNames: Set<String>): KtAnnotationEntry? {
        return function.annotationEntries.firstOrNull { entry ->
            val referenceText = entry.typeReference?.text ?: return@firstOrNull false
            referenceText == ANNOTATION_FQN || referenceText in shortNames
        }
    }

    fun isAnnotated(function: KtNamedFunction, shortNames: Set<String>): Boolean {
        return annotationEntry(function, shortNames) != null
    }

    private fun usableShortNames(file: KtFile, fqn: String, shortName: String): Set<String> {
        val names = mutableSetOf(shortName)
        var shadowedByImport = false
        val imports = file.importDirectives.toList()
        for (import in imports) {
            val importedFqn = import.importedFqName?.asString() ?: continue
            val alias = import.aliasName
            if (alias != null) {
                if (importedFqn == fqn) names += alias
                continue
            }
            if (importedFqn == fqn) continue
            if (importedFqn.substringAfterLast('.') == shortName) shadowedByImport = true
        }
        if (shadowedByImport) names -= shortName
        return names
    }
}
