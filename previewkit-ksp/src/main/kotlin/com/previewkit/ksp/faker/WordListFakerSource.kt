package com.previewkit.ksp.faker

import com.previewkit.config.FakerSource
import java.util.ServiceLoader

internal val DEFAULT_WORDS: List<String> = listOf(
    "apple",
    "brave",
    "cloud",
    "dream",
    "eagle",
    "flame",
    "grace",
    "heart",
    "ivory",
    "jewel",
    "karma",
    "lemon",
    "magic",
    "noble",
    "ocean",
    "pearl",
    "quiet",
    "river",
    "stone",
    "tiger",
    "ultra",
    "vivid",
    "water",
    "xenon",
    "youth",
    "zenith",
    "amber",
    "bliss",
    "coral",
    "dusk",
    "ember",
    "fable",
    "glade",
    "honey",
    "inlet",
    "jade",
    "knoll",
    "lunar",
    "maple",
    "noon",
    "opal",
    "prism",
    "quartz",
    "ridge",
    "solstice",
    "tundra",
    "umber",
    "valley",
    "willow",
    "xeric",
)

/**
 * Дефолтная реализация FakerSource на основе встроенного словаря.
 * Проект перекрывает через ServiceLoader, зарегистрировав свою реализацию FakerSource.
 */
internal class WordListFakerSource : FakerSource {
    override fun words(): List<String> = DEFAULT_WORDS
    override fun value(typeFqn: String, seed: Int): String? = null
}

internal fun loadWords(): List<String> {
    val projectSource = ServiceLoader
        .load(FakerSource::class.java, FakerSource::class.java.classLoader)
        .firstOrNull()
    return projectSource?.words() ?: DEFAULT_WORDS
}
