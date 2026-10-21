package com.previewkit.idea.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElementVisitor
import com.previewkit.idea.manifest.UnsupportedManifestEntry
import com.previewkit.idea.manifest.UnsupportedManifestKind
import com.previewkit.idea.manifest.UnsupportedManifestService
import com.previewkit.idea.util.WithPreviewMatcher
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtVisitorVoid

internal class UnsupportedParametersManifestInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                val file = function.containingKtFile
                val functionName = function.name ?: return
                val annotationEntry = WithPreviewMatcher.annotationEntry(
                    function,
                    WithPreviewMatcher.annotationShortNames(file),
                ) ?: return
                val sourceFilePath = file.virtualFile?.path ?: return
                val module = ModuleUtilCore.findModuleForPsiElement(function) ?: return
                val packageFqn = file.packageFqName.asString()
                val records = UnsupportedManifestService
                    .getInstance(function.project)
                    .entriesFor(module)
                    .filter { record -> record.kind == UnsupportedManifestKind.UNSUPPORTED_PARAMETER }
                    .filter { record ->
                        matchesRecord(record, functionName, packageFqn, sourceFilePath, function)
                    }
                    .distinct()
                for (record in records) {
                    holder.registerProblem(
                        annotationEntry,
                        problemMessage(record),
                        ProblemHighlightType.WARNING,
                        RemoveWithPreviewQuickFix(annotationEntry),
                    )
                }
            }
        }
    }

    private fun matchesRecord(
        record: UnsupportedManifestEntry,
        functionName: String,
        packageFqn: String,
        sourceFilePath: String,
        function: KtNamedFunction,
    ): Boolean {
        if (record.functionName != functionName) return false
        if (record.packageName != packageFqn) return false
        if (!matchesSourcePath(record.sourceFilePath, sourceFilePath)) return false
        if (record.paramName.isEmpty()) return true
        return function.valueParameters.any { parameter -> parameter.name == record.paramName }
    }

    private fun matchesSourcePath(recordPath: String, sourceFilePath: String): Boolean {
        if (recordPath.isEmpty()) return true
        val normalizedRecord = recordPath.replace(WINDOWS_SEPARATOR_CHAR, UNIX_SEPARATOR_CHAR)
        val normalizedSource = sourceFilePath.replace(WINDOWS_SEPARATOR_CHAR, UNIX_SEPARATOR_CHAR)
        return normalizedRecord == normalizedSource ||
            normalizedRecord.endsWith("/$normalizedSource") ||
            normalizedSource.endsWith("/$normalizedRecord")
    }

    private fun problemMessage(record: UnsupportedManifestEntry): String {
        val parameter =
            if (record.paramName.isEmpty()) "A required parameter" else "Parameter '${record.paramName}'"
        return "$parameter of type '${record.typeFqn}' is not supported by the last " +
            "PreviewKit generation: ${record.reason}."
    }

    private companion object {
        const val WINDOWS_SEPARATOR_CHAR: Char = '\\'
        const val UNIX_SEPARATOR_CHAR: Char = '/'
    }
}
