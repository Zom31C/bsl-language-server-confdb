package com.github._1c_syntax.bsl.languageserver.context;

import com.github._1c_syntax.bsl.mdo.AccountingRegister;
import com.github._1c_syntax.bsl.mdo.AccumulationRegister;
import com.github._1c_syntax.bsl.mdo.BusinessProcess;
import com.github._1c_syntax.bsl.mdo.CalculationRegister;
import com.github._1c_syntax.bsl.mdo.Catalog;
import com.github._1c_syntax.bsl.mdo.ChartOfAccounts;
import com.github._1c_syntax.bsl.mdo.ChartOfCalculationTypes;
import com.github._1c_syntax.bsl.mdo.ChartOfCharacteristicTypes;
import com.github._1c_syntax.bsl.mdo.CommonCommand;
import com.github._1c_syntax.bsl.mdo.CommonForm;
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
import com.github._1c_syntax.bsl.mdo.Role;
import com.github._1c_syntax.bsl.mdo.Subsystem;
import com.github._1c_syntax.bsl.mdo.Task;
import com.github._1c_syntax.bsl.mdo.WebService;
import com.github._1c_syntax.bsl.mdo.children.EnumValue;
import com.github._1c_syntax.bsl.mdo.children.ObjectAttribute;
import com.github._1c_syntax.bsl.mdo.children.ObjectCommand;
import com.github._1c_syntax.bsl.mdo.children.ObjectForm;
import com.github._1c_syntax.bsl.mdo.children.ObjectModule;
import com.github._1c_syntax.bsl.mdo.children.ObjectTabularSection;
import com.github._1c_syntax.bsl.mdo.support.FormType;
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
import com.github._1c_syntax.bsl.types.ValueTypeDescription;
import com.github._1c_syntax.bsl.types.value.PrimitiveValueType;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

  /** confdb meta_object.type → MDOType для объектов верхнего уровня. */
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
    Map.entry("WebService", MDOType.WEB_SERVICE),
    Map.entry("Role", MDOType.ROLE),
    Map.entry("Subsystem", MDOType.SUBSYSTEM),
    Map.entry("CommonCommand", MDOType.COMMON_COMMAND),
    Map.entry("CommonForm", MDOType.COMMON_FORM)
  );

  /** Типы регистров, у которых модуль obj — это модуль набора записей. */
  private static final Set<String> REGISTER_TYPES = Set.of(
    "InformationRegister", "AccumulationRegister", "AccountingRegister", "CalculationRegister");

  private ConfdbSolutionProvider() {
    // утилитарный класс
  }

  /**
   * Читает базу confdb и собирает {@link Solution}.
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
    var rows = readMetaObjects(connection);
    var modulesByObject = readModulesByObject(connection);
    var attributesByObject = readAttributes(connection);
    var tabularsByObject = readTabulars(connection);
    var enumValuesByObject = readEnumValues(connection);
    var contentBySubsystem = readSubsystemContent(connection);

    var rowsByParent = new HashMap<Integer, List<MetaRow>>();
    var rowsById = new HashMap<Integer, MetaRow>();
    for (var row : rows) {
      if (row.parentId() != null) {
        rowsByParent.computeIfAbsent(row.parentId(), key -> new ArrayList<>()).add(row);
      }
      rowsById.put(row.id(), row);
    }

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
    var formsBuilt = 0;
    var attributesBuilt = 0;
    // mdclasses сравнивает MdoReference без учёта регистра: дубликаты имён,
    // различающихся только регистром (опечатки конфигурации), роняют построение
    // индексов (Duplicate key) — повторяющиеся имена пропускаются
    var seenTopLevel = new HashSet<String>();
    for (var row : rowsByParent.getOrDefault(root.id(), List.of())) {
      var mdoType = MDO_TYPES.get(row.type());
      if (mdoType == null) {
        continue;
      }
      if (!seenTopLevel.add(row.type() + '#' + row.name().toLowerCase(Locale.ROOT))) {
        continue;
      }
      var reference = MdoReference.create(mdoType, row.name());
      if ("Role".equals(row.type())) {
        var role = Role.builder()
          .uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .build();
        configurationBuilder.role(role);
        topLevel.add(role);
        objectsBuilt++;
        continue;
      }
      if ("Subsystem".equals(row.type())) {
        var subsystem = buildSubsystem(row, rowsByParent, contentBySubsystem, rowsById, "Subsystem");
        configurationBuilder.subsystem(subsystem);
        topLevel.add(subsystem);
        objectsBuilt++;
        continue;
      }
      if ("CommonModule".equals(row.type())) {
        var moduleRows = modulesByObject.getOrDefault(row.id(), List.of());
        if (moduleRows.isEmpty()) {
          continue;
        }
        var commonModule = buildCommonModule(row, reference, moduleRows.get(0), workspaceRoot);
        configurationBuilder.commonModule(commonModule);
        topLevel.add(commonModule);
        modulesBound++;
        objectsBuilt++;
        continue;
      }
      if ("CommonCommand".equals(row.type()) || "CommonForm".equals(row.type())) {
        var isForm = "CommonForm".equals(row.type());
        var boundModules = buildBoundModules(reference,
          isForm ? ModuleType.FormModule : ModuleType.CommandModule,
          modulesByObject.getOrDefault(row.id(), List.of()), workspaceRoot);
        if ("CommonCommand".equals(row.type())) {
          var command = CommonCommand.builder()
            .uuid(row.uuid()).name(row.name()).mdoReference(reference)
            .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
            .modules(boundModules).build();
          configurationBuilder.commonCommand(command);
          topLevel.add(command);
        } else {
          var form = CommonForm.builder()
            .uuid(row.uuid()).name(row.name()).mdoReference(reference)
            .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
            .formType(FormType.MANAGED).modules(boundModules).build();
          configurationBuilder.commonForm(form);
          topLevel.add(form);
          formsBuilt++;
        }
        modulesBound += boundModules.size();
        objectsBuilt++;
        continue;
      }
      var modules = buildObjectModules(row.type(), reference,
        modulesByObject.getOrDefault(row.id(), List.of()), workspaceRoot);
      var forms = buildForms(rowsByParent.getOrDefault(row.id(), List.of()),
        row, reference, modulesByObject, workspaceRoot);
      formsBuilt += forms.size();
      var commands = buildCommands(rowsByParent.getOrDefault(row.id(), List.of()),
        row, reference, mdoType, modulesByObject, workspaceRoot);
      var attributes = buildAttributes(attributesByObject.getOrDefault(row.id(), List.of()),
        row, reference, mdoType, null);
      var tabulars = buildTabularSections(tabularsByObject.getOrDefault(row.id(), List.of()),
        attributesByObject.getOrDefault(row.id(), List.of()), row, reference, mdoType);
      attributesBuilt += attributes.size()
        + tabulars.stream().mapToInt(ts -> ts.getAttributes().size()).sum();
      var enumValues = buildEnumValues(enumValuesByObject.getOrDefault(row.id(), List.of()),
        row.name(), reference);
      var md = addTopLevelObject(configurationBuilder, row, reference,
        modules, forms, commands, attributes, tabulars, enumValues, attributesByObject);
      if (md == null) {
        continue;
      }
      topLevel.add(md);
      modulesBound += modules.size() + forms.size()
        + commands.stream().mapToInt(command -> command.getModules().size()).sum();
      objectsBuilt++;
    }

    // модули самой конфигурации (сеанса, управляемого приложения)
    var configurationReference = MdoReference.create(MDOType.CONFIGURATION, root.name());
    var configurationModules = new ArrayList<Module>();
    for (var moduleRow : modulesByObject.getOrDefault(root.id(), List.of())) {
      var moduleType = switch (moduleRow.codeName()) {
        case "seance" -> ModuleType.SessionModule;
        case "app" -> ModuleType.ApplicationModule;
        default -> null; // 802/con — модули обычного приложения и менеджера значений
      };
      if (moduleType == null) {
        continue;
      }
      configurationModules.add(ObjectModule.builder()
        .moduleType(moduleType)
        .uri(moduleUri(workspaceRoot, moduleRow.path()))
        .owner(configurationReference)
        .supportVariant(SupportVariant.NOT_SUPPORTED)
        .isProtected(false)
        .build());
    }
    modulesBound += configurationModules.size();

    var configuration = configurationBuilder
      .modules(configurationModules)
      .children(topLevel)
      .build();
    LOGGER.info("confdb: loaded configuration '{}' — {} objects, {} modules, {} forms, {} attributes",
      root.name(), objectsBuilt, modulesBound, formsBuilt, attributesBuilt);
    return Solution.builder()
      .baseConfiguration(configuration)
      .mergedConfiguration(configuration)
      .extensions(List.of())
      .provenance(Map.of())
      .build();
  }

  // -- чтение таблиц ----------------------------------------------------------

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

  private static Map<Integer, List<AttributeRow>> readAttributes(Connection connection) throws SQLException {
    var result = new HashMap<Integer, List<AttributeRow>>();
    try (var statement = connection.prepareStatement(
      "SELECT object_id, name, type_str, tabular FROM meta_attribute ORDER BY ord")) {
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          var row = new AttributeRow(resultSet.getString(2),
            resultSet.getString(3), resultSet.getString(4));
          result.computeIfAbsent(resultSet.getInt(1), key -> new ArrayList<>()).add(row);
        }
      }
    }
    return result;
  }

  private static Map<Integer, List<TabularRow>> readTabulars(Connection connection) throws SQLException {
    var result = new HashMap<Integer, List<TabularRow>>();
    try (var statement = connection.prepareStatement(
      "SELECT object_id, ord, name FROM meta_tabular ORDER BY ord")) {
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          result.computeIfAbsent(resultSet.getInt(1), key -> new ArrayList<>())
            .add(new TabularRow(resultSet.getInt(2), resultSet.getString(3)));
        }
      }
    }
    return result;
  }

  private static Map<Integer, List<EnumValueRow>> readEnumValues(Connection connection) throws SQLException {
    var result = new HashMap<Integer, List<EnumValueRow>>();
    try (var statement = connection.prepareStatement(
      "SELECT object_id, ord, name FROM enum_value ORDER BY ord")) {
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          result.computeIfAbsent(resultSet.getInt(1), key -> new ArrayList<>())
            .add(new EnumValueRow(resultSet.getInt(2), resultSet.getString(3)));
        }
      }
    }
    return result;
  }

  private static Map<Integer, List<Integer>> readSubsystemContent(Connection connection) throws SQLException {
    var result = new HashMap<Integer, List<Integer>>();
    try (var statement = connection.prepareStatement(
      "SELECT subsystem_id, target_id FROM subsystem_content")) {
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          result.computeIfAbsent(resultSet.getInt(1), key -> new ArrayList<>())
            .add(resultSet.getInt(2));
        }
      }
    }
    return result;
  }

  // -- сборка детей -----------------------------------------------------------

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

  private static List<ObjectForm> buildForms(List<MetaRow> children, MetaRow ownerRow,
                                             MdoReference ownerReference,
                                             Map<Integer, List<ModuleRow>> modulesByObject,
                                             Path workspaceRoot) {
    var forms = new ArrayList<ObjectForm>();
    var ownerType = MDO_TYPES.get(ownerRow.type());
    if (ownerType == null) {
      return forms;
    }
    var seen = new HashSet<String>();
    for (var row : children) {
      if (!"Form".equals(row.type()) && !row.type().endsWith("Form")) {
        continue;
      }
      if (!seen.add(row.name().toLowerCase(Locale.ROOT))) {
        continue;
      }
      var formReference = MdoReference.create(ownerType,
        ownerRow.name() + ".Form." + row.name());
      var formModules = new ArrayList<Module>();
      for (var moduleRow : modulesByObject.getOrDefault(row.id(), List.of())) {
        formModules.add(ObjectModule.builder()
          .moduleType(ModuleType.FormModule)
          .uri(moduleUri(workspaceRoot, moduleRow.path()))
          .owner(formReference)
          .supportVariant(SupportVariant.NOT_SUPPORTED)
          .isProtected(false)
          .build());
      }
      forms.add(ObjectForm.builder()
        .uuid(row.uuid())
        .name(row.name())
        .mdoReference(formReference)
        .objectBelonging(ObjectBelonging.OWN)
        .supportVariant(SupportVariant.NOT_SUPPORTED)
        .formType(FormType.MANAGED)
        .owner(ownerReference)
        .modules(formModules)
        .build());
    }
    return forms;
  }

  private static List<Module> buildBoundModules(MdoReference owner, ModuleType moduleType,
                                                List<ModuleRow> moduleRows, Path workspaceRoot) {
    var modules = new ArrayList<Module>();
    for (var moduleRow : moduleRows) {
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

  private static List<ObjectCommand> buildCommands(List<MetaRow> children, MetaRow ownerRow,
                                                   MdoReference ownerReference, MDOType ownerType,
                                                   Map<Integer, List<ModuleRow>> modulesByObject,
                                                   Path workspaceRoot) {
    var commands = new ArrayList<ObjectCommand>();
    var seen = new HashSet<String>();
    for (var row : children) {
      if (!row.type().endsWith("Command")) {
        continue;
      }
      if (!seen.add(row.name().toLowerCase(Locale.ROOT))) {
        continue;
      }
      var commandReference = MdoReference.create(ownerType,
        ownerRow.name() + ".Command." + row.name());
      commands.add(ObjectCommand.builder()
        .uuid(row.uuid())
        .name(row.name())
        .mdoReference(commandReference)
        .owner(ownerReference)
        .objectBelonging(ObjectBelonging.OWN)
        .supportVariant(SupportVariant.NOT_SUPPORTED)
        .modules(buildBoundModules(commandReference, ModuleType.CommandModule,
          modulesByObject.getOrDefault(row.id(), List.of()), workspaceRoot))
        .build());
    }
    return commands;
  }

  private static List<ObjectAttribute> buildAttributes(List<AttributeRow> attributeRows,
                                                       MetaRow ownerRow, MdoReference ownerReference,
                                                       MDOType ownerType,
                                                       @Nullable String tabularSection) {
    var attributes = new ArrayList<ObjectAttribute>();
    var seen = new HashSet<String>();
    for (var row : attributeRows) {
      var inTabular = row.tabular() != null;
      if (tabularSection == null && inTabular) {
        continue; // реквизит табличной части строится вместе с ней
      }
      if (tabularSection != null && !tabularSection.equals(row.tabular())) {
        continue;
      }
      if (!seen.add(row.name().toLowerCase(Locale.ROOT))) {
        continue;
      }
      var segment = tabularSection == null
        ? ownerRow.name() + ".Attribute." + row.name()
        : ownerRow.name() + ".TabularSection." + tabularSection + ".Attribute." + row.name();
      attributes.add(ObjectAttribute.builder()
        .name(row.name())
        .mdoReference(MdoReference.create(ownerType, segment))
        .owner(ownerReference)
        .objectBelonging(ObjectBelonging.OWN)
        .supportVariant(SupportVariant.NOT_SUPPORTED)
        .type(parseTypeDescription(row.typeStr()))
        .build());
    }
    return attributes;
  }

  private static List<ObjectTabularSection> buildTabularSections(List<TabularRow> tabularRows,
                                                                 List<AttributeRow> attributeRows,
                                                                 MetaRow ownerRow,
                                                                 MdoReference ownerReference,
                                                                 MDOType ownerType) {
    var sections = new ArrayList<ObjectTabularSection>();
    var seen = new HashSet<String>();
    for (var tabular : tabularRows) {
      if (!seen.add(tabular.name().toLowerCase(Locale.ROOT))) {
        continue;
      }
      var reference = MdoReference.create(ownerType,
        ownerRow.name() + ".TabularSection." + tabular.name());
      sections.add(ObjectTabularSection.builder()
        .name(tabular.name())
        .mdoReference(reference)
        .owner(ownerReference)
        .objectBelonging(ObjectBelonging.OWN)
        .supportVariant(SupportVariant.NOT_SUPPORTED)
        .attributes(buildAttributes(attributeRows, ownerRow, ownerReference, ownerType, tabular.name()))
        .build());
    }
    return sections;
  }

  private static List<EnumValue> buildEnumValues(List<EnumValueRow> valueRows, String ownerName,
                                                 MdoReference ownerReference) {
    var values = new ArrayList<EnumValue>();
    var seen = new HashSet<String>();
    for (var row : valueRows) {
      if (!seen.add(row.name().toLowerCase(Locale.ROOT))) {
        continue;
      }
      values.add(EnumValue.builder()
        .name(row.name())
        .mdoReference(MdoReference.create(MDOType.ENUM,
          ownerName + ".EnumValue." + row.name()))
        .owner(ownerReference)
        .objectBelonging(ObjectBelonging.OWN)
        .supportVariant(SupportVariant.NOT_SUPPORTED)
        .build());
    }
    return values;
  }

  private static Subsystem buildSubsystem(MetaRow row, Map<Integer, List<MetaRow>> rowsByParent,
                                          Map<Integer, List<Integer>> contentBySubsystem,
                                          Map<Integer, MetaRow> rowsById, String referencePrefix) {
    var reference = MdoReference.create(MDOType.SUBSYSTEM, referencePrefix + "." + row.name());
    var builder = Subsystem.builder()
      .uuid(row.uuid())
      .name(row.name())
      .mdoReference(reference)
      .objectBelonging(ObjectBelonging.OWN)
      .supportVariant(SupportVariant.NOT_SUPPORTED);
    var content = new ArrayList<MdoReference>();
    for (var targetId : contentBySubsystem.getOrDefault(row.id(), List.of())) {
      var target = rowsById.get(targetId);
      if (target == null) {
        continue;
      }
      var targetType = MDO_TYPES.get(target.type());
      if (targetType == null) {
        continue;
      }
      content.add(MdoReference.create(targetType, target.name()));
    }
    builder.content(content);
    var childPrefix = referencePrefix + "." + row.name() + ".Subsystem";
    for (var child : rowsByParent.getOrDefault(row.id(), List.of())) {
      if ("Subsystem".equals(child.type())) {
        builder.subsystem(buildSubsystem(child, rowsByParent, contentBySubsystem, rowsById, childPrefix));
      }
    }
    return builder.build();
  }

  /** Разбирает confdb type_str в {@link ValueTypeDescription}. */
  private static ValueTypeDescription parseTypeDescription(@Nullable String typeStr) {
    if (typeStr == null || typeStr.isEmpty()) {
      return ValueTypeDescription.EMPTY;
    }
    var primitives = new ArrayList<com.github._1c_syntax.bsl.types.ValueType>();
    var refs = new ArrayList<MdoReference>();
    for (var rawPart : typeStr.split("\\|")) {
      var part = rawPart.trim();
      if (part.startsWith("ОпределяемыйТип:")) {
        var open = part.indexOf('(');
        var close = part.lastIndexOf(')');
        if (open < 0 || close <= open) {
          continue; // определяемый тип без раскрытия состава
        }
        part = part.substring(open + 1, close).trim();
      }
      if (part.startsWith("Строка")) {
        primitives.add(PrimitiveValueType.STRING);
      } else if (part.startsWith("Число")) {
        primitives.add(PrimitiveValueType.NUMBER);
      } else if (part.startsWith("Дата")) {
        primitives.add(PrimitiveValueType.DATE);
      } else if (part.startsWith("Булево")) {
        primitives.add(PrimitiveValueType.BOOLEAN);
      } else if (part.startsWith("Ссылка: ")) {
        var path = part.substring("Ссылка: ".length()).trim();
        var slash = path.indexOf('/');
        if (slash > 0) {
          var mdoType = MDO_TYPES.get(path.substring(0, slash));
          if (mdoType != null) {
            refs.add(MdoReference.create(mdoType, path.substring(slash + 1)));
          }
        }
      }
      // остальные типы (ХранилищеЗначения, УникальныйИдентификатор, ...) пока пропускаются
    }
    if (!refs.isEmpty()) {
      return ValueTypeDescription.createRef(refs);
    }
    if (!primitives.isEmpty()) {
      return ValueTypeDescription.create(primitives);
    }
    return ValueTypeDescription.EMPTY;
  }

  // -- сборка объектов верхнего уровня ----------------------------------------

  @Nullable
  private static MD addTopLevelObject(Configuration.ConfigurationBuilder builder, MetaRow row,
                                      MdoReference reference, List<Module> modules,
                                      List<ObjectForm> forms, List<ObjectCommand> commands,
                                      List<ObjectAttribute> attributes,
                                      List<ObjectTabularSection> tabulars, List<EnumValue> enumValues,
                                      Map<Integer, List<AttributeRow>> attributesByObject) {
    return switch (row.type()) {
      case "Catalog" -> {
        var md = Catalog.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).commands(commands)
          .attributes(attributes).tabularSections(tabulars).build();
        builder.catalog(md);
        yield md;
      }
      case "Document" -> {
        var md = Document.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).commands(commands)
          .attributes(attributes).tabularSections(tabulars).build();
        builder.document(md);
        yield md;
      }
      case "Constant" -> {
        var constantType = attributesByObject.getOrDefault(row.id(), List.of()).stream()
          .filter(attribute -> attribute.tabular() == null)
          .findFirst()
          .map(attribute -> parseTypeDescription(attribute.typeStr()))
          .orElse(ValueTypeDescription.EMPTY);
        var md = Constant.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).type(constantType).build();
        builder.constant(md);
        yield md;
      }
      case "Enum" -> {
        var md = Enum.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).enumValues(enumValues).build();
        builder.Enum(md);
        yield md;
      }
      case "Report" -> {
        var md = Report.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).tabularSections(tabulars).build();
        builder.report(md);
        yield md;
      }
      case "DataProcessor" -> {
        var md = DataProcessor.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).tabularSections(tabulars).build();
        builder.dataProcessor(md);
        yield md;
      }
      case "InformationRegister" -> {
        var md = InformationRegister.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).commands(commands).attributes(attributes).build();
        builder.informationRegister(md);
        yield md;
      }
      case "AccumulationRegister" -> {
        var md = AccumulationRegister.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).commands(commands).attributes(attributes).build();
        builder.accumulationRegister(md);
        yield md;
      }
      case "AccountingRegister" -> {
        var md = AccountingRegister.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).build();
        builder.accountingRegister(md);
        yield md;
      }
      case "CalculationRegister" -> {
        var md = CalculationRegister.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).build();
        builder.calculationRegister(md);
        yield md;
      }
      case "DocumentJournal" -> {
        var md = DocumentJournal.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).build();
        builder.documentJournal(md);
        yield md;
      }
      case "ExchangePlan" -> {
        var md = ExchangePlan.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).commands(commands).attributes(attributes).build();
        builder.exchangePlan(md);
        yield md;
      }
      case "BusinessProcess" -> {
        var md = BusinessProcess.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).tabularSections(tabulars).build();
        builder.businessProcess(md);
        yield md;
      }
      case "Task" -> {
        var md = Task.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).tabularSections(tabulars).build();
        builder.task(md);
        yield md;
      }
      case "ChartOfAccounts" -> {
        var md = ChartOfAccounts.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).tabularSections(tabulars).build();
        builder.chartOfAccounts(md);
        yield md;
      }
      case "ChartOfCalculationTypes" -> {
        var md = ChartOfCalculationTypes.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).build();
        builder.chartOfCalculationTypes(md);
        yield md;
      }
      case "ChartOfCharacteristicType" -> {
        var md = ChartOfCharacteristicTypes.builder().uuid(row.uuid()).name(row.name()).mdoReference(reference)
          .objectBelonging(ObjectBelonging.OWN).supportVariant(SupportVariant.NOT_SUPPORTED)
          .modules(modules).forms(forms).attributes(attributes).build();
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

  /** Строка meta_attribute. */
  private record AttributeRow(String name, String typeStr, @Nullable String tabular) {
  }

  /** Строка meta_tabular. */
  private record TabularRow(int ord, String name) {
  }

  /** Строка enum_value. */
  private record EnumValueRow(int ord, String name) {
  }
}
