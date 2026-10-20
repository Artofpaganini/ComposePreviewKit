# PreviewKit

Автогенерация Compose-превью для KMP/Android-модулей: разраб помечает content-composable
аннотацией `@WithPreview` - при сборке KSP строит mock-фабрики для всех моделей из его параметров
и `@Preview`-обёртки под все поверхности проекта. Ноль ручных sample-классов.

Актуальная спека и принятые решения: `../.specs/preview-auto-mock.md` (в репозитории Alva).

## Публикация и подключение во внешний проект

## Публикация

Локальная проверка: `./gradlew publishToMavenLocal`.
Maven Central: `./gradlew publishToMavenCentral` (CI-workflow `publish.yml`), требуются секреты
репозитория: `CENTRAL_USERNAME`/`CENTRAL_PASSWORD` (Central User Token) и
`PREVIEWKIT_SIGNING_KEY`/`PREVIEWKIT_SIGNING_PASSWORD` (armored GPG-ключ).

## Подключение во внешний проект

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google { ... }; mavenCentral() } }

// build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "..."
    id("org.jetbrains.kotlin.plugin.compose") version "..."
    id("org.jetbrains.compose") version "..."
    id("com.previewkit") version "0.1.0"
}

import com.previewkit.gradle.PreviewToolkitExtension
configure<PreviewToolkitExtension> {
    enabled.set(true)
    defaultBox.set("...")                       // опционально
    kspArg("previewkit.registry", "...")        // реестр multipreview-аннотаций
}
```

Требования: KMP-проект с >1 таргетом (metadata-компиляция; без неё фабрики не генерируются),
compose-runtime и tooling-preview в зависимостях модуля. Обёртки генерируются только для
android-компиляций; фабрики - в commonMain.

## Состав

| Модуль | Назначение |
|---|---|
| `previewkit-annotations` | `@WithPreview`, `@PreviewMock`, `@PreviewMockIgnore`, `@ConsiderForPreviewMock`, `@WithPreviewArgs`, `@PreviewSurface`, `@PreviewKitAnnotations` |
| `previewkit-ksp` | KSP-процессор: сбор моделей, генерация mock-фабрик и `@Preview`-обёрток |
| `previewkit-config-api` | SPI-контракты: `TypeMockProvider`, `PreviewArgProvider`, `FakerSource` |
| `previewkit-gradle` | Переносимый плагин `com.previewkit` (DSL `previewToolkit`) |
| `previewkit-sample` | Standalone-песочница (jvm + ios) |

В Alva плагин `com.previewkit` не используется - там свой adapter в `build-convention-plugins`
(`cmp-setup` вешает wiring на каждый KMP-модуль автоматически).

## Quick start (разрабу)

```kotlin
@WithPreview
@Composable
internal fun MyScreen(modifier: Modifier = Modifier, uiState: MyUiModel, onAction: (MyAction) -> Unit)
```

Собрать модуль - всё. Генерация раздельная:
- `build/generated/ksp/android/androidMain/kotlin` - `MyScreenPreview.kt`: `@Preview`-функции под
  все поверхности реестра + `CombinedPreview` (только Android; iOS обёрток не получает - в Compose
  нет iOS-превью);
- `build/generated/ksp/metadata/commonMain/kotlin` (пакет модели): `previewMyUiModel(variant = 0)`,
  `previewMyUiModelList(count, variant)`, `MyUiModelPreviewProvider` - платформо-нейтральные
  data-хелперы, доступны из commonMain.

Данные детерминированные: `variant: Int` сдвигает все поля (строки - слова из словаря,
коллекции - 2 различающихся элемента). Обёртки зовут `variant = 0`.

## Управление

| Действие | Как |
|---|---|
| Выключить превью в модуле | `previews { enabled = false }` в build.gradle.kts модуля |
| Выключить везде (CI/Release) | `-Palva.previews=false` |
| Другой вариант данных в обёртке | `@WithPreview(variant = 1)` |
| Особое состояние / свой стейт | рукописное `@Preview`-превью; данные - поверх фабрики: `previewMyUiModel().copy(title = "...")` |
| Все варианты данных рядом | автоматически: `MyScreenAllVariantsPreview` (`@PreviewParameter` по провайдеру) - для экранов с одним обязательным model-параметром |
| Модель вне графа параметров | `@PreviewMock` на классе; исключить - `@PreviewMockIgnore` |
| Форс-переген модуля | `./gradlew :module:kspAndroidMain` (обычно не нужен) |

## Моки по типам

Порядок разрешения: SPI `TypeMockProvider` -> встроенные Compose-типы (`Color`, `Dp`, `TextUnit`,
`Shape`, `Brush`) -> сгенерированные фабрики -> SPI `PreviewArgProvider` -> примитивы/коллекции/
enum/sealed/value class -> inline-сбор data class. Аннотированные Android-ресурс-Int'ы
(`@DrawableRes`/`@StringRes`/`@ColorRes`) получают валидные `android.R.*` плейсхолдеры.

Аннотированные ресурс-Int'ы (`@DrawableRes`/`@StringRes`/`@ColorRes`) получают валидные
плейсхолдеры через `previewPlaceholder*ResId()` (Android: `android.R.*`; iOS: 0).

Свой тип (например `android.net.Uri`): реализуй `TypeMockProvider`, зарегистрируй в
`META-INF/services` (образец - `core/preview-config` в Alva).

## Режимы

- `previewkit.strict=true` (в Alva включён) - неподдерживаемый тип параметра становится ошибкой
  сборки вместо warn; разово погасить: `-Palva.previews.strict=false`, постоянно:
  `previews { strict = false }` в модуле (property способен только отключать - как у гейта).
- Wiring вешается только на compose-модули (детект плагина `org.jetbrains.compose`).

## Release

Сгенерированное превью недостижимо из production-кода - R8 вырезает его из release;
`core:preview`-ресурсы снимаются resource shrinker'ом. Проверено mapping'ом (ноль preview-символов).

## Ограничения

- параметр типа не-data class / неразрешимый вложенный тип -> обёртка не генерируется (warn в
  KSP-логе), превью пишется руками;
- cross-module тип с internal-конструктором не соберётся (видимость Kotlin);
- рендер превью - только Android (Android Studio); iOS generated-код не видит.
