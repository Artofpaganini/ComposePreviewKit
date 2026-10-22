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

### Свои аннотации поверхностей и бокс проекта

Во что заворачивается превью экрана - регулируется реестром: скелет обёртки
генерируется по аннотациям реестра, каждая поверхность несёт свой `boxFqn`
(у Alva это `AlvaPreviewBox`, в другом проекте - бокс на базе темы проекта):

```kotlin
// 1. Реестр поверхностей проекта - class с @PreviewKitAnnotations,
//    внутри - мультипревью-аннотации; поверхности помечены @PreviewSurface
@PreviewKitAnnotations
class XbetPreviewRegistry {

    @Target(AnnotationTarget.FUNCTION)
    @PreviewSurface(id = "panel", boxFqn = "com.xbet.ui.preview.XbetPreviewBox")
    @Preview(name = "Xbet Panel", showBackground = true)
    annotation class PreviewXbetPanel

    @Target(AnnotationTarget.FUNCTION)
    @Preview(name = "Xbet Item", showBackground = true)
    annotation class PreviewXbetItem          // не-surface: завернётся в defaultBox
}

// 2. Wiring - FQN реестра + бокс по умолчанию для не-surface аннотаций
configure<PreviewToolkitExtension> {
    kspArg("previewkit.registry", "com.xbet.ui.preview.XbetPreviewRegistry")
    kspArg("previewkit.defaultBox", "com.xbet.ui.preview.XbetPreviewBox")
    kspArg("previewkit.namePrefix", "Xbet")   // опционально: нейминг генерации
}
```

Генератор на каждую `@WithPreview`-функцию выпустит по одной preview-функции
на каждую аннотацию реестра (surface - в своём `boxFqn`, остальные - в
`defaultBox`) + CombinedPreview. Хочешь другой скелет - правишь бокс, код
генерации не меняется.

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
| `previewkit-idea` | IDEA/Android Studio плагин: гаттер, Regenerate Previews, инспекции |

В Alva плагин `com.previewkit` не используется - там свой adapter в `build-convention-plugins`
(`cmp-setup` вешает wiring на каждый KMP-модуль автоматически).

## IDEA-плагин (Android Studio)

`previewkit-idea` - MVP-плагин под Android Studio **2024.3+** (**2025.x поддерживается**;
IntelliJ Platform 243-299 линейки):

- гаттер-иконка у `@WithPreview`-composable -> переход к сгенерированному `<Name>Preview.kt`
  (ищется в `build/generated/ksp/android/androidMain/kotlin` и
  `build/generated/ksp/metadata/commonMain/kotlin` модуля; нет файла - уведомление);
- правый клик по модулю/папке в Project view -> **Regenerate Previews**: перегенерирует
  только выбранный модуль (`./gradlew :module:kspAndroidMain` от корня его билда;
  plain-Android - каскадом `kspDebugKotlin`, если `kspAndroidMain` в модуле нет);
  соседние модули не запускаются; по завершении - уведомление (успех; при ошибке -
  реальный блок "What went wrong" из вывода gradle); повторный клик во время выполнения
  безопасен (single-flight, "уже выполняется");
- правый клик (Project view/редактор) -> **Preview Coverage Report** - запускает отчёт покрытия
  (`previewCoverageReport`) и открывает результат в редакторе;
- шорткаты: `Ctrl+Alt+P` - Regenerate Previews, `Ctrl+Alt+Shift+P` - Preview Coverage Report;
- включённые/standalone-билды (`includeBuild`) поддерживаются: действие запускается от корня
  своего билда (walk-up до `settings.gradle[.kts]` от каталога модуля);
- запуск тасков - всегда прямой вызов `./gradlew` от корня билда (на любой поддерживаемой
  версии AS, блокирующий фоновый прогон); по завершении - уведомление об успехе/ошибке;
  полный вывод gradle - в idea.log, префикс `PreviewKit gradle:`;
- инспекции: `@WithPreview` на private-функции (KSP такие цели пропускает) и на обязательный
  параметр типа, который процессор не соберёт (warn + quick-fix "Remove @WithPreview").

