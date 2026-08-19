# Query errors found by confdb (ConfdbQueryValidation)

<!-- Блоки выше заполняются автоматически, не трогать -->
## Diagnostic description

The diagnostic highlights query texts in the embedded language where confdb
(a 1C configuration extractor, the 1c-conf-db-extractor project) found syntax
errors or references to missing metadata.

Validation is performed on the confdb side against the full 1C query language
syntax and the configuration metadata (objects, attributes, tabular sections,
enum values) and stored in the `query_violation` table of the SQLite database
by the `confdb check-queries` command. The diagnostic is active when
`confdbDatabase` is set in `.bsl-language-server.json`.

## Examples

Query of a missing catalog:
```sdbl
Запрос.Текст = "ВЫБРАТЬ 1 ИЗ Справочник.Несуществует";
```

Broken query text:
```sdbl
Запрос.Текст = "ВЫБРАТЬ 1 ИЗ";
```

## Sources

- confdb project: 1C configuration extractor into SQLite (1c-conf-db-extractor)
