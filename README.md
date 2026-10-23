# PreviewKit

Автогенерация Compose-превью для KMP/Android-модулей: помечаешь content-composable
аннотацией `@WithPreview` — при сборке KSP строит mock-фабрики для всех моделей из его
параметров и `@Preview`-обёртки под все поверхности проекта. Ноль ручных sample-классов.

## Быстрый старт

```kotlin
@WithPreview
@Composable
internal fun MyScreen(
    modifier: Modifier = Modifier,
    uiState: MyUiModel,
    onAction: (MyAction) -> Unit,
)
```

Собрать модуль — всё. В `build/generated/ksp` появятся mock-фабрики моделей
(`previewMyUiModel(variant)`), `@Preview`-обёртки под все поверхности проекта
и `MyScreenAllVariantsPreview` со всеми вариантами данных.

## Подключение

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google { ... }; mavenCentral() } }

// build.gradle.kts модуля (обычно через convention-плагин проекта)
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "..."
    id("org.jetbrains.kotlin.plugin.compose") version "..."
    id("org.jetbrains.compose") version "..."
    id("com.previewkit") version "0.1.1"
}

import com.previewkit.gradle.PreviewToolkitExtension
configure<PreviewToolkitExtension> {
    enabled.set(true)
    defaultBox.set("...")                       // опционально
    kspArg("previewkit.registry", "...")        // реестр multipreview-аннотаций
}
```

Требования: KMP-проект с >1 таргетом (metadata-компиляция; без неё фабрики не
генерируются), compose-runtime и tooling-preview в зависимостях модуля. Обёртки
генерируются только для android-компиляций; фабрики — в commonMain.

## Свои аннотации поверхностей и бокс проекта

Во что заворачивается превью экрана — регулируется реестром: скелет обёртки
генерируется по аннотациям реестра, каждая поверхность несёт свой `boxFqn`
(тема/бокс твоего проекта):

```kotlin
// 1. Реестр поверхностей проекта - class с @PreviewKitAnnotations,
//    внутри - мультипревью-аннотации; поверхности помечены @PreviewSurface
@PreviewKitAnnotations
class MyProjectPreviewRegistry {

    @Target(AnnotationTarget.FUNCTION)
    @PreviewSurface(id = "panel", boxFqn = "com.myproject.ui.preview.MyPreviewBox")
    @Preview(name = "My Panel", showBackground = true)
    annotation class PreviewMyPanel

    @Target(AnnotationTarget.FUNCTION)
    @Preview(name = "My Item", showBackground = true)
    annotation class PreviewMyItem          // не-surface: завернётся в defaultBox
}

