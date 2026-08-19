package com.github._1c_syntax.bsl.languageserver.context;

import com.github._1c_syntax.bsl.mdo.AccountingRegister;
import com.github._1c_syntax.bsl.mdo.AccumulationRegister;
import com.github._1c_syntax.bsl.mdo.BusinessProcess;
import com.github._1c_syntax.bsl.mdo.CalculationRegister;
import com.github._1c_syntax.bsl.mdo.Catalog;
import com.github._1c_syntax.bsl.mdo.ChartOfAccounts;
import com.github._1c_syntax.bsl.mdo.ChartOfCalculationTypes;
import com.github._1c_syntax.bsl.mdo.ChartOfCharacteristicTypes;
import com.github._1c_syntax.bsl.mdo.CommonModule;
import com.github._1c_syntax.bsl.mdo.Constant;
import com.github._1c_syntax.bsl.mdo.DataProcessor;
import com.github._1c_syntax.bsl.mdo.Document;
import com.github._1c_syntax.bsl.mdo.DocumentJournal;
import com.github._1c_syntax.bsl.mdo.Enum;
import com.github._1c_syntax.bsl.mdo.ExchangePlan;
import com.github._1c_syntax.bsl.mdo.HTTPService;
import com.github._1c_syntax.bsl.mdo.InformationRegister;
import com.github._1c_syntax.bsl.mdo.MD;
import com.github._1c_syntax.bsl.mdo.Module;
import com.github._1c_syntax.bsl.mdo.Report;
import com.github._1c_syntax.bsl.mdo.Task;
import com.github._1c_syntax.bsl.mdo.WebService;
import com.github._1c_syntax.bsl.mdo.children.ObjectModule;
import com.github._1c_syntax.bsl.mdo.support.ObjectBelonging;
import com.github._1c_syntax.bsl.mdo.support.ReturnValueReuse;
import com.github._1c_syntax.bsl.mdclasses.Configuration;
import com.github._1c_syntax.bsl.mdclasses.Solution;
import com.github._1c_syntax.bsl.support.SupportVariant;
import com.github._1c_syntax.bsl.types.ConfigurationSource;
import com.github._1c_syntax.bsl.types.MDOType;
import com.github._1c_syntax.bsl.types.MdoReference;
import com.github._1c_syntax.bsl.types.ModuleType;
import com.github._1c_syntax.bsl.types.ScriptVariant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Источник метаданных конфигурации из базы SQLite, собранной confdb
 * (экстрактор конфигурации 1С, проект 1c-conf-db-extractor).
 * <p>
 * Строит штатный {@link Solution} mdclasses через билдеры, поэтому все потребители
 * (система типов, completion, hover, definition, диагностики, CLI/MCP-режимы)
 * работают без изменений. Файлы модулей должны физически присутствовать в workspace —
 * это дамп {@code confdb extract --dump}, подготовленный {@code confdb prep-lsp}
 * (UTF-8 без BOM, LF).
 * <p>
 * Маппинг объект → файл берётся из самой базы: строки {@code file} с kind='bsl'
 * связаны с {@code module} по суффиксу пути {@code .<code_name>.bsl}.
 */
@Slf4j
public final class ConfdbSolutionProvider {

  /** confdb meta_object.type → MDOType для объектов, поддерживаемых мостом. */
  private static final Map<String, MDOType> MDO_TYPES = Map.ofEntries(
    Map.entry("Catalog", MDOType.CATALOG),
    Map.entry("Document", MDOType.DOCUMENT),
    Map.entry("CommonModule", MDOType.COMMON_MODULE),
    Map.entry("Constant", MDOType.CONSTANT),
    Map.entry("Enum", MDOType.ENUM),
    Map.entry("Report", MDOType.REPORT),
    Map.entry("DataProcessor", MDOType.DATA_PROCESSOR),
    Map.entry("InformationRegister", MDOType.INFORMATION_REGISTER),
    Map.entry("AccumulationRegister", MDOType.ACCUMULATION_REGISTER),
    Map.entry("AccountingRegister", MDOType.ACCOUNTING_REGISTER),
    Map.entry("CalculationRegister", MDOType.CALCULATION_REGISTER),
    Map.entry("DocumentJournal", MDOType.DOCUMENT_JOURNAL),
    Map.entry("ExchangePlan", MDOType.EXCHANGE_PLAN),
    Map.entry("BusinessProcess", MDOType.BUSINESS_PROCESS),
    Map.entry("Task", MDOType.TASK),
    Map.entry("ChartOfAccounts", MDOType.CHART_OF_ACCOUNTS),
    Map.entry("ChartOfCalculationTypes", MDOType.CHART_OF_CALCULATION_TYPES),
    Map.entry("ChartOfCharacteristicType", MDOType.CHART_OF_CHARACTERISTIC_TYPES),
    Map.entry("HTTPService", MDOType.HTTP_SERVICE),
    Map.entry("WebService", MDOType.WEB_SERVICE)
  );

