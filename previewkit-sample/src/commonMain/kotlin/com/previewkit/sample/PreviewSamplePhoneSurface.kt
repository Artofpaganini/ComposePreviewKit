package com.previewkit.sample

import com.previewkit.annotation.PreviewSurface

@PreviewSurface(id = "phone", boxFqn = "com.previewkit.sample.SampleBox")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class PreviewSamplePhoneSurface
