package com.previewkit.idea.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.previewkit.idea.util.WithPreviewMatcher
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.lexer.KtTokens

internal class PrivateWithPreviewInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                val file = function.containingKtFile ?: return
                val shortNames = WithPreviewMatcher.annotationShortNames(file)
                val entry = WithPreviewMatcher.annotationEntry(function, shortNames) ?: return
                if (!function.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
                holder.registerProblem(
                    entry,
                    "@WithPreview is ignored on private functions: the KSP processor skips them, " +
                        "so no preview will be generated. Make the function non-private or remove the annotation.",
                    ProblemHighlightType.WARNING,
                )
            }
        }
    }
}
