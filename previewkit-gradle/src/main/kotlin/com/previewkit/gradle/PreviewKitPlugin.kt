package com.previewkit.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

private const val KSP_PLUGIN_ID = "com.google.devtools.ksp"
private const val KMP_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
private const val ANDROID_LIBRARY_PLUGIN_ID = "com.android.library"
private const val ANDROID_APPLICATION_PLUGIN_ID = "com.android.application"

private const val ARTIFACT_GROUP = "io.github.artofpaganini.previewkit"
private const val ARTIFACT_VERSION = "0.1.0"
private const val ANNOTATIONS_COORDINATES = "$ARTIFACT_GROUP:previewkit-annotations:$ARTIFACT_VERSION"
private const val KSP_CORE_COORDINATES = "$ARTIFACT_GROUP:previewkit-ksp:$ARTIFACT_VERSION"

private const val KSP_COMMON_METADATA_CONFIG = "kspCommonMainMetadata"
private const val KSP_COMMON_METADATA_TASK = "kspCommonMainKotlinMetadata"
private const val GENERATED_COMMON_MAIN_DIR = "build/generated/ksp/metadata/commonMain/kotlin"
private const val KSP_DEBUG_CONFIG = "kspDebug"
private const val KSP_DEBUG_TASK = "kspDebugKotlin"
private const val COMPILE_ONLY_CONFIG = "compileOnly"

private const val COMMON_MAIN_SOURCE_SET = "commonMain"
private const val PREVIEWS_TASK = "previews"
private const val PREVIEWS_TASK_GROUP = "preview"

private const val KSP_ARG_GATE_NAME_KEY = "previewkit.gateArgName"
private const val KSP_ARG_DEFAULT_BOX = "previewkit.defaultBox"
private const val KSP_ARG_FACTORIES = "previewMock.factories"
private const val DEFAULT_GATE_ARG_NAME = "previewkit.enabled"

private const val FACTORY_ENTRY_SEPARATOR = ","
private const val FACTORY_KV_SEPARATOR = ":"

/**
 * Единая точка подключения PreviewKit к модулю-потребителю (KMP или plain-Android).
 *
 * apply делает:
 * - применяет ksp-плагин;
 * - KMP: вешает процессор на kspCommonMainMetadata, добавляет generated-srcDir в commonMain
 *   и зависимость на previewkit-annotations;
 * - Android (library/application): вешает процессор на kspDebug (выход KSP кладёт в debug
 *   sourceSet сам), аннотации - compileOnly, чтобы они не попали в рантайм; сгенерированные
 *   обёртки требуют androidx compose tooling-preview в debug-классpath потребителя;
 * - вешает гейт wrapper-фазы и пробрасывает конфиг DSL в ksp-аргументы;
 * - регистрирует таск previews для регенерации превью без полного билда.
 */
class PreviewKitPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val extension = target.extensions.create("previewToolkit", PreviewToolkitExtension::class.java)
        applyDefaults(extension)

        target.pluginManager.apply(KSP_PLUGIN_ID)

        target.pluginManager.withPlugin(KMP_PLUGIN_ID) {
            wireCommonMain(target)
            wireCompilationDependencies(target)
            registerPreviewsTask(target, KSP_COMMON_METADATA_TASK)
        }

        target.pluginManager.withPlugin(ANDROID_LIBRARY_PLUGIN_ID) {
            wireAndroidDebug(target)
            registerPreviewsTask(target, KSP_DEBUG_TASK)
        }

        target.pluginManager.withPlugin(ANDROID_APPLICATION_PLUGIN_ID) {
            wireAndroidDebug(target)
            registerPreviewsTask(target, KSP_DEBUG_TASK)
        }

        target.afterEvaluate { evaluated ->
            evaluated.dependencies.add(KSP_COMMON_METADATA_CONFIG, KSP_CORE_COORDINATES)
            evaluated.configurations.matching { config -> config.name == KSP_DEBUG_CONFIG }
                .configureEach { config ->
                    config.dependencies.add(evaluated.dependencies.create(KSP_CORE_COORDINATES))
                }
            configureKspArguments(evaluated, extension)
        }
    }

    private fun applyDefaults(extension: PreviewToolkitExtension) {
        extension.enabled.convention(false)
        extension.gateArgName.convention(DEFAULT_GATE_ARG_NAME)
        extension.defaultBox.convention("")
    }

    private fun wireCommonMain(target: Project) {
        val kotlin = target.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val commonMain = kotlin.sourceSets.getByName(COMMON_MAIN_SOURCE_SET)
        commonMain.kotlin.srcDir(GENERATED_COMMON_MAIN_DIR)
        commonMain.dependencies {
            implementation(ANNOTATIONS_COORDINATES)
        }
    }

    private fun wireCompilationDependencies(target: Project) {
        // KSP creates the metadata task lazily and not every KMP layout has it (single jvm target),
        // so the dependency stays a lazy provider and silently resolves to nothing when absent
        target.tasks.withType(KotlinCompilationTask::class.java).configureEach { task ->
            if (task.name != KSP_COMMON_METADATA_TASK) {
                task.dependsOn(
                    target.provider {
                        target.tasks.matching { candidate -> candidate.name == KSP_COMMON_METADATA_TASK }
                    },
                )
            }
        }
    }

    private fun registerPreviewsTask(target: Project, kspTaskName: String) {
        target.tasks.register(PREVIEWS_TASK) { task ->
            task.group = PREVIEWS_TASK_GROUP
            task.description = "Regenerate @WithPreview Compose previews (KSP only, no full build)"
            task.dependsOn(kspTaskName)
        }
    }

    private fun wireAndroidDebug(target: Project) {
        target.dependencies.add(COMPILE_ONLY_CONFIG, ANNOTATIONS_COORDINATES)
    }

    private fun configureKspArguments(target: Project, extension: PreviewToolkitExtension) {
        val gateArgName = extension.gateArgName.getOrElse(DEFAULT_GATE_ARG_NAME)
        val gateEnabled = resolveGate(target, extension, gateArgName)

        val ksp = target.extensions.getByType(KspExtension::class.java)
        ksp.arg(KSP_ARG_GATE_NAME_KEY, gateArgName)
        ksp.arg(gateArgName, gateEnabled.toString())

        val defaultBox = extension.defaultBox.getOrElse("")
        if (defaultBox.isNotEmpty()) {
            ksp.arg(KSP_ARG_DEFAULT_BOX, defaultBox)
        }

        val factories = extension.typeFactories.getOrElse(emptyMap())
        if (factories.isNotEmpty()) {
            val joined = factories.entries.joinToString(FACTORY_ENTRY_SEPARATOR) { entry ->
                "${entry.key}$FACTORY_KV_SEPARATOR${entry.value}"
            }
            ksp.arg(KSP_ARG_FACTORIES, joined)
        }

        extension.extraArgs.getOrElse(emptyMap()).forEach { (key, value) ->
            ksp.arg(key, value)
        }
    }

    private fun resolveGate(target: Project, extension: PreviewToolkitExtension, gateArgName: String): Boolean {
        val enabledByDsl = extension.enabled.getOrElse(false)
        val enabledByProperty = target.providers.gradleProperty(gateArgName)
            .map { value -> value.toBoolean() }
            .getOrElse(false)
        val previewsTaskRequested = target.gradle.startParameter.taskNames.any { name ->
            name.substringAfterLast(':') == PREVIEWS_TASK
        }
        return enabledByDsl || enabledByProperty || previewsTaskRequested
    }
}
