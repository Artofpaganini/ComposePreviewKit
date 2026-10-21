package com.previewkit.idea.util

import com.intellij.openapi.diagnostic.Logger
import java.io.File

private const val EXIT_SUCCESS: Int = 0
private const val EXIT_CANCELLED: Int = -2

internal data class GradleRunResult(
    val exitCode: Int,
    val outputTail: List<String>,
    val cancelled: Boolean = false,
) {
    val isSuccess: Boolean
        get() = exitCode == EXIT_SUCCESS

    fun failureMessage(): String {
        if (outputTail.isEmpty()) return START_FAILURE_MESSAGE
        val failure = outputTail.indexOfFirst { line -> line.contains(WHAT_WENT_WRONG_MARKER, ignoreCase = true) }
        if (failure >= 0) {
            val details = outputTail.drop(failure + 1).filter { line -> line.isNotBlank() }.take(2)
            if (details.isNotEmpty()) return details.joinToString(separator = "\n")
        }
        val notFound = outputTail.lastOrNull { line -> line.contains(NOT_FOUND_MARKER, ignoreCase = true) }
        if (notFound != null) return "$notFound\n$PLUGIN_HINT"
        return outputTail.lastOrNull { line -> line.isNotBlank() } ?: START_FAILURE_MESSAGE
    }

    companion object {
        fun startFailure(): GradleRunResult = GradleRunResult(exitCode = EXIT_START_FAILURE, outputTail = emptyList())

        private const val EXIT_START_FAILURE: Int = -1
        private const val WHAT_WENT_WRONG_MARKER: String = "What went wrong"
        private const val NOT_FOUND_MARKER: String = "not found"
        private const val PLUGIN_HINT: String = "Apply the com.previewkit plugin somewhere and retry."
        private const val START_FAILURE_MESSAGE: String =
            "Gradle process could not be started. See idea.log for details."
    }
}

internal object GradleTaskRunner {

    private val LOG: Logger = Logger.getInstance(GradleTaskRunner::class.java)

    private const val GRADLE_WRAPPER_COMMAND: String = "./gradlew"
    private const val GRADLE_WRAPPER_FILE_NAME: String = "gradlew"
    private const val OUTPUT_LOG_PREFIX: String = "PreviewKit gradle:"
    private const val OUTPUT_TAIL_LIMIT: Int = 40

    fun run(
        buildRootPath: String,
        taskNames: List<String>,
        onOutputLine: ((String) -> Unit)? = null,
        isCanceled: () -> Boolean = { false },
    ): GradleRunResult {
        LOG.info("PreviewKit: gradle start root=$buildRootPath tasks=$taskNames")
        return try {
            val wrapper = File(buildRootPath, GRADLE_WRAPPER_FILE_NAME)
            if (!wrapper.canExecute()) wrapper.setExecutable(true)
            val process = ProcessBuilder(listOf(GRADLE_WRAPPER_COMMAND) + taskNames)
                .directory(File(buildRootPath))
                .redirectErrorStream(true)
                .start()
            val outputTail = mutableListOf<String>()
            var firstLine: String? = null
            var cancelled = false
            process.inputStream.bufferedReader().useLines { outputLines ->
                for (line in outputLines) {
                    if (isCanceled()) {
                        cancelled = true
                        process.destroy()
                        break
                    }
                    if (firstLine == null) firstLine = line
                    outputTail += line
                    if (outputTail.size > OUTPUT_TAIL_LIMIT) outputTail.removeAt(0)
                    onOutputLine?.invoke(line)
                }
            }
            val exitCode = process.waitFor()
            LOG.info("$OUTPUT_LOG_PREFIX first=$firstLine")
            LOG.info("$OUTPUT_LOG_PREFIX last=${outputTail.lastOrNull()}")
            LOG.info("PreviewKit: gradle exit=$exitCode cancelled=$cancelled root=$buildRootPath")
            GradleRunResult(
                exitCode = if (cancelled) EXIT_CANCELLED else exitCode,
                outputTail = outputTail.toList(),
                cancelled = cancelled,
            )
        } catch (failure: Throwable) {
            LOG.warn("PreviewKit: gradle failed to start, root=$buildRootPath", failure)
            GradleRunResult.startFailure()
        }
    }
}
