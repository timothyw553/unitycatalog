package io.unitycatalog.server.service;

import static io.unitycatalog.server.utils.TestUtils.CATALOG_NAME;
import static io.unitycatalog.server.utils.TestUtils.SCHEMA_FULL_NAME;
import static io.unitycatalog.server.utils.TestUtils.SCHEMA_NAME;
import static io.unitycatalog.server.utils.TestUtils.TABLE_FULL_NAME;
import static io.unitycatalog.server.utils.TestUtils.TABLE_NAME;
import static io.unitycatalog.server.utils.TestUtils.assertApiException;
import static io.unitycatalog.server.utils.TestUtils.assertPermissionDenied;
import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.client.api.CatalogsApi;
import io.unitycatalog.client.api.GrantsApi;
import io.unitycatalog.client.api.TablesApi;
import io.unitycatalog.client.model.PermissionsChange;
import io.unitycatalog.client.model.PermissionsList;
import io.unitycatalog.client.model.Privilege;
import io.unitycatalog.client.model.PrivilegeAssignment;
import io.unitycatalog.client.model.SecurableType;
import io.unitycatalog.client.model.UpdateCatalog;
import io.unitycatalog.client.model.UpdatePermissions;
import io.unitycatalog.server.base.ServerConfig;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.sdk.access.SdkAccessControlBaseCRUDTest;
import io.unitycatalog.server.utils.TestUtils;
import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link PermissionService} with authorization enabled.
 *
 * <p>A real Unity Catalog server is booted (via {@link SdkAccessControlBaseCRUDTest}) with {@code
 * server.authorization=enable} and the permission endpoints are exercised end-to-end through the
 * generated {@link GrantsApi} SDK client (built with {@link TestUtils#createApiClient}). Driving
 * the SDK rather than a raw HTTP client also exercises the Armeria authentication ({@code
 * AuthDecorator}) and authorization ({@code UnityAccessDecorator}) decorators wrapping the service.
 */
public class PermissionServiceTest extends SdkAccessControlBaseCRUDTest {

  @BeforeEach
  @SneakyThrows
  public void setUp() {
    super.setUp();
    createTestUser(REGULAR_1);
    createTestUser(REGULAR_2);
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  @SneakyThrows
  public void testPermissionsServiceUseCases() {
    // Grants are scoped to a single securable, so we exercise the catalog, schema and table levels
    // independently and then read each one back. The base test only creates the catalog and schema,
    // so the table is created here before privileges can be granted on it.
    createExternalTable(
        new TablesApi(adminApiClient),
        CATALOG_NAME,
        SCHEMA_NAME,
        TABLE_NAME,
        testDirectoryRoot.resolve(TABLE_NAME).toUri().toString());

    // Admin (metastore owner) grants catalog-level privileges.
    grantsApi.update(
        SecurableType.CATALOG,
        CATALOG_NAME,
        addPrivileges(REGULAR_1, Privilege.USE_CATALOG, Privilege.CREATE_SCHEMA));
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_2, Privilege.USE_CATALOG));

    // Schema-level privileges for regular-1.
    grantsApi.update(
        SecurableType.SCHEMA,
        SCHEMA_FULL_NAME,
        addPrivileges(REGULAR_1, Privilege.USE_SCHEMA, Privilege.CREATE_TABLE));

    // Table-level privileges for regular-1.
    grantsApi.update(
        SecurableType.TABLE,
        TABLE_FULL_NAME,
        addPrivileges(REGULAR_1, Privilege.SELECT, Privilege.MODIFY));

    // Reading back as the owner exposes the full ACL. Each securable is queried separately because
    // a GET only returns the privileges granted directly on that securable.
    PermissionsList catalogPermissions = grantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(catalogPermissions, REGULAR_1))
        .containsExactlyInAnyOrder(Privilege.USE_CATALOG, Privilege.CREATE_SCHEMA);
    assertThat(privilegesFor(catalogPermissions, REGULAR_2)).containsExactly(Privilege.USE_CATALOG);

    PermissionsList schemaPermissions = grantsApi.get(SecurableType.SCHEMA, SCHEMA_FULL_NAME, null);
    assertThat(privilegesFor(schemaPermissions, REGULAR_1))
        .containsExactlyInAnyOrder(Privilege.USE_SCHEMA, Privilege.CREATE_TABLE);
    assertThat(privilegesFor(schemaPermissions, REGULAR_2)).isEmpty();

    PermissionsList tablePermissions = grantsApi.get(SecurableType.TABLE, TABLE_FULL_NAME, null);
    assertThat(privilegesFor(tablePermissions, REGULAR_1))
        .containsExactlyInAnyOrder(Privilege.SELECT, Privilege.MODIFY);
    assertThat(privilegesFor(tablePermissions, REGULAR_2)).isEmpty();
  }

  @Test
  @SneakyThrows
  public void nonOwnerCannotGrantPermissionsToOthers() {
    // regular-1 cannot grant permissions to regular-2
    assertPermissionDenied(
        () ->
            grantsApiFor(REGULAR_1)
                .update(
                    SecurableType.CATALOG,
                    CATALOG_NAME,
                    addPrivileges(REGULAR_2, Privilege.USE_CATALOG)));
  }

  @Test
  @SneakyThrows
  public void nonOwnerCanReadCatalogPermissions() {
    // GET is gated only on authentication, so a non-owner gets their own (empty) view.
    PermissionsList response =
        grantsApiFor(REGULAR_1).get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1)).isEmpty();
  }

  @Test
  @SneakyThrows
  public void manageCanReadAndUpdatePermissions() {
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_1, Privilege.MANAGE));

    grantsApiFor(REGULAR_1)
        .update(
            SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_2, Privilege.USE_CATALOG));

    CatalogsApi catalogsApi =
        new CatalogsApi(TestUtils.createApiClient(createTestUserServerConfig(REGULAR_1)));
    assertThat(
            catalogsApi
                .updateCatalog(CATALOG_NAME, new UpdateCatalog().comment("managed update"))
                .getComment())
        .isEqualTo("managed update");

    PermissionsList response =
        grantsApiFor(REGULAR_1).get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1)).containsExactly(Privilege.MANAGE);
    assertThat(privilegesFor(response, REGULAR_2)).containsExactly(Privilege.USE_CATALOG);
  }

  @Test
  @SneakyThrows
  public void readMetadataCanReadAllPermissionsButCannotUpdateThem() {
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_1, Privilege.READ_METADATA));
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_2, Privilege.USE_CATALOG));

    PermissionsList response =
        grantsApiFor(REGULAR_1).get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1)).containsExactly(Privilege.READ_METADATA);
    assertThat(privilegesFor(response, REGULAR_2)).containsExactly(Privilege.USE_CATALOG);

    assertPermissionDenied(
        () ->
            grantsApiFor(REGULAR_1)
                .update(
                    SecurableType.CATALOG,
                    CATALOG_NAME,
                    addPrivileges(REGULAR_2, Privilege.CREATE_SCHEMA)));

    CatalogsApi catalogsApi =
        new CatalogsApi(TestUtils.createApiClient(createTestUserServerConfig(REGULAR_1)));
    assertPermissionDenied(
        () -> catalogsApi.updateCatalog(CATALOG_NAME, new UpdateCatalog().comment("not allowed")));
  }

  @Test
  @SneakyThrows
  public void inheritedReadMetadataRequiresAccessToItsParents() {
    String schemaReader = "schema-reader@example.com";
    String catalogReader = "catalog-reader@example.com";
    String metastoreReader = "metastore-reader@example.com";
    createTestUser(schemaReader);
    createTestUser(catalogReader);
    createTestUser(metastoreReader);

    createExternalTable(
        new TablesApi(adminApiClient),
        CATALOG_NAME,
        SCHEMA_NAME,
        TABLE_NAME,
        testDirectoryRoot.resolve(TABLE_NAME).toUri().toString());
    grantsApi.update(
        SecurableType.TABLE, TABLE_FULL_NAME, addPrivileges(REGULAR_2, Privilege.SELECT));

    // A leaf grant needs both parent USE privileges before it can reveal everyone else's grants.
    grantsApi.update(
        SecurableType.TABLE, TABLE_FULL_NAME, addPrivileges(REGULAR_1, Privilege.READ_METADATA));
    assertThat(
            privilegesFor(
                grantsApiFor(REGULAR_1).get(SecurableType.TABLE, TABLE_FULL_NAME, null), REGULAR_2))
        .isEmpty();
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_1, Privilege.USE_CATALOG));
    assertThat(
            privilegesFor(
                grantsApiFor(REGULAR_1).get(SecurableType.TABLE, TABLE_FULL_NAME, null), REGULAR_2))
        .isEmpty();
    grantsApi.update(
        SecurableType.SCHEMA, SCHEMA_FULL_NAME, addPrivileges(REGULAR_1, Privilege.USE_SCHEMA));
    assertThat(
            privilegesFor(
                grantsApiFor(REGULAR_1).get(SecurableType.TABLE, TABLE_FULL_NAME, null), REGULAR_2))
        .containsExactly(Privilege.SELECT);

    // A schema grant needs USE_CATALOG, while catalog and metastore grants need no USE privileges.
    grantsApi.update(
        SecurableType.SCHEMA,
        SCHEMA_FULL_NAME,
        addPrivileges(schemaReader, Privilege.READ_METADATA));
    assertThat(
            privilegesFor(
                grantsApiFor(schemaReader).get(SecurableType.TABLE, TABLE_FULL_NAME, null),
                REGULAR_2))
        .isEmpty();
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(schemaReader, Privilege.USE_CATALOG));
    assertThat(
            privilegesFor(
                grantsApiFor(schemaReader).get(SecurableType.TABLE, TABLE_FULL_NAME, null),
                REGULAR_2))
        .containsExactly(Privilege.SELECT);

    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(catalogReader, Privilege.READ_METADATA));
    assertThat(
            privilegesFor(
                grantsApiFor(catalogReader).get(SecurableType.TABLE, TABLE_FULL_NAME, null),
                REGULAR_2))
        .containsExactly(Privilege.SELECT);

    grantsApi.update(
        SecurableType.METASTORE,
        METASTORE_NAME,
        addPrivileges(metastoreReader, Privilege.READ_METADATA));
    assertThat(
            privilegesFor(
                grantsApiFor(metastoreReader).get(SecurableType.TABLE, TABLE_FULL_NAME, null),
                REGULAR_2))
        .containsExactly(Privilege.SELECT);
  }

  @Test
  @SneakyThrows
  public void invalidPrivilegeIsRejectedBeforeAnyGrantIsStored() {
    UpdatePermissions request =
        addPrivileges(REGULAR_1, Privilege.CREATE_CATALOG, Privilege.SELECT);

    assertApiException(
        () -> grantsApi.update(SecurableType.METASTORE, METASTORE_NAME, request),
        ErrorCode.INVALID_ARGUMENT,
        "SELECT");

    PermissionsList response = grantsApi.get(SecurableType.METASTORE, METASTORE_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1)).isEmpty();
  }

  @Test
  @SneakyThrows
  public void invalidPrincipalIsRejectedBeforeAnyGrantIsStored() {
    UpdatePermissions request =
        new UpdatePermissions()
            .changes(
                List.of(
                    new PermissionsChange()
                        .principal(REGULAR_1)
                        .add(List.of(Privilege.USE_CATALOG))
                        .remove(List.of()),
                    new PermissionsChange()
                        .principal("missing-user@example.com")
                        .add(List.of(Privilege.CREATE_SCHEMA))
                        .remove(List.of())));

    assertApiException(
        () -> grantsApi.update(SecurableType.CATALOG, CATALOG_NAME, request),
        ErrorCode.NOT_FOUND,
        "User");

    PermissionsList response = grantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1)).isEmpty();
  }

  @Test
  @SneakyThrows
  public void onlyCatalogAdministratorCanGrantExternalUseSchema() {
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_1, Privilege.USE_CATALOG));
    grantsApi.update(
        SecurableType.SCHEMA, SCHEMA_FULL_NAME, addPrivileges(REGULAR_1, Privilege.MANAGE));

    assertPermissionDenied(
        () ->
            grantsApiFor(REGULAR_1)
                .update(
                    SecurableType.SCHEMA,
                    SCHEMA_FULL_NAME,
                    addPrivileges(REGULAR_2, Privilege.EXTERNAL_USE_SCHEMA)));

    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_1, Privilege.MANAGE));
    grantsApiFor(REGULAR_1)
        .update(
            SecurableType.SCHEMA,
            SCHEMA_FULL_NAME,
            addPrivileges(REGULAR_2, Privilege.EXTERNAL_USE_SCHEMA));

    PermissionsList response = grantsApi.get(SecurableType.SCHEMA, SCHEMA_FULL_NAME, null);
    assertThat(privilegesFor(response, REGULAR_2)).containsExactly(Privilege.EXTERNAL_USE_SCHEMA);

    // A schema manager still cannot revoke this privilege after losing catalog administration.
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, removePrivileges(REGULAR_1, Privilege.MANAGE));
    assertPermissionDenied(
        () ->
            grantsApiFor(REGULAR_1)
                .update(
                    SecurableType.SCHEMA,
                    SCHEMA_FULL_NAME,
                    removePrivileges(REGULAR_2, Privilege.EXTERNAL_USE_SCHEMA)));
    response = grantsApi.get(SecurableType.SCHEMA, SCHEMA_FULL_NAME, null);
    assertThat(privilegesFor(response, REGULAR_2)).containsExactly(Privilege.EXTERNAL_USE_SCHEMA);
  }

  @Test
  @SneakyThrows
  public void manageRequiresParentUseBeforeUpdatingLeafPermissions() {
    String schemaManager = "schema-manager@example.com";
    String catalogManager = "catalog-manager@example.com";
    createTestUser(schemaManager);
    createTestUser(catalogManager);
    createExternalTable(
        new TablesApi(adminApiClient),
        CATALOG_NAME,
        SCHEMA_NAME,
        TABLE_NAME,
        testDirectoryRoot.resolve(TABLE_NAME).toUri().toString());

    grantsApi.update(
        SecurableType.TABLE, TABLE_FULL_NAME, addPrivileges(REGULAR_1, Privilege.MANAGE));
    assertPermissionDenied(
        () ->
            grantsApiFor(REGULAR_1)
                .update(
                    SecurableType.TABLE,
                    TABLE_FULL_NAME,
                    addPrivileges(REGULAR_2, Privilege.SELECT)));
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(REGULAR_1, Privilege.USE_CATALOG));
    assertPermissionDenied(
        () ->
            grantsApiFor(REGULAR_1)
                .update(
                    SecurableType.TABLE,
                    TABLE_FULL_NAME,
                    addPrivileges(REGULAR_2, Privilege.SELECT)));
    grantsApi.update(
        SecurableType.SCHEMA, SCHEMA_FULL_NAME, addPrivileges(REGULAR_1, Privilege.USE_SCHEMA));
    grantsApiFor(REGULAR_1)
        .update(SecurableType.TABLE, TABLE_FULL_NAME, addPrivileges(REGULAR_2, Privilege.SELECT));

    grantsApi.update(
        SecurableType.SCHEMA, SCHEMA_FULL_NAME, addPrivileges(schemaManager, Privilege.MANAGE));
    assertPermissionDenied(
        () ->
            grantsApiFor(schemaManager)
                .update(
                    SecurableType.TABLE,
                    TABLE_FULL_NAME,
                    addPrivileges(REGULAR_2, Privilege.MODIFY)));
    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(schemaManager, Privilege.USE_CATALOG));
    grantsApiFor(schemaManager)
        .update(SecurableType.TABLE, TABLE_FULL_NAME, addPrivileges(REGULAR_2, Privilege.MODIFY));

    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, addPrivileges(catalogManager, Privilege.MANAGE));
    grantsApiFor(catalogManager)
        .update(
            SecurableType.TABLE, TABLE_FULL_NAME, addPrivileges(REGULAR_2, Privilege.APPLY_TAG));

    PermissionsList response = grantsApi.get(SecurableType.TABLE, TABLE_FULL_NAME, null);
    assertThat(privilegesFor(response, REGULAR_2))
        .containsExactlyInAnyOrder(Privilege.SELECT, Privilege.MODIFY, Privilege.APPLY_TAG);
  }

  @Test
  @SneakyThrows
  public void revokingAllPrivilegesRemovesCoveredExplicitGrants() {
    grantsApi.update(
        SecurableType.CATALOG,
        CATALOG_NAME,
        addPrivileges(
            REGULAR_1,
            Privilege.ALL_PRIVILEGES,
            Privilege.USE_CATALOG,
            Privilege.MANAGE,
            Privilege.READ_METADATA,
            Privilege.EXTERNAL_USE_SCHEMA));

    PermissionsList beforeRevoke = grantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(beforeRevoke, REGULAR_1))
        .containsExactlyInAnyOrder(
            Privilege.ALL_PRIVILEGES,
            Privilege.USE_CATALOG,
            Privilege.MANAGE,
            Privilege.READ_METADATA,
            Privilege.EXTERNAL_USE_SCHEMA);

    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, removePrivileges(REGULAR_1, Privilege.ALL_PRIVILEGES));

    PermissionsList response = grantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1))
        .containsExactlyInAnyOrder(
            Privilege.MANAGE, Privilege.READ_METADATA, Privilege.EXTERNAL_USE_SCHEMA);
  }

  @Test
  @SneakyThrows
  public void revokingOneAllPrivilegesChildDoesNotReduceAllPrivileges() {
    grantsApi.update(
        SecurableType.CATALOG,
        CATALOG_NAME,
        addPrivileges(REGULAR_1, Privilege.ALL_PRIVILEGES, Privilege.USE_CATALOG));

    PermissionsList beforeRevoke = grantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(beforeRevoke, REGULAR_1))
        .containsExactlyInAnyOrder(Privilege.ALL_PRIVILEGES, Privilege.USE_CATALOG);

    grantsApi.update(
        SecurableType.CATALOG, CATALOG_NAME, removePrivileges(REGULAR_1, Privilege.USE_CATALOG));

    PermissionsList response = grantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1)).containsExactly(Privilege.ALL_PRIVILEGES);
  }

  @Test
  @SneakyThrows
  public void explicitAddWinsOverAllPrivilegesRemoval() {
    grantsApi.update(
        SecurableType.CATALOG,
        CATALOG_NAME,
        addPrivileges(REGULAR_1, Privilege.ALL_PRIVILEGES, Privilege.CREATE_SCHEMA));

    PermissionsChange add =
        new PermissionsChange()
            .principal(REGULAR_1)
            .add(List.of(Privilege.USE_CATALOG))
            .remove(List.of());
    PermissionsChange removeAll =
        new PermissionsChange()
            .principal(REGULAR_1)
            .add(List.of())
            .remove(List.of(Privilege.ALL_PRIVILEGES));
    grantsApi.update(
        SecurableType.CATALOG,
        CATALOG_NAME,
        new UpdatePermissions().changes(List.of(add, removeAll)));

    PermissionsList response = grantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1)).containsExactly(Privilege.USE_CATALOG);
  }

  @Test
  @SneakyThrows
  public void duplicateAddAndRemoveForOnePrincipalIsRejected() {
    PermissionsChange add =
        new PermissionsChange()
            .principal(REGULAR_1)
            .add(List.of(Privilege.USE_CATALOG))
            .remove(List.of());
    PermissionsChange remove =
        new PermissionsChange()
            .principal(REGULAR_1)
            .add(List.of())
            .remove(List.of(Privilege.USE_CATALOG));

    assertApiException(
        () ->
            grantsApi.update(
                SecurableType.CATALOG,
                CATALOG_NAME,
                new UpdatePermissions().changes(List.of(add, remove))),
        ErrorCode.INVALID_ARGUMENT,
        "Duplicate privileges");

    PermissionsList response = grantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null);
    assertThat(privilegesFor(response, REGULAR_1)).isEmpty();
  }

  @Test
  public void unauthenticatedRequestIsRejected() {
    // No bearer token -> the AuthDecorator rejects the request before it reaches the service.
    GrantsApi unauthGrantsApi = new GrantsApi(TestUtils.createApiClient(serverConfig));
    assertApiException(
        () -> unauthGrantsApi.get(SecurableType.CATALOG, CATALOG_NAME, null),
        ErrorCode.UNAUTHENTICATED,
        "authorization");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Builds a {@link GrantsApi} client authenticated as the given test user. */
  private GrantsApi grantsApiFor(String userEmail) {
    ServerConfig userConfig = createTestUserServerConfig(userEmail);
    return new GrantsApi(TestUtils.createApiClient(userConfig));
  }

  private static UpdatePermissions addPrivileges(String principal, Privilege... privileges) {
    PermissionsChange change =
        new PermissionsChange().principal(principal).add(List.of(privileges)).remove(List.of());
    return new UpdatePermissions().changes(List.of(change));
  }

  private static UpdatePermissions removePrivileges(String principal, Privilege... privileges) {
    PermissionsChange change =
        new PermissionsChange().principal(principal).add(List.of()).remove(List.of(privileges));
    return new UpdatePermissions().changes(List.of(change));
  }

  /** Returns the privileges assigned to {@code principal} in a PermissionsList response. */
  private static List<Privilege> privilegesFor(PermissionsList permissionsList, String principal) {
    List<Privilege> privileges = new ArrayList<>();
    if (permissionsList.getPrivilegeAssignments() == null) {
      return privileges;
    }
    for (PrivilegeAssignment assignment : permissionsList.getPrivilegeAssignments()) {
      if (principal.equals(assignment.getPrincipal())) {
        privileges.addAll(assignment.getPrivileges());
      }
    }
    return privileges;
  }
}
