package com.previewkit.ksp.resolve

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

private const val ANNOTATION_PREVIEW_MOCK_FQN = "com.previewkit.annotation.PreviewMock"
private const val ANNOTATION_PREVIEW_MOCK_IGNORE_FQN = "com.previewkit.annotation.PreviewMockIgnore"
private const val ANNOTATION_CONSIDER_FQN = "com.previewkit.annotation.ConsiderForPreviewMock"
private const val MAX_TRANSITIVE_DEPTH = 10

internal class ModelCollector(private val logger: KSPLogger) {

    private val seenFqns = mutableSetOf<String>()
    private val result = mutableListOf<ModelToGenerate>()

    internal fun collect(resolver: Resolver, targets: List<WithPreviewTarget>): List<ModelToGenerate> {
        seenFqns.clear()
        result.clear()

        collectFromTargets(targets, resolver)

        collectAnnotated(resolver, ANNOTATION_PREVIEW_MOCK_FQN, isAnnotated = true)
            .forEach { model -> addModel(model) }
        collectAnnotated(resolver, ANNOTATION_CONSIDER_FQN, isAnnotated = false)
            .forEach { model -> addModel(model) }

        return result.toList()
    }

    private fun addModel(model: ModelToGenerate) {
        val fqn = declarationFqn(model.declaration) ?: return
        if (!seenFqns.add(fqn)) return
        result.add(model)
        if (model is ModelToGenerate.SealedModel) {
            for (concrete in model.concretes) {
                addConcrete(concrete, model.isAnnotated)
            }
        }
    }

    private fun addConcrete(concrete: KSClassDeclaration, parentIsAnnotated: Boolean) {
        val fqn = declarationFqn(concrete) ?: return
        if (!seenFqns.add(fqn)) return
        val model = categorize(concrete, parentIsAnnotated) ?: return
        result.add(model)
        if (model is ModelToGenerate.SealedModel) {
            for (nested in model.concretes) {
                addConcrete(nested, parentIsAnnotated)
            }
        }
    }

    private fun collectFromTargets(targets: List<WithPreviewTarget>, resolver: Resolver) {
        for (target in targets) {
            target.function.parameters
                .filter { param -> !param.hasDefault }
                .map { param -> param.type.resolve() }
                .filter { type -> !type.isFunctionType && !type.isSuspendFunctionType }
                .forEach { type -> collectTransitive(type, resolver, depth = 0) }
        }
    }

    private fun collectTransitive(type: KSType, resolver: Resolver, depth: Int) {
        if (depth > MAX_TRANSITIVE_DEPTH) return
        val (decl, model) = resolveCollectible(type) ?: return
        addModel(model)
        traverseModelFields(decl, model, resolver, depth)
        type.arguments.forEach { typeArg ->
            typeArg.type?.resolve()?.let { argType -> collectTransitive(argType, resolver, depth + 1) }
        }
    }

    private fun resolveCollectible(type: KSType): Pair<KSClassDeclaration, ModelToGenerate>? {
        val decl = (type.declaration as? KSClassDeclaration)?.takeIf { d ->
            val fqn = declarationFqn(d)
            fqn != null && !isBuiltinFqn(fqn) && fqn !in seenFqns &&
                d.containingFile != null && !d.hasAnnotationByFqn(ANNOTATION_PREVIEW_MOCK_IGNORE_FQN)
        }
        val model = decl?.let { d -> categorize(d, isAnnotated = false) }
        return if (decl != null && model != null) decl to model else null
    }

    private fun traverseModelFields(
        decl: KSClassDeclaration,
        model: ModelToGenerate,
        resolver: Resolver,
        depth: Int,
    ) {
        when (model) {
            is ModelToGenerate.DataClassModel -> traverseDataClassFields(decl, resolver, depth)
            is ModelToGenerate.SealedModel -> traverseSealedFields(model, resolver, depth)
            is ModelToGenerate.ValueClassModel -> traverseValueClassFields(decl, resolver, depth)
            is ModelToGenerate.EnumModel, is ModelToGenerate.ObjectModel -> Unit
        }
    }

