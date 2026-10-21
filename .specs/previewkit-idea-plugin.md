# MVP IntelliJ/Android Studio плагина previewkit-idea

> Спека инструмента PreviewKit: `../Alva/.specs/preview-auto-mock.md` (актуальна, 19.10.2026).
> Там `previewkit-idea` помечен как out of scope - эта спека его запускает.

## Дословный запрос
> MVP IntelliJ/Android Studio плагина previewkit-idea в репозитории
> ~/work/ComposePreviewKit (модуль previewkit-idea). Установочная база читается
> в README.md репо; спецификация инструмента - в Alva:
> /Users/Victor/work/Alva/.specs/preview-auto-mock.md (актуальна).
>
> Что такое PreviewKit: KSP-процессор генерирует по аннотации @WithPreview
> (com.previewkit.annotation) mock-фабрики (previewXxx(), commonMain) и
> @Preview-обёртки XxxPreview.kt (androidMain) при сборке модуля. Обёртки
> именуются <Composable><Surface>Preview.kt, лежат в
> build/generated/ksp/android/androidMain/kotlin (для Alva) или
> build/generated/ksp/metadata/commonMain/kotlin (режим без реестра).
>
> MVP-скоуп плагина:
> 1. Гаттер-иконка рядом с @WithPreview-annotated @Composable -> переход
>    к сгенерированному файлу <Name>Preview.kt (или сообщение, если нет).
> 2. Действие "Regenerate Previews" (правый клик по модулю/папке):
>    ./gradlew :module:kspAndroidMain (для plain-Android: kspDebugKotlin)
>    с прогресс-индикатором.
> 3. Инспекции: @WithPreview на private-функции (warn), на функции с
>    не-поддерживаемым типом параметра - подсветка + quick-fix
>    "Remove @WithPreview".
> 4. Сборка: buildPlugin -> zip в build/distributions. WITHOUT маркетплейса -
>    распространение "Install Plugin from Disk". Совместимость: Android
>    Studio (IntelliJ Platform 2024.2+, Kotlin JPS/выбери оптимальный).
>
> Ограничения: язык Kotlin; никаких файлов вне модуля previewkit-idea;
> обнови README репо разделом про плагин; в конце - buildPlugin зелёный
> и zip готов к установке (Install from Disk проверю сам).
> Первый шаг: оцени структуру существующего репо (settings.gradle.kts,
> libs.versions.toml) и предложи каркас модуля до написания кода.

## Цель
IDE-плагин для PreviewKit: из Android Studio видно, где превью сгенерируются
(гаттер), куда переходить (навигация к `<Name>Preview.kt`), пересобрать
генерацию без терминала (действие "Regenerate Previews") и ловить ошибки
аннотации до сборки (две инспекции). Поставляется zip-ом через
"Install Plugin from Disk", без маркетплейса.

## Скоуп
- Каталог / проект: base (репо `~/work/ComposePreviewKit`)
- Профиль: feature · Размер: M
- Затронутые модули: новый `previewkit-idea` (весь код плагина);
  wiring-файлы корня: `settings.gradle.kts`, `gradle/libs.versions.toml`,
  `README.md` - перечисленные корневые файлы единственное разрешённое
  исключение из "никаких файлов вне модуля previewkit-idea".

## Существующий код (разведка 20.10.2026)
### Похожие реализации
| Что | Где (file:line) | Что берём за образец |
|---|---|---|
| Имя файла-обёртки: `<Name>Preview` в пакете composable | previewkit-ksp/src/main/kotlin/com/previewkit/ksp/emit/SurfaceEmitter.kt:148 (реестр), :68 (fallback без реестра) | точное зеркало нейминга в плагине |
| FQN аннотации: `com.previewkit.annotation.WithPreview`, retention BINARY, target FUNCTION | previewkit-annotations/src/commonMain/kotlin/com/previewkit/annotation/WithPreview.kt | матч по строке FQN, без зависимости на модуль annotations |
| Генерация на `kspAndroidMain`, модульный гейт выключения `previews { enabled = false }` | README.md:67-87, preview-auto-mock.md | контекст действия Regenerate |
| Private/local цели пропускаются KSP молча | preview-auto-mock.md (WithPreviewCollector: "private/local пропускаются") | обоснование инспекции private-warn |
| Неподдерживаемый тип: обёртка не генерируется (warn KSP / error в strict) | preview-auto-mock.md, SurfaceEmitter.kt:340-343 | обоснование инспекции unsupported |
| Режим без реестра: генерация идёт на metadata-компиляции | README.md:53-54, GenerationMode.kt, previewkit-sample (jvm+ios, без android) | второй корень поиска `<Name>Preview.kt` |

