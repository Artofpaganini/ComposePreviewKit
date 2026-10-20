package com.previewkit.config

import com.squareup.kotlinpoet.CodeBlock

/**
 * SPI-провайдер мок-вызова по FQN типа.
 *
 * Реализуется проектом и регистрируется через ServiceLoader
 * (META-INF/services/com.previewkit.config.TypeMockProvider).
 * Используется ядром для подстановки мок-фабрик по типу параметра.
 */
public interface TypeMockProvider {
    public fun mockCall(typeFqn: String, context: ProviderContext): CodeBlock?
}
