package com.previewkit.idea.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.previewkit.idea.util.GradleModuleLocator
import com.previewkit.idea.util.GradleRunResult
import com.previewkit.idea.util.GradleTaskRunner
import com.previewkit.idea.util.PreviewKitNotifications
import com.previewkit.idea.util.PreviewRunGuard
import java.io.File

internal class PreviewCoverageReportAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: VirtualFile.EMPTY_ARRAY
        if (!PreviewRunGuard.tryAcquire()) {
            showBalloon(project, ALREADY_RUNNING_MESSAGE, informational = true)
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "PreviewKit: Coverage Report",
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    coverage(project, files, indicator)
                } finally {
                    PreviewRunGuard.release()
                }
            }
        })
    }

    private fun coverage(
        project: Project,
        files: Array<VirtualFile>,
        indicator: ProgressIndicator,
    ) {
                val buildRoot = GradleModuleLocator.selectedBuildRoot(project, files)
                if (buildRoot == null) {
                    LOG.info("PreviewKit: coverage files=${files.size} root=none")
                    return
                }
                LOG.info("PreviewKit: coverage files=${files.size} root=$buildRoot tasks=[$COVERAGE_TASK]")
                indicator.text = "PreviewKit: running $COVERAGE_TASK in ${File(buildRoot).name}"
                val result = GradleTaskRunner.run(
                    buildRoot,
                    listOf(COVERAGE_TASK),
                    onOutputLine = { line -> indicator.text = "PreviewKit gradle: ${line.take(140)}" },
                    isCanceled = { indicator.isCanceled },
                )
                when {
                    result.cancelled -> showBalloon(project, CANCELLED_MESSAGE, informational = true)
                    result.isSuccess -> openReport(project)
                    else -> showBalloon(project, result.failureMessage(), informational = false)
                }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun openReport(project: Project) {
        val reportFile = findReportFile(project)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (reportFile == null) {
                PreviewKitNotifications.warning(project, TITLE, REPORT_NOT_FOUND_MESSAGE)
                return@invokeLater
            }
            FileEditorManager.getInstance(project).openFile(reportFile, true)
        }
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

    private fun findReportFile(project: Project): VirtualFile? {
        val basePath = project.basePath ?: return null
        val reportPath = File(basePath, REPORT_RELATIVE_PATH).toPath()
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(reportPath)
    }

    private companion object {
        private val LOG: Logger = Logger.getInstance(PreviewCoverageReportAction::class.java)
        const val TITLE: String = "PreviewKit"
        const val COVERAGE_TASK: String = "previewCoverageReport"
        const val REPORT_RELATIVE_PATH: String = "build/reports/previewkit-coverage.txt"
        const val REPORT_NOT_FOUND_MESSAGE: String =
            "Coverage report was not found. Build the project and try again."
        const val CANCELLED_MESSAGE: String = "Coverage report cancelled."
        const val ALREADY_RUNNING_MESSAGE: String =
            "PreviewKit task is already running. Wait for it to finish."
    }
}
