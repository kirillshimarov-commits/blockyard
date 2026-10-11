# Blockyard

Лаунчер Minecraft с каталогами Modrinth и CurseForge. Tauri 2 + Rust + React.

**[⬇ Скачать последнюю версию](https://github.com/kirillshimarov-commits/blockyard/releases/latest)**

## Что умеет (v0.1)

- **Сборки (инстансы):** у каждой своя папка, версия игры, загрузчик, моды и миры
- **Любая версия Minecraft:** релизы и снапшоты, данные скачиваются с серверов Mojang
- **Загрузчики:** Fabric, Quilt, Forge и NeoForge (Forge — от 1.7.10 до последних версий), выбор версии загрузчика при создании сборки
- **Шейдеры:** каталог Modrinth и CurseForge; Iris или Oculus ставится автоматически
- **Готовые сборки (модпаки):** установка `.mrpack` с Modrinth и модпаков CurseForge в один клик — версия, загрузчик, моды и настройки
- **Моды с Modrinth и CurseForge:** поиск, сортировка, фильтр по версии сборки; установка в один клик вместе с обязательными зависимостями (например, Fabric API). Один и тот же мод из двух источников не ставится дважды
- **Моды с запретом на стороннее скачивание** (часть CurseForge): лаунчер открывает страницу загрузки и сам переносит файл из папки «Загрузки» в сборку
- **Java ставится сама:** нужная версия (8 / 17 / 21) скачивается с Adoptium при первом запуске
- **Аккаунты:** вход через Microsoft (лицензия) или офлайн под любым ником
- **Ресурспаки** с Modrinth и CurseForge, в том числе для ванильных сборок
- **Обновление модов** одной кнопкой (Modrinth и CurseForge), **выключение модов** переключателем без удаления
- **Экспорт и импорт сборок** в `.mrpack` (а также импорт модпаков CurseForge из файла)
- **Анализ вылетов:** лаунчер объясняет причину простыми словами (нет зависимости, конфликт модов, мало памяти…)
- **Автообновление лаунчера** из релизов GitHub (нужен публичный репозиторий)
- **Оформление:** полупрозрачное окно (Mica / Acrylic), светлая и тёмная тема, цвет акцентов, фон-градиент или своя картинка, скругления, масштаб, компактная панель
- **Журнал игры** в реальном времени, остановка игры из лаунчера
- Проверка SHA-1 у всех скачанных файлов, параллельная загрузка

## Где хранятся данные

`%APPDATA%\Blockyard\`

```
meta/       версии, библиотеки, ресурсы (общие для всех сборок)
runtimes/   скачанная Java
instances/  сборки: <id>/minecraft — папка игры (mods, saves, config)
settings.json
```

## Запуск для разработки (Windows)

Нужны [Node.js 22](https://nodejs.org), [pnpm](https://pnpm.io/installation), [Rust](https://rustup.rs) и [зависимости Tauri](https://v2.tauri.app/start/prerequisites/) (WebView2 уже есть в Windows 10/11, нужны Microsoft C++ Build Tools).

```bash
pnpm install
pnpm tauri dev      # запуск с горячей перезагрузкой
pnpm tauri build    # установщик в src-tauri/target/release/bundle/
```

Готовый установщик также собирается автоматически в GitHub Actions при каждом пуше: вкладка **Actions → Build → Artifacts → blockyard-windows**.

## Ключ CurseForge

Ключ встраивается в лаунчер при сборке из переменной окружения `CURSEFORGE_API_KEY` и в коде не хранится.

- **GitHub Actions:** Settings → Secrets and variables → Actions → секрет `CURSEFORGE_API_KEY`
- **Локально:** задайте переменную перед `pnpm tauri dev` / `pnpm tauri build`

Без ключа вкладка CurseForge неактивна, Modrinth работает как обычно.

## Вход через Microsoft

Нужно один раз зарегистрировать приложение в Azure:

1. [portal.azure.com](https://portal.azure.com) → **App registrations** → **New registration**: любое имя, тип аккаунтов **Personal Microsoft accounts only**.
2. В приложении: **Authentication** → **Allow public client flows** → **Yes** → Save.
3. Скопируйте **Application (client) ID** и добавьте секрет `MS_CLIENT_ID` в GitHub (Settings → Secrets and variables → Actions).
4. Подайте заявку на доступ к Minecraft API: [aka.ms/mce-reviewappid](https://aka.ms/mce-reviewappid). Пока Mojang её не одобрит, вход будет падать с сообщением об этом.

## Релизы

GitHub → **Actions** → **Build** → **Run workflow** → в поле версии введите, например, `v0.3.0` → **Run workflow**. Через ~10 минут установщик появится на странице **Releases** (также работает пуш тега `v*`).

## Структура

```
src/                     интерфейс (React + TypeScript)
  pages/                 Библиотека, Сборка, Моды, Настройки
src-tauri/src/
  lib.rs                 команды Tauri, запуск и остановка игры
  launcher/
    mojang.rs            манифест версий, библиотеки, нативные файлы, ресурсы
    fabric.rs            профили Fabric и Quilt
    forge.rs             установка Forge и NeoForge (процессоры установщика)
    modpack.rs           установка модпаков Modrinth и CurseForge
    java.rs              автоустановка Java
    launch.rs            сборка аргументов и запуск
    modrinth.rs          поиск и установка модов с Modrinth
    curseforge.rs        поиск и установка модов с CurseForge
    mods.rs              общие типы, защита от дублей, ручные загрузки
    updates.rs           проверка и установка обновлений модов
    auth.rs              вход через Microsoft (device code → Xbox → Minecraft)
    crash.rs             разбор отчётов о сбоях
    instance.rs          сборки
```

## Планы

- [ ] Миры и скриншоты в сборке
