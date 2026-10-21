package com.previewkit.idea.gutter

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import com.previewkit.idea.util.PreviewIcons
import com.previewkit.idea.util.WithPreviewMatcher
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.awt.event.MouseEvent
import java.util.function.Supplier

internal class WithPreviewLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val function = element as? KtNamedFunction ?: return null
        if (function.name == null) return null
        val file = function.containingKtFile ?: return null
        val annotationShortNames = WithPreviewMatcher.annotationShortNames(file)
        if (!WithPreviewMatcher.isAnnotated(function, annotationShortNames)) return null
        if (!isComposable(function, file)) return null
        return LineMarkerInfo(
            function,
            function.nameIdentifier?.textRange ?: function.textRange,
            PreviewIcons.Gutter,
            Function<KtNamedFunction, String> { markerFunction ->
                "Open generated preview for ${markerFunction.name}"
            },
            GutterNavigationHandler,
            GutterIconRenderer.Alignment.LEFT,
            Supplier { "PreviewKit: open generated preview" },
        )
    }

    private fun isComposable(function: KtNamedFunction, file: org.jetbrains.kotlin.psi.KtFile): Boolean {
        val composableShortNames = WithPreviewMatcher.composableShortNames(file)
        return WithPreviewMatcher.isAnnotated(function, composableShortNames)
    }

    private object GutterNavigationHandler : GutterIconNavigationHandler<KtNamedFunction> {
        override fun navigate(event: MouseEvent, function: KtNamedFunction) {
            GeneratedPreviewLocator.openGeneratedPreview(function, function.project)
        }
    }
}
