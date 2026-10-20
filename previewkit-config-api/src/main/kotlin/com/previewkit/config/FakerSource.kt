package com.previewkit.config

/**
 * SPI-источник фейковых текстовых данных для генерации превью.
 *
 * Реализуется проектом и регистрируется через ServiceLoader
 * (META-INF/services/com.previewkit.config.FakerSource).
 * Дефолтная реализация ядра используется, если проект не предоставил свою.
 */
public interface FakerSource {
    public fun words(): List<String>
    public fun value(typeFqn: String, seed: Int): String?
}
