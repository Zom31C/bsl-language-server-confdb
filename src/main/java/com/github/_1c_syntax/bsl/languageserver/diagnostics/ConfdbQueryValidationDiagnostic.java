/*
 * This file is a part of BSL Language Server.
 *
 * Copyright (c) 2018-2026
 * Alexey Sosnoviy <labotamy@gmail.com>, Nikita Fedkin <nixel2007@gmail.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * BSL Language Server is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * BSL Language Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BSL Language Server.
 */
package com.github._1c_syntax.bsl.languageserver.diagnostics;

import com.github._1c_syntax.bsl.languageserver.configuration.LanguageServerConfiguration;
import com.github._1c_syntax.bsl.languageserver.context.ConfdbQueryViolationStore;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticMetadata;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticScope;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticSeverity;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticTag;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticType;
import com.github._1c_syntax.bsl.languageserver.utils.Ranges;

import java.nio.file.Path;

/**
 * Ошибки запросов во встроенном языке, найденные confdb.
 * <p>
 * confdb (экстрактор конфигурации 1С) проверяет тексты запросов в модулях
 * по полному синтаксису языка запросов 1С и метаданным конфигурации
 * и сохраняет нарушения в таблицу {@code query_violation} (команда
 * {@code confdb check-queries}). Диагностика подсвечивает литералы запросов
 * с ошибками в координатах, сохранённых confdb.
 */
@DiagnosticMetadata(
  type = DiagnosticType.ERROR,
  severity = DiagnosticSeverity.CRITICAL,
  scope = DiagnosticScope.BSL,
  minutesToFix = 10,
  tags = {
    DiagnosticTag.SQL
  }
)
public class ConfdbQueryValidationDiagnostic extends AbstractDiagnostic {

  private final ConfdbQueryViolationStore violationStore;
  private final LanguageServerConfiguration languageServerConfiguration;

  public ConfdbQueryValidationDiagnostic(ConfdbQueryViolationStore violationStore,
                                         LanguageServerConfiguration languageServerConfiguration) {
    this.violationStore = violationStore;
    this.languageServerConfiguration = languageServerConfiguration;
  }

  @Override
  protected void check() {
    var confdbDatabase = languageServerConfiguration.getConfdbDatabase();
    if (confdbDatabase == null) {
      return;
    }
    var root = documentContext.getServerContext().getConfigurationRoot();
    if (root == null) {
      return;
    }
    var database = confdbDatabase.isAbsolute()
      ? confdbDatabase
      : root.resolve(confdbDatabase);

    String relativePath;
    try {
      relativePath = root.relativize(Path.of(documentContext.getUri()))
        .toString()
        .replace('\\', '/');
    } catch (IllegalArgumentException e) {
      return;
    }

    for (var violation : violationStore.get(database, relativePath)) {
      diagnosticStorage.addDiagnostic(
        Ranges.create(
          violation.line(), violation.col(),
          violation.lineEnd(), violation.colEnd()),
        info.getMessage(violation.message()),
        null);
    }
  }
}
