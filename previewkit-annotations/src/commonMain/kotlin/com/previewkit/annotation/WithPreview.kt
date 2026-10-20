package com.previewkit.annotation

/**
 * Помечает @Composable content-функцию для автогенерации @Preview-обёрток.
 * Набор поверхностей берётся из реестра multipreview-аннотаций (ksp-arg previewkit.registry).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class WithPreview(
    val variant: Int = 0,
)
