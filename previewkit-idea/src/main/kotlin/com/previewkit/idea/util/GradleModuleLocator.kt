package com.previewkit.idea.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

internal object GradleModuleLocator {

    private const val TASK_KSP_ANDROID_MAIN: String = "kspAndroidMain"
    private const val ROOT_TASK_PATH: String = ":kspAndroidMain"

    private const val SETTINGS_FILE: String = "settings.gradle"
    private const val SETTINGS_KTS_FILE: String = "settings.gradle.kts"

    private data class ModuleCandidate(
        val buildRoot: String,
        val gradlePath: String,
        val rootPath: String,
    )

    fun selectedModules(project: Project, files: Array<VirtualFile>): List<Module> {
        return ReadAction.compute<List<Module>, RuntimeException> {
            val allModules = ModuleManager.getInstance(project).modules.toList()
            allModules.filter { module -> matchesSelection(module, files) }
        }
    }

    fun selectedBuildRoot(project: Project, files: Array<VirtualFile>): String? {
        val modulePaths = ReadAction.compute<List<String>, RuntimeException> {
            selectedModules(project, files).mapNotNull { module -> modulePathOf(module) }
        }
        val selectedRoot = modulePaths.firstNotNullOfOrNull { modulePath -> buildRootOf(modulePath) }
        if (selectedRoot != null) return selectedRoot
        val basePath = project.basePath ?: return null
        return buildRootOf(basePath)
    }

    fun buildTargets(project: Project, files: Array<VirtualFile>): Map<String, List<String>> {
        return ReadAction.compute<Map<String, List<String>>, RuntimeException> {
            val candidates = collectCandidates(project, files)
            val dominant = candidates.filter { candidate -> isDominant(candidate, candidates) }
            val targets = linkedMapOf<String, MutableList<String>>()
            for (candidate in dominant) {
                val taskPath = taskPathOf(candidate.gradlePath)
                targets.getOrPut(candidate.buildRoot) { mutableListOf() } += taskPath
            }
            targets.forEach { (_, tasks) ->
                if (tasks.size > 1) tasks.remove(ROOT_TASK_PATH)
            }
            targets
        }
    }

    private fun collectCandidates(
        project: Project,
        files: Array<VirtualFile>,
    ): List<ModuleCandidate> {
        val candidates = mutableListOf<ModuleCandidate>()
        for (module in ModuleManager.getInstance(project).modules.toList()) {
            val modulePath = modulePathOf(module) ?: continue
            val buildRoot = buildRootOf(modulePath) ?: continue
            val gradlePath = gradlePathOf(buildRoot, modulePath) ?: continue
            for (root in ModuleRootManager.getInstance(module).contentRoots) {
                if (files.any { file -> relates(file.path, root.path) }) {
                    candidates += ModuleCandidate(buildRoot, gradlePath, root.path)
                    break
                }
            }
        }
        return candidates
    }

    private fun isDominant(
        candidate: ModuleCandidate,
        candidates: List<ModuleCandidate>,
    ): Boolean {
        return candidates.none { other ->
            other !== candidate &&
                other.rootPath.length > candidate.rootPath.length &&
                other.rootPath.startsWith(candidate.rootPath)
        }
    }

    private fun taskPathOf(gradlePath: String): String {
        return if (gradlePath == ":") ":$TASK_KSP_ANDROID_MAIN" else "$gradlePath:$TASK_KSP_ANDROID_MAIN"
    }

    private fun matchesSelection(module: Module, files: Array<VirtualFile>): Boolean {
        val roots = ModuleRootManager.getInstance(module).contentRoots
        return roots.any { root -> files.any { selected -> relates(selected.path, root.path) } }
    }

    private fun relates(selectedPath: String, rootPath: String): Boolean {
        return selectedPath == rootPath ||
            selectedPath.startsWith("$rootPath/") ||
            rootPath.startsWith("$selectedPath/")
    }

    private fun modulePathOf(module: Module): String? {
        val external = ExternalSystemApiUtil.getExternalProjectPath(module)
        if (external != null) return external
        return ModuleRootManager.getInstance(module).contentRoots.firstOrNull()?.path
    }

    private fun buildRootOf(modulePath: String): String? {
        var directory: File? = File(modulePath)
        while (directory != null) {
            if (containsSettings(directory)) return directory.absolutePath
            directory = directory.parentFile
        }
        return null
    }

    private fun containsSettings(directory: File): Boolean {
        return File(directory, SETTINGS_FILE).isFile || File(directory, SETTINGS_KTS_FILE).isFile
    }

    private fun gradlePathOf(buildRootPath: String, modulePath: String): String? {
        val buildRootDir = File(buildRootPath).canonicalFile.toPath()
        val moduleDir = File(modulePath).canonicalFile.toPath()
        val relative = buildRootDir.relativize(moduleDir)
        if (relative.startsWith("..")) return null
        if (relative.toString().isEmpty()) return ":"
        return ":" + relative.toString().replace(File.separatorChar, ':')
    }


}
