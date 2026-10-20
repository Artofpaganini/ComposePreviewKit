package com.previewkit.ksp.emit

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.previewkit.config.PreviewArgProvider
import com.previewkit.config.ProviderContext
import com.previewkit.ksp.context.KspProviderContext
import com.previewkit.ksp.resolve.TypeOverrideSource
import com.previewkit.ksp.resolve.buildClassName
import com.previewkit.ksp.resolve.declarationFqn
import com.previewkit.ksp.resolve.findConcreteSubclasses
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import java.util.ServiceLoader

private const val MAX_NESTING_DEPTH = 5
private const val STRING_VARIANTS_COUNT = 3

private const val FQN_INT = "kotlin.Int"
private const val FQN_LONG = "kotlin.Long"
private const val FQN_BOOLEAN = "kotlin.Boolean"
private const val FQN_DOUBLE = "kotlin.Double"
private const val FQN_FLOAT = "kotlin.Float"
private const val FQN_STRING = "kotlin.String"
private const val FQN_LIST = "kotlin.collections.List"
private const val FQN_SET = "kotlin.collections.Set"
private const val FQN_MAP = "kotlin.collections.Map"

internal class ArgumentBuilder(
    private val logger: KSPLogger,
    private val words: List<String>,
    private val argProviders: List<PreviewArgProvider>,
    private val collectionSize: Int,
) {

    internal sealed interface Result {
        data class Success(val code: CodeBlock) : Result
        data class Unsupported(val reason: String) : Result
    }

    internal fun buildArgument(
        parameter: KSValueParameter,
        paramIndex: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int = 0,
        visited: Set<String> = emptySet(),
    ): Result {
        val resolvedType = parameter.type.resolve()
        val isNullable = resolvedType.isMarkedNullable
        val result = buildTypedArgument(
            parameter = parameter,
            resolvedType = resolvedType,
            paramIndex = paramIndex,
            variantOffset = 0,
            processedModels = processedModels,
            typeOverrideSource = typeOverrideSource,
            context = context,
            depth = depth,
            visited = visited,
        )
        return when {
            result is Result.Unsupported && isNullable -> Result.Success(CodeBlock.of("null"))
            else -> result
        }
    }

    private fun buildTypedArgument(
        parameter: KSValueParameter,
        resolvedType: KSType,
        paramIndex: Int,
        variantOffset: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int,
        visited: Set<String>,
    ): Result {
        if (resolvedType.declaration.qualifiedName?.asString() == FQN_INT) {
            val annotationFqns = parameter.annotations
                .mapNotNull { annotation -> annotation.annotationType.resolve().declaration.qualifiedName?.asString() }
                .toList()
            typeOverrideSource.buildForType(FQN_INT, context.withParameterAnnotations(annotationFqns))
                ?.let { return Result.Success(it) }
        }
        return buildFromType(
            type = resolvedType,
            paramIndex = paramIndex,
            variantOffset = variantOffset,
            processedModels = processedModels,
            typeOverrideSource = typeOverrideSource,
            context = context,
            depth = depth,
            visited = visited,
        )
    }

    @Suppress("ReturnCount")
    private fun buildFromType(
        type: KSType,
        paramIndex: Int,
        variantOffset: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int,
        visited: Set<String>,
    ): Result {
        val declaration = type.declaration
        val fqn = declarationFqn(declaration)
            ?: return Result.Unsupported("type with unknown FQN")

        val variantExpr = variantExpression(variantOffset)
        val callContext: ProviderContext = context.withVariantExpr(variantExpr)

        val override = typeOverrideSource.buildForType(fqn, callContext)
        if (override != null) return Result.Success(override)

        val factoryMemberName = processedModels[fqn]
        if (factoryMemberName != null) {
            return Result.Success(CodeBlock.of("%M(variant = $variantExpr)", factoryMemberName))
        }

        for (provider in argProviders) {
            val provided = provider.provide(fqn, callContext)
            if (provided != null) return Result.Success(provided)
        }

        val effectiveParamIndex = paramIndex + variantOffset

        when (fqn) {
            FQN_INT -> return Result.Success(buildIntArgument(effectiveParamIndex))
            FQN_LONG -> return Result.Success(buildLongArgument(effectiveParamIndex))
            FQN_BOOLEAN -> return Result.Success(buildBooleanArgument(effectiveParamIndex))
            FQN_DOUBLE -> return Result.Success(buildDoubleArgument(effectiveParamIndex))
            FQN_FLOAT -> return Result.Success(buildFloatArgument(effectiveParamIndex))
            FQN_STRING -> return Result.Success(buildStringArgument(effectiveParamIndex))
        }

        val typeArguments = type.arguments
        when (fqn) {
            FQN_LIST -> return buildCollectionArgument(
                elementTypes = typeArguments,
                collectionFn = "listOf",
                paramIndex = paramIndex,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
            )
            FQN_SET -> return buildCollectionArgument(
                elementTypes = typeArguments,
                collectionFn = "setOf",
                paramIndex = paramIndex,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
            )
            FQN_MAP -> return buildMapArgument(
                typeArguments = typeArguments,
                paramIndex = paramIndex,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
            )
        }

        val classDecl = declaration as? KSClassDeclaration
            ?: return Result.Unsupported("not a class: $fqn")

        if (classDecl.classKind == ClassKind.ENUM_CLASS) {
            return buildEnumArgument(classDecl, variantOffset)
        }

        if (Modifier.VALUE in classDecl.modifiers && classDecl.classKind == ClassKind.CLASS) {
            return buildValueClassArgument(
                classDecl = classDecl,
                paramIndex = paramIndex,
                variantOffset = variantOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
                fqn = fqn,
            )
        }

        if (classDecl.classKind == ClassKind.OBJECT) {
            return buildObjectArgument(classDecl)
        }

        if (Modifier.SEALED in classDecl.modifiers) {
            return buildSealedArgument(
                classDecl = classDecl,
                paramIndex = paramIndex,
                variantOffset = variantOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
                fqn = fqn,
            )
        }

        if (classDecl.classKind == ClassKind.CLASS && Modifier.DATA in classDecl.modifiers) {
            return buildInlineDataClass(
                classDecl = classDecl,
                paramIndex = paramIndex,
                variantOffset = variantOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
                fqn = fqn,
            )
        }

        return Result.Unsupported("unsupported type $fqn (classKind=${classDecl.classKind})")
    }

    private fun variantExpression(offset: Int): String =
        if (offset == 0) "variant" else "variant + $offset"

    private fun buildIntArgument(effectiveParamIndex: Int): CodeBlock =
        CodeBlock.of("variant + %L", effectiveParamIndex + 1)

    private fun buildLongArgument(effectiveParamIndex: Int): CodeBlock =
        CodeBlock.of("(variant + %L).toLong()", effectiveParamIndex + 1)

    private fun buildBooleanArgument(effectiveParamIndex: Int): CodeBlock =
        CodeBlock.of("variant %% 2 == %L", effectiveParamIndex % 2)

    private fun buildDoubleArgument(effectiveParamIndex: Int): CodeBlock =
        CodeBlock.of("(variant + %L).toDouble()", effectiveParamIndex + 1)

    private fun buildFloatArgument(effectiveParamIndex: Int): CodeBlock =
        CodeBlock.of("(variant + %L).toFloat()", effectiveParamIndex + 1)

    private fun buildStringArgument(effectiveParamIndex: Int): CodeBlock {
        val base = effectiveParamIndex * STRING_VARIANTS_COUNT
        val word0 = words[base % words.size]
        val word1 = words[(base + 1) % words.size]
        val word2 = words[(base + 2) % words.size]
        return CodeBlock.of(
            "arrayOf(%S, %S, %S)[variant %% %L]",
            word0,
            word1,
            word2,
            STRING_VARIANTS_COUNT,
        )
    }

    private fun buildEnumArgument(classDecl: KSClassDeclaration, variantOffset: Int): Result {
        val entryCount = classDecl.declarations
            .filterIsInstance<KSClassDeclaration>()
            .count { entry -> entry.classKind == ClassKind.ENUM_ENTRY }
        if (entryCount == 0) {
            return Result.Unsupported("enum '${classDecl.simpleName.asString()}' has no entries")
        }
        val className = buildClassName(classDecl)
        val variantExpr = variantExpression(variantOffset)
        return Result.Success(
            CodeBlock.of("%T.entries[$variantExpr %% %L]", className, entryCount),
        )
    }

    private fun buildObjectArgument(classDecl: KSClassDeclaration): Result {
        val className = buildClassName(classDecl)
        return Result.Success(CodeBlock.of("%T", className))
    }

    @Suppress("ReturnCount")
    private fun buildValueClassArgument(
        classDecl: KSClassDeclaration,
        paramIndex: Int,
        variantOffset: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int,
        visited: Set<String>,
        fqn: String,
    ): Result {
        if (depth >= MAX_NESTING_DEPTH) {
            return Result.Unsupported("max nesting depth $MAX_NESTING_DEPTH at value class $fqn")
        }
        if (fqn in visited) {
            return Result.Unsupported("cyclic reference at value class $fqn")
        }
        val ctor = classDecl.primaryConstructor
            ?: return Result.Unsupported("no primary constructor for value class $fqn")
        val params = ctor.parameters.toList()
        if (params.size != 1) {
            return Result.Unsupported(
                "value class $fqn has ${params.size} params, expected exactly 1",
            )
        }
        val innerParam = params[0]
        val innerType = innerParam.type.resolve()
        val isInnerNullable = innerType.isMarkedNullable
        val innerResult = buildFromType(
            type = innerType,
            paramIndex = paramIndex,
            variantOffset = variantOffset,
            processedModels = processedModels,
            typeOverrideSource = typeOverrideSource,
            context = context,
            depth = depth + 1,
            visited = visited + fqn,
        )
        val innerCode = when {
            innerResult is Result.Success -> innerResult.code
            isInnerNullable -> CodeBlock.of("null")
            else -> return Result.Unsupported(
                "value class $fqn inner param: ${(innerResult as Result.Unsupported).reason}",
            )
        }
        val className = buildClassName(classDecl)
        return Result.Success(
            CodeBlock.builder()
                .add("%T(", className)
                .add(innerCode)
                .add(")")
                .build(),
        )
    }

    @Suppress("ReturnCount")
    private fun buildSealedArgument(
        classDecl: KSClassDeclaration,
        paramIndex: Int,
        variantOffset: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int,
        visited: Set<String>,
        fqn: String,
    ): Result {
        if (depth >= MAX_NESTING_DEPTH) {
            return Result.Unsupported("max nesting depth $MAX_NESTING_DEPTH at sealed $fqn")
        }
        if (fqn in visited) {
            return Result.Unsupported("cyclic reference at sealed $fqn")
        }
        val concretes = findConcreteSubclasses(classDecl)
        if (concretes.isEmpty()) {
            return Result.Unsupported("no concrete subclasses for sealed $fqn")
        }

        val newVisited = visited + fqn

        if (concretes.size == 1) {
            return buildConcreteInstance(
                concrete = concretes[0],
                paramIndex = paramIndex,
                variantOffset = variantOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth + 1,
                visited = newVisited,
            )
        }

        val code = CodeBlock.builder()
        code.add("when (${variantExpression(variantOffset)} %% %L) {\n", concretes.size)
        code.indent()

        for ((index, concrete) in concretes.withIndex()) {
            val concreteResult = buildConcreteInstance(
                concrete = concrete,
                paramIndex = paramIndex,
                variantOffset = variantOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth + 1,
                visited = newVisited,
            )
            val concreteCode = when (concreteResult) {
                is Result.Unsupported -> return Result.Unsupported(
                    "sealed concrete at index $index: ${concreteResult.reason}",
                )
                is Result.Success -> concreteResult.code
            }
            if (index == concretes.size - 1) {
                code.add("else -> ")
            } else {
                code.add("%L -> ", index)
            }
            code.add(concreteCode)
            code.add("\n")
        }

        code.unindent()
        code.add("}")
        return Result.Success(code.build())
    }

    @Suppress("ReturnCount")
    private fun buildConcreteInstance(
        concrete: KSClassDeclaration,
        paramIndex: Int,
        variantOffset: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int,
        visited: Set<String>,
    ): Result {
        val concreteFqn = declarationFqn(concrete)
            ?: return Result.Unsupported("concrete subclass with unknown FQN")

        val factoryMemberName = processedModels[concreteFqn]
        if (factoryMemberName != null) {
            val variantExpr = variantExpression(variantOffset)
            return Result.Success(CodeBlock.of("%M(variant = $variantExpr)", factoryMemberName))
        }

        if (concrete.classKind == ClassKind.OBJECT) {
            return buildObjectArgument(concrete)
        }

        if (concrete.classKind == ClassKind.CLASS && Modifier.DATA in concrete.modifiers) {
            return buildInlineDataClass(
                classDecl = concrete,
                paramIndex = paramIndex,
                variantOffset = variantOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
                fqn = concreteFqn,
            )
        }

        return Result.Unsupported("unsupported concrete kind for $concreteFqn")
    }

    @Suppress("ReturnCount")
    private fun buildCollectionArgument(
        elementTypes: List<KSTypeArgument>,
        collectionFn: String,
        paramIndex: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int,
        visited: Set<String>,
    ): Result {
        if (elementTypes.isEmpty()) {
            return Result.Unsupported("collection with no type arguments")
        }
        val elementType = elementTypes[0].type?.resolve()
            ?: return Result.Unsupported("collection element type is unresolvable")

        val code = CodeBlock.builder()
        code.add("$collectionFn(\n")
        code.indent()

        for (elementIndex in 0 until collectionSize) {
            val elementIsNullable = elementType.isMarkedNullable
            val elementResult = buildFromType(
                type = elementType,
                paramIndex = paramIndex,
                variantOffset = elementIndex,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
            )
            val elementCode = when {
                elementResult is Result.Success -> elementResult.code
                elementIsNullable -> CodeBlock.of("null")
                else -> return Result.Unsupported(
                    "collection element[$elementIndex]: ${(elementResult as Result.Unsupported).reason}",
                )
            }
            code.add(elementCode)
            code.add(",\n")
        }

        code.unindent()
        code.add(")")
        return Result.Success(code.build())
    }

    @Suppress("ReturnCount")
    private fun buildMapArgument(
        typeArguments: List<KSTypeArgument>,
        paramIndex: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int,
        visited: Set<String>,
    ): Result {
        if (typeArguments.size < 2) {
            return Result.Unsupported("Map with fewer than 2 type arguments")
        }
        val keyType = typeArguments[0].type?.resolve()
            ?: return Result.Unsupported("Map key type is unresolvable")
        val valueType = typeArguments[1].type?.resolve()
            ?: return Result.Unsupported("Map value type is unresolvable")

        val code = CodeBlock.builder()
        code.add("mapOf(\n")
        code.indent()

        for (pairIndex in 0 until collectionSize) {
            val keyIsNullable = keyType.isMarkedNullable
            val valueIsNullable = valueType.isMarkedNullable
            val keyOffset = pairIndex * 2
            val valueOffset = pairIndex * 2 + 1

            val keyResult = buildFromType(
                type = keyType,
                paramIndex = paramIndex,
                variantOffset = keyOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
            )
            val valueResult = buildFromType(
                type = valueType,
                paramIndex = paramIndex,
                variantOffset = valueOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth,
                visited = visited,
            )

            val keyCode = when {
                keyResult is Result.Success -> keyResult.code
                keyIsNullable -> CodeBlock.of("null")
                else -> return Result.Unsupported(
                    "Map key[$pairIndex]: ${(keyResult as Result.Unsupported).reason}",
                )
            }
            val valueCode = when {
                valueResult is Result.Success -> valueResult.code
                valueIsNullable -> CodeBlock.of("null")
                else -> return Result.Unsupported(
                    "Map value[$pairIndex]: ${(valueResult as Result.Unsupported).reason}",
                )
            }

            code.add(keyCode)
            code.add(" to ")
            code.add(valueCode)
            code.add(",\n")
        }

        code.unindent()
        code.add(")")
        return Result.Success(code.build())
    }

    @Suppress("ReturnCount")
    internal fun buildInlineDataClass(
        classDecl: KSClassDeclaration,
        paramIndex: Int,
        variantOffset: Int,
        processedModels: Map<String, MemberName>,
        typeOverrideSource: TypeOverrideSource,
        context: KspProviderContext,
        depth: Int,
        visited: Set<String>,
        fqn: String,
    ): Result {
        if (depth >= MAX_NESTING_DEPTH) {
            return Result.Unsupported("max nesting depth $MAX_NESTING_DEPTH at $fqn")
        }
        if (fqn in visited) {
            return Result.Unsupported("cyclic reference at $fqn")
        }

        val ctor = classDecl.primaryConstructor
            ?: return Result.Unsupported("no primary constructor for $fqn")

        val ctorVisibility = ctor.getVisibility()
        if (ctorVisibility != Visibility.PUBLIC) {
            logger.warn(
                "PreviewKit: inline build for $fqn — constructor is $ctorVisibility, " +
                    "generated code will not compile (R-B cross-module risk).",
            )
        }

        val className = buildClassName(classDecl)
        val newVisited = visited + fqn
        val codeBuilder = CodeBlock.builder()
        codeBuilder.add("%T(\n", className)
        codeBuilder.indent()

        for ((index, param) in ctor.parameters.withIndex()) {
            val paramName = param.name?.asString()
                ?: return Result.Unsupported("unnamed constructor parameter at index $index in $fqn")

            val resolvedParamType = param.type.resolve()
            val isParamNullable = resolvedParamType.isMarkedNullable

            if (resolvedParamType.declaration.qualifiedName?.asString() == FQN_INT) {
                val annotationFqns = param.annotations
                    .mapNotNull { annotation -> annotation.annotationType.resolve().declaration.qualifiedName?.asString() }
                    .toList()
                typeOverrideSource.buildForType(FQN_INT, context.withParameterAnnotations(annotationFqns))?.let { resCode ->
                    codeBuilder.add("%N = ", param.name?.asString())
                    codeBuilder.add(resCode)
                    codeBuilder.add(",\n")
                    continue
                }
            }

            val argResult = buildTypedArgument(
                parameter = param,
                resolvedType = resolvedParamType,
                paramIndex = paramIndex + index,
                variantOffset = variantOffset,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = context,
                depth = depth + 1,
                visited = newVisited,
            )

            val paramCode = when {
                argResult is Result.Success -> argResult.code
                isParamNullable -> CodeBlock.of("null")
                else -> return Result.Unsupported(
                    "unsupported param '$paramName' in $fqn: ${(argResult as Result.Unsupported).reason}",
                )
            }

            codeBuilder.add("%N = ", paramName)
            codeBuilder.add(paramCode)
            codeBuilder.add(",\n")
        }

        codeBuilder.unindent()
        codeBuilder.add(")")
        return Result.Success(codeBuilder.build())
    }
}

internal fun loadArgProviders(): List<PreviewArgProvider> =
    ServiceLoader.load(PreviewArgProvider::class.java, PreviewArgProvider::class.java.classLoader).toList()
