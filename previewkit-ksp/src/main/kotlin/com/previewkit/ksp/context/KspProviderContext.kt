package com.previewkit.ksp.context

import com.google.devtools.ksp.processing.KSPLogger
import com.previewkit.config.ProviderContext
import com.squareup.kotlinpoet.MemberName

/**
 * Реализация ProviderContext для KSP-процессора.
 * Создаётся один раз на фазу обработки, variantExpr меняется per-вызов через [withVariantExpr].
 */
internal class KspProviderContext(
    override val logger: KSPLogger,
    override val variantExpr: String,
    override val parameterAnnotationFqns: List<String>,
    private val processedModelsWithPackage: Map<String, Pair<String, String>>,
) : ProviderContext {

    override fun factoryFor(typeFqn: String): MemberName? {
        val (pkg, name) = processedModelsWithPackage[typeFqn] ?: return null
        return MemberName(pkg, name)
    }

    internal fun withVariantExpr(expr: String): KspProviderContext =
        KspProviderContext(logger, expr, parameterAnnotationFqns, processedModelsWithPackage)

    internal fun withParameterAnnotations(annotationFqns: List<String>): KspProviderContext =
        KspProviderContext(logger, variantExpr, annotationFqns, processedModelsWithPackage)
}
