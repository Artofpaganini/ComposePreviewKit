package com.previewkit.ksp.emit

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.previewkit.ksp.context.KspProviderContext
import com.previewkit.ksp.manifest.UnsupportedManifestSink
import com.previewkit.ksp.resolve.PreviewAnnotationRef
import com.previewkit.ksp.resolve.TypeOverrideSource
import com.previewkit.ksp.resolve.declarationFqn
import com.previewkit.ksp.resolve.WithPreviewTarget
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import kotlin.collections.emptyList

private val COMPOSABLE_CLASS_NAME = ClassName("androidx.compose.runtime", "Composable")
private val PREVIEW_CLASS_NAME = ClassName("androidx.compose.ui.tooling.preview", "Preview")
private val PREVIEW_PARAMETER_CLASS_NAME = ClassName("androidx.compose.ui.tooling.preview", "PreviewParameter")
private val OPT_IN_CLASS_NAME = ClassName("kotlin", "OptIn")
private val COLUMN_MEMBER = MemberName("androidx.compose.foundation.layout", "Column")

private const val FQN_OPT_IN = "kotlin.OptIn"
private const val FQN_UNIT = "kotlin.Unit"
private const val OPT_IN_MARKER_ARG = "markerClass"

private const val LAMBDA_TODO_MESSAGE = "PreviewKit: собери это руками"
private const val STUB_TODO_PREFIX = "PreviewKit: собери это руками: "
private const val STUB_COMMENT_FORMAT = "PreviewKit: %L"

