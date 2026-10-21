package com.previewkit.idea.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.previewkit.idea.manifest.UnsupportedManifestService
import com.previewkit.idea.util.GradleModuleLocator
import com.previewkit.idea.util.GradleRunResult
import com.previewkit.idea.util.GradleTaskRunner
import com.previewkit.idea.util.PreviewKitNotifications
import com.previewkit.idea.util.PreviewRunGuard
import java.io.File

internal class RegeneratePreviewsAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        if (!PreviewRunGuard.tryAcquire()) {
            showInfo(project, ALREADY_RUNNING_MESSAGE)
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "PreviewKit: Regenerate Previews",
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    regenerate(project, files, indicator)
                } finally {
                    PreviewRunGuard.release()
                }
            }
        })
    }

    private fun regenerate(project: Project, files: Array<VirtualFile>, indicator: ProgressIndicator) {
        val modules = GradleModuleLocator.selectedModules(project, files)
        val markerTargets = GradleModuleLocator.buildTargets(modules)
        val buildTargets = markerTargets
            .ifEmpty { GradleModuleLocator.buildFallbackTargets(project, files) }
        val mode = when {
            markerTargets.isNotEmpty() -> "markers"
            buildTargets.isNotEmpty() -> "fallback"
            else -> "empty"
        }
        LOG.info(
            "PreviewKit: regenerate files=${files.size} modules=${modules.size} " +
                "mode=$mode targets=$buildTargets",
        )
        if (buildTargets.isEmpty()) {
            showWarning(project, NO_MODULES_MESSAGE)
            return
        }
        for ((buildRootPath, taskNames) in buildTargets) {
            indicator.text = "PreviewKit: running ${taskNames.joinToString(" ")} in ${File(buildRootPath).name}"
            val result = when (mode) {
                "fallback" -> runWithSelectorCascade(buildRootPath, indicator)
                else -> GradleTaskRunner.run(
                    buildRootPath,
                    taskNames,
                    onOutputLine = { line -> indicator.text = "PreviewKit gradle: ${line.take(140)}" },
                    isCanceled = { indicator.isCanceled },
                )
            }
            notifyCompletion(project, buildRootPath, result)
            if (result.cancelled) return
        }
        UnsupportedManifestService.getInstance(project).invalidate()
    }

    private fun runWithSelectorCascade(
        buildRootPath: String,
        indicator: ProgressIndicator,
    ): GradleRunResult {
        var result = GradleTaskRunner.run(
            buildRootPath,
            listOf(FALLBACK_TASK_KSP_ANDROID_MAIN),
            onOutputLine = { line -> indicator.text = "PreviewKit gradle: ${line.take(140)}" },
            isCanceled = { indicator.isCanceled },
        )
        if (!result.isSuccess && result.failureMessage().contains(NOT_FOUND_MARKER, ignoreCase = true)) {
            result = GradleTaskRunner.run(
                buildRootPath,
                listOf(FALLBACK_TASK_KSP_DEBUG_KOTLIN),
                onOutputLine = { line -> indicator.text = "PreviewKit gradle: ${line.take(140)}" },
                isCanceled = { indicator.isCanceled },
            )
        }
        return result
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val visible = project != null && !files.isNullOrEmpty() &&
            GradleModuleLocator.selectedModules(project, files).isNotEmpty()
        event.presentation.isEnabledAndVisible = visible
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun notifyCompletion(project: Project, buildRootPath: String, result: GradleRunResult) {
        val content = when {
            result.cancelled -> CANCELLED_MESSAGE
            result.isSuccess -> REGENERATED_MESSAGE.format(File(buildRootPath).name)
            else -> result.failureMessage()
        }
        showBalloon(project, content, result.isSuccess || result.cancelled)
    }

    private fun showWarning(project: Project, content: String) {
        showBalloon(project, content, informational = false)
    }

    private fun showInfo(project: Project, content: String) {
        showBalloon(project, content, informational = true)
    }

    private fun showBalloon(project: Project, content: String, informational: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (informational) {
                PreviewKitNotifications.info(project, TITLE, content)
            } else {
                PreviewKitNotifications.warning(project, TITLE, content)
            }
        }
    }

    private companion object {
        private val LOG: Logger = Logger.getInstance(RegeneratePreviewsAction::class.java)
        const val TITLE: String = "PreviewKit"
        const val NO_MODULES_MESSAGE: String =
            "No modules with PreviewKit KSP wiring were detected in the selection."
        const val REGENERATED_MESSAGE: String = "Previews regenerated (root=%s)"
        const val CANCELLED_MESSAGE: String = "Regeneration cancelled."
        const val ALREADY_RUNNING_MESSAGE: String =
            "PreviewKit task is already running. Wait for it to finish."
        const val NOT_FOUND_MARKER: String = "not found"
        const val FALLBACK_TASK_KSP_ANDROID_MAIN: String = "kspAndroidMain"
        const val FALLBACK_TASK_KSP_DEBUG_KOTLIN: String = "kspDebugKotlin"
    }
}
