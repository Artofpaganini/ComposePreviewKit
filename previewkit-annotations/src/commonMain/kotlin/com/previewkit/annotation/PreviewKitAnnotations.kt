package com.previewkit.annotation

import kotlin.reflect.KClass

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS)
public annotation class PreviewKitAnnotations(vararg val value: KClass<out Annotation>)