internal class SurfaceEmitter(
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
    private val processedModels: Map<String, MemberName>,
    private val processedProviders: Map<String, MemberName>,
    private val argumentBuilder: ArgumentBuilder,
    private val typeOverrideSource: TypeOverrideSource,
    private val providerContext: KspProviderContext,
    private val defaultBox: String = "",
    private val catalog: List<PreviewAnnotationRef> = emptyList(),
    private val namePrefix: String = "",
    private val nameSuffixes: List<String> = emptyList(),
    private val strict: Boolean = false,
    private val unsupportedStub: Boolean = false,
    private val manifestSink: UnsupportedManifestSink,
) {
    internal fun emit(target: WithPreviewTarget) {
        val fnSimpleName = target.function.simpleName.asString()

        val optInAnnotation = buildOptInAnnotation(collectOptInMarkers(target))

        if (catalog.isEmpty()) {
            logger.warn(
                "SurfaceEmitter: '$fnSimpleName' — catalog empty (no registry), " +
                    "generating a plain @Preview wrapper instead",
            )
            emitFallback(target, optInAnnotation)
            return
        }

        emitUnifiedFile(target, optInAnnotation)
    }

    /** Zero-config mode: no registry configured - generate a single plain @Preview wrapper. */
    private fun emitFallback(target: WithPreviewTarget, optInAnnotation: AnnotationSpec?) {
        val fnSimpleName = target.function.simpleName.asString()
        val packageName = target.function.packageName.asString()
        val fileName = "${fnSimpleName}Preview"
        val argsCode = buildArgsCode(target = target, wrapperName = fileName) ?: return
        val bodyCode = buildRawBodyCode(target, target.variant, argsCode)
        val usedNames = mutableSetOf<String>()
        val functionName = buildUniqueName("${fnSimpleName}Preview", usedNames)

        val funSpec = FunSpec.builder(functionName)
            .addModifiers(KModifier.PRIVATE)
            .addAnnotation(
                AnnotationSpec.builder(PREVIEW_CLASS_NAME)
                    .addMember("showBackground = true")
                    .build(),
            )
            .addAnnotation(AnnotationSpec.builder(COMPOSABLE_CLASS_NAME).build())
            .apply { optInAnnotation?.let(::addAnnotation) }
            .addCode(bodyCode)
            .build()

        val containingFile = target.function.containingFile
        val dependencies = if (containingFile != null) {
            Dependencies(aggregating = false, containingFile)
        } else {
            Dependencies(aggregating = false)
        }

        val outputStream = codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = packageName,
            fileName = fileName,
            extensionName = "kt",
        )
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            FileSpec.builder(packageName, fileName)
                .addFunction(funSpec)
                .build()
                .writeTo(writer)
        }
        logger.logging("SurfaceEmitter: generated fallback '$fileName' in '$packageName'")
    }

    /**
     * Calls of the target may require opt-in (experimental marker on the target itself or in its
     * signature, often declared via @file:OptIn in the consumer file). Generated wrappers live in
     * their own file, so the markers must be re-emitted on every generated preview function.
     */
    private fun collectOptInMarkers(target: WithPreviewTarget): List<ClassName> {
        val optInAnnotations = buildList {
            addAll(target.function.annotations)
            target.function.containingFile?.let { file -> addAll(file.annotations) }
        }.filter { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == FQN_OPT_IN
        }
        val markerTypes = optInAnnotations.flatMap { annotation ->
            val value = annotation.arguments
                .find { arg -> arg.name?.asString() == OPT_IN_MARKER_ARG }
                ?.value as? List<*> ?: emptyList<Any>()
            value.filterIsInstance<KSType>()
        }
        return markerTypes.mapNotNull { markerType ->
            (markerType.declaration as? KSClassDeclaration)
                ?.qualifiedName?.asString()
                ?.let(ClassName::bestGuess)
        }.distinct()
    }

    private fun buildOptInAnnotation(markers: List<ClassName>): AnnotationSpec? {
        if (markers.isEmpty()) return null
        val builder = AnnotationSpec.builder(OPT_IN_CLASS_NAME)
        for (marker in markers) {
            builder.addMember("%T::class", marker)
        }
        return builder.build()
    }

    private fun emitUnifiedFile(
        target: WithPreviewTarget,
        optInAnnotation: AnnotationSpec?,
    ) {
        val fnSimpleName = target.function.simpleName.asString()
        val packageName = target.function.packageName.asString()
        val fileName = "${fnSimpleName}Preview"

        val argsCode = buildArgsCode(target = target, wrapperName = fileName) ?: return

        val fileBuilder = FileSpec.builder(packageName, fileName)
        val usedNames = mutableSetOf<String>()

        for (ref in catalog) {
            val core = buildCoreName(ref.simpleName)
            val functionName = buildUniqueName("$fnSimpleName${core}Preview", usedNames)
            val funSpec = buildAnnotationFunSpec(
                functionName = functionName,
                ref = ref,
                target = target,
                argsCode = argsCode,
                optInAnnotation = optInAnnotation,
            )
            fileBuilder.addFunction(funSpec)
        }

        val surfaces = catalog.filter { ref -> ref.isSurface && ref.boxFqn.isNotEmpty() }
        if (surfaces.size > 1) {
            val combinedName = buildUniqueName("${fnSimpleName}CombinedPreview", usedNames)
            val combinedSpec = buildCombinedFunSpec(
                functionName = combinedName,
                surfaces = surfaces,
                target = target,
                argsCode = argsCode,
                optInAnnotation = optInAnnotation,
            )
            fileBuilder.addFunction(combinedSpec)
        }

        buildAllVariantsFunSpec(target, optInAnnotation, usedNames)?.let(fileBuilder::addFunction)

        val containingFile = target.function.containingFile
        val dependencies = if (containingFile != null) {
            Dependencies(aggregating = false, containingFile)
        } else {
            Dependencies(aggregating = false)
        }

        val fileSpec = fileBuilder.build()
        val outputStream = codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = packageName,
            fileName = fileName,
            extensionName = "kt",
        )
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer -> fileSpec.writeTo(writer) }
        logger.logging("SurfaceEmitter: generated unified '$fileName' in '$packageName' (${catalog.size} catalog entries, ${surfaces.size} surfaces)")
    }

    private fun buildAnnotationFunSpec(
        functionName: String,
        ref: PreviewAnnotationRef,
        target: WithPreviewTarget,
        argsCode: CodeBlock,
        optInAnnotation: AnnotationSpec?,
    ): FunSpec {
        val annotationClass = ClassName.bestGuess(ref.fqn)
        val bodyCode = when {
            ref.isSurface && ref.boxFqn.isNotEmpty() -> buildBodyCode(target, ref.boxFqn, target.variant, argsCode)
            !ref.isSurface && defaultBox.isNotEmpty() -> buildBodyCode(target, defaultBox, target.variant, argsCode)
            else -> {
                if (!ref.isSurface) {
                    logger.warn("SurfaceEmitter: non-surface '${ref.fqn}' — defaultBox not set, emitting raw (set ksp-arg 'previewkit.defaultBox' to wrap in a box)")
                }
                buildRawBodyCode(target, target.variant, argsCode)
            }
        }
        return FunSpec.builder(functionName)
            .addModifiers(KModifier.PRIVATE)
            .addAnnotation(AnnotationSpec.builder(annotationClass).build())
            .addAnnotation(AnnotationSpec.builder(COMPOSABLE_CLASS_NAME).build())
            .apply { optInAnnotation?.let(::addAnnotation) }
            .addCode(bodyCode)
            .build()
    }

    private fun buildCombinedFunSpec(
        functionName: String,
        surfaces: List<PreviewAnnotationRef>,
        target: WithPreviewTarget,
        argsCode: CodeBlock,
        optInAnnotation: AnnotationSpec?,
    ): FunSpec {
        val targetMember = MemberName(
            target.function.packageName.asString(),
            target.function.simpleName.asString(),
        )

        val columnBody = CodeBlock.builder()
        for (ref in surfaces) {
            val callBlock = CodeBlock.builder()
                .add("%M(\n", targetMember)
                .indent()
                .add(argsCode)
                .unindent()
                .add(")")
                .build()
            val boxMember = parseFqnToMemberName(ref.boxFqn)
            columnBody
                .add("%M {\n", boxMember)
                .indent()
                .add(callBlock)
                .add("\n")
                .unindent()
                .add("}\n")
        }

        val bodyCode = CodeBlock.builder()
            .addStatement("val variant = %L", target.variant)
            .add("%M {\n", COLUMN_MEMBER)
            .indent()
            .add(columnBody.build())
            .unindent()
            .add("}")
            .build()

        val previewAnnotation = AnnotationSpec.builder(PREVIEW_CLASS_NAME)
            .addMember("showBackground = true")
            .build()

        return FunSpec.builder(functionName)
            .addModifiers(KModifier.PRIVATE)
            .addAnnotation(previewAnnotation)
            .addAnnotation(AnnotationSpec.builder(COMPOSABLE_CLASS_NAME).build())
            .apply { optInAnnotation?.let(::addAnnotation) }
            .addCode(bodyCode)
            .build()
    }

    private fun buildRawBodyCode(
        target: WithPreviewTarget,
        variantLiteral: Int,
        argsCode: CodeBlock,
    ): CodeBlock {
        val targetMember = MemberName(
            target.function.packageName.asString(),
            target.function.simpleName.asString(),
        )
        return CodeBlock.builder()
            .addStatement("val variant = %L", variantLiteral)
            .add("%M(\n", targetMember)
            .indent()
            .add(argsCode)
            .unindent()
            .add(")")
            .build()
    }

    private fun buildBodyCode(
        target: WithPreviewTarget,
        boxFqn: String,
        variantLiteral: Int,
        argsCode: CodeBlock,
    ): CodeBlock {
        val targetMember = MemberName(
            target.function.packageName.asString(),
            target.function.simpleName.asString(),
        )

        val callBlock = CodeBlock.builder()
            .add("%M(\n", targetMember)
            .indent()
            .add(argsCode)
            .unindent()
            .add(")")
            .build()

        val innerBlock = if (boxFqn.isEmpty()) {
            logger.warn("SurfaceEmitter: empty boxFqn for surface entry, emitting without box wrapper (set defaultBox in DSL or boxFqn in @PreviewSurface)")
            callBlock
        } else {
            val boxMember = parseFqnToMemberName(boxFqn)
            CodeBlock.builder()
                .add("%M {\n", boxMember)
                .indent()
                .add(callBlock)
                .add("\n")
                .unindent()
                .add("}")
                .build()
        }

        return CodeBlock.builder()
            .addStatement("val variant = %L", variantLiteral)
            .add(innerBlock)
            .build()
    }

    private fun reportSkip(problem: String, wrapperName: String) {
        val message = "SurfaceEmitter: $problem. Skipping wrapper '$wrapperName'."
        if (strict) logger.error(message) else logger.warn(message)
    }

    private fun buildArgsCode(
        target: WithPreviewTarget,
        wrapperName: String,
    ): CodeBlock? {
        val params = target.function.parameters.toList()
        if (params.isEmpty()) return CodeBlock.of("")
        val code = CodeBlock.builder()
        val problems = mutableListOf<String>()
        for ((index, param) in params.withIndex()) {
            appendParam(code, param, index, target, problems)
        }
        val problem = problems.firstOrNull() ?: return code.build()
        reportSkip(problem, wrapperName)
        return null
    }

    private fun appendParam(
        code: CodeBlock.Builder,
        param: KSValueParameter,
        index: Int,
        target: WithPreviewTarget,
        problems: MutableList<String>,
    ) {
        if (param.hasDefault) return
        appendRequiredParam(code, param, index, target, problems)
    }

    private fun appendRequiredParam(
        code: CodeBlock.Builder,
        param: KSValueParameter,
        index: Int,
        target: WithPreviewTarget,
        problems: MutableList<String>,
    ) {
        val paramName = param.name?.asString()
        if (paramName == null) {
            val fnSimpleName = target.function.simpleName.asString()
            manifestSink.recordUnsupportedParameter(
                sourceFilePath = targetSourceFilePath(target),
                packageName = target.function.packageName.asString(),
                functionName = fnSimpleName,
                paramName = "",
                typeFqn = typeFqnOf(param.type.resolve()),
                reason = "unnamed parameter at index $index",
            )
            problems += "'$fnSimpleName' has unnamed parameter at index $index"
            return
        }
        val paramCode = resolveParamCode(
            code = code,
            param = param,
            paramName = paramName,
            paramIndex = index,
            target = target,
            problems = problems,
        ) ?: return
        code.add("%N = ", paramName)
        code.add(paramCode)
        code.add(",\n")
    }

    @Suppress("ReturnCount")
    private fun resolveParamCode(
        code: CodeBlock.Builder,
        param: KSValueParameter,
        paramName: String,
        paramIndex: Int,
        target: WithPreviewTarget,
        problems: MutableList<String>,
    ): CodeBlock? {
        val resolvedType = param.type.resolve()

        if (resolvedType.isFunctionType || resolvedType.isSuspendFunctionType) {
            return lambdaBodyCode(resolvedType)
        }

        return when (
            val result = argumentBuilder.buildArgument(
                parameter = param,
                paramIndex = paramIndex,
                processedModels = processedModels,
                typeOverrideSource = typeOverrideSource,
                context = providerContext,
            )
        ) {
            is ArgumentBuilder.Result.Success -> result.code
            is ArgumentBuilder.Result.Unsupported -> {
                val fnSimpleName = target.function.simpleName.asString()
                manifestSink.recordUnsupportedParameter(
                    sourceFilePath = targetSourceFilePath(target),
                    packageName = target.function.packageName.asString(),
                    functionName = fnSimpleName,
                    paramName = paramName,
                    typeFqn = typeFqnOf(resolvedType),
                    reason = result.reason,
                )
                if (!unsupportedStub || strict) {
                    problems += "cannot resolve '$paramName' in '$fnSimpleName' — ${result.reason}"
                    null
                } else {
                    code.addComment(STUB_COMMENT_FORMAT, result.reason)
                    CodeBlock.of("TODO(%S)", STUB_TODO_PREFIX + result.reason)
                }
            }
        }
    }

    private fun lambdaBodyCode(functionType: KSType): CodeBlock {
        val returnType = functionType.arguments.lastOrNull()?.type?.resolve()
        val isUnitReturn = returnType != null &&
            typeFqnOf(returnType) == FQN_UNIT &&
            !returnType.isMarkedNullable
        return if (isUnitReturn) {
            CodeBlock.of("{}")
        } else {
            CodeBlock.of("{ TODO(%S) }", LAMBDA_TODO_MESSAGE)
        }
    }

    private fun targetSourceFilePath(target: WithPreviewTarget): String =
        target.function.containingFile?.filePath.orEmpty()

    private fun typeFqnOf(type: KSType): String =
        declarationFqn(type.declaration).orEmpty()

    private fun buildCoreName(simpleName: String): String {
        val afterPrefix = if (namePrefix.isNotEmpty()) simpleName.removePrefix(namePrefix) else simpleName
        if (nameSuffixes.isEmpty()) return afterPrefix
        for (suffix in nameSuffixes) {
            if (afterPrefix.endsWith(suffix)) {
                val stripped = afterPrefix.removeSuffix(suffix)
                return if (stripped.isEmpty()) afterPrefix else stripped
            }
        }
        return afterPrefix
    }

    /**
     * One preview pane per variant: wires the generated XxxPreviewProvider into the single
     * required model parameter. Skipped when the target has zero or several required
     * non-lambda parameters or no generated provider for that type.
     */
    private fun buildAllVariantsFunSpec(
        target: WithPreviewTarget,
        optInAnnotation: AnnotationSpec?,
        usedNames: MutableSet<String>,
    ): FunSpec? {
        val fnSimpleName = target.function.simpleName.asString()
        val params = target.function.parameters.toList()
        val requiredModelParams = params.filter { param ->
            if (param.hasDefault) return@filter false
            val resolved = param.type.resolve()
            !resolved.isFunctionType && !resolved.isSuspendFunctionType
        }
        if (requiredModelParams.size != 1) return null

        val variedParam = requiredModelParams.first()
        val variedName = variedParam.name?.asString() ?: return null
        val variedFqn = declarationFqn(variedParam.type.resolve().declaration) ?: return null
        val providerName = processedProviders[variedFqn] ?: return null
        val variedType = ClassName.bestGuess(variedFqn)

        val parameterSpec = ParameterSpec.builder(variedName, variedType)
            .addAnnotation(
                AnnotationSpec.builder(PREVIEW_PARAMETER_CLASS_NAME)
                    .addMember("%T::class", ClassName.bestGuess("${providerName.packageName}.${providerName.simpleName}"))
                    .build(),
            )
            .build()

        val argsCode = CodeBlock.builder()
        for ((index, param) in params.withIndex()) {
            val name = param.name?.asString() ?: return null
            when {
                param == variedParam -> {
                    argsCode.add("%N = %N,\n", name, name)
                }
                param.hasDefault -> Unit
                param.type.resolve().let { type -> type.isFunctionType || type.isSuspendFunctionType } -> {
                    argsCode.add("%N = {},\n", name)
                }
                else -> {
                    val result = argumentBuilder.buildArgument(
                        parameter = param,
                        paramIndex = index,
                        processedModels = processedModels,
                        typeOverrideSource = typeOverrideSource,
                        context = providerContext,
                    )
                    when (result) {
                        is ArgumentBuilder.Result.Success -> {
                            argsCode.add("%N = ", name)
                            argsCode.add(result.code)
                            argsCode.add(",\n")
                        }
                        is ArgumentBuilder.Result.Unsupported -> return null
                    }
                }
            }
        }
        val argsCodeBlock = argsCode.build()

        val bodyCode = if (defaultBox.isNotEmpty()) {
            buildBodyCode(target, defaultBox, target.variant, argsCodeBlock)
        } else {
            buildRawBodyCode(target, target.variant, argsCodeBlock)
        }

        val functionName = buildUniqueName("${fnSimpleName}AllVariantsPreview", usedNames)
        return FunSpec.builder(functionName)
            .addModifiers(KModifier.PRIVATE)
            .addAnnotation(
                AnnotationSpec.builder(PREVIEW_CLASS_NAME)
                    .addMember("showBackground = true")
                    .build(),
            )
            .addAnnotation(AnnotationSpec.builder(COMPOSABLE_CLASS_NAME).build())
            .apply { optInAnnotation?.let(::addAnnotation) }
            .addParameter(parameterSpec)
            .addCode(bodyCode)
            .build()
    }

    private fun buildUniqueName(candidate: String, used: MutableSet<String>): String {
        if (used.add(candidate)) return candidate
        var suffix = 2
        while (true) {
            val disambiguated = "$candidate$suffix"
            if (used.add(disambiguated)) return disambiguated
            suffix++
        }
    }

    private fun parseFqnToMemberName(fqn: String): MemberName {
        val lastDot = fqn.lastIndexOf('.')
        return if (lastDot >= 0) {
            MemberName(fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
        } else {
            MemberName("", fqn)
        }
    }
}
