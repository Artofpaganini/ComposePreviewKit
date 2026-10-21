package com.previewkit.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.previewkit.ksp.context.KspProviderContext
import com.previewkit.ksp.emit.ArgumentBuilder
import com.previewkit.ksp.emit.FunctionEmitter
import com.previewkit.ksp.emit.SurfaceEmitter
import com.previewkit.ksp.emit.loadArgProviders
import com.previewkit.ksp.faker.loadWords
import com.previewkit.ksp.manifest.UnsupportedManifestSink
import com.previewkit.ksp.resolve.KSP_ARG_REGISTRY
import com.previewkit.ksp.resolve.ModelCollector
import com.previewkit.ksp.resolve.declarationFqn
import com.previewkit.ksp.resolve.MultipreviewCatalog
import com.previewkit.ksp.resolve.TypeOverrideSource
import com.previewkit.ksp.resolve.WithPreviewCollector
import com.previewkit.ksp.resolve.buildCompoundSimpleName
import com.previewkit.ksp.resolve.buildFunctionName
import com.squareup.kotlinpoet.MemberName

private const val KSP_ARG_GATE_NAME_KEY = "previewkit.gateArgName"
private const val DEFAULT_GATE_ARG_NAME = "previewkit.enabled"
private const val KSP_ARG_DEFAULT_BOX_KEY = "previewkit.defaultBox"
private const val KSP_ARG_GENERATE_KEY = "previewkit.generate"
private const val KSP_ARG_NAME_PREFIX_KEY = "previewkit.namePrefix"
private const val KSP_ARG_NAME_SUFFIXES_KEY = "previewkit.nameSuffixes"
private const val KSP_ARG_COLLECTION_SIZE = "previewkit.collectionSize"
private const val KSP_ARG_PROVIDER_VARIANTS = "previewkit.providerVariants"
private const val KSP_ARG_STRICT = "previewkit.strict"
private const val KSP_ARG_UNSUPPORTED_STUB = "previewkit.unsupportedStub"
private const val DEFAULT_COLLECTION_SIZE = 2
private const val DEFAULT_PROVIDER_VARIANTS = 3

internal class PreviewKitProcessor(
    private val environment: SymbolProcessorEnvironment,
) : SymbolProcessor {

    private var processed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()
        processed = true

        val logger = environment.logger
        val codeGenerator = environment.codeGenerator
        val options = environment.options

        val typeOverrideSource = TypeOverrideSource(
            kspArgs = options,
            logger = logger,
        )

        val words = loadWords()
        val argProviders = loadArgProviders()

        val argumentBuilder = ArgumentBuilder(
            logger = logger,
            words = words,
            argProviders = argProviders,
            collectionSize = options[KSP_ARG_COLLECTION_SIZE]?.toIntOrNull() ?: DEFAULT_COLLECTION_SIZE,
        )

        val requestedMode = GenerationMode.from(options[KSP_ARG_GENERATE_KEY])

        val gateArgName = options[KSP_ARG_GATE_NAME_KEY] ?: DEFAULT_GATE_ARG_NAME
        val gateEnabled = options[gateArgName] == "true"

        val mode = when (requestedMode) {
            GenerationMode.AUTO -> if (isAndroidCompilation(resolver)) {
                GenerationMode.WRAPPERS_ONLY
            } else {
                GenerationMode.FACTORIES_ONLY
            }
            else -> requestedMode
        }

        val manifestSink = UnsupportedManifestSink()
        val targets = if (gateEnabled) WithPreviewCollector(logger, manifestSink).collect(resolver) else emptyList()

        val models = ModelCollector(logger).collect(resolver, targets)

        val processedModels: Map<String, MemberName> = models
            .mapNotNull { model ->
                val fqn = declarationFqn(model.declaration) ?: return@mapNotNull null
                val packageName = model.declaration.packageName.asString()
                val functionName = buildFunctionName(model.declaration)
                fqn to MemberName(packageName, functionName)
            }
            .toMap()

        val processedProviders: Map<String, MemberName> = models
            .mapNotNull { model ->
                val fqn = declarationFqn(model.declaration) ?: return@mapNotNull null
                val packageName = model.declaration.packageName.asString()
                val providerName = buildCompoundSimpleName(model.declaration) + "PreviewProvider"
                fqn to MemberName(packageName, providerName)
            }
            .toMap()

        val providerContext = KspProviderContext(
            logger = logger,
            variantExpr = "variant",
            parameterAnnotationFqns = emptyList(),
            processedModelsWithPackage = processedModels.mapValues { (_, memberName) ->
                memberName.packageName to memberName.simpleName
            },
        )

        val emitter = FunctionEmitter(
            logger = logger,
            codeGenerator = codeGenerator,
            processedModels = processedModels,
            typeOverrideSource = typeOverrideSource,
            argumentBuilder = argumentBuilder,
            providerContext = providerContext,
            collectionSize = options[KSP_ARG_COLLECTION_SIZE]?.toIntOrNull() ?: DEFAULT_COLLECTION_SIZE,
            providerVariants = options[KSP_ARG_PROVIDER_VARIANTS]?.toIntOrNull() ?: DEFAULT_PROVIDER_VARIANTS,
        )

        if (mode != GenerationMode.WRAPPERS_ONLY) {
            for (model in models) {
                emitter.emit(model)
            }
        }

        if (mode == GenerationMode.FACTORIES_ONLY || !gateEnabled) return emptyList()

        val defaultBox = options[KSP_ARG_DEFAULT_BOX_KEY].orEmpty()

        val registryFqn = options[KSP_ARG_REGISTRY]
        val catalog = MultipreviewCatalog(logger, resolver).build(registryFqn)

        val namePrefix = options[KSP_ARG_NAME_PREFIX_KEY].orEmpty()
        val nameSuffixes = options[KSP_ARG_NAME_SUFFIXES_KEY]
            ?.split(",")?.map { s -> s.trim() }.orEmpty()

        val surfaceEmitter = SurfaceEmitter(
            logger = logger,
            codeGenerator = codeGenerator,
            processedModels = processedModels,
            processedProviders = processedProviders,
            argumentBuilder = argumentBuilder,
            typeOverrideSource = typeOverrideSource,
            providerContext = providerContext,
            defaultBox = defaultBox,
            catalog = catalog,
            namePrefix = namePrefix,
            nameSuffixes = nameSuffixes,
            strict = options[KSP_ARG_STRICT] == "true",
            unsupportedStub = options[KSP_ARG_UNSUPPORTED_STUB] != "false",
            manifestSink = manifestSink,
        )

        for (target in targets) {
            surfaceEmitter.emit(target)
        }

        manifestSink.writeTo(codeGenerator)

        return emptyList()
    }

    private fun isAndroidCompilation(resolver: Resolver): Boolean =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(ANDROID_CONTEXT_FQN)) != null
}

private const val ANDROID_CONTEXT_FQN = "android.content.Context"
