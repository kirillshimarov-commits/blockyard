# Blockyard

Лаунчер Minecraft со встроенным каталогом модов Modrinth. Tauri 2 + Rust + React.

## Что умеет (v0.1)

- **Сборки (инстансы):** у каждой своя папка, версия игры, загрузчик, моды и миры
- **Любая версия Minecraft:** релизы и снапшоты, данные скачиваются с серверов Mojang
- **Fabric:** выбор версии загрузчика при создании сборки
- **Моды с Modrinth и CurseForge:** поиск, сортировка, фильтр по версии сборки; установка в один клик вместе с обязательными зависимостями (например, Fabric API). Один и тот же мод из двух источников не ставится дважды
- **Моды с запретом на стороннее скачивание** (часть CurseForge): лаунчер открывает страницу загрузки и сам переносит файл из папки «Загрузки» в сборку
- **Java ставится сама:** нужная версия (8 / 17 / 21) скачивается с Adoptium при первом запуске
- **Офлайн-аккаунт:** игра под любым ником
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

## Структура

```
src/                     интерфейс (React + TypeScript)
  pages/                 Библиотека, Сборка, Моды, Настройки
src-tauri/src/
  lib.rs                 команды Tauri, запуск и остановка игры
  launcher/
    mojang.rs            манифест версий, библиотеки, нативные файлы, ресурсы
    fabric.rs            профили Fabric
    java.rs              автоустановка Java
    launch.rs            сборка аргументов и запуск
    modrinth.rs          поиск и установка модов с Modrinth
    curseforge.rs        поиск и установка модов с CurseForge
    mods.rs              общие типы, защита от дублей, ручные загрузки
    instance.rs          сборки
```

## Планы

- [ ] Вход через Microsoft (лицензионные аккаунты)
- [ ] Quilt, NeoForge, Forge
- [ ] Ресурспаки и шейдеры с Modrinth
- [ ] Импорт и экспорт модпаков `.mrpack`
- [ ] Обновление модов
