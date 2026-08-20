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
package com.github._1c_syntax.bsl.languageserver.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище нарушений запросов, найденных confdb.
 * <p>
 * Таблица {@code query_violation} заполняется командой {@code confdb check-queries}
 * (экстрактор конфигурации 1С, проект 1c-conf-db-extractor): в ней для каждого
 * ошибочного запроса в модулях сохранены путь файла дампа и позиция литерала
 * в координатах LSP. Данные читаются лениво и кэшируются по пути базы.
 */
@Slf4j
@Component
public class ConfdbQueryViolationStore {

  private final Map<Path, Map<String, List<Violation>>> cache = new ConcurrentHashMap<>();

  /**
   * Возвращает нарушения для файла по его пути относительно корня дампа.
   */
  public List<Violation> get(Path database, String relativePath) {
    var violations = cache.computeIfAbsent(database.toAbsolutePath(), this::load);
    return violations.getOrDefault(relativePath, List.of());
  }

  private Map<String, List<Violation>> load(Path database) {
    var result = new HashMap<String, List<Violation>>();
    var url = "jdbc:sqlite:" + database;
    try (var connection = DriverManager.getConnection(url);
         var statement = connection.prepareStatement(
           "SELECT path, line, col, line_end, col_end, message FROM query_violation")) {
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          result.computeIfAbsent(resultSet.getString(1), key -> new ArrayList<>())
            .add(new Violation(resultSet.getInt(2), resultSet.getInt(3),
              resultSet.getInt(4), resultSet.getInt(5), resultSet.getString(6)));
        }
      }
    } catch (SQLException e) {
      LOGGER.info("confdb query_violation is not available for {}: {}", database, e.getMessage());
      return Map.of();
    }
    LOGGER.info("confdb: loaded {} files with query violations from {}", result.size(), database);
    return result;
  }

  /** Нарушение: диапазон литерала запроса (0-based) и текст ошибки. */
  public record Violation(int line, int col, int lineEnd, int colEnd, String message) {
  }
}
