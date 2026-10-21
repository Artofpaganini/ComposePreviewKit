package com.previewkit.idea.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtAnnotationEntry

internal class RemoveWithPreviewQuickFix(annotationEntry: KtAnnotationEntry) : LocalQuickFix {

    private val entryPointer: SmartPsiElementPointer<KtAnnotationEntry> =
        SmartPointerManager.getInstance(annotationEntry.project).createSmartPsiElementPointer(annotationEntry)

    override fun getFamilyName(): String = FAMILY_NAME

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        entryPointer.element?.delete()
    }

    companion object {
        const val FAMILY_NAME: String = "Remove @WithPreview"
    }
}
