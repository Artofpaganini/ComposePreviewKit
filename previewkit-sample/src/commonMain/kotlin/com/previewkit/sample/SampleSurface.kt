package com.previewkit.sample

import androidx.compose.runtime.Composable

@Composable
fun SampleBox(content: @Composable () -> Unit) {
    content()
}
