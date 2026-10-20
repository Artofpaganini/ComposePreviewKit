package com.previewkit.gradle

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * DSL-конфиг плагина PreviewKit. Значения маппятся в ksp-аргументы процессора.
 *
 * - enabled - гейт wrapper-фазы. false -> обёртки не генерятся (release/CI без флага).
 * - gateArgName - имя ksp-арга гейта, если проект хочет своё вместо previewkit.enabled.
 * - defaultBox - FQN box-обёртки для маркеров с пустым boxFqn.
 * - typeFactory / kspArg - escape hatch: проброс типовых фабрик и произвольных ksp-аргов без SPI-модуля.
 */
abstract class PreviewToolkitExtension {

    abstract val enabled: Property<Boolean>

    abstract val gateArgName: Property<String>

    abstract val defaultBox: Property<String>

    abstract val typeFactories: MapProperty<String, String>

    abstract val extraArgs: MapProperty<String, String>

    fun typeFactory(typeFqn: String, factoryFqn: String) {
        typeFactories.put(typeFqn, factoryFqn)
    }

    fun kspArg(key: String, value: String) {
        extraArgs.put(key, value)
    }
}
