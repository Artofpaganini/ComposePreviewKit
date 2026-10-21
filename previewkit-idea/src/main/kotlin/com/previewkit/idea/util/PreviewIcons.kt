package com.previewkit.idea.util

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal object PreviewIcons {

    @JvmField
    val Gutter: Icon = IconLoader.getIcon("/icons/previewkitPreview.svg", PreviewIcons::class.java)
}
