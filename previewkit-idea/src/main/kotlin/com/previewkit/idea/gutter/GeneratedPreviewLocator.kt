package com.previewkit.idea.gutter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.previewkit.idea.util.PreviewKitNotifications
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.File
import java.nio.file.Path

internal object GeneratedPreviewLocator {

    private const val FILE_SUFFIX: String = "Preview.kt"

    private val GENERATED_ROOTS: List<String> = listOf(
        "build/generated/ksp/android/androidMain/kotlin",
        "build/generated/ksp/metadata/commonMain/kotlin",
    )

    private const val NOT_FOUND_TITLE: String = "PreviewKit"
    private const val NOT_FOUND_MESSAGE: String =
        "Generated preview for '%s' was not found. Build the module or run Regenerate Previews, then retry."

    fun openGeneratedPreview(function: KtNamedFunction, project: Project) {
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val candidatePaths = ReadAction.compute<List<Path>, RuntimeException> {
                locateCandidatePaths(function)
            }
            val located = candidatePaths
                .filter { candidate -> candidate.toFile().isFile }
                .firstNotNullOfOrNull { candidate -> resolve(candidate) }
            application.invokeLater {
                if (project.isDisposed) return@invokeLater
                if (located == null) {
                    val functionName = function.name ?: "function"
                    PreviewKitNotifications.info(
                        project,
                        NOT_FOUND_TITLE,
                        NOT_FOUND_MESSAGE.format(functionName),
                    )
                    return@invokeLater
                }
                FileEditorManager.getInstance(project).openFile(located, true)
            }
        }
    }

    private fun locateCandidatePaths(function: KtNamedFunction): List<Path> {
        val module = ModuleUtilCore.findModuleForPsiElement(function) ?: return emptyList()
        val functionName = function.name ?: return emptyList()
        val packagePath = function.containingKtFile
            ?.packageFqName
            ?.asString()
            ?.replace('.', '/')
            ?: return emptyList()
        val relativePath = "$packagePath/$functionName$FILE_SUFFIX"
        val contentRoots = ModuleRootManager.getInstance(module).contentRoots
        return contentRoots.flatMap { contentRoot ->
            GENERATED_ROOTS.map { generatedRoot ->
                File(contentRoot.path, "$generatedRoot/$relativePath").toPath()
            }
        }
    }

    private fun resolve(path: Path): VirtualFile? {
        val fileSystem = LocalFileSystem.getInstance()
        fileSystem.findFileByNioFile(path)?.let { found -> return found }
        return fileSystem.refreshAndFindFileByNioFile(path)
    }
}
