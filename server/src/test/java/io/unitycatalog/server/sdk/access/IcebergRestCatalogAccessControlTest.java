package io.unitycatalog.server.sdk.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.auth.AuthToken;
import io.unitycatalog.client.api.TablesApi;
import io.unitycatalog.client.model.CreateTable;
import io.unitycatalog.client.model.DataSourceFormat;
import io.unitycatalog.client.model.SecurableType;
import io.unitycatalog.client.model.TableInfo;
import io.unitycatalog.client.model.TableType;
import io.unitycatalog.server.base.ServerConfig;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.service.credential.CloudCredentialVendor;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.service.iceberg.IcebergObjectMapper;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.TestUtils;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.apache.iceberg.aws.AwsClientProperties;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

/** Access-control coverage for the Iceberg REST catalog. */
public class IcebergRestCatalogAccessControlTest extends SdkAccessControlBaseCRUDTest {

  private static final String READER = "iceberg-reader@localhost";
  private static final String TABLE_NAME = "iceberg_auth_table";
  private static final String TABLE_LOCATION = "s3://test-bucket0/iceberg/auth-table";

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty("s3.bucketPath.0", "s3://test-bucket0");
    serverProperties.setProperty("s3.region.0", "us-west-2");
    serverProperties.setProperty("s3.awsRoleArn.0", "arn:aws:iam::123456789012:role/test-role");
  }

  @Override
  protected void setUpCredentialOperations(ServerProperties serverProperties) {
    cloudCredentialVendor = mock(CloudCredentialVendor.class);
    when(cloudCredentialVendor.vendCredential(any(CredentialContext.class)))
        .thenReturn(
            new io.unitycatalog.server.model.TemporaryCredentials()
                .awsTempCredentials(
                    new io.unitycatalog.server.model.AwsCredentials()
                        .accessKeyId("test-access-key")
                        .secretAccessKey("test-secret-key")
                        .sessionToken("test-session-token")));
  }

  @Test
  public void loadTableSeparatesMetadataAccessFromCredentialVending() throws Exception {
    TablesApi adminTablesApi = new TablesApi(adminApiClient);
    TableInfo tableInfo =
        adminTablesApi.createTable(
            new CreateTable()
                .name(TABLE_NAME)
                .catalogName(TestUtils.CATALOG_NAME)
                .schemaName(TestUtils.SCHEMA_NAME)
                .columns(TEST_COLUMNS)
                .storageLocation(TABLE_LOCATION)
                .tableType(TableType.EXTERNAL)
                .dataSourceFormat(DataSourceFormat.DELTA));
    setUniformMetadataLocation(tableInfo, writeIcebergMetadata());

    createTestUser(READER);
    grantPermissions(READER, SecurableType.CATALOG, TestUtils.CATALOG_NAME, Privileges.USE_CATALOG);
    grantPermissions(
        READER, SecurableType.SCHEMA, TestUtils.SCHEMA_FULL_NAME, Privileges.USE_SCHEMA);
    grantPermissions(
        READER,
        SecurableType.TABLE,
        TestUtils.SCHEMA_FULL_NAME + "." + TABLE_NAME,
        Privileges.READ_METADATA);

    WebClient readerClient = icebergClient(createTestUserServerConfig(READER));
    LoadTableResponse withoutExternalUse = loadTable(readerClient);
    assertThat(withoutExternalUse.tableMetadata()).isNotNull();
    assertThat(withoutExternalUse.config()).isEmpty();

    grantPermissions(
        READER, SecurableType.SCHEMA, TestUtils.SCHEMA_FULL_NAME, Privileges.EXTERNAL_USE_SCHEMA);
    LoadTableResponse withoutSelect = loadTable(readerClient);
    assertThat(withoutSelect.tableMetadata()).isNotNull();
    assertThat(withoutSelect.config()).isEmpty();

    grantPermissions(
        READER,
        SecurableType.TABLE,
        TestUtils.SCHEMA_FULL_NAME + "." + TABLE_NAME,
        Privileges.SELECT);
    assertCredentialConfig(loadTable(readerClient));

    revokePermissions(
        READER, SecurableType.SCHEMA, TestUtils.SCHEMA_FULL_NAME, Privileges.EXTERNAL_USE_SCHEMA);
    assertThat(loadTable(readerClient).config()).isEmpty();

    // Metastore owners can vend the config without an explicit schema external-use grant.
    assertCredentialConfig(loadTable(icebergClient(adminConfig)));
  }

  private WebClient icebergClient(ServerConfig config) {
    return WebClient.builder(config.getServerUrl() + "/api/2.1/unity-catalog/iceberg")
        .auth(AuthToken.ofOAuth2(config.getAuthToken()))
        .build();
  }

  private LoadTableResponse loadTable(WebClient client) throws IOException {
    AggregatedHttpResponse response =
        client
            .get(
                "/v1/catalogs/"
                    + TestUtils.CATALOG_NAME
                    + "/namespaces/"
                    + TestUtils.SCHEMA_NAME
                    + "/tables/"
                    + TABLE_NAME)
            .aggregate()
            .join();
    assertThat(response.status().code()).isEqualTo(200);
    return IcebergObjectMapper.mapper().readValue(response.contentUtf8(), LoadTableResponse.class);
  }

  private void assertCredentialConfig(LoadTableResponse response) {
    assertThat(response.config())
        .containsEntry(S3FileIOProperties.ACCESS_KEY_ID, "test-access-key")
        .containsEntry(S3FileIOProperties.SECRET_ACCESS_KEY, "test-secret-key")
        .containsEntry(S3FileIOProperties.SESSION_TOKEN, "test-session-token")
        .containsEntry(AwsClientProperties.CLIENT_REGION, "us-west-2");
  }

  private Path writeIcebergMetadata() throws IOException, URISyntaxException {
    Path source =
        Path.of(Objects.requireNonNull(getClass().getResource("/iceberg.metadata.json")).toURI());
    Path metadataFile = testDirectoryRoot.resolve("iceberg-auth.metadata.json");
    String metadata =
        Files.readString(source).replace("file:/tmp/uniform_iceberg_table", TABLE_LOCATION);
    return Files.writeString(metadataFile, metadata);
  }

  private void setUniformMetadataLocation(TableInfo tableInfo, Path metadataFile) {
    try (Session session = hibernateConfigurator.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      TableInfoDAO tableInfoDAO =
          session.get(TableInfoDAO.class, UUID.fromString(tableInfo.getTableId()));
      assertThat(tableInfoDAO).isNotNull();
      tableInfoDAO.setUniformIcebergMetadataLocation(metadataFile.toUri().toString());
      transaction.commit();
    }
  }
}