// 2. Wiring - FQN реестра + бокс по умолчанию для не-surface аннотаций
configure<PreviewToolkitExtension> {
    kspArg("previewkit.registry", "com.myproject.ui.preview.MyProjectPreviewRegistry")
    kspArg("previewkit.defaultBox", "com.myproject.ui.preview.MyPreviewBox")
    kspArg("previewkit.namePrefix", "My")   // опционально: нейминг генерации
}
```

Генератор на каждую `@WithPreview`-функцию выпустит по одной preview-функции
на каждую аннотацию реестра (surface — в своём `boxFqn`, остальные — в
`defaultBox`) + CombinedPreview. Хочешь другой скелет — правишь бокс, код
генерации не меняется.

## Управление

| Действие | Как |
|---|---|
| Включить/выключить превью в модуле | `previews { enabled = false }` в build.gradle.kts модуля |
| Другой вариант данных в обёртке | `@WithPreview(variant = 1)` |
| Особое состояние / свой стейт | рукописное `@Preview` поверх фабрики: `previewMyUiModel().copy(title = "...")` |
| Все варианты данных рядом | автоматически: `MyScreenAllVariantsPreview` (`@PreviewParameter` по провайдеру) — для экранов с одним обязательным model-параметром |
| Модель вне графа параметров | `@PreviewMock` на классе; исключить — `@PreviewMockIgnore` |
| Неподдерживаемый параметр | в обёртке ставится заглушка `param = TODO("PreviewKit: собери это руками: <причина>")` с комментарием-причиной над строкой аргумента; вернуть старый пропуск обёртки — `previewkit.unsupportedStub=false` |
| Отчёт покрытия превью | `./gradlew previewCoverageReport`: по модулям — сгенерированные обёртки, unsupported-записи (функция.параметр -> причина) и private/local-цели; файл: `build/reports/previewkit-coverage.txt` |
| Форс-переген модуля | кнопкой Regenerate Previews в IDE или `./gradlew :module:kspAndroidMain` |

## Моки по типам

Порядок разрешения: SPI `TypeMockProvider` -> встроенные Compose-типы (`Color`, `Dp`,
`TextUnit`, `Shape`, `Brush`) -> сгенерированные фабрики -> SPI `PreviewArgProvider` ->
примитивы/коллекции/enum/sealed/value class -> inline-сбор data class. Аннотированные
Android-ресурс-Int'ы (`@DrawableRes`/`@StringRes`/`@ColorRes`) получают валидные
`android.R.*` плейсхолдеры.

Свой тип (например `android.net.Uri`): реализуй `TypeMockProvider`, зарегистрируй в
`META-INF/services` — модуль берёт мок из твоего провайдера.

Данные детерминированные: `variant: Int` сдвигает все поля (строки — слова из словаря,
коллекции — 2 различающихся элемента). Обёртки зовут `variant = 0`.

## Режимы

- `previewkit.strict=true` — неподдерживаемый тип параметра становится ошибкой сборки
  вместо заглушки; заглушки в этом режиме не применяются.
- `previewkit.unsupportedStub` (включён по умолчанию; отключение —
  `previewkit.unsupportedStub=false`) — обёртка генерируется, а для неподдерживаемого
  обязательного параметра ставится заглушка `TODO(...)` вместо пропуска всей обёртки;
  манифест unsupported-записей пишется в обоих режимах. `previewkit.strict=true` старше
  флага: при strict обёртка по-прежнему не генерируется, сборка падает.
- Лямбда с не-Unit возвратом (`() -> X`, в т.ч. suspend и с ресивером) всегда получает
  тело `{ TODO("PreviewKit: собери это руками") }` — пустая лямбда для такого возврата
  не компилируется.

## Release

Сгенерированное превью недостижимо из production-кода — R8 вырезает его из release,
ресурсы снимаются shrinker'ом. Манифест unsupported-записей — текстовый файл в
`build/generated`, в артефакты не пакуется.

## Ограничения

Генератор строит фейковые данные для: data class, enum, sealed interface/class
(перебор наследников), value class, object, коллекции, лямбды, примитивы/String,
compose-типы и аннотированные ресурсы.

Не собирает (обёртка не генерируется; это видно в редакторе — подсвечивает
IDE-плагин по манифесту, а не только warn в KSP-логе):
- обычный class, который нельзя создать конструктором (билдер/фабрика) — превью
  пишется руками;
- cross-module тип с internal-конструктором (видимость Kotlin);
- неразрешимый вложенный тип.

Граф моделей собирается на глубину 15: типы глубже фабрик не получают — параметр
с таким типом превратится в заглушку и попадёт в манифест.

- рендер превью — только Android (Android Studio); iOS generated-код не видит.

## IDEA-плагин (Android Studio)

`previewkit-idea` — плагин под Android Studio **2024.3+** (IntelliJ Platform 243-линейка):

- гаттер-иконка у `@WithPreview`-composable -> переход к сгенерированному
  `<Name>Preview.kt` (ищется в `build/generated/ksp/android/androidMain/kotlin` и
  `build/generated/ksp/metadata/commonMain/kotlin` модуля; нет файла — уведомление);
- правый клик по модулю/папке в Project view или по коду -> **Regenerate Previews**:
  перегенерирует только выбранный модуль (`./gradlew :module:kspAndroidMain` от корня
  его билда; plain-Android — каскадом `kspDebugKotlin`, если `kspAndroidMain` в модуле
  нет); соседние модули не запускаются; по завершении — уведомление (успех; при ошибке —
  реальный блок "What went wrong" из вывода gradle); повторный клик во время выполнения
  безопасен (single-flight, "уже выполняется");
- правый клик (Project view/редактор) -> **Preview Coverage Report** — запускает отчёт
  покрытия (`previewCoverageReport`) и открывает результат в редакторе;
- инспекция: неподдерживаемый параметр подсвечивается по манифесту последней генерации,
  quick-fix **Remove @WithPreview**; `@WithPreview` на private-функции — предупреждение;
- шорткаты: `Ctrl+Alt+P` — Regenerate Previews, `Ctrl+Alt+Shift+P` — Preview Coverage
  Report;
- включённые/standalone-билды (`includeBuild`) поддерживаются: действие запускается от
  корня своего билда (walk-up до `settings.gradle[.kts]` от каталога модуля);
- запуск тасков — всегда прямой вызов `./gradlew` от корня билда (на любой
  поддерживаемой версии студии).

### Установка и обновление

1. **Из диска** (ручной): скачать zip из
   [Releases](https://github.com/Artofpaganini/ComposePreviewKit/releases)
   или собрать `./gradlew :previewkit-idea:buildPlugin` -> Install Plugin from Disk.
2. **Полуавтомат (рекомендуется)**: в AS один раз — Settings -> Plugins -> шестерёнка ->
   **Manage Plugin Repositories** -> добавить
   `https://raw.githubusercontent.com/Artofpaganini/ComposePreviewKit/master/previewkit-updatePlugins.xml`
   -> плагин появляется во вкладке Marketplace и обновляется из студии.
3. Релиз: тег `plugin-v*` (или кнопка workflow) — CI собирает zip и прикладывает к
   Release; для update-репозитория перезаписать `releases/previewkit-idea-*.zip` одним
   коммитом.

## Состав

| Модуль | Назначение |
|---|---|
| `previewkit-annotations` | `@WithPreview`, `@PreviewMock`, `@PreviewMockIgnore`, `@ConsiderForPreviewMock`, `@PreviewSurface`, `@PreviewKitAnnotations` |
| `previewkit-ksp` | KSP-процессор: сбор моделей, генерация mock-фабрик, `@Preview`-обёрток и манифеста unsupported-записей |
| `previewkit-config-api` | SPI-контракты: `TypeMockProvider`, `PreviewArgProvider`, `FakerSource` |
| `previewkit-gradle` | Переносимый плагин `com.previewkit` (DSL `previewToolkit`) + таск `previewCoverageReport` |
| `previewkit-idea` | IDEA/Android Studio плагин: гаттер, Regenerate Previews, инспекции |
| `previewkit-sample` | Standalone-песочница |
