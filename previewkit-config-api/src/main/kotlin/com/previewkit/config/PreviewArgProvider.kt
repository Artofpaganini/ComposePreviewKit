package com.previewkit.config

import com.squareup.kotlinpoet.CodeBlock

/**
 * SPI-провайдер значения аргумента превью для заданного типа.
 *
 * Реализуется проектом и регистрируется через ServiceLoader
 * (META-INF/services/com.previewkit.config.PreviewArgProvider).
 * Первый провайдер, вернувший не-null, побеждает в цепочке.
 */
public interface PreviewArgProvider {
    public fun provide(typeFqn: String, context: ProviderContext): CodeBlock?
}
