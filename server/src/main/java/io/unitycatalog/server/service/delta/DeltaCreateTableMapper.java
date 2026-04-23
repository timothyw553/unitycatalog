package io.unitycatalog.server.service.delta;

import io.unitycatalog.server.delta.model.CreateTableRequest;
import io.unitycatalog.server.delta.model.DeltaProtocol;
import io.unitycatalog.server.delta.model.StructField;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.ColumnInfo;
import io.unitycatalog.server.model.CreateTable;
import io.unitycatalog.server.model.DataSourceFormat;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.utils.ColumnUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a Delta REST Catalog {@link CreateTableRequest} (with typed Delta columns and kebab-case
 * field names) into the UC {@link CreateTable} (with UC {@link ColumnInfo}s and
 * partition-index-per-column). The server holds path params for catalog and schema; the rest comes
 * from the request body.
 *
 * <p>{@code protocol} and {@code domain-metadata} consistency, and their projection into
 * table-property form, are handled by {@link DeltaPropertyMapper} so the same rules apply on
 * update / commit. This mapper adds the create-specific checks on top: required fields (name,
 * location, columns, protocol, table-type, data-source-format), the DELTA-only format rule, and
 * the UC-managed invariant that MANAGED tables must declare the
 * {@link DeltaTableFeatures#CATALOG_MANAGED} writer feature.
 */
public final class DeltaCreateTableMapper {

  private DeltaCreateTableMapper() {}

  public static CreateTable toCreateTable(String catalog, String schema, CreateTableRequest req) {
    if (req.getName() == null || req.getName().isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Table name is required.");
    }
    if (req.getLocation() == null || req.getLocation().isBlank()) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Table location is required.");
    }
    if (req.getColumns() == null || req.getColumns().getFields() == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Table columns are required.");
    }

    TableType tableType = toUCTableType(req.getTableType());
    if (req.getProtocol() == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "protocol is required.");
    }
    requireCatalogManagedForManaged(req.getProtocol(), tableType);
    DeltaPropertyMapper.validateDomainMetadataAgainstProtocol(
        req.getProtocol(), req.getDomainMetadata());

    List<ColumnInfo> columns = new ArrayList<>();
    List<StructField> fields = req.getColumns().getFields();
    for (int i = 0; i < fields.size(); i++) {
      columns.add(ColumnUtils.toColumnInfo(fields.get(i), i));
    }
    ColumnUtils.applyPartitionColumns(columns, req.getPartitionColumns());

    return new CreateTable()
        .name(req.getName())
        .catalogName(catalog)
        .schemaName(schema)
        .tableType(tableType)
        .dataSourceFormat(toUCDataSourceFormat(req.getDataSourceFormat()))
        .columns(columns)
        .comment(req.getComment())
        .storageLocation(req.getLocation())
        .properties(
            DeltaPropertyMapper.mergeDerivedWithClient(
                req.getProtocol(), req.getDomainMetadata(), req.getProperties()));
  }

  /**
   * Enforces that MANAGED tables declare the UC-managed {@link DeltaTableFeatures#CATALOG_MANAGED}
   * writer feature. The full UC-managed contract (required feature / property list) is published
   * in {@link DeltaStagingTableMapper#toStagingTableResponse}; this check enforces only the
   * load-bearing invariant on the assumption that the client honored the rest. The Delta log
   * remains the source of truth.
   */
  private static void requireCatalogManagedForManaged(DeltaProtocol protocol, TableType tableType) {
    if (tableType != TableType.MANAGED) return;
    List<String> writerFeatures = protocol.getWriterFeatures();
    if (writerFeatures == null || !writerFeatures.contains(DeltaTableFeatures.CATALOG_MANAGED)) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "MANAGED tables must declare the '"
              + DeltaTableFeatures.CATALOG_MANAGED
              + "' writer feature.");
    }
  }

  private static TableType toUCTableType(io.unitycatalog.server.delta.model.TableType type) {
    if (type == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "table-type is required.");
    }
    return switch (type) {
      case MANAGED -> TableType.MANAGED;
      case EXTERNAL -> TableType.EXTERNAL;
    };
  }

  private static DataSourceFormat toUCDataSourceFormat(
      io.unitycatalog.server.delta.model.DataSourceFormat format) {
    if (format == null) {
      throw new BaseException(ErrorCode.INVALID_ARGUMENT, "data-source-format is required.");
    }
    // Only DELTA is accepted.
    if (format == io.unitycatalog.server.delta.model.DataSourceFormat.DELTA) {
      return DataSourceFormat.DELTA;
    }
    throw new BaseException(
        ErrorCode.INVALID_ARGUMENT, "Unsupported data-source-format: " + format.getValue());
  }

}
