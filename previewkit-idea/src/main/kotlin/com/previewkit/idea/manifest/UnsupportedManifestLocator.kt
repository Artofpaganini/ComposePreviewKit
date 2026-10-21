package com.previewkit.idea.manifest

import java.io.File

internal const val MANIFEST_PATH_SUFFIX: String = "/previewkit/manifest/unsupported-parameters.txt"

internal object UnsupportedManifestLocator {

    private const val GENERATED_ROOT: String = "build/generated/ksp"

    fun loadManifests(contentRootPaths: List<String>): Map<String, List<UnsupportedManifestEntry>> {
        return contentRootPaths.associateWith { contentRootPath -> loadManifestsOfRoot(contentRootPath) }
    }

    private fun loadManifestsOfRoot(contentRootPath: String): List<UnsupportedManifestEntry> {
        val generatedRoot = File(contentRootPath, GENERATED_ROOT)
        if (!generatedRoot.isDirectory) return emptyList()
        return generatedRoot.walkTopDown()
            .filter { file -> file.isFile && normalizedPath(file).endsWith(MANIFEST_PATH_SUFFIX) }
            .toList()
            .flatMap { file -> parseManifest(file) }
    }

    private fun parseManifest(file: File): List<UnsupportedManifestEntry> {
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return UnsupportedManifestParser.parse(content)
    }

    private fun normalizedPath(file: File): String = file.path.replace(File.separatorChar, '/')
}
