package com.previewkit.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

private const val MODULE_KSP_ROOT_SUFFIX = "/build/generated/ksp"
private const val ROOT_KSP_ROOT = "build/generated/ksp"
private const val MANIFEST_PATH_SUFFIX = "/previewkit/manifest/unsupported-parameters.txt"
private const val MANIFEST_HEADER = "previewkit-unsupported-v1"
private const val KIND_UNSUPPORTED_PARAMETER = "unsupported_parameter"
private const val KIND_PRIVATE_OR_LOCAL_FUNCTION = "private_or_local_function"
private const val MANIFEST_FIELD_COUNT = 7
private const val WRAPPERS_DIRECTORY = "android/androidMain/kotlin"
private const val WRAPPER_FILE_SUFFIX = "Preview.kt"
private const val REPORT_DIRECTORY = "build/reports"
private const val REPORT_FILE_NAME = "previewkit-coverage.txt"
private const val REPORT_TITLE = "PreviewKit preview coverage report"
private const val LINE_SEPARATOR = '\n'
private const val FIELD_SEPARATOR = '\t'
private const val PERCENT_CHAR = '%'

internal data class ManifestEntry(
    val kind: String,
    val functionName: String,
    val paramName: String,
    val reason: String,
)

internal data class ModuleCoverage(
    val name: String,
    val wrapperCount: Int,
    val entries: List<ManifestEntry>,
    val hasManifest: Boolean,
)

internal data class ModuleKspRoot(
    val moduleName: String,
    val directory: File,
)

@DisableCachingByDefault(because = "manual diagnostic report, always executed fresh")
internal abstract class PreviewCoverageReportTask : DefaultTask() {

    @TaskAction
    internal fun generateReport() {
        val root = project.rootDir
        val modules = collectModuleCoverage(root)
        val lines = buildReportLines(modules)
        val reportFile = writeReportFile(root, lines)
        for (line in lines) {
            logger.lifecycle(line)
        }
        logger.lifecycle("PreviewKit: coverage report written to ${reportFile.invariantSeparatorsPath}")
    }

    private fun collectModuleCoverage(root: File): List<ModuleCoverage> {
        return collectKspRoots(root)
            .sortedBy { kspRoot -> kspRoot.moduleName }
            .map { kspRoot -> collectCoverage(kspRoot) }
    }

    private fun collectCoverage(kspRoot: ModuleKspRoot): ModuleCoverage {
        val manifestFiles = kspRoot.directory.walkTopDown()
            .filter { file -> file.isFile && file.invariantSeparatorsPath.endsWith(MANIFEST_PATH_SUFFIX) }
            .toList()
        val entries = manifestFiles
            .flatMap { file -> parseManifest(file) }
            .distinct()
        return ModuleCoverage(
            name = kspRoot.moduleName,
            wrapperCount = countWrappers(kspRoot.directory),
            entries = entries,
            hasManifest = manifestFiles.isNotEmpty(),
        )
    }

    private fun collectKspRoots(root: File): List<ModuleKspRoot> {
        return root.walkTopDown()
            .onEnter { directory -> canEnter(directory, root) }
            .filter { directory -> directory.isDirectory && isModuleKspRoot(directory, root) }
            .map { directory -> ModuleKspRoot(moduleNameOf(directory, root), directory) }
            .toList()
    }

    private fun canEnter(directory: File, root: File): Boolean {
        val name = directory.name
        if (name.startsWith(".") || name == "node_modules") return false
        val relative = directory.relativeTo(root).invariantSeparatorsPath
        val insideBuild = when {
            relative == "build" -> ""
            relative.startsWith("build/") -> relative.removePrefix("build/")
            else -> relative.substringAfter("/build/", missingDelimiterValue = "")
        }
        return insideBuild.isEmpty() ||
            insideBuild == "generated" ||
            insideBuild.startsWith("generated/ksp")
    }

    private fun isModuleKspRoot(directory: File, root: File): Boolean {
        val relative = directory.relativeTo(root).invariantSeparatorsPath
        return relative == ROOT_KSP_ROOT || relative.endsWith(MODULE_KSP_ROOT_SUFFIX)
    }

