package io.unitycatalog.server.sdk.access;

import static io.unitycatalog.server.utils.TestUtils.assertPermissionDenied;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.unitycatalog.client.api.ModelVersionsApi;
import io.unitycatalog.client.api.RegisteredModelsApi;
import io.unitycatalog.client.api.TemporaryCredentialsApi;
import io.unitycatalog.client.model.CreateModelVersion;
import io.unitycatalog.client.model.CreateRegisteredModel;
import io.unitycatalog.client.model.FinalizeModelVersion;
import io.unitycatalog.client.model.GenerateTemporaryModelVersionCredential;
import io.unitycatalog.client.model.ModelVersionInfo;
import io.unitycatalog.client.model.ModelVersionOperation;
import io.unitycatalog.client.model.RegisteredModelInfo;
import io.unitycatalog.client.model.SecurableType;
import io.unitycatalog.client.model.UpdateModelVersion;
import io.unitycatalog.client.model.UpdateRegisteredModel;
import io.unitycatalog.server.base.ServerConfig;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.service.credential.CloudCredentialVendor;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.TestUtils;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

/**
 * SDK-based access control tests for Registered Model and Model Version CRUD operations.
 *
 * <p>This test class verifies:
 *
 * <ul>
 *   <li>Model creation requires CREATE MODEL permission on schema
 *   <li>Model get requires ownership
 *   <li>Model list is filtered based on ownership
 *   <li>Model update requires ownership
 *   <li>Model version creation requires ownership of parent model
 *   <li>Model version get requires ownership
 *   <li>Model version list requires ownership
 *   <li>Model version update requires ownership
 *   <li>Model version delete requires ownership
 *   <li>Model delete requires ownership
 * </ul>
 */
public class SdkModelAccessControlCRUDTest extends SdkAccessControlBaseCRUDTest {

  private static final String MODEL_STORAGE_ROOT = "s3://model-bucket/models";

