# BSL Language Server (форк с интеграцией confdb)

Форк проекта [1c-syntax/bsl-language-server](https://github.com/1c-syntax/bsl-language-server).
Все возможности оригинала сохранены; доработки форка — в ветке
**`confdb-metadata-source`**.

## Особенности форка

Интеграция с **confdb** — экстрактором конфигураций 1С:Предприятие 8 в SQLite
(без платформы 1С и EDT, проект
[Zom31C/1confdb-knw-lsp](https://github.com/Zom31C/1confdb-knw-lsp)):

- **Метаданные конфигурации из базы confdb.** Если в `.bsl-language-server.json`
  задан `confdbDatabase` (путь к `.db`), сервер строит модель конфигурации
  (`Solution` mdclasses) из SQLite-базы confdb вместо EDT-файлов: объекты
  20 типов, формы, команды, роли, подсистемы с составом, реквизиты с типами,
  табличные части, значения перечислений. Работают типизация, переходы,
  ссылки, метаданно-зависимые диагностики — без EDT и платформы;
- **Диагностика `ConfdbQueryValidation`** — подсветка ошибок запросов во
  встроенном языке: confdb проверяет тексты запросов в модулях по полному
  синтаксису языка запросов 1С и метаданным (`confdb check-queries`) и
  сохраняет нарушения в таблицу `query_violation`; диагностика читает её и
  подсвечивает литералы запросов;
- **Компактный режим `outline: true`** у инструмента MCP `document_symbols` —
  текстовое оглавление модуля (области и сигнатуры с диапазонами строк)
  вместо полного JSON-дерева;
- **Устойчивость MCP-инструментов**: пустой результат вместо ошибок на
  неразрешимых позициях (`find_references`/`call_hierarchy` на встроенных
  методах), понятные сообщения при отсутствии файла в дампе.

## Использование

Самостоятельная сборка и запуск MCP-режима:

```sh
gradlew bootJar
java -jar build/libs/bsl-language-server-<версия>-exec.jar mcp
```

Для подключения метаданных confdb положите в корень workspace
`.bsl-language-server.json`:

```json
{ "confdbDatabase": "/path/to/out.db" }
```

В составе готового решения (экстракция `.cf` → подготовка дампа → единый
MCP-сервер с инструментами confdb и `bsl_*`) — см. дистрибутив
[Zom31C/1confdb-knw-lsp](https://github.com/Zom31C/1confdb-knw-lsp).

---

# BSL Language Server

[![Actions Status](https://github.com/1c-syntax/bsl-language-server/workflows/Java%20CI/badge.svg)](https://github.com/1c-syntax/bsl-language-server/actions)
[![Download](https://img.shields.io/github/release/1c-syntax/bsl-language-server.svg?label=download&style=flat)](https://github.com/1c-syntax/bsl-language-server/releases/latest)
[![JitPack](https://jitpack.io/v/1c-syntax/bsl-language-server.svg)](https://jitpack.io/#1c-syntax/bsl-language-server)
[![GitHub Releases](https://img.shields.io/github/downloads/1c-syntax/bsl-language-server/latest/total?style=flat-square)](https://github.com/1c-syntax/bsl-language-server/releases)
[![GitHub All Releases](https://img.shields.io/github/downloads/1c-syntax/bsl-language-server/total?style=flat-square)](https://github.com/1c-syntax/bsl-language-server/releases)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=1c-syntax_bsl-language-server&metric=alert_status)](https://sonarcloud.io/dashboard?id=1c-syntax_bsl-language-server)
[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=1c-syntax_bsl-language-server&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=1c-syntax_bsl-language-server)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=1c-syntax_bsl-language-server&metric=coverage)](https://sonarcloud.io/dashboard?id=1c-syntax_bsl-language-server)
[![Benchmark](https://1c-syntax.github.io/bsl-language-server/dev/bench/benchmark.svg)](https://1c-syntax.github.io/bsl-language-server/dev/bench/index.html)
[![telegram](https://img.shields.io/badge/telegram-chat-green.svg)](https://t.me/bsl_language_server)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/1c-syntax/bsl-language-server)

Реализация протокола [language server protocol](https://microsoft.github.io/language-server-protocol/) для языка 1C (BSL) - языка 1С:Предприятие 8 и [OneScript](http://oscript.io).

Сайт проекта - https://1c-syntax.github.io/bsl-language-server

Сайт проекта (develop) - https://1c-syntax.github.io/bsl-language-server/dev

Исходники документации - [GitHub](docs/index.md)

---

[Language Server Protocol](https://microsoft.github.io/language-server-protocol/) implementation for 1C (BSL) - 1C:Enterprise 8 and [OneScript](http://oscript.io) languages.

Project site - https://1c-syntax.github.io/bsl-language-server/en

Project site (develop) - https://1c-syntax.github.io/bsl-language-server/dev/en

Documentation sources - [GitHub](docs/en/index.md)
