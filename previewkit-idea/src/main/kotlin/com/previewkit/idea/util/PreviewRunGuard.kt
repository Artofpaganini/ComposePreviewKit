package com.previewkit.idea.util

import java.util.concurrent.atomic.AtomicBoolean

internal object PreviewRunGuard {

    private val busy = AtomicBoolean(false)

    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)

    fun release() {
        busy.set(false)
    }
}