Сборка и установка (без маркетплейса):

```bash
./gradlew :previewkit-idea:buildPlugin
# zip: previewkit-idea/build/distributions/previewkit-idea-<version>.zip
```

Android Studio -> Settings -> Plugins -> шестерёнка -> **Install Plugin from Disk** -> выбрать zip.

Установка и обновление - три способа:
1. **Из диска** (ручной): скачать zip из [Releases](https://github.com/Artofpaganini/ComposePreviewKit/releases)
   или собрать `./gradlew :previewkit-idea:buildPlugin` -> Install Plugin from Disk.
2. **Полуавтомат (рекомендую)**: в AS один раз - Settings -> Plugins -> шестерёнка ->
   **Manage Plugin Repositories** -> добавить
   `https://raw.githubusercontent.com/Artofpaganini/ComposePreviewKit/master/previewkit-updatePlugins.xml`
   -> плагин появляется во вкладке Marketplace и обновляется из студии.
3. Релиз: тег `plugin-v*` (или кнопка workflow) - CI собирает zip и прикладывает к Release;
   для update-репозитория перезаписать `releases/previewkit-idea-*.zip` одним коммитом.
   Сборка плагина вне обычного CI намеренно (платформа тяжёлая) - release-workflow
   включает модуль через `PREVIEWKIT_RELEASE=true`; верификатор перед релизом гоняется локально:
   `./gradlew :previewkit-idea:verifyPlugin`.

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
| Неподдерживаемый параметр (по умолчанию заглушка) | в обёртке ставится `param = TODO("PreviewKit: собери это руками: <причина>")` с комментарием-причиной над строкой аргумента; вернуть старый пропуск обёртки - `previewkit.unsupportedStub=false` |
| Отчёт покрытия превью | `./gradlew previewCoverageReport` (плагин применён хоть где-то, запускать отдельным вызовом после сборки): по модулям репозитория - сгенерированные обёртки, unsupported-записи (функция.параметр -> причина) и private/local-цели из манифестов; модуль без манифеста помечается "not built or previews disabled". Файл: `build/reports/previewkit-coverage.txt` |
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
- `previewkit.unsupportedStub` (включён по умолчанию; отключение - `previewkit.unsupportedStub=false`,
  включать аргументом нельзя, как у гейта strict) - обёртка генерируется, а для неподдерживаемого
  обязательного параметра в ней ставится заглушка `param = TODO("PreviewKit: собери это руками: <причина>")`
  (над строкой аргумента - комментарий-причина) вместо пропуска всей обёртки; манифест
  unsupported-записей пишется в обоих режимах. `previewkit.strict=true` старше флага: при strict
  обёртка по-прежнему не генерируется, сборка падает.
- Лямбда с не-Unit возвратом (`() -> X`, в т.ч. suspend и с ресивером) всегда получает тело
  `{ TODO("PreviewKit: собери это руками") }` - пустое `{}` для не-Unit возврата не компилируется;
  такие лямбды в манифест не попадают.
- Wiring вешается только на compose-модули (детект плагина `org.jetbrains.compose`).

## Release

Сгенерированное превью недостижимо из production-кода - R8 вырезает его из release;
`core:preview`-ресурсы снимаются resource shrinker'ом. Проверено mapping'ом (ноль preview-символов).

## Ограничения

Генератор строит фейковые данные для: data class, enum, sealed interface/class (перебор
наследников), value class, object, коллекции, лямбды, примитивы/String, compose-типы
и аннотированные ресурсы.

Не собирает (обёртка не генерируется; теперь это видно в редакторе - подсвечивает
IDE-плагин по манифесту, а не только warn в KSP-логе):
- обычный class, который нельзя создать конструктором (билдер/фабрика) - превью пишется руками;
- cross-module тип с internal-конструктором (видимость Kotlin);
- неразрешимый вложенный тип.

- рендер превью - только Android (Android Studio); iOS generated-код не видит.
