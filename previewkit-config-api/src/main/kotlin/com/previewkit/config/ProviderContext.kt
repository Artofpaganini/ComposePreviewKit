package com.previewkit.config

import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.MemberName

/**
 * Контекст, передаваемый провайдерам при разрешении аргументов и мок-значений.
 *
 * Даёт доступ к логгеру KSP, текущему выражению варианта и фабрике по FQN типа.
 */
public interface ProviderContext {
    public val logger: KSPLogger
    public val variantExpr: String
    public val parameterAnnotationFqns: List<String>
    public fun factoryFor(typeFqn: String): MemberName?
}
