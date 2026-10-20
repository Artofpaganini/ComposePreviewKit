package com.previewkit.ksp.emit

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.previewkit.ksp.context.KspProviderContext
import com.previewkit.ksp.resolve.ModelToGenerate
import com.previewkit.ksp.resolve.TypeOverrideSource
import com.previewkit.ksp.resolve.buildClassName
import com.previewkit.ksp.resolve.buildCompoundSimpleName
import com.previewkit.ksp.resolve.buildFunctionName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

private const val VARIANT_PARAM = "variant"
private val PREVIEW_PARAMETER_PROVIDER_CLASS_NAME = ClassName(
    "androidx.compose.ui.tooling.preview",
    "PreviewParameterProvider",
)
private val SEQUENCE_CLASS_NAME = ClassName("kotlin.sequences", "Sequence")
private val LIST_CLASS_NAME = ClassName("kotlin.collections", "List")

internal class FunctionEmitter(
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
    private val processedModels: Map<String, MemberName>,
    private val typeOverrideSource: TypeOverrideSource,
    private val argumentBuilder: ArgumentBuilder,
    private val providerContext: KspProviderContext,
    private val collectionSize: Int,
    private val providerVariants: Int,
) {
    private data class ParamCode(val name: String, val code: CodeBlock)

    internal fun emit(model: ModelToGenerate) {
        when (model) {
            is ModelToGenerate.DataClassModel -> emitDataClass(model)
            is ModelToGenerate.SealedModel -> emitSealed(model)
            is ModelToGenerate.EnumModel -> emitEnum(model)
            is ModelToGenerate.ValueClassModel -> emitValueClass(model)
            is ModelToGenerate.ObjectModel -> emitObject(model)
        }
    }

    private fun buildParamCodes(
        parameters: List<KSValueParameter>,
        simpleName: String,
        isAnnotated: Boolean,
    ): List<ParamCode>? {
        return parameters.mapIndexed { index, param ->
            val paramName = param.name?.asString() ?: run {
                logSkip(isAnnotated, simpleName, "unnamed constructor parameter at index $index")
                return null
            }
            when (
                val argResult = argumentBuilder.buildArgument(
                    parameter = param,
                    paramIndex = index,
                    processedModels = processedModels,
                    typeOverrideSource = typeOverrideSource,
                    context = providerContext,
                )
            ) {
                is ArgumentBuilder.Result.Unsupported -> {
                    logSkip(isAnnotated, simpleName, "parameter '$paramName': ${argResult.reason}")
                    return null
                }
                is ArgumentBuilder.Result.Success -> ParamCode(paramName, argResult.code)
            }
        }
    }

    private fun emitDataClass(model: ModelToGenerate.DataClassModel) {
        val declaration = model.declaration
        val simpleName = declaration.simpleName.asString()

        val primaryCtor = declaration.primaryConstructor
        if (primaryCtor == null) {
            logSkip(model.isAnnotated, simpleName, "no primary constructor")
            return
        }

        val paramCodes = buildParamCodes(
            parameters = primaryCtor.parameters.toList(),
            simpleName = simpleName,
            isAnnotated = model.isAnnotated,
        ) ?: return

        val returnType = buildClassName(declaration)
        val bodyBuilder = CodeBlock.builder()
        bodyBuilder.add("return %T(\n", returnType)
        bodyBuilder.indent()
        for (paramCode in paramCodes) {
            bodyBuilder.add("%N = ", paramCode.name)
            bodyBuilder.add(paramCode.code)
            bodyBuilder.add(",\n")
        }
        bodyBuilder.unindent()
        bodyBuilder.add(")")

        emitFile(
            declaration = declaration,
            returnType = returnType,
            body = bodyBuilder.build(),
        )
    }

    private fun emitSealed(model: ModelToGenerate.SealedModel) {
        val declaration = model.declaration
        val simpleName = declaration.simpleName.asString()
        val concretes = model.concretes

        if (concretes.isEmpty()) {
            logSkip(model.isAnnotated, simpleName, "no concrete subclasses found")
            return
        }

        val returnType = buildClassName(declaration)
        val bodyBuilder = CodeBlock.builder()

        if (concretes.size == 1) {
            val concreteFunctionName = buildFunctionName(concretes[0])
            bodyBuilder.add("return %N(variant = variant)", concreteFunctionName)
        } else {
            bodyBuilder.add("return when (variant %% %L) {\n", concretes.size)
            bodyBuilder.indent()
            for ((index, concrete) in concretes.withIndex()) {
                val concreteFunctionName = buildFunctionName(concrete)
                if (index == concretes.size - 1) {
                    bodyBuilder.add("else -> %N(variant = variant)\n", concreteFunctionName)
                } else {
                    bodyBuilder.add("%L -> %N(variant = variant)\n", index, concreteFunctionName)
                }
            }
            bodyBuilder.unindent()
            bodyBuilder.add("}")
        }

        emitFile(
            declaration = declaration,
            returnType = returnType,
            body = bodyBuilder.build(),
        )
    }

    private fun emitEnum(model: ModelToGenerate.EnumModel) {
        val declaration = model.declaration
        val simpleName = declaration.simpleName.asString()

        val entryCount = declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .count { entry -> entry.classKind == ClassKind.ENUM_ENTRY }

        if (entryCount == 0) {
            logSkip(model.isAnnotated, simpleName, "enum has no entries")
            return
        }

        val returnType = buildClassName(declaration)
        val body = CodeBlock.of("return %T.entries[variant %% %L]", returnType, entryCount)

        emitFile(
            declaration = declaration,
            returnType = returnType,
            body = body,
        )
    }

    private fun emitValueClass(model: ModelToGenerate.ValueClassModel) {
        val declaration = model.declaration
        val simpleName = declaration.simpleName.asString()

        val primaryCtor = declaration.primaryConstructor
        if (primaryCtor == null) {
            logSkip(model.isAnnotated, simpleName, "no primary constructor")
            return
        }

        val params = primaryCtor.parameters.toList()
        if (params.size != 1) {
            logSkip(model.isAnnotated, simpleName, "value class has ${params.size} params, expected 1")
            return
        }

        val argResult = argumentBuilder.buildArgument(
            parameter = params[0],
            paramIndex = 0,
            processedModels = processedModels,
            typeOverrideSource = typeOverrideSource,
            context = providerContext,
        )

        when (argResult) {
            is ArgumentBuilder.Result.Unsupported -> {
                logSkip(model.isAnnotated, simpleName, argResult.reason)
            }
            is ArgumentBuilder.Result.Success -> {
                val returnType = buildClassName(declaration)
                val body = CodeBlock.builder()
                    .add("return %T(", returnType)
                    .add(argResult.code)
                    .add(")")
                    .build()
                emitFile(
                    declaration = declaration,
                    returnType = returnType,
                    body = body,
                )
            }
        }
    }

    private fun emitObject(model: ModelToGenerate.ObjectModel) {
        val declaration = model.declaration
        val returnType = buildClassName(declaration)
        val body = CodeBlock.of("return %T", returnType)
        emitFile(
            declaration = declaration,
            returnType = returnType,
            body = body,
        )
    }

    private fun emitFile(
        declaration: KSClassDeclaration,
        returnType: ClassName,
        body: CodeBlock,
    ) {
        val packageName = declaration.packageName.asString()
        val functionName = buildFunctionName(declaration)
        val compoundName = buildCompoundSimpleName(declaration)

        val functionSpec = FunSpec.builder(functionName)
            .addModifiers(KModifier.INTERNAL)
            .addParameter(
                ParameterSpec.builder(VARIANT_PARAM, Int::class)
                    .defaultValue("0")
                    .build(),
            )
            .returns(returnType)
            .addCode(body)
            .build()

        val providerSpec = buildProviderTypeSpec(
            functionName = functionName,
            returnType = returnType,
            providerClassName = "${compoundName}PreviewProvider",
        )

        val containingFile = declaration.containingFile
        val dependencies = if (containingFile != null) {
            Dependencies(aggregating = false, containingFile)
        } else {
            Dependencies(aggregating = false)
        }

        val listHelperSpec = buildListHelperFunSpec(functionName, returnType)

        val fileSpec = FileSpec.builder(packageName, functionName)
            .addFunction(functionSpec)
            .addFunction(listHelperSpec)
            .addType(providerSpec)
            .build()

        val outputStream = codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = packageName,
            fileName = functionName,
            extensionName = "kt",
        )
        outputStream.bufferedWriter().use { writer -> fileSpec.writeTo(writer) }

        logger.logging("PreviewKit: generated $functionName in package $packageName")
    }

    private fun buildListHelperFunSpec(
        functionName: String,
        returnType: ClassName,
    ): FunSpec {
        val listTypeName = LIST_CLASS_NAME.parameterizedBy(returnType)
        val body = CodeBlock.of(
            "return %T(count) { index -> %N(variant = variant + index) }",
            LIST_CLASS_NAME,
            functionName,
        )
        return FunSpec.builder("${functionName}List")
            .addModifiers(KModifier.INTERNAL)
            .addParameter(
                ParameterSpec.builder("count", Int::class)
                    .defaultValue("%L", collectionSize)
                    .build(),
            )
            .addParameter(
                ParameterSpec.builder(VARIANT_PARAM, Int::class)
                    .defaultValue("0")
                    .build(),
            )
            .returns(listTypeName)
            .addCode(body)
            .build()
    }

    private fun buildProviderTypeSpec(
        functionName: String,
        returnType: ClassName,
        providerClassName: String,
    ): TypeSpec {
        val valuesCode = CodeBlock.builder()
            .add("sequenceOf(\n")
            .indent()
            .apply {
                for (variantIndex in 0 until providerVariants) {
                    add("%N(%L),\n", functionName, variantIndex)
                }
            }
            .unindent()
            .add(")")
            .build()

        val valuesProperty = PropertySpec.builder(
            "values",
            SEQUENCE_CLASS_NAME.parameterizedBy(returnType),
        )
            .addModifiers(KModifier.OVERRIDE)
            .initializer(valuesCode)
            .build()

        return TypeSpec.classBuilder(providerClassName)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(PREVIEW_PARAMETER_PROVIDER_CLASS_NAME.parameterizedBy(returnType))
            .addProperty(valuesProperty)
            .build()
    }

    private fun logSkip(isAnnotated: Boolean, className: String, reason: String) {
        if (isAnnotated) {
            logger.error(
                "PreviewKit: @PreviewMock on '$className' cannot generate factory — $reason. " +
                    "Annotate nested types with @PreviewMock or use only supported types.",
            )
        } else {
            logger.warn("PreviewKit: skipping '$className' (auto-scan) — $reason")
        }
    }
}
