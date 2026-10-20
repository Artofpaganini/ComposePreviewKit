package com.previewkit.ksp.resolve

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

private const val PREVIEW_FQN = "androidx.compose.ui.tooling.preview.Preview"
private const val PREVIEW_SURFACE_FQN = "com.previewkit.annotation.PreviewSurface"
private const val PREVIEW_KIT_ANNOTATIONS_FQN = "com.previewkit.annotation.PreviewKitAnnotations"
private const val MAX_DEPTH = 10

internal const val KSP_ARG_REGISTRY = "previewkit.registry"

internal class MultipreviewCatalog(
    private val logger: KSPLogger,
    private val resolver: Resolver,
) {
    internal fun build(registryFqn: String?): List<PreviewAnnotationRef> {
        val (registryDecl, registryAnn) = resolveRegistry(registryFqn) ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val annotationTypes = registryAnn.arguments
            .find { arg -> arg.name?.asString() == "value" }
            ?.value as? List<KSType> ?: emptyList()

        val refs = annotationTypes.mapNotNull { ksType ->
            val decl = ksType.declaration as? KSClassDeclaration ?: return@mapNotNull null
            buildRef(decl)
        }

        logger.warn("MultipreviewCatalog: catalog=${refs.size} from registry '$registryFqn'")
        return refs
    }

    private fun resolveRegistry(registryFqn: String?): Pair<KSClassDeclaration, KSAnnotation>? {
        return when {
            registryFqn.isNullOrBlank() -> {
                logger.warn("MultipreviewCatalog: ksp-arg '$KSP_ARG_REGISTRY' not set — catalog empty. Create a registry object annotated @PreviewKitAnnotations and pass its FQN.")
                null
            }
            else -> resolveRegistryFrom(registryFqn)
        }
    }

    private fun resolveRegistryFrom(registryFqn: String): Pair<KSClassDeclaration, KSAnnotation>? {
        val registryDecl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(registryFqn))
        if (registryDecl == null) {
            logger.warn("MultipreviewCatalog: registry '$registryFqn' not found (getClassDeclarationByName returned null). Check the FQN in ksp-arg '$KSP_ARG_REGISTRY'.")
            return null
        }
        val registryAnn = registryDecl.annotations.find { ann ->
            ann.annotationType.resolve().declaration.qualifiedName?.asString() == PREVIEW_KIT_ANNOTATIONS_FQN
        }
        if (registryAnn == null) {
            logger.warn("MultipreviewCatalog: registry '$registryFqn' has no @PreviewKitAnnotations annotation.")
            return null
        }
        return registryDecl to registryAnn
    }

    private fun buildRef(decl: KSClassDeclaration): PreviewAnnotationRef? {
        val fqn = decl.qualifiedName?.asString() ?: return null
        val simpleName = decl.simpleName.asString()
        return resolveAnnotationRef(decl, fqn, simpleName)
    }

    private fun resolveAnnotationRef(decl: KSClassDeclaration, fqn: String, simpleName: String): PreviewAnnotationRef? {
        val surfaceAnn = decl.annotations.find { ann ->
            ann.annotationType.resolve().declaration.qualifiedName?.asString() == PREVIEW_SURFACE_FQN
        }
        if (surfaceAnn != null) {
            val boxFqn = surfaceAnn.arguments.find { arg -> arg.name?.asString() == "boxFqn" }?.value as? String ?: ""
            return PreviewAnnotationRef(fqn = fqn, simpleName = simpleName, isSurface = true, boxFqn = boxFqn)
        }
        if (reachesPreview(decl, mutableSetOf(), 0)) {
            return PreviewAnnotationRef(fqn = fqn, simpleName = simpleName, isSurface = false, boxFqn = "")
        }
        logger.warn("MultipreviewCatalog: skipping '$fqn' — not a @PreviewSurface and does not reach @Preview transitively.")
        return null
    }

    private fun reachesPreview(decl: KSClassDeclaration, visited: MutableSet<String>, depth: Int): Boolean {
        val fqn = decl.qualifiedName?.asString()
        if (depth > MAX_DEPTH || fqn == null || !visited.add(fqn)) return false
        return decl.annotations.any { ann -> annotationReachesPreview(ann, visited, depth) }
    }

    private fun annotationReachesPreview(ann: KSAnnotation, visited: MutableSet<String>, depth: Int): Boolean {
        val metaDecl = ann.annotationType.resolve().declaration as? KSClassDeclaration ?: return false
        val metaFqn = metaDecl.qualifiedName?.asString() ?: return false
        return metaFqn == PREVIEW_FQN || reachesPreview(metaDecl, visited, depth + 1)
    }
}
