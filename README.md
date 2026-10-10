# Blockyard

Лаунчер Minecraft со встроенным каталогом модов Modrinth. Tauri 2 + Rust + React.

## Что умеет (v0.1)

- **Сборки (инстансы):** у каждой своя папка, версия игры, загрузчик, моды и миры
- **Любая версия Minecraft:** релизы и снапшоты, данные скачиваются с серверов Mojang
- **Fabric:** выбор версии загрузчика при создании сборки
- **Моды с Modrinth:** поиск, сортировка, фильтр по версии сборки; установка в один клик вместе с обязательными зависимостями (например, Fabric API)
- **Java ставится сама:** нужная версия (8 / 17 / 21) скачивается с Adoptium при первом запуске
- **Офлайн-аккаунт:** игра под любым ником
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
    modrinth.rs          поиск и установка модов с зависимостями
    instance.rs          сборки
```

## Планы

- [ ] Вход через Microsoft (лицензионные аккаунты)
- [ ] Quilt, NeoForge, Forge
- [ ] Ресурспаки и шейдеры с Modrinth
- [ ] Импорт и экспорт модпаков `.mrpack`
- [ ] Обновление модов
- [ ] CurseForge (нужен API-ключ)
