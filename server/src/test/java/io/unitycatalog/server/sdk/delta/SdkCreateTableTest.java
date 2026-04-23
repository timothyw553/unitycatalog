package io.unitycatalog.server.sdk.delta;

import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.client.ApiException;
import io.unitycatalog.client.delta.api.TablesApi;
import io.unitycatalog.client.delta.model.ClusteringDomainMetadata;
import io.unitycatalog.client.delta.model.CreateStagingTableRequest;
import io.unitycatalog.client.delta.model.CreateTableRequest;
import io.unitycatalog.client.delta.model.DataSourceFormat;
import io.unitycatalog.client.delta.model.DeltaProtocol;
import io.unitycatalog.client.delta.model.DomainMetadataUpdates;
import io.unitycatalog.client.delta.model.ErrorType;
import io.unitycatalog.client.delta.model.LoadTableResponse;
import io.unitycatalog.client.delta.model.PrimitiveType;
import io.unitycatalog.client.delta.model.StagingTableResponse;
import io.unitycatalog.client.delta.model.StructField;
import io.unitycatalog.client.delta.model.StructType;
import io.unitycatalog.client.delta.model.TableType;
import io.unitycatalog.client.model.CreateCatalog;
import io.unitycatalog.client.model.CreateSchema;
import io.unitycatalog.server.base.BaseCRUDTestWithMockCredentials;
import io.unitycatalog.server.base.ServerConfig;
import io.unitycatalog.server.base.catalog.CatalogOperations;
import io.unitycatalog.server.base.schema.SchemaOperations;
import io.unitycatalog.server.sdk.catalog.SdkCatalogOperations;
import io.unitycatalog.server.sdk.schema.SdkSchemaOperations;
import io.unitycatalog.server.service.delta.DeltaTableFeatures;
import io.unitycatalog.server.utils.TableProperties;
import io.unitycatalog.server.utils.TestUtils;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Delta REST Catalog {@code POST /v1/.../tables} endpoint. Consolidated
 * into one test with sections so the server start + mock-cloud setup runs once. Covers both MANAGED
 * (staging-finalize) and EXTERNAL flows plus the protocol / domain-metadata validation rules.
 */
public class SdkCreateTableTest extends BaseCRUDTestWithMockCredentials {

  private TablesApi deltaTablesApi;

  @Override
  protected CatalogOperations createCatalogOperations(ServerConfig serverConfig) {
    return new SdkCatalogOperations(TestUtils.createApiClient(serverConfig));
  }

  @Override
  protected SchemaOperations createSchemaOperations(ServerConfig serverConfig) {
    return new SdkSchemaOperations(TestUtils.createApiClient(serverConfig));
  }

  @BeforeEach
  @Override
  public void setUp() {
    super.setUp();
    deltaTablesApi = new TablesApi(TestUtils.createApiClient(serverConfig));
    createS3Catalog();
  }

  @Test
  public void testCreateTableEndpoint() throws ApiException {
    // -------- MANAGED happy path: staging -> createTable -> LoadTableResponse --------
    String tableName = "tbl_happy";
    StagingTableResponse staging =
        deltaTablesApi.createStagingTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            new CreateStagingTableRequest().name(tableName));