  /** Типы регистров, у которых модуль obj — это модуль набора записей. */
  private static final Set<String> REGISTER_TYPES = Set.of(
    "InformationRegister", "AccumulationRegister", "AccountingRegister", "CalculationRegister");

  private ConfdbSolutionProvider() {
    // утилитарный класс
  }

  /**
   * Читет базу confdb и собирает {@link Solution}.
   *
   * @param database      путь к базе SQLite confdb
   * @param workspaceRoot корень workspace с файлами дампа (для URI модулей)
   * @return Solution; при ошибке чтения — {@link Solution#EMPTY}
   */
  public static Solution createSolution(Path database, Path workspaceRoot) {
    var url = "jdbc:sqlite:" + database.toAbsolutePath();
    try (var connection = DriverManager.getConnection(url)) {
      return readSolution(connection, workspaceRoot);
    } catch (SQLException e) {
      LOGGER.error("Can't read confdb database {}: {}", database, e.getMessage());
      return Solution.EMPTY;
    }
  }

  private static Solution readSolution(Connection connection, Path workspaceRoot) throws SQLException {
    var root = readRootConfiguration(connection);

    var modulesByObject = readModulesByObject(connection);
    var rows = readMetaObjects(connection);

    var configurationBuilder = Configuration.builder()
      .configurationSource(ConfigurationSource.DESIGNER)
      .name(root.name())
      .uuid(root.uuid())
      .mdoReference(MdoReference.create(MDOType.CONFIGURATION, root.name()))
      .scriptVariant(ScriptVariant.RUSSIAN)
      .objectBelonging(ObjectBelonging.OWN)
      .supportVariant(SupportVariant.NOT_SUPPORTED);

    // дети собираются и в типизированные коллекции, и в children —
    // по children mdclasses строит карты childrenByMdoRef/modulesByURI (findChild)
    var topLevel = new ArrayList<MD>();
    var objectsBuilt = 0;
    var modulesBound = 0;
    for (var row : rows) {
      if (row.parentId() == null || row.parentId() != root.id()) {
        continue;
      }
      var mdoType = MDO_TYPES.get(row.type());
      if (mdoType == null) {
        continue;
      }
      var reference = MdoReference.create(mdoType, row.name());
      var moduleRows = modulesByObject.getOrDefault(row.id(), List.of());
      if ("CommonModule".equals(row.type())) {
        if (moduleRows.isEmpty()) {
          continue;
        }
        var commonModule = buildCommonModule(row, reference, moduleRows.get(0), workspaceRoot);
        configurationBuilder.commonModule(commonModule);
        topLevel.add(commonModule);
      } else {
        var modules = buildObjectModules(row.type(), reference, moduleRows, workspaceRoot);
        if (modules.isEmpty()) {
          continue;
        }
        var md = addTopLevelObject(configurationBuilder, row, reference, modules);
        if (md == null) {
          continue;
        }
        topLevel.add(md);
        modulesBound += modules.size();
      }
      objectsBuilt++;
    }

    var configuration = configurationBuilder.children(topLevel).build();
    LOGGER.info("confdb: loaded configuration '{}' — {} objects, {} modules bound",
      root.name(), objectsBuilt, modulesBound);
    return Solution.builder()
      .baseConfiguration(configuration)
      .mergedConfiguration(configuration)
      .extensions(List.of())
      .provenance(Map.of())
      .build();
  }