  @Override
  protected void setUpProperties() {
    super.setUpProperties();
    serverProperties.setProperty(
        ServerProperties.Property.MODEL_STORAGE_ROOT.getKey(), MODEL_STORAGE_ROOT);
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
  @SneakyThrows
  public void testModelAccess() {
    createCommonTestUsers();
    setupCommonCatalogAndSchema();

    // Create API clients for different users
    ServerConfig principal1Config = createTestUserServerConfig(PRINCIPAL_1);
    ServerConfig principal2Config = createTestUserServerConfig(PRINCIPAL_2);

    RegisteredModelsApi principal1ModelsApi =
        new RegisteredModelsApi(TestUtils.createApiClient(principal1Config));
    RegisteredModelsApi principal2ModelsApi =
        new RegisteredModelsApi(TestUtils.createApiClient(principal2Config));
    ModelVersionsApi principal1VersionsApi =
        new ModelVersionsApi(TestUtils.createApiClient(principal1Config));
    ModelVersionsApi principal2VersionsApi =
        new ModelVersionsApi(TestUtils.createApiClient(principal2Config));

    // Grant USE SCHEMA and CREATE MODEL to principal-1
    grantPermissions(PRINCIPAL_1, SecurableType.SCHEMA, "cat_pr1.sch_pr1", Privileges.USE_SCHEMA);
    grantPermissions(PRINCIPAL_1, SecurableType.SCHEMA, "cat_pr1.sch_pr1", Privileges.CREATE_MODEL);

    // Grant USE SCHEMA to principal-2
    grantPermissions(PRINCIPAL_2, SecurableType.SCHEMA, "cat_pr1.sch_pr1", Privileges.USE_SCHEMA);
    grantPermissions(PRINCIPAL_2, SecurableType.CATALOG, "cat_pr1", Privileges.USE_CATALOG);
    grantPermissions(
        PRINCIPAL_2, SecurableType.SCHEMA, "cat_pr1.sch_pr1", Privileges.CREATE_FUNCTION);
    assertPermissionDenied(
        () ->
            principal2ModelsApi.createRegisteredModel(
                new CreateRegisteredModel()
                    .name("function_privilege_is_not_model_privilege")
                    .catalogName("cat_pr1")
                    .schemaName("sch_pr1")));

    // TEST: Create registered model as principal-1 - should succeed
    CreateRegisteredModel createModel =
        new CreateRegisteredModel().name("mod_pr1").catalogName("cat_pr1").schemaName("sch_pr1");
    RegisteredModelInfo modelInfo = principal1ModelsApi.createRegisteredModel(createModel);
    assertThat(modelInfo).isNotNull();
    assertThat(modelInfo.getName()).isEqualTo("mod_pr1");

    // TEST: Get registered model as principal-1 (owner) - should succeed
    RegisteredModelInfo getModelInfo =
        principal1ModelsApi.getRegisteredModel("cat_pr1.sch_pr1.mod_pr1", null);
    assertThat(getModelInfo).isNotNull();

    // TEST: Get registered model as principal-2 (not owner) - should fail
    assertPermissionDenied(
        () -> principal2ModelsApi.getRegisteredModel("cat_pr1.sch_pr1.mod_pr1", null));

    // TEST: List registered models as principal-1 - should see owned model
    List<RegisteredModelInfo> principal1Models =
        listAllRegisteredModels(principal1ModelsApi, "cat_pr1", "sch_pr1");
    assertThat(principal1Models).hasSize(1);

    // TEST: List registered models as principal-2 - should see empty list
    List<RegisteredModelInfo> principal2Models =
        listAllRegisteredModels(principal2ModelsApi, "cat_pr1", "sch_pr1");
    assertThat(principal2Models).isEmpty();

    // TEST: Update registered model as principal-1 (owner) - should succeed
    UpdateRegisteredModel updateModel = new UpdateRegisteredModel().comment("hello");
    RegisteredModelInfo updatedModel =
        principal1ModelsApi.updateRegisteredModel("cat_pr1.sch_pr1.mod_pr1", updateModel);
    assertThat(updatedModel.getComment()).isEqualTo("hello");

    // TEST: Update registered model as principal-2 (not owner) - should fail
    UpdateRegisteredModel updateModel2 = new UpdateRegisteredModel().comment("hello2");
    assertPermissionDenied(
        () -> principal2ModelsApi.updateRegisteredModel("cat_pr1.sch_pr1.mod_pr1", updateModel2));

    // TEST: Create model version as principal-1 (owner) - should succeed
    CreateModelVersion createVersion =
        new CreateModelVersion()
            .catalogName("cat_pr1")
            .schemaName("sch_pr1")
            .modelName("mod_pr1")
            .source("model_source");
    ModelVersionInfo versionInfo = principal1VersionsApi.createModelVersion(createVersion);
    assertThat(versionInfo).isNotNull();
    assertThat(versionInfo.getVersion()).isEqualTo(1L);

    // TEST: Create model version as principal-2 (not owner) - should fail
    CreateModelVersion createVersion2 =
        new CreateModelVersion()
            .catalogName("cat_pr1")
            .schemaName("sch_pr1")
            .modelName("mod_pr1")
            .source("model_source");
    assertPermissionDenied(() -> principal2VersionsApi.createModelVersion(createVersion2));

    // TEST: Get model version as principal-1 (owner) - should succeed
    ModelVersionInfo getVersionInfo =
        principal1VersionsApi.getModelVersion("cat_pr1.sch_pr1.mod_pr1", 1L, null);
    assertThat(getVersionInfo).isNotNull();

    // TEST: Get model version as principal-2 (not owner) - should fail
    assertPermissionDenied(
        () -> principal2VersionsApi.getModelVersion("cat_pr1.sch_pr1.mod_pr1", 1L, null));

    // TEST: List model versions as principal-1 - should see all versions
    List<ModelVersionInfo> principal1Versions =
        listAllModelVersions(principal1VersionsApi, "cat_pr1.sch_pr1.mod_pr1");
    assertThat(principal1Versions).hasSize(1);

    // TEST: List model versions as principal-2 - should fail
    assertPermissionDenied(
        () -> listAllModelVersions(principal2VersionsApi, "cat_pr1.sch_pr1.mod_pr1"));

    // TEST: Update model version as principal-1 (owner) - should succeed
    UpdateModelVersion updateVersion = new UpdateModelVersion().comment("hello");
    ModelVersionInfo updatedVersion =
        principal1VersionsApi.updateModelVersion("cat_pr1.sch_pr1.mod_pr1", 1L, updateVersion);
    assertThat(updatedVersion.getComment()).isEqualTo("hello");

    // TEST: Update model version as principal-2 (not owner) - should fail
    UpdateModelVersion updateVersion2 = new UpdateModelVersion().comment("hello2");
    assertPermissionDenied(
        () ->
            principal2VersionsApi.updateModelVersion(
                "cat_pr1.sch_pr1.mod_pr1", 1L, updateVersion2));

    // TEST: Delete model version as principal-2 (not owner) - should fail
    assertPermissionDenied(
        () -> principal2VersionsApi.deleteModelVersion("cat_pr1.sch_pr1.mod_pr1", 1L));

    // TEST: Delete model version as principal-1 (owner) - should succeed
    principal1VersionsApi.deleteModelVersion("cat_pr1.sch_pr1.mod_pr1", 1L);

    // Verify deletion
    List<ModelVersionInfo> versionsAfterDelete =
        listAllModelVersions(principal1VersionsApi, "cat_pr1.sch_pr1.mod_pr1");
    assertThat(versionsAfterDelete).isEmpty();

    // TEST: Delete registered model as principal-2 (not owner) - should fail
    assertPermissionDenied(
        () -> principal2ModelsApi.deleteRegisteredModel("cat_pr1.sch_pr1.mod_pr1", false));

    // TEST: Delete registered model as principal-1 (owner) - should succeed
    principal1ModelsApi.deleteRegisteredModel("cat_pr1.sch_pr1.mod_pr1", false);

    // Verify deletion
    List<RegisteredModelInfo> modelsAfterDelete =
        listAllRegisteredModels(principal1ModelsApi, "cat_pr1", "sch_pr1");
    assertThat(modelsAfterDelete).isEmpty();
  }

  @Test
  @SneakyThrows
  public void createModelVersionAcceptsDirectAndInheritedPrivilege() {
    createCommonTestUsers();
    String modelName = "version_privilege_model";
    String fullName = TestUtils.SCHEMA_FULL_NAME + "." + modelName;

    RegisteredModelsApi adminModelsApi = new RegisteredModelsApi(adminApiClient);
    adminModelsApi.createRegisteredModel(
        new CreateRegisteredModel()
            .name(modelName)
            .catalogName(TestUtils.CATALOG_NAME)
            .schemaName(TestUtils.SCHEMA_NAME));
    String bodyModelName = "body_target_model";
    String bodyFullName = TestUtils.SCHEMA_FULL_NAME + "." + bodyModelName;
    adminModelsApi.createRegisteredModel(
        new CreateRegisteredModel()
            .name(bodyModelName)
            .catalogName(TestUtils.CATALOG_NAME)
            .schemaName(TestUtils.SCHEMA_NAME));
    ModelVersionsApi adminVersionsApi = new ModelVersionsApi(adminApiClient);
    adminVersionsApi.createModelVersion(createModelVersion(bodyModelName, "body-source-1"));
    ModelVersionInfo bodyTarget =
        adminVersionsApi.createModelVersion(createModelVersion(bodyModelName, "body-source-2"));

    grantPermissions(
        REGULAR_1, SecurableType.CATALOG, TestUtils.CATALOG_NAME, Privileges.USE_CATALOG);
    grantPermissions(
        REGULAR_1, SecurableType.SCHEMA, TestUtils.SCHEMA_FULL_NAME, Privileges.USE_SCHEMA);
    grantPermissions(
        REGULAR_1, SecurableType.REGISTERED_MODEL, fullName, Privileges.CREATE_MODEL_VERSION);

    grantPermissions(
        REGULAR_2, SecurableType.CATALOG, TestUtils.CATALOG_NAME, Privileges.USE_CATALOG);
    grantPermissions(
        REGULAR_2,
        SecurableType.SCHEMA,
        TestUtils.SCHEMA_FULL_NAME,
        Privileges.USE_SCHEMA,
        Privileges.CREATE_MODEL_VERSION);

    grantPermissions(
        PRINCIPAL_2, SecurableType.CATALOG, TestUtils.CATALOG_NAME, Privileges.USE_CATALOG);
    grantPermissions(
        PRINCIPAL_2, SecurableType.SCHEMA, TestUtils.SCHEMA_FULL_NAME, Privileges.USE_SCHEMA);
    grantPermissions(PRINCIPAL_2, SecurableType.REGISTERED_MODEL, fullName, Privileges.EXECUTE);

    grantPermissions(
        PRINCIPAL_1, SecurableType.CATALOG, TestUtils.CATALOG_NAME, Privileges.USE_CATALOG);
    grantPermissions(
        PRINCIPAL_1, SecurableType.SCHEMA, TestUtils.SCHEMA_FULL_NAME, Privileges.USE_SCHEMA);
    grantPermissions(PRINCIPAL_1, SecurableType.REGISTERED_MODEL, fullName, Privileges.MANAGE);

    ModelVersionsApi directApi =
        new ModelVersionsApi(TestUtils.createApiClient(createTestUserServerConfig(REGULAR_1)));
    ModelVersionsApi inheritedApi =
        new ModelVersionsApi(TestUtils.createApiClient(createTestUserServerConfig(REGULAR_2)));
    ModelVersionsApi executeOnlyApi =
        new ModelVersionsApi(TestUtils.createApiClient(createTestUserServerConfig(PRINCIPAL_2)));
    ModelVersionsApi managerApi =
        new ModelVersionsApi(TestUtils.createApiClient(createTestUserServerConfig(PRINCIPAL_1)));
    TemporaryCredentialsApi directCredentialsApi =
        new TemporaryCredentialsApi(
            TestUtils.createApiClient(createTestUserServerConfig(REGULAR_1)));
    TemporaryCredentialsApi inheritedCredentialsApi =
        new TemporaryCredentialsApi(
            TestUtils.createApiClient(createTestUserServerConfig(REGULAR_2)));
    TemporaryCredentialsApi executeOnlyCredentialsApi =
        new TemporaryCredentialsApi(
            TestUtils.createApiClient(createTestUserServerConfig(PRINCIPAL_2)));

    ModelVersionInfo direct =
        directApi.createModelVersion(createModelVersion(modelName, "direct-source"));
    ModelVersionInfo inherited =
        inheritedApi.createModelVersion(createModelVersion(modelName, "inherited-source"));
    assertThat(direct.getVersion()).isEqualTo(1L);
    assertThat(inherited.getVersion()).isEqualTo(2L);
    assertThat(
            directCredentialsApi.generateTemporaryModelVersionCredentials(
                writeCredentialRequest(modelName, direct.getVersion())))
        .isNotNull();
    assertThat(
            inheritedCredentialsApi.generateTemporaryModelVersionCredentials(
                writeCredentialRequest(modelName, inherited.getVersion())))
        .isNotNull();
    assertPermissionDenied(
        () ->
            executeOnlyCredentialsApi.generateTemporaryModelVersionCredentials(
                writeCredentialRequest(modelName, direct.getVersion())));
    // The authorized URL target is authoritative even when the body names another model/version.
    ModelVersionInfo finalizedDirect =
        directApi.finalizeModelVersion(
            fullName,
            direct.getVersion(),
            new FinalizeModelVersion().fullName(bodyFullName).version(bodyTarget.getVersion()));
    assertThat(finalizedDirect.getModelName()).isEqualTo(modelName);
    assertThat(finalizedDirect.getVersion()).isEqualTo(direct.getVersion());
    assertThat(
            inheritedApi.finalizeModelVersion(
                fullName,
                inherited.getVersion(),
                new FinalizeModelVersion().fullName(fullName).version(inherited.getVersion())))
        .isNotNull();
    assertPermissionDenied(
        () ->
            executeOnlyApi.createModelVersion(
                createModelVersion(modelName, "execute-only-source")));

    ModelVersionInfo managerCannotFinalize =
        new ModelVersionsApi(adminApiClient)
            .createModelVersion(createModelVersion(modelName, "manage-source"));
    assertPermissionDenied(
        () ->
            managerApi.finalizeModelVersion(
                fullName,
                managerCannotFinalize.getVersion(),
                new FinalizeModelVersion()
                    .fullName(fullName)
                    .version(managerCannotFinalize.getVersion())));
  }

  @Test
  @SneakyThrows
  public void browseOptInIncludesRegisteredModelAndVersions() {
    String browser = "model-browser@example.com";
    String modelName = "browsable_model";
    String fullName = TestUtils.SCHEMA_FULL_NAME + "." + modelName;
    createTestUser(browser);

    RegisteredModelsApi adminModelsApi = new RegisteredModelsApi(adminApiClient);
    ModelVersionsApi adminVersionsApi = new ModelVersionsApi(adminApiClient);
    adminModelsApi.createRegisteredModel(
        new CreateRegisteredModel()
            .name(modelName)
            .catalogName(TestUtils.CATALOG_NAME)
            .schemaName(TestUtils.SCHEMA_NAME));
    adminVersionsApi.createModelVersion(createModelVersion(modelName, "browse-source"));

    grantPermissions(browser, SecurableType.CATALOG, TestUtils.CATALOG_NAME, Privileges.BROWSE);
    RegisteredModelsApi browserModelsApi =
        new RegisteredModelsApi(TestUtils.createApiClient(createTestUserServerConfig(browser)));
    ModelVersionsApi browserVersionsApi =
        new ModelVersionsApi(TestUtils.createApiClient(createTestUserServerConfig(browser)));

    assertPermissionDenied(() -> browserModelsApi.getRegisteredModel(fullName, false));
    assertThat(browserModelsApi.getRegisteredModel(fullName, true).getBrowseOnly()).isTrue();
    assertPermissionDenied(() -> browserVersionsApi.getModelVersion(fullName, 1L, false));
    assertThat(browserVersionsApi.getModelVersion(fullName, 1L, true)).isNotNull();
    assertPermissionDenied(() -> browserVersionsApi.listModelVersions(fullName, 100, null, false));
    assertThat(browserVersionsApi.listModelVersions(fullName, 100, null, true).getModelVersions())
        .hasSize(1);
  }

  private static CreateModelVersion createModelVersion(String modelName, String source) {
    return new CreateModelVersion()
        .catalogName(TestUtils.CATALOG_NAME)
        .schemaName(TestUtils.SCHEMA_NAME)
        .modelName(modelName)
        .source(source);
  }

  private static GenerateTemporaryModelVersionCredential writeCredentialRequest(
      String modelName, long version) {
    return new GenerateTemporaryModelVersionCredential()
        .catalogName(TestUtils.CATALOG_NAME)
        .schemaName(TestUtils.SCHEMA_NAME)
        .modelName(modelName)
        .version(version)
        .operation(ModelVersionOperation.READ_WRITE_MODEL_VERSION);
  }
}