    LoadTableResponse resp =
        deltaTablesApi.createTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            managedTableRequest(tableName, staging.getLocation()));

    assertThat(resp.getMetadata()).isNotNull();
    assertThat(resp.getMetadata().getTableType()).isEqualTo(TableType.MANAGED);
    // Finalized table inherits the staging location and the UUID allocated at staging time.
    assertThat(resp.getMetadata().getLocation()).isEqualTo(staging.getLocation());
    assertThat(resp.getMetadata().getTableUuid()).isEqualTo(staging.getTableId());
    assertThat(resp.getMetadata().getColumns().getFields())
        .extracting(StructField::getName)
        .containsExactly("id", "amount");
    // Every feature in the request's protocol is mirrored as delta.feature.* = supported in the
    // stored table properties (both reader- and writer-side features collapse to one key per
    // feature name). Client-supplied properties from the request are preserved alongside.
    assertThat(resp.getMetadata().getProperties())
        .containsEntry(featureKey(DeltaTableFeatures.CATALOG_MANAGED), "supported")
        .containsEntry(featureKey(DeltaTableFeatures.DELETION_VECTORS), "supported")
        .containsEntry(featureKey(DeltaTableFeatures.IN_COMMIT_TIMESTAMP), "supported")
        .containsEntry(featureKey(DeltaTableFeatures.V2_CHECKPOINT), "supported")
        .containsEntry(featureKey(DeltaTableFeatures.VACUUM_PROTOCOL_CHECK), "supported")
        .containsEntry("delta.enableDeletionVectors", "true");

    // -------- EXTERNAL happy path at a fresh (unregistered) storage path --------
    String externalName = "tbl_external";
    String externalLocation = "s3://test-bucket0/external-path/tbl_external";
    LoadTableResponse extResp =
        deltaTablesApi.createTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            externalTableRequest(externalName, externalLocation));
    assertThat(extResp.getMetadata().getTableType()).isEqualTo(TableType.EXTERNAL);
    assertThat(extResp.getMetadata().getLocation()).isEqualTo(externalLocation);

    // -------- ICEBERG rejected --------
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createTable(
                TestUtils.CATALOG_NAME2,
                TestUtils.SCHEMA_NAME2,
                managedTableRequest("tbl_iceberg", "s3://test-bucket0/unused")
                    .dataSourceFormat(DataSourceFormat.ICEBERG)),
        ErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        "Unsupported data-source-format");

    // -------- name missing --------
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createTable(
                TestUtils.CATALOG_NAME2,
                TestUtils.SCHEMA_NAME2,
                managedTableRequest(null, "s3://test-bucket0/unused")),
        ErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        "Table name is required");

    // -------- protocol missing --------
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createTable(
                TestUtils.CATALOG_NAME2,
                TestUtils.SCHEMA_NAME2,
                managedTableRequest("tbl_no_protocol", "s3://test-bucket0/unused").protocol(null)),
        ErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        "protocol is required");

    // -------- MANAGED without catalogManaged writer feature rejected --------
    StagingTableResponse stagingNoCm =
        deltaTablesApi.createStagingTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            new CreateStagingTableRequest().name("tbl_no_cm"));
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createTable(
                TestUtils.CATALOG_NAME2,
                TestUtils.SCHEMA_NAME2,
                managedTableRequest("tbl_no_cm", stagingNoCm.getLocation())
                    .protocol(
                        new DeltaProtocol()
                            .minReaderVersion(3)
                            .minWriterVersion(7)
                            .readerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS))
                            // catalogManaged intentionally omitted.
                            .writerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS)))),
        ErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        DeltaTableFeatures.CATALOG_MANAGED);

    // -------- domain-metadata without matching feature rejected --------
    StagingTableResponse stagingDm =
        deltaTablesApi.createStagingTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            new CreateStagingTableRequest().name("tbl_bad_domain"));
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createTable(
                TestUtils.CATALOG_NAME2,
                TestUtils.SCHEMA_NAME2,
                managedTableRequest("tbl_bad_domain", stagingDm.getLocation())
                    .domainMetadata(
                        new DomainMetadataUpdates()
                            .deltaClustering(
                                new ClusteringDomainMetadata()
                                    .clusteringColumns(List.of(List.of("id")))))),
        ErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        "'clustering' writer feature");

    // -------- partition-columns referencing unknown column --------
    StagingTableResponse stagingForBadPart =
        deltaTablesApi.createStagingTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            new CreateStagingTableRequest().name("tbl_bad_part"));
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createTable(
                TestUtils.CATALOG_NAME2,
                TestUtils.SCHEMA_NAME2,
                managedTableRequest("tbl_bad_part", stagingForBadPart.getLocation())
                    .partitionColumns(List.of("nope"))),
        ErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        "partition-columns references unknown column: nope");

    // -------- partition-columns happy case --------
    StagingTableResponse stagingPart =
        deltaTablesApi.createStagingTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            new CreateStagingTableRequest().name("tbl_part"));
    LoadTableResponse partResp =
        deltaTablesApi.createTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            managedTableRequest("tbl_part", stagingPart.getLocation())
                .partitionColumns(List.of("id")));
    assertThat(partResp.getMetadata().getPartitionColumns()).containsExactly("id");
  }

  /** Creates a catalog + schema whose staging tables resolve under s3://test-bucket0/. */
  @SneakyThrows
  private void createS3Catalog() {
    catalogOperations.createCatalog(
        new CreateCatalog()
            .name(TestUtils.CATALOG_NAME2)
            .storageRoot("s3://test-bucket0/catalogs/drc"));
    schemaOperations.createSchema(
        new CreateSchema().name(TestUtils.SCHEMA_NAME2).catalogName(TestUtils.CATALOG_NAME2));
  }

  /** Canonical (id long, amount double) columns shared across requests. */
  private static StructType simpleSchema() {
    return new StructType()
        .type("struct")
        .fields(
            List.of(
                new StructField()
                    .name("id")
                    .type(new PrimitiveType().type("long"))
                    .nullable(false)
                    .metadata(Map.of()),
                new StructField()
                    .name("amount")
                    .type(new PrimitiveType().type("double"))
                    .nullable(true)
                    .metadata(Map.of())));
  }

  /** Minimal protocol meeting the UC catalog-managed MANAGED contract. */
  private static DeltaProtocol managedProtocol() {
    return new DeltaProtocol()
        .minReaderVersion(3)
        .minWriterVersion(7)
        .readerFeatures(
            List.of(
                DeltaTableFeatures.DELETION_VECTORS,
                DeltaTableFeatures.V2_CHECKPOINT,
                DeltaTableFeatures.VACUUM_PROTOCOL_CHECK))
        .writerFeatures(
            List.of(
                DeltaTableFeatures.CATALOG_MANAGED,
                DeltaTableFeatures.DELETION_VECTORS,
                DeltaTableFeatures.IN_COMMIT_TIMESTAMP,
                DeltaTableFeatures.V2_CHECKPOINT,
                DeltaTableFeatures.VACUUM_PROTOCOL_CHECK));
  }

  /** Build a canonical MANAGED Delta table request. */
  private static CreateTableRequest managedTableRequest(String name, String location) {
    return new CreateTableRequest()
        .name(name)
        .location(location)
        .tableType(TableType.MANAGED)
        .dataSourceFormat(DataSourceFormat.DELTA)
        .columns(simpleSchema())
        .protocol(managedProtocol())
        .properties(Map.of("delta.enableDeletionVectors", "true"));
  }

  /** Build an EXTERNAL Delta table request at an arbitrary storage path. */
  private static CreateTableRequest externalTableRequest(String name, String location) {
    return new CreateTableRequest()
        .name(name)
        .location(location)
        .tableType(TableType.EXTERNAL)
        .dataSourceFormat(DataSourceFormat.DELTA)
        .columns(simpleSchema())
        // EXTERNAL tables don't require catalogManaged; use a minimal modern Delta protocol.
        .protocol(
            new DeltaProtocol()
                .minReaderVersion(3)
                .minWriterVersion(7)
                .readerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS))
                .writerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS)))
        .properties(Map.of("delta.enableDeletionVectors", "true"));
  }

  /** {@code delta.feature.<name>} for the stored UC property assertions. */
  private static String featureKey(String feature) {
    return TableProperties.DELTA_FEATURE_PREFIX + feature;
  }
}
