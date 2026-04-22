package io.unitycatalog.server.sdk.delta;

import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.client.ApiException;
import io.unitycatalog.client.delta.api.TablesApi;
import io.unitycatalog.client.delta.model.CreateStagingTableRequest;
import io.unitycatalog.client.delta.model.CredentialOperation;
import io.unitycatalog.client.delta.model.ErrorType;
import io.unitycatalog.client.delta.model.StagingTableResponse;
import io.unitycatalog.client.delta.model.StorageCredential;
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
import io.unitycatalog.server.utils.TestUtils;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Delta REST Catalog {@code POST /v1/.../staging-tables} endpoint.
 * Consolidated into one test with sections so the server start + mock-cloud setup runs once. Each
 * section uses a distinct table name so they don't collide.
 */
public class SdkCreateStagingTableTest extends BaseCRUDTestWithMockCredentials {

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
  public void testCreateStagingTableEndpoint() throws ApiException {
    // -------- happy path: S3-rooted catalog --------
    StagingTableResponse resp =
        deltaTablesApi.createStagingTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            new CreateStagingTableRequest().name("tbl_s3_happy"));

    assertThat(resp.getTableId()).isNotNull();
    assertThat(resp.getTableType()).isEqualTo(TableType.MANAGED);
    assertThat(resp.getLocation()).startsWith("s3://test-bucket0/");
    // The staging path is a UUID under the catalog root; the table name does NOT appear in the
    // path (the client hasn't committed it yet).
    assertThat(resp.getLocation()).contains(resp.getTableId().toString());

    assertThat(resp.getStorageCredentials()).hasSize(1);
    StorageCredential sc = resp.getStorageCredentials().get(0);
    assertThat(sc.getOperation()).isEqualTo(CredentialOperation.READ_WRITE);
    assertThat(sc.getPrefix()).isEqualTo(resp.getLocation());
    assertThat(sc.getConfig().getS3AccessKeyId()).isEqualTo("accessKey0");
    assertThat(sc.getConfig().getS3SecretAccessKey()).isNotBlank();

    // Required / suggested protocol + properties reflect UC's catalog-managed Delta contract.
    assertThat(resp.getRequiredProtocol().getMinReaderVersion()).isEqualTo(3);
    assertThat(resp.getRequiredProtocol().getMinWriterVersion()).isEqualTo(7);
    assertThat(resp.getRequiredProtocol().getReaderFeatures())
        .containsExactlyInAnyOrder(
            DeltaTableFeatures.CATALOG_MANAGED,
            DeltaTableFeatures.DELETION_VECTORS,
            DeltaTableFeatures.V2_CHECKPOINT,
            DeltaTableFeatures.VACUUM_PROTOCOL_CHECK);
    assertThat(resp.getRequiredProtocol().getWriterFeatures())
        .containsExactlyInAnyOrder(
            DeltaTableFeatures.CATALOG_MANAGED,
            DeltaTableFeatures.DELETION_VECTORS,
            DeltaTableFeatures.IN_COMMIT_TIMESTAMP,
            DeltaTableFeatures.V2_CHECKPOINT,
            DeltaTableFeatures.VACUUM_PROTOCOL_CHECK);
    assertThat(resp.getSuggestedProtocol().getReaderFeatures())
        .containsExactlyInAnyOrder(DeltaTableFeatures.COLUMN_MAPPING);
    assertThat(resp.getSuggestedProtocol().getWriterFeatures())
        .containsExactlyInAnyOrder(
            DeltaTableFeatures.COLUMN_MAPPING,
            DeltaTableFeatures.DOMAIN_METADATA,
            DeltaTableFeatures.ROW_TRACKING);

    assertThat(resp.getRequiredProperties())
        .containsEntry("delta.checkpointPolicy", "v2")
        .containsEntry("delta.checkpoint.writeStatsAsJson", "false")
        .containsEntry("delta.checkpoint.writeStatsAsStruct", "true")
        .containsEntry("delta.enableDeletionVectors", "true")
        .containsEntry("delta.enableInCommitTimestamps", "true")
        // The rule-based property binds the Delta table to the UC-allocated tableId.
        .containsEntry("io.unitycatalog.tableId", resp.getTableId().toString())
        // Engine-managed (tied to inCommitTimestamp); null value = engine computes at commit time.
        .containsEntry("delta.inCommitTimestampEnablementVersion", null)
        .containsEntry("delta.inCommitTimestampEnablementTimestamp", null);
    assertThat(resp.getSuggestedProperties())
        .containsEntry("delta.enableRowTracking", "true")
        // Null value = client generates a UUID-suffixed column name when enabling row tracking.
        .containsEntry("delta.rowTracking.materializedRowIdColumnName", null)
        .containsEntry("delta.rowTracking.materializedRowCommitVersionColumnName", null);

    // -------- name missing --------
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createStagingTable(
                TestUtils.CATALOG_NAME2, TestUtils.SCHEMA_NAME2, new CreateStagingTableRequest()),
        ErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
        "Staging table name is required");

    // -------- name blank (empty + whitespace) --------
    // The empty case pins the getName().isBlank() branch; the whitespace case is the reason we
    // use isBlank() over isEmpty() -- whitespace-only shouldn't become a valid staging table name.
    for (String blank : List.of("", "   ")) {
      TestUtils.assertDeltaApiException(
          () ->
              deltaTablesApi.createStagingTable(
                  TestUtils.CATALOG_NAME2,
                  TestUtils.SCHEMA_NAME2,
                  new CreateStagingTableRequest().name(blank)),
          ErrorType.INVALID_PARAMETER_VALUE_EXCEPTION,
          "Staging table name is required");
    }

    // -------- catalog not found --------
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createStagingTable(
                "no_such_catalog",
                TestUtils.SCHEMA_NAME2,
                new CreateStagingTableRequest().name("x")),
        ErrorType.NO_SUCH_CATALOG_EXCEPTION,
        "not found");

    // -------- schema not found --------
    TestUtils.assertDeltaApiException(
        () ->
            deltaTablesApi.createStagingTable(
                TestUtils.CATALOG_NAME2,
                "no_such_schema",
                new CreateStagingTableRequest().name("x")),
        ErrorType.NO_SUCH_SCHEMA_EXCEPTION,
        "not found");

    // -------- duplicate name: distinct UUIDs, distinct locations --------
    StagingTableResponse dup =
        deltaTablesApi.createStagingTable(
            TestUtils.CATALOG_NAME2,
            TestUtils.SCHEMA_NAME2,
            new CreateStagingTableRequest().name("tbl_s3_happy"));
    assertThat(dup.getTableId()).isNotEqualTo(resp.getTableId());
    assertThat(dup.getLocation()).isNotEqualTo(resp.getLocation());
  }

  /** Creates a catalog + schema whose staging tables resolve under s3://test-bucket0/. */
  @SneakyThrows
  private void createS3Catalog() {
    // We create a second catalog (TestUtils.CATALOG_NAME2) with an S3 managed root so staging
    // locations resolve to s3://test-bucket0/... and cloud credentials come back populated. The
    // default catalog in the base class has no managed location; it falls back to
    // TABLE_STORAGE_ROOT (file://) for which credentials are empty.
    catalogOperations.createCatalog(
        new CreateCatalog()
            .name(TestUtils.CATALOG_NAME2)
            .storageRoot("s3://test-bucket0/catalogs/drc"));
    schemaOperations.createSchema(
        new CreateSchema().name(TestUtils.SCHEMA_NAME2).catalogName(TestUtils.CATALOG_NAME2));
  }
}
