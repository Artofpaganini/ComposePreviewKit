package com.previewkit.annotation

/**
 * Помечает аннотацию проекта как preview-поверхность. id - стабильный ключ поверхности,
 * boxFqn - FQN @Composable-бокса-обёртки (тема/Surface/DeviceState); пустая строка означает
 * использование defaultBox из DSL gradle-плагина. Вешается на аннотацию проекта,
 * несущую device-spec @Preview под ней.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class PreviewSurface(
    val id: String,
    val boxFqn: String = "",
)
