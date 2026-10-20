package com.previewkit.ksp.resolve

import com.google.devtools.ksp.processing.KSPLogger
import com.previewkit.config.ProviderContext
import com.previewkit.config.TypeMockProvider
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import java.util.ServiceLoader

private const val FQN_COLOR = "androidx.compose.ui.graphics.Color"
private const val FQN_DP = "androidx.compose.ui.unit.Dp"
private const val FQN_TEXT_UNIT = "androidx.compose.ui.unit.TextUnit"
private const val FQN_SHAPE = "androidx.compose.ui.graphics.Shape"
private const val FQN_BRUSH = "androidx.compose.ui.graphics.Brush"
private const val KSP_ARG_FACTORIES = "previewMock.factories"
private const val FACTORY_ENTRY_SEPARATOR = ","
private const val FACTORY_KV_SEPARATOR = ":"
private const val PREVIEW_SIZE_VALUE = 16

/**
 * Источник override-значений для конкретных типов при построении аргументов.
 *
 * Цепочка (chain-of-responsibility):
 * 1. SPI TypeMockProvider (зарегистрированные проектом через ServiceLoader)
 * 2. Встроенные Compose-типы (Color, Dp, TextUnit, Shape, Brush)
 * 3. ksp-arg фабрики (escape hatch через previewMock.factories)
 */
internal class TypeOverrideSource(
    kspArgs: Map<String, String>,
    private val logger: KSPLogger,
) {
    private val typeMockProviders: List<TypeMockProvider> =
        ServiceLoader.load(TypeMockProvider::class.java, TypeMockProvider::class.java.classLoader).toList()

    private val kspArgFactories: Map<String, CodeBlock> = parseKspArgFactories(kspArgs)

    internal fun buildForType(typeFqn: String, context: ProviderContext): CodeBlock? {
        for (provider in typeMockProviders) {
            val result = provider.mockCall(typeFqn, context)
            if (result != null) return result
        }

        val builtin = buildBuiltInCompose(typeFqn)
        if (builtin != null) return builtin

        return kspArgFactories[typeFqn]
    }

    private fun buildBuiltInCompose(fqn: String): CodeBlock? = when (fqn) {
        FQN_COLOR -> CodeBlock.of(
            "%T.Magenta",
            ClassName("androidx.compose.ui.graphics", "Color"),
        )
        FQN_DP -> CodeBlock.of(
            "%L.%M",
            PREVIEW_SIZE_VALUE,
            MemberName("androidx.compose.ui.unit", "dp"),
        )
        FQN_TEXT_UNIT -> CodeBlock.of(
            "%L.%M",
            PREVIEW_SIZE_VALUE,
            MemberName("androidx.compose.ui.unit", "sp"),
        )
        FQN_SHAPE -> CodeBlock.of(
            "%T",
            ClassName("androidx.compose.ui.graphics", "RectangleShape"),
        )
        FQN_BRUSH -> CodeBlock.of(
            "%T(%T.Magenta)",
            ClassName("androidx.compose.ui.graphics", "SolidColor"),
            ClassName("androidx.compose.ui.graphics", "Color"),
        )
        else -> null
    }

    private fun parseKspArgFactories(kspArgs: Map<String, String>): Map<String, CodeBlock> {
        val arg = kspArgs[KSP_ARG_FACTORIES] ?: return emptyMap()
        return arg.split(FACTORY_ENTRY_SEPARATOR)
            .mapNotNull { entry -> parseFactoryEntry(entry.trim()) }
            .toMap()
    }

    private fun parseFactoryEntry(entry: String): Pair<String, CodeBlock>? {
        val parts = entry.split(FACTORY_KV_SEPARATOR)
        if (parts.size != 2) {
            logger.warn("PreviewKit: invalid factory entry '$entry', expected TypeFQN:FactoryFQN")
            return null
        }
        val typeFqn = parts[0].trim()
        val factoryFqn = parts[1].trim()
        val lastDot = factoryFqn.lastIndexOf('.')
        if (lastDot < 0) {
            logger.warn("PreviewKit: factory FQN '$factoryFqn' must be fully qualified (package.name)")
            return null
        }
        val factoryPackage = factoryFqn.substring(0, lastDot)
        val factoryName = factoryFqn.substring(lastDot + 1)
        val code = CodeBlock.of("%M()", MemberName(factoryPackage, factoryName))
        logger.logging("PreviewKit: registered ksp-arg factory $typeFqn -> $factoryFqn")
        return typeFqn to code
    }
}