    private fun traverseDataClassFields(decl: KSClassDeclaration, resolver: Resolver, depth: Int) {
        val ctor = decl.primaryConstructor ?: return
        for (param in ctor.parameters) {
            val paramType = param.type.resolve()
            collectTransitive(paramType, resolver, depth + 1)
            collectTypeArgTransitive(paramType, resolver, depth + 1)
        }
    }

    private fun traverseSealedFields(model: ModelToGenerate.SealedModel, resolver: Resolver, depth: Int) {
        for (concrete in model.concretes) {
            traverseSealedConcreteFields(concrete, resolver, depth)
        }
    }

    private fun traverseSealedConcreteFields(concrete: KSClassDeclaration, resolver: Resolver, depth: Int) {
        val ctor = concrete.primaryConstructor ?: return
        for (param in ctor.parameters) {
            val paramType = param.type.resolve()
            collectTransitive(paramType, resolver, depth + 1)
            collectTypeArgTransitive(paramType, resolver, depth + 1)
        }
    }

    private fun traverseValueClassFields(decl: KSClassDeclaration, resolver: Resolver, depth: Int) {
        val ctor = decl.primaryConstructor ?: return
        for (param in ctor.parameters) {
            collectTransitive(param.type.resolve(), resolver, depth + 1)
        }
    }

    private fun collectTypeArgTransitive(paramType: KSType, resolver: Resolver, depth: Int) {
        for (typeArg in paramType.arguments) {
            val argType = typeArg.type?.resolve() ?: continue
            collectTransitive(argType, resolver, depth)
        }
    }

    private fun collectAnnotated(
        resolver: Resolver,
        annotationFqn: String,
        isAnnotated: Boolean,
    ): List<ModelToGenerate> {
        return resolver.getSymbolsWithAnnotation(annotationFqn)
            .filterIsInstance<KSClassDeclaration>()
            .filter { declaration -> !declaration.hasAnnotationByFqn(ANNOTATION_PREVIEW_MOCK_IGNORE_FQN) }
            .mapNotNull { declaration -> categorize(declaration, isAnnotated) }
            .toList()
    }

    private fun categorize(
        declaration: KSClassDeclaration,
        isAnnotated: Boolean,
    ): ModelToGenerate? {
        return when {
            Modifier.DATA in declaration.modifiers && declaration.classKind == ClassKind.CLASS ->
                ModelToGenerate.DataClassModel(declaration, isAnnotated)

            declaration.classKind == ClassKind.ENUM_CLASS ->
                ModelToGenerate.EnumModel(declaration, isAnnotated)

            Modifier.VALUE in declaration.modifiers && declaration.classKind == ClassKind.CLASS ->
                ModelToGenerate.ValueClassModel(declaration, isAnnotated)

            declaration.classKind == ClassKind.OBJECT ->
                ModelToGenerate.ObjectModel(declaration, isAnnotated)

            Modifier.SEALED in declaration.modifiers ->
                ModelToGenerate.SealedModel(declaration, isAnnotated, findConcreteSubclasses(declaration))

            else -> {
                logger.warn(
                    "PreviewKit: cannot categorize '${declaration.simpleName.asString()}' " +
                        "(kind=${declaration.classKind}), skipping",
                )
                null
            }
        }
    }

    private fun isBuiltinFqn(fqn: String): Boolean {
        return fqn.startsWith("kotlin.") ||
            fqn.startsWith("java.") ||
            fqn.startsWith("javax.") ||
            fqn.startsWith("android.") ||
            fqn.startsWith("androidx.") ||
            fqn.startsWith("com.google.") ||
            fqn.startsWith("org.jetbrains.")
    }
}

internal fun KSClassDeclaration.hasAnnotationByFqn(fqn: String): Boolean {
    return annotations.any { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == fqn
    }
}