### Точки интеграции
- Файловая: `build/generated/ksp/android/androidMain/kotlin/<pkg>/<Name>Preview.kt`
  (обёртки, реестр) и `build/generated/ksp/metadata/commonMain/kotlin/...`
  (фабрики; в no-registry/JVM-режиме там же обёртки) - корни под content root
  модуля IDE.
- Gradle: таски `:module:kspAndroidMain` (KMP/Alva-стиль) и `:module:kspDebugKotlin`
  (plain-Android); запуск из IDE через External System API с прогрессом.
- Сборка репо: Gradle 9.6.0, Kotlin 2.4.20, AGP 9.4.1, version catalog
  `gradle/libs.versions.toml`; CI `.github/workflows/build.yml` - `./gradlew build`,
  macos-latest, JDK 21.
- Аннотация видима плагину только по FQN-строке: модуль annotations не
  подключается к плагину (BINARY retention, KMP).

## Требования
### Функциональные
- [ ] FR1: модуль `previewkit-idea` встроен в сборку репо через
      `settings.gradle.kts` (include) и `libs.versions.toml` (alias плагина
      org.jetbrains.intellij.platform 2.x); код плагина - только внутри модуля.
      _(из: "модуль previewkit-idea", "оцени структуру существующего репо
      (settings.gradle.kts, libs.versions.toml)")_
- [ ] FR2: гаттер-иконка (line marker) у функций с `@WithPreview`
      (`com.previewkit.annotation.WithPreview`) на `@Composable`; клик ->
      переход к сгенерированному `<Name>Preview.kt` (файл ищется в
      `build/generated/ksp/**` корней android/androidMain и metadata/commonMain
      content root'а модуля, в пакете функции). Файл не найден -> уведомление
      (не падение, без исключений в логе).
      _(из: "Гаттер-иконка рядом с @WithPreview-annotated @Composable -> переход
      к сгенерированному файлу <Name>Preview.kt (или сообщение, если нет)")_
- [ ] FR3: действие "Regenerate Previews" в popup-меню Project view на узле
      модуля и папки: определяет Gradle-путь модуля (модулей - для папки,
      сводя в один вызов `./gradlew`), выбирает таск `kspAndroidMain`, при
      отсутствии таска в модуле - `kspDebugKotlin` (plain-Android), запускает с
      прогресс-индикатором (Build tool window).
      _(из: "Действие "Regenerate Previews" (правый клик по модулю/папке):
      ./gradlew :module:kspAndroidMain (для plain-Android: kspDebugKotlin)
      с прогресс-индикатором")_
- [ ] FR4: инспекция - предупреждение на `@WithPreview` у private-функции
      (KSP такие цели молча пропускает - превью не будет).
      _(из: "@WithPreview на private-функции (warn)")_
- [ ] FR5: инспекция - подсветка функции с `@WithPreview`, у которой есть
      обязательный параметр типа, который KSP не поддержит (приближение по PSI:
      тип не резолвится в primitive/String/collection/enum/sealed/value class/
      object/data class с доступным конструктором) + quick-fix
      "Remove @WithPreview" (удаляет аннотацию). Северыти warning, текст указывает,
      что KSP-лог - источник истины.
      _(из: "на функции с не-поддерживаемым типом параметра - подсветка +
      quick-fix "Remove @WithPreview"")_
- [ ] FR6: `buildPlugin` собирает zip в `previewkit-idea/build/distributions/`;
      маркетплейс-интеграции нет; plugin.xml с id `com.previewkit.idea`,
      since-build 242 (Android Studio 2024.2+), until-build широкий (совместимость
      с AS 2025.x). Распространение - "Install Plugin from Disk".
      _(из: "buildPlugin -> zip в build/distributions", "WITHOUT маркетплейса",
      "IntelliJ Platform 2024.2+")_
- [ ] FR7: совместимость-этаж: Android Studio (IntelliJ Platform 2024.2+).
      Сборка плагина - против IntelliJ IDEA Community 2024.2.x + bundled Kotlin
      plugin (зависимость `org.jetbrains.kotlin`), API-использование - стабильное
      ядро (PSI/LineMarker/LocalInspection/ExternalSystem), без AS-специфичных
      SDK-классов. _(из: "Совместимость: Android Studio (IntelliJ Platform
      2024.2+, Kotlin JPS/выбери оптимальный)" - выбор инструментария делегирован:
      IntelliJ Platform Gradle Plugin 2.x)_
- [ ] FR8: весь код - Kotlin; вне модуля `previewkit-idea` файлы плагина не
      создаются (кроме FR1 wiring-файлов и FR9 README).
      _(из: "язык Kotlin; никаких файлов вне модуля previewkit-idea")_
- [ ] FR9: README репо получает раздел про плагин: что делает, как собрать zip,
      как установить из диска. _(из: "обнови README репо разделом про плагин")_
- [ ] FR10: финал - `:previewkit-idea:buildPlugin` зелёный, zip готов к
      установке. _(из: "в конце - buildPlugin зелёный и zip готов к установке")_

### Нефункциональные
- [ ] Performance: line marker - дешёвый (без full resolve в queryAvailable;
      резолв только по клику), инспекции - локальные, без анализа всего проекта.
- [ ] Безопасность: секретов нет; плагин ничего не отправляет наружу
      (без маркетплейса и телеметрии).
- [ ] Тестирование: opt-in - тесты не пишем (проектный override); проверка -
      сборка + buildPlugin; IDE-runtime - владелец (NOT RUN для агента).
- [ ] Kotlin-запреты глобального AGENTS.md (без `!!`, без `Any`, без magic
      numbers, meaningful lambda params) соблюдаются и в плагинном коде.

## Критерии приёмки (AC)
- [ ] WHEN `./gradlew :previewkit-idea:buildPlugin` EXPECT BUILD SUCCESSFUL и
      zip в `previewkit-idea/build/distributions/` (agent-PASS).
- [ ] WHEN `./gradlew build` EXPECT зелёный для всего репо (новый модуль не
      ломает существующие 4 модуля) (agent-PASS, финальный полный прогон).
- [ ] WHEN install zip в Android Studio и открыть Alva-файл с `@WithPreview`
      EXPECT гаттер-иконка, клик -> открыт `<Name>Preview.kt` или уведомление
      (user-owned, NOT RUN - "Install from Disk проверю сам").
- [ ] WHEN правый клик по модулю/папке "Regenerate Previews" EXPECT gradle-таск
      с прогрессом в Build-окне (user-owned, NOT RUN).
- [ ] WHEN private-функция с `@WithPreview` EXPECT warning-инспекция
      (user-owned, NOT RUN; корректность кода - review).
- [ ] WHEN параметр неподдерживаемого типа EXPECT подсветка + quick-fix
      "Remove @WithPreview" удаляет аннотацию (user-owned, NOT RUN; корректность
      кода - review).

## Definition of Done
- [ ] Все AC агента зелёные (2 из 6; 4 user-owned NOT RUN зафиксированы)
- [ ] Сборка репо зелёная, detekt/ktlint в репо не подключены - не применимо
- [ ] Ни один атом намерения не потерян

## Архитектура
### Модули и зависимости
```
previewkit-idea/                        # обычный Gradle JVM-модуль, НЕ KMP
  build.gradle.kts                      # org.jetbrains.intellij.platform 2.x,
                                        # intellijIdeaCommunity 2024.2.x,
                                        # bundled Kotlin plugin, jvmTarget 17,
                                        # buildSearchableOptions=false
  src/main/kotlin/com/previewkit/idea/
    gutter/    WithPreviewLineMarkerProvider.kt, GeneratedPreviewLocator.kt
    action/    RegeneratePreviewsAction.kt, GradleTaskRunner.kt (External System API)
    inspection/ PrivateWithPreviewInspection.kt,
               UnsupportedParameterTypeInspection.kt,
               RemoveWithPreviewQuickFix.kt
    util/      нейминг/матч FQN, Gradle-путь модуля
  src/main/resources/META-INF/plugin.xml
  src/main/resources/icons/...          # гаттер + иконка плагина (SVG)
```
Пакетная разбивка - по фиче (gutter/action/inspection), util - только
переиспользуемое. Внутренние классы - internal по умолчанию.

### plugin.xml
- `depends: com.intellij.modules.platform` + `depends: org.jetbrains.kotlin`
  (Kotlin PSI). EP: `com.intellij.lineMarkerProvider` (language Kotlin),
  `com.intellij.codeInspection.localInspection` x2, action в
  ProjectViewPopupMenu (module/folder context).
- Иконки/строки без хардкода текста в коде - messages bundle либо локальные
  константы (решение плана).

### Поиск сгенерированного файла
Гаттер/локатор: content roots модуля -> `build/generated/ksp/` рекурсивно ->
`<pkg>/<Name>Preview.kt`. Порядок: `ksp/android/androidMain/kotlin`, затем
`ksp/metadata/commonMain/kotlin`. Кеш по virtual file, инвалидация при изменении
generated-корней (файловый листенер - решение плана, MVP может без кеша).

### Запуск gradle
External System API (Gradle execution) с ExecutionEnvironment -> прогресс и
вывод в Build tool window. Gradle-путь модуля - из external linkage модуля;
fallback - относительный путь content root от корня проекта.

## UI/UX
### Точки присутствия
- Гаттер: иконка слева от `@WithPreview`-функции; tooltip с подсказкой.
- Project view popup: пункт "Regenerate Previews" на модулях и папках.
- Notifications: группа "PreviewKit" (не найдено / не определился модуль).
### Состояния
- Обёртка есть -> навигация; нет -> уведомление с предложением Regenerate/сборки;
  папка вне модулей -> тихое игнорирование (пункт меню не показывается) либо
  уведомление - решение плана.

## Трейдоффы и решения
| Вопрос | Решение | Почему (слова пользователя) |
|---|---|---|
| Инструментарий сборки плагина | IntelliJ Platform Gradle Plugin 2.x (не JPS-модуль) | "Kotlin JPS/выбери оптимальный" - делегировано; 2.x совместим с Gradle 9.6 репо и даёт buildPlugin из коробки |
| Против чего собирать | IC 2024.2.x + bundled Kotlin plugin, since 242, until широкий | "IntelliJ Platform 2024.2+" - этаж совместимости; AS = IDEA Community-базис |
| Таск выбора | kspAndroidMain -> fallback kspDebugKotlin | "(для plain-Android: kspDebugKotlin)" |
| Поиск обёртки | оба ksp-корня (androidMain и metadata/commonMain) | "лежат в build/generated/ksp/android/androidMain/kotlin (для Alva) или build/generated/ksp/metadata/commonMain/kotlin (режим без реестра)" |
| Зависимость на annotations-модуль | нет, матч по FQN-строке | "KSP-процессор генерирует по аннотации @WithPreview (com.previewkit.annotation)" - BINARY retention, KMP-модуль в classpath плагина не нужен |
| Quick-fix на private-warn | нет (только warn) | в запросе fix указан только для unsupported-типа |
| Инспекция unsupported | PSI-приближение, warning, KSP-лог = истина | точный KSP-резолв (SPI overrides, cross-module) в IDE невоспроизводим дёшево |

## План работы
1. plan - каркас модуля: plugin.xml EP-набор, wiring settings/catalog, версии
   intellij-platform (проверить актуальную стабильную 2.x на момент работ),
   границы миссий. Принятие контракта родителем до кода.
2. execute - каркас модуля + сборка (buildPlugin минимален), затем фичи:
   гаттер -> действие -> инспекции -> README.
3. validation - `:previewkit-idea:buildPlugin` + полный `./gradlew build`
   (один финальный прогон), impact-research по CI (build.yml трогать нельзя -
   модуль попадает в `build` автоматически).
4. review - milestone batch review независимым ревьюером (высокий риск: новый
   модуль + build wiring).

## Риски и блокеры
- CI (macos-latest): `./gradlew build` начнёт резолвить IC-платформу (~1 GB
  скачивание, +минуты). build.yml менять нельзя (вне модуля) - принимаем
  удлинение CI, кеш setup-gradle частично покрывает.
- Дрейф Kotlin PSI API между 242 и AS 2025.x - использовать только стабильное
  ядро PSI/EP; until широкий - риск ограничен MVP-набором API.
- `allprojects { apply maven-publish }` в корне применится и к новому модулю -
  убедиться, что не ломает buildPlugin (пустая публикация допустима).
- Точное имя модуля в IDE и Gradle-путь из external linkage - API между
  версиями IDE отличается; fallback-путь обязателен.

## Edge Cases
- `@WithPreview` на не-`@Composable` функции, на local fun, на member-функции -
  гаттер и инспекции ведут себя консистентно (KSP цель - верхнеуровневая/
  member composable; local fun без PSI-гаттера по определению).
- Несколько `@WithPreview`-аннотаций-импортов (алиас-импорт) - матч по FQN
  строки резолва, не по тексту.
- Обёртки нет (сборка не гонялась / модуль выключен) -> уведомление.
- Папка в корне проекта / вне Gradle-модуля -> действие недоступно или
  уведомление.
- Модуль с `previews { enabled = false }` -> инспекции/гаттер всё равно работают
  (источник истины - аннотация), Regenerate выдаёт зелёный no-op или отсутствие
  таска - не падение.

## Out of scope
- Инлайн-рендер превью в редакторе/tool window (следующая итерация плагина).
- Маркетплейс, подпись, marketplace-валидация.
- Автоматический переген после правок (watcher).
- iOS-превью и рендер вне Android Studio.
- Тесты плагина (opt-in), IntellijPlatform-verifier-прогон.
- Правки `.github/workflows/*`, корневого `build.gradle.kts`, модулей
  annotations/ksp/gradle/sample.
- Обновление спеки `preview-auto-mock.md` в Alva.

## Open Questions
- [ ] OQ1: точная стабильная версия org.jetbrains.intellij.platform 2.x -
      фиксируется на execute (проверка docs/GitHub releases на дату работ).
      Закрыто 20.10: 2.19.0.
- [ ] OQ2 (закрыто 21.10 реализацией): поддержка Android Studio 2025.x.
      Решение - KAA из плагина удалён полностью: KSP-процессор при генерации
      пишет манифест unsupported-случаев
      (build/generated/ksp/**/resources/previewkit/manifest/
      unsupported-parameters.txt, всегда, включая пустой), плагин подсвечивает
      по манифесту (точность = решение самого генератора, включая
      кросс-модульные типы). Плагин - только на стабильных платформенных API.
      untilBuild 299.*; verifyPlugin Compatible на IC-243/251/252. Подстройка
      под версии AS больше не требуется; правды о "последней генерации" - как
      у сгенерированных превью (обновляется каждой сборкой).

## Изменения 21.10.2026 (верификация)
- Совместимость сужена с "2024.2+" до **AS 2024.3.x (IntelliJ Platform
  243-линейка)**: verifier на IC-242 показал NoSuchMethodError-риски
  (KAA-члены отсутствуют в бандле 2024.2), на IC-251/252 - аналогично для
  2.2/2.3. FR7 трактуется как: floor поднят до 2024.3, until 243.*.
- Добавлен JetBrains Plugin Verifier в workflow плагина
  (`:previewkit-idea:verifyPlugin`, ides: IC 2024.3.7.1) - вердикт
  "Compatible. 4 usages of deprecated API" (deprecated: ExternalSystem
  TaskExecutionSpec.create и сопутствующие - не блокеры, API живёт во всём
  коридоре).
- Модуль исключён из CI (`settings.gradle.kts`, guard по env CI): бесплатный
  macos-раннер не тянет ~4.4 GB IC-платформы в кэше actions (лимит 10 GB) и
  лишние минуты сборки. Локальная сборка и установка - без изменений.
