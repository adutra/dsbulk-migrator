/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.cloudgate.migrator.direct;

import com.datastax.cloudgate.migrator.ExportedColumn;
import com.datastax.cloudgate.migrator.MigrationSettings;
import com.datastax.cloudgate.migrator.TableProcessor;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TableMigrator extends TableProcessor {

  private static final Logger LOGGER = LoggerFactory.getLogger(TableMigrator.class);

  protected final Path dataDir;

  protected final Path exportAckDir;
  protected final Path exportAckFile;

  protected final Path importAckDir;
  protected final Path importAckFile;

  public TableMigrator(
      TableMetadata table, MigrationSettings settings, List<ExportedColumn> exportedColumns) {
    super(table, settings, exportedColumns);
    this.dataDir =
        settings
            .getExportDir()
            .resolve(table.getKeyspace().asInternal())
            .resolve(table.getName().asInternal());
    this.exportAckDir = settings.getExportDir().resolve("__exported__");
    this.importAckDir = settings.getExportDir().resolve("__imported__");
    this.exportAckFile =
        exportAckDir.resolve(
            table.getKeyspace().asInternal() + "__" + table.getName().asInternal() + ".exported");
    this.importAckFile =
        importAckDir.resolve(
            table.getKeyspace().asInternal() + "__" + table.getName().asInternal() + ".imported");
  }

  public abstract TableMigrationReport exportTable();

  public abstract TableMigrationReport importTable();

  protected String createOperationId(boolean export) {
    ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
    String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(now);
    return String.format(
        "%s_%s_%s_%s",
        (export ? "EXPORT" : "IMPORT"),
        table.getKeyspace().asInternal(),
        table.getName().asInternal(),
        timestamp);
  }

  protected String checkAlreadyExported() {
    if (Files.exists(exportAckFile)) {
      LOGGER.warn(
          "Table {}.{}: already exported, skipping (delete this file to re-export: {}).",
          table.getKeyspace(),
          table.getName(),
          exportAckFile);
      try {
        return Files.readString(exportAckFile);
      } catch (IOException ignored) {
      }
    }
    return null;
  }

  protected boolean checkNotYetExported() {
    if (!Files.exists(exportAckFile)) {
      LOGGER.warn(
          "Table {}.{}: not yet exported, skipping import.", table.getKeyspace(), table.getName());
      return true;
    }
    return false;
  }

  protected String checkAlreadyImported() {
    if (Files.exists(importAckFile)) {
      LOGGER.warn(
          "Table {}.{}: already imported, skipping (delete this file to re-import: {}).",
          table.getKeyspace(),
          table.getName(),
          importAckFile);
      try {
        return Files.readString(importAckFile);
      } catch (IOException ignored) {
      }
    }
    return null;
  }

  protected void createExportAckFile(String operationId) {
    try {
      Files.createDirectories(exportAckDir);
      Files.createFile(exportAckFile);
      Files.write(exportAckFile, operationId.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  protected void createImportAckFile(String operationId) {
    try {
      Files.createDirectories(importAckDir);
      Files.createFile(importAckFile);
      Files.write(importAckFile, operationId.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  protected List<String> createExportArgs(String operationId) {
    List<String> args = new ArrayList<>();
    args.add("unload");
    if (settings.getExportBundle().isPresent()) {
      args.add("-b");
      args.add(String.valueOf(settings.getImportBundle()));
    } else {
      args.add("-h");
      args.add("[\"" + settings.getExportHostString() + "\"]");
    }
    if (settings.getExportUsername().isPresent()) {
      args.add("-u");
      args.add(settings.getExportUsername().get());
    }
    if (settings.getExportPassword().isPresent()) {
      args.add("-p");
      args.add(settings.getExportPassword().get());
    }
    args.add("-url");
    args.add(String.valueOf(dataDir));
    args.add("-maxRecords");
    args.add(String.valueOf(settings.getExportMaxRecords()));
    args.add("-maxConcurrentFiles");
    args.add(settings.getExportMaxConcurrentFiles());
    args.add("-maxConcurrentQueries");
    args.add(settings.getExportMaxConcurrentQueries());
    args.add("--schema.splits");
    args.add(settings.getExportSplits());
    args.add("-cl");
    args.add(String.valueOf(settings.getExportConsistency()));
    args.add("-header");
    args.add("false");
    args.add("-verbosity");
    args.add("0");
    args.add("--engine.executionId");
    args.add(operationId);
    args.add("-logDir");
    args.add(String.valueOf(settings.getDsbulkLogDir()));
    args.add("-query");
    args.add(buildExportQuery());
    return args;
  }

  protected List<String> createImportArgs(String operationId) {
    List<String> args = new ArrayList<>();
    args.add("load");
    if (settings.getImportBundle().isPresent()) {
      args.add("-b");
      args.add(String.valueOf(settings.getImportBundle()));
    } else {
      args.add("-h");
      args.add("[\"" + settings.getImportHostString() + "\"]");
    }
    if (settings.getImportUsername().isPresent()) {
      args.add("-u");
      args.add(settings.getImportUsername().get());
    }
    if (settings.getImportPassword().isPresent()) {
      args.add("-p");
      args.add(settings.getImportPassword().get());
    }
    args.add("-url");
    args.add(String.valueOf(dataDir));
    args.add("-maxConcurrentFiles");
    args.add(settings.getImportMaxConcurrentFiles());
    args.add("-maxConcurrentQueries");
    args.add(settings.getImportMaxConcurrentQueries());
    args.add("-cl");
    args.add(String.valueOf(settings.getImportConsistency()));
    args.add("-header");
    args.add("false");
    args.add("-verbosity");
    args.add("0");
    args.add("--engine.executionId");
    args.add(operationId);
    args.add("-logDir");
    args.add(String.valueOf(settings.getDsbulkLogDir()));
    args.add("-m");
    args.add(buildImportMapping());
    int regularColumns = countRegularColumns();
    if (regularColumns == 0) {
      args.add("-k");
      args.add(escape(table.getKeyspace()));
      args.add("-t");
      args.add(escape(table.getName()));
    } else if (regularColumns == 1) {
      args.add("-query");
      args.add(buildSingleImportQuery());
    } else {
      args.add("--batch.mode");
      args.add("DISABLED");
      args.add("-query");
      args.add(buildBatchImportQuery());
    }
    return args;
  }

  protected String escape(String text) {
    return text.replace("\"", "\\\"");
  }

  protected String getImportDefaultTimestamp() {
    return String.valueOf(settings.getImportDefaultTimestamp());
  }
}