    private fun moduleNameOf(directory: File, root: File): String {
        val moduleRelative = directory.relativeTo(root).invariantSeparatorsPath
            .removeSuffix(MODULE_KSP_ROOT_SUFFIX)
            .removePrefix(ROOT_KSP_ROOT)
        return moduleRelative.ifEmpty { root.name }
    }

    private fun countWrappers(kspDirectory: File): Int {
        val wrappersDirectory = File(kspDirectory, WRAPPERS_DIRECTORY)
        if (!wrappersDirectory.isDirectory) return 0
        return wrappersDirectory.walkTopDown()
            .count { file -> file.isFile && file.name.endsWith(WRAPPER_FILE_SUFFIX) }
    }

    private fun parseManifest(file: File): List<ManifestEntry> {
        val lines = file.readLines()
        if (lines.firstOrNull() != MANIFEST_HEADER) return emptyList()
        return lines.drop(1).mapNotNull { line -> parseEntry(line) }
    }

    private fun parseEntry(line: String): ManifestEntry? {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != MANIFEST_FIELD_COUNT) return null
        return ManifestEntry(
            kind = percentDecode(fields[0]),
            functionName = percentDecode(fields[3]),
            paramName = percentDecode(fields[4]),
            reason = percentDecode(fields[6]),
        )
    }

    private fun percentDecode(value: String): String {
        if (PERCENT_CHAR !in value) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val hexEnd = index + 2
            if (char == PERCENT_CHAR && hexEnd < value.length && isHexDigit(value[index + 1]) && isHexDigit(value[hexEnd])) {
                builder.append(value.substring(index + 1, hexEnd + 1).toInt(radix = 16).toChar())
                index = hexEnd + 1
            } else {
                builder.append(char)
                index += 1
            }
        }
        return builder.toString()
    }

    private fun isHexDigit(char: Char): Boolean {
        return char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
    }

    private fun buildReportLines(modules: List<ModuleCoverage>): List<String> {
        val totalWrappers = modules.sumOf { module -> module.wrapperCount }
        val modulesWithPreviews = modules.count { module -> module.hasManifest || module.wrapperCount > 0 }
        val totalProblems = modules.sumOf { module -> module.entries.size }
        val lines = mutableListOf(
            REPORT_TITLE,
            "Total: $totalWrappers ${pluralize(totalWrappers, "wrapper", "wrappers")} in " +
                "$modulesWithPreviews ${pluralize(modulesWithPreviews, "module", "modules")} with previews, " +
                "$totalProblems ${pluralize(totalProblems, "problem", "problems")}",
            "",
        )
        for (module in modules) {
            if (!module.hasManifest) {
                lines += "module ${module.name}: no manifest - not built or previews disabled"
                continue
            }
            lines += "module ${module.name}: wrappers ${module.wrapperCount}"
            appendManifestEntries(module, lines)
            lines += ""
        }
        return lines
    }

    private fun appendManifestEntries(module: ModuleCoverage, lines: MutableList<String>) {
        val unsupported = module.entries.filter { entry -> entry.kind == KIND_UNSUPPORTED_PARAMETER }
        val privateLocal = module.entries.filter { entry -> entry.kind == KIND_PRIVATE_OR_LOCAL_FUNCTION }
        lines += "  unsupported parameters (${unsupported.size}):"
        for (entry in unsupported) {
            lines += "    ${formatParameterTarget(entry)} -> ${entry.reason}"
        }
        lines += "  private/local functions (${privateLocal.size}):"
        for (entry in privateLocal) {
            lines += "    ${entry.functionName}"
        }
    }

    private fun pluralize(count: Int, singular: String, plural: String): String {
        return if (count == 1) singular else plural
    }

    private fun formatParameterTarget(entry: ManifestEntry): String {
        if (entry.paramName.isEmpty()) return "${entry.functionName} (unnamed param)"
        return "${entry.functionName}.${entry.paramName}"
    }

    private fun writeReportFile(root: File, lines: List<String>): File {
        val reportDirectory = File(root, REPORT_DIRECTORY)
        reportDirectory.mkdirs()
        val reportFile = File(reportDirectory, REPORT_FILE_NAME)
        reportFile.writeText(lines.joinToString(separator = LINE_SEPARATOR.toString()))
        return reportFile
    }
}