  private static MetaRow readRootConfiguration(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement(
      "SELECT id, name, uuid FROM meta_object WHERE parent_id IS NULL LIMIT 1")) {
      try (var resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return new MetaRow(resultSet.getInt(1), null, "",
            resultSet.getString(2), resultSet.getString(3));
        }
      }
    }
    return new MetaRow(0, null, "", "Configuration", "");
  }

  private static List<MetaRow> readMetaObjects(Connection connection) throws SQLException {
    var rows = new ArrayList<MetaRow>();
    try (var statement = connection.prepareStatement(
      "SELECT id, parent_id, type, name, uuid FROM meta_object")) {
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          var parentId = resultSet.getObject(2) == null ? null : resultSet.getInt(2);
          rows.add(new MetaRow(resultSet.getInt(1), parentId,
            resultSet.getString(3), resultSet.getString(4), resultSet.getString(5)));
        }
      }
    }
    return rows;
  }

  private static Map<Integer, List<ModuleRow>> readModulesByObject(Connection connection) throws SQLException {
    var result = new HashMap<Integer, List<ModuleRow>>();
    try (var statement = connection.prepareStatement(
      "SELECT f.object_id, f.path, m.code_name, m.context "
      + "FROM file f JOIN module m ON m.object_id = f.object_id "
      + "WHERE f.kind = 'bsl' AND f.path LIKE '%.' || m.code_name || '.bsl'")) {
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          var moduleRow = new ModuleRow(resultSet.getString(2),
            resultSet.getString(3), resultSet.getString(4));
          result.computeIfAbsent(resultSet.getInt(1), key -> new ArrayList<>()).add(moduleRow);
        }
      }
    }
    return result;
  }

  private static List<Module> buildObjectModules(String type, MdoReference owner,
                                                 List<ModuleRow> moduleRows, Path workspaceRoot) {
    var modules = new ArrayList<Module>();
    for (var moduleRow : moduleRows) {
      var moduleType = switch (moduleRow.codeName()) {
        case "obj" -> REGISTER_TYPES.contains(type) ? ModuleType.RecordSetModule : ModuleType.ObjectModule;
        case "mgr" -> ModuleType.ManagerModule;
        case "seance" -> ModuleType.SessionModule;
        default -> null;
      };
      if (moduleType == null) {
        continue;
      }
      modules.add(ObjectModule.builder()
        .moduleType(moduleType)
        .uri(moduleUri(workspaceRoot, moduleRow.path()))
        .owner(owner)
        .supportVariant(SupportVariant.NOT_SUPPORTED)
        .isProtected(false)
        .build());
    }
    return modules;
  }

  private static CommonModule buildCommonModule(MetaRow row, MdoReference reference,
                                                ModuleRow moduleRow, Path workspaceRoot) {
    var context = moduleRow.context() == null ? "" : moduleRow.context();
    var flags = Arrays.stream(context.split(","))
      .map(String::trim)
      .collect(Collectors.toSet());
    return CommonModule.builder()
      .uuid(row.uuid())
      .name(row.name())
      .mdoReference(reference)
      .objectBelonging(ObjectBelonging.OWN)
      .supportVariant(SupportVariant.NOT_SUPPORTED)
      .moduleType(ModuleType.CommonModule)
      .uri(moduleUri(workspaceRoot, moduleRow.path()))
      .isProtected(false)
      .server(flags.contains("Сервер"))
      .serverCall(flags.contains("Вызов сервера"))
      .externalConnection(flags.contains("Внешнее соединение"))
      .clientOrdinaryApplication(flags.contains("Клиент (обычное приложение)"))
      .clientManagedApplication(flags.contains("Клиент (управляемое приложение)"))
      .global(flags.contains("Глобальный"))
      .returnValuesReuse(flags.contains("Повторное использование")
        ? ReturnValueReuse.DURING_REQUEST
        : ReturnValueReuse.DONT_USE)
      .build();
  }

  @Nullable
  private static MD addTopLevelObject(Configuration.ConfigurationBuilder builder, MetaRow row,
                                      MdoReference reference, List<Module> modules) {
    return switch (row.type()) {
      case "Catalog" -> {
        var md = Catalog.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.catalog(md);
        yield md;
      }
      case "Document" -> {
        var md = Document.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.document(md);
        yield md;
      }
      case "Constant" -> {
        var md = Constant.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.constant(md);
        yield md;
      }
      case "Enum" -> {
        var md = Enum.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.Enum(md);
        yield md;
      }
      case "Report" -> {
        var md = Report.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.report(md);
        yield md;
      }
      case "DataProcessor" -> {
        var md = DataProcessor.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.dataProcessor(md);
        yield md;
      }
      case "InformationRegister" -> {
        var md = InformationRegister.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.informationRegister(md);
        yield md;
      }
      case "AccumulationRegister" -> {
        var md = AccumulationRegister.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.accumulationRegister(md);
        yield md;
      }
      case "AccountingRegister" -> {
        var md = AccountingRegister.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.accountingRegister(md);
        yield md;
      }
      case "CalculationRegister" -> {
        var md = CalculationRegister.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.calculationRegister(md);
        yield md;
      }
      case "DocumentJournal" -> {
        var md = DocumentJournal.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.documentJournal(md);
        yield md;
      }
      case "ExchangePlan" -> {
        var md = ExchangePlan.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.exchangePlan(md);
        yield md;
      }
      case "BusinessProcess" -> {
        var md = BusinessProcess.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.businessProcess(md);
        yield md;
      }
      case "Task" -> {
        var md = Task.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.task(md);
        yield md;
      }
      case "ChartOfAccounts" -> {
        var md = ChartOfAccounts.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.chartOfAccounts(md);
        yield md;
      }
      case "ChartOfCalculationTypes" -> {
        var md = ChartOfCalculationTypes.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.chartOfCalculationTypes(md);
        yield md;
      }
      case "ChartOfCharacteristicType" -> {
        var md = ChartOfCharacteristicTypes.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.chartOfCharacteristicTypes(md);
        yield md;
      }
      case "HTTPService" -> {
        var md = HTTPService.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.httpService(md);
        yield md;
      }
      case "WebService" -> {
        var md = WebService.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).build();
        builder.webService(md);
        yield md;
      }
      // тип есть в MDO_TYPES, но билдер здесь ещё не поддержан
      default -> null;
    };
  }

  private static URI moduleUri(Path workspaceRoot, String relativePath) {
    return workspaceRoot.resolve(relativePath).toUri();
  }

  /** Строка meta_object. */
  private record MetaRow(int id, Integer parentId, String type, String name, String uuid) {
  }

  /** Строка модуля объекта: относительный путь файла в дампе, code_name, контекст. */
  private record ModuleRow(String path, String codeName, String context) {
  }
}
