package com.previewkit.ksp.resolve

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.previewkit.ksp.manifest.UnsupportedManifestSink

internal const val WITH_PREVIEW_FQN = "com.previewkit.annotation.WithPreview"

private const val REASON_PRIVATE_FUNCTION = "private function"
private const val REASON_LOCAL_FUNCTION = "local function"

/**
 * Собирает функции, помеченные @WithPreview, и читает параметры аннотации.
 */
internal class WithPreviewCollector(
    private val logger: KSPLogger,
    private val manifestSink: UnsupportedManifestSink,
) {

    internal fun collect(resolver: Resolver): List<WithPreviewTarget> {
        return resolver.getSymbolsWithAnnotation(WITH_PREVIEW_FQN)
            .filterIsInstance<KSFunctionDeclaration>()
            .mapNotNull { function -> buildTarget(function) }
            .toList()
    }

    private fun buildTarget(function: KSFunctionDeclaration): WithPreviewTarget? {
        val simpleName = function.simpleName.asString()

        val visibility = function.getVisibility()
        if (visibility == Visibility.PRIVATE || visibility == Visibility.LOCAL) {
            val reason = if (visibility == Visibility.PRIVATE) REASON_PRIVATE_FUNCTION else REASON_LOCAL_FUNCTION
            manifestSink.recordPrivateOrLocalFunction(
                sourceFilePath = function.containingFile?.filePath.orEmpty(),
                packageName = function.packageName.asString(),
                functionName = simpleName,
                reason = reason,
            )
            logger.warn("WithPreview: skipping '$simpleName' — private/local functions cannot generate wrappers")
            return null
        }

        val annotation = function.annotations.find { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == WITH_PREVIEW_FQN
        } ?: return null

        return WithPreviewTarget(
            function = function,
            variant = readVariant(annotation),
        )
    }

    private fun readVariant(annotation: KSAnnotation): Int {
        val arg = annotation.arguments.find { arg -> arg.name?.asString() == "variant" }
        return (arg?.value as? Int) ?: 0
    }
}
