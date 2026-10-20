package com.previewkit.ksp

internal enum class GenerationMode {
    FACTORIES_ONLY,
    WRAPPERS_ONLY,
    FACTORIES_AND_WRAPPERS,
    AUTO;

    companion object {
        fun from(raw: String?): GenerationMode =
            entries.firstOrNull { mode -> mode.name.equals(raw, ignoreCase = true) }
                ?: FACTORIES_AND_WRAPPERS
    }
}
