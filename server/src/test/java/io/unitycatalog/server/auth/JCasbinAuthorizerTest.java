package io.unitycatalog.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JCasbinAuthorizerTest {
  private UnityCatalogAuthorizer authenticator;
  private UUID metastoreId;
  private HibernateConfigurator hibernateConfigurator;

  @BeforeEach
  void setUp() throws Exception {
    Properties properties = new Properties();
    properties.setProperty(Property.SERVER_ENV.getKey(), "test");
    ServerProperties serverProperties = new ServerProperties(properties);
    Properties hibernateProperties =
        HibernateConfigurator.setupHibernateProperties(serverProperties);
    hibernateProperties.setProperty(
        "hibernate.connection.url",
        "jdbc:h2:mem:jcasbin_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    hibernateConfigurator = new HibernateConfigurator(hibernateProperties);
    metastoreId = UUID.randomUUID();
    authenticator = new JCasbinAuthorizer(hibernateConfigurator, metastoreId);
  }

  @Test
  void resolvesUsernameFromStandardHibernateProperty() {
    Properties properties = new Properties();
    properties.setProperty("hibernate.connection.username", "alice");
    assertThat(JCasbinAuthorizer.resolveConnectionUsername(properties)).isEqualTo("alice");
  }

  @Test
  void fallsBackToLegacyUserProperty() {
    Properties properties = new Properties();
    properties.setProperty("hibernate.connection.user", "bob");
    assertThat(JCasbinAuthorizer.resolveConnectionUsername(properties)).isEqualTo("bob");
  }

  @Test
  void prefersStandardUsernameWhenBothPresent() {
    Properties properties = new Properties();
    properties.setProperty("hibernate.connection.username", "alice");
    properties.setProperty("hibernate.connection.user", "bob");
    assertThat(JCasbinAuthorizer.resolveConnectionUsername(properties)).isEqualTo("alice");
  }

  @Test
  void testGrantAuthorization() {
    UUID principal = UUID.randomUUID();
    UUID resource = UUID.randomUUID();
    Privileges action = Privileges.CREATE_CATALOG;

    authenticator.grantAuthorization(principal, resource, action);
    assertThat(authenticator.authorize(principal, resource, action)).isTrue();
  }

  @Test
  void testRevokeAuthorization() {
    UUID principal = UUID.randomUUID();
    UUID resource = UUID.randomUUID();
    Privileges action = Privileges.CREATE_CATALOG;

    authenticator.grantAuthorization(principal, resource, action);
    assertThat(authenticator.authorize(principal, resource, action)).isTrue();
    authenticator.revokeAuthorization(principal, resource, action);
    assertThat(authenticator.authorize(principal, resource, action)).isFalse();
  }

  @Test
  void testClearAuthorizationsForPrincipal() {
    UUID principal = UUID.randomUUID();
    UUID principal2 = UUID.randomUUID();
    UUID resource = UUID.randomUUID();
    Privileges action = Privileges.CREATE_CATALOG;

    authenticator.grantAuthorization(principal, resource, action);
    authenticator.grantAuthorization(principal2, resource, action);
    assertThat(authenticator.authorize(principal, resource, action)).isTrue();
    assertThat(authenticator.authorize(principal2, resource, action)).isTrue();

    authenticator.clearAuthorizationsForPrincipal(principal);
    assertThat(authenticator.authorize(principal, resource, action)).isFalse();
    assertThat(authenticator.authorize(principal2, resource, action)).isTrue();

    authenticator.clearAuthorizationsForPrincipal(principal2);
    assertThat(authenticator.authorize(principal2, resource, action)).isFalse();
  }

  @Test
  void testAddHierarchyChild() throws Exception {
    UUID principal = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();
    UUID schema = UUID.randomUUID();
    Privileges action = Privileges.SELECT;

    useResourceTypes(
        Map.of(
            catalog, SecurableType.CATALOG,
            schema, SecurableType.SCHEMA),
        Map.of());
    authenticator.addHierarchyChild(catalog, schema);
    authenticator.grantAuthorization(principal, catalog, action);
    assertThat(authenticator.authorize(principal, schema, action)).isTrue();
  }

  @Test
  void metastorePrivilegeDoesNotInheritToCatalog() {
    UUID principal = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();

    authenticator.addHierarchyChild(metastoreId, catalog);
    authenticator.grantAuthorization(principal, metastoreId, Privileges.CREATE_CATALOG);

    assertThat(authenticator.authorize(principal, catalog, Privileges.CREATE_CATALOG)).isFalse();
  }

  @Test
  void readMetadataInheritsFromMetastore() {
    UUID principal = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();
    UUID schema = UUID.randomUUID();
    UUID table = UUID.randomUUID();

    authenticator.addHierarchyChild(catalog, schema);
    authenticator.addHierarchyChild(schema, table);
    authenticator.grantAuthorization(principal, metastoreId, Privileges.READ_METADATA);

    assertThat(authenticator.authorize(principal, table, Privileges.READ_METADATA)).isTrue();
    assertThat(authenticator.authorize(principal, table, Privileges.SELECT)).isFalse();
  }

  @Test
  void allPrivilegesCoversOrdinaryPrivilegesButNotSpecialPrivileges() throws Exception {
    UUID principal = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();
    UUID schema = UUID.randomUUID();
    UUID table = UUID.randomUUID();

    useResourceTypes(
        Map.of(
            catalog, SecurableType.CATALOG,
            schema, SecurableType.SCHEMA),
        Map.of(table, TableType.MANAGED));

    authenticator.addHierarchyChild(catalog, schema);
    authenticator.addHierarchyChild(schema, table);
    authenticator.grantAuthorization(principal, catalog, Privileges.ALL_PRIVILEGES);

    assertThat(authenticator.authorize(principal, table, Privileges.SELECT)).isTrue();
    assertThat(authenticator.authorize(principal, table, Privileges.APPLY_TAG)).isTrue();
    assertThat(authenticator.authorize(principal, table, Privileges.MANAGE)).isFalse();
    assertThat(authenticator.authorize(principal, table, Privileges.READ_METADATA)).isFalse();
    assertThat(authenticator.authorize(principal, schema, Privileges.EXTERNAL_USE_SCHEMA))
        .isFalse();
  }

  @Test
  void allPrivilegesOnlyCoversPrivilegesApplicableToTheTableSubtype() throws Exception {
    UUID principal = UUID.randomUUID();
    UUID table = UUID.randomUUID();
    UUID materializedView = UUID.randomUUID();

    useResourceTypes(
        Map.of(),
        Map.of(
            table, TableType.MANAGED,
            materializedView, TableType.MATERIALIZED_VIEW));
    authenticator.grantAuthorization(principal, table, Privileges.ALL_PRIVILEGES);
    authenticator.grantAuthorization(principal, materializedView, Privileges.ALL_PRIVILEGES);

    assertThat(authenticator.authorize(principal, table, Privileges.MODIFY)).isTrue();
    assertThat(authenticator.authorize(principal, table, Privileges.REFRESH)).isFalse();
    assertThat(authenticator.authorize(principal, materializedView, Privileges.REFRESH)).isTrue();
    assertThat(authenticator.authorize(principal, materializedView, Privileges.MODIFY)).isFalse();
  }

  @Test
  void inheritedPrivilegesOnlyApplyToTheTableSubtype() throws Exception {
    UUID principal = UUID.randomUUID();
    UUID schema = UUID.randomUUID();
    UUID table = UUID.randomUUID();
    UUID materializedView = UUID.randomUUID();

    useResourceTypes(
        Map.of(schema, SecurableType.SCHEMA),
        Map.of(
            table, TableType.MANAGED,
            materializedView, TableType.MATERIALIZED_VIEW));
    authenticator.addHierarchyChild(schema, table);
    authenticator.addHierarchyChild(schema, materializedView);
    authenticator.grantAuthorization(principal, schema, Privileges.MODIFY);
    authenticator.grantAuthorization(principal, schema, Privileges.REFRESH);

    assertThat(authenticator.authorize(principal, table, Privileges.MODIFY)).isTrue();
    assertThat(authenticator.authorize(principal, table, Privileges.REFRESH)).isFalse();
    assertThat(authenticator.authorize(principal, materializedView, Privileges.REFRESH)).isTrue();
    assertThat(authenticator.authorize(principal, materializedView, Privileges.MODIFY)).isFalse();
  }

  @Test
  void allPrivilegesOnlyProvidesBrowseFromAValidGrantSource() throws Exception {
    UUID catalogPrincipal = UUID.randomUUID();
    UUID schemaPrincipal = UUID.randomUUID();
    UUID tablePrincipal = UUID.randomUUID();
    UUID locationPrincipal = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();
    UUID schema = UUID.randomUUID();
    UUID table = UUID.randomUUID();
    UUID externalLocation = UUID.randomUUID();

    useResourceTypes(
        Map.of(
            catalog, SecurableType.CATALOG,
            schema, SecurableType.SCHEMA,
            externalLocation, SecurableType.EXTERNAL_LOCATION),
        Map.of(table, TableType.MANAGED));
    authenticator.addHierarchyChild(catalog, schema);
    authenticator.addHierarchyChild(schema, table);
    authenticator.grantAuthorization(catalogPrincipal, catalog, Privileges.ALL_PRIVILEGES);
    authenticator.grantAuthorization(schemaPrincipal, schema, Privileges.ALL_PRIVILEGES);
    authenticator.grantAuthorization(tablePrincipal, table, Privileges.ALL_PRIVILEGES);
    authenticator.grantAuthorization(
        locationPrincipal, externalLocation, Privileges.ALL_PRIVILEGES);

    assertThat(authenticator.authorize(catalogPrincipal, table, Privileges.BROWSE)).isTrue();
    assertThat(authenticator.authorize(schemaPrincipal, table, Privileges.BROWSE)).isFalse();
    assertThat(authenticator.authorize(tablePrincipal, table, Privileges.BROWSE)).isFalse();
    assertThat(authenticator.authorize(locationPrincipal, externalLocation, Privileges.BROWSE))
        .isTrue();
  }

  @Test
  void manageInheritsAndGrantsMetadataButNotDataAccess() throws Exception {
    UUID principal = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();
    UUID schema = UUID.randomUUID();
    UUID table = UUID.randomUUID();

    useResourceTypes(
        Map.of(
            catalog, SecurableType.CATALOG,
            schema, SecurableType.SCHEMA),
        Map.of(table, TableType.MANAGED));
    authenticator.addHierarchyChild(catalog, schema);
    authenticator.addHierarchyChild(schema, table);
    authenticator.grantAuthorization(principal, catalog, Privileges.MANAGE);

    assertThat(authenticator.authorize(principal, table, Privileges.MANAGE)).isTrue();
    assertThat(authenticator.authorize(principal, table, Privileges.READ_METADATA)).isTrue();
    assertThat(authenticator.authorize(principal, table, Privileges.SELECT)).isFalse();
    assertThat(authenticator.authorize(principal, table, Privileges.MODIFY)).isFalse();
  }

  @Test
  void testRemoveHierarchyChild() throws Exception {
    UUID principal = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();
    UUID schema = UUID.randomUUID();
    Privileges action = Privileges.SELECT;

    useResourceTypes(
        Map.of(
            catalog, SecurableType.CATALOG,
            schema, SecurableType.SCHEMA),
        Map.of());
    authenticator.addHierarchyChild(catalog, schema);
    authenticator.grantAuthorization(principal, catalog, action);
    assertThat(authenticator.authorize(principal, schema, action)).isTrue();
    authenticator.removeHierarchyChild(catalog, schema);
    assertThat(authenticator.authorize(principal, schema, action)).isFalse();
  }

  @Test
  void testRemoveHierarchyChildren() throws Exception {
    UUID principal = UUID.randomUUID();
    UUID catalog = UUID.randomUUID();
    UUID schema = UUID.randomUUID();
    Privileges action = Privileges.SELECT;

    useResourceTypes(
        Map.of(
            catalog, SecurableType.CATALOG,
            schema, SecurableType.SCHEMA),
        Map.of());
    authenticator.addHierarchyChild(catalog, schema);
    authenticator.grantAuthorization(principal, catalog, action);
    assertThat(authenticator.authorize(principal, schema, action)).isTrue();
    authenticator.removeHierarchyChildren(catalog);
    assertThat(authenticator.authorize(principal, schema, action)).isFalse();
  }

  @Test
  void testAuthorizeAny() {
    UUID principal = UUID.randomUUID();
    UUID resource = UUID.randomUUID();

    assertThat(
            authenticator.authorizeAny(
                principal, resource, Privileges.USE_CATALOG, Privileges.CREATE_CATALOG))
        .isFalse();
    authenticator.grantAuthorization(principal, resource, Privileges.USE_CATALOG);
    assertThat(
            authenticator.authorizeAny(
                principal, resource, Privileges.USE_CATALOG, Privileges.CREATE_CATALOG))
        .isTrue();
  }

  @Test
  void testAuthorizeAll() {
    UUID principal = UUID.randomUUID();
    UUID resource = UUID.randomUUID();

    assertThat(
            authenticator.authorizeAll(
                principal, resource, Privileges.USE_CATALOG, Privileges.CREATE_CATALOG))
        .isFalse();
    authenticator.grantAuthorization(principal, resource, Privileges.USE_CATALOG);
    assertThat(
            authenticator.authorizeAll(
                principal, resource, Privileges.USE_CATALOG, Privileges.CREATE_CATALOG))
        .isFalse();
    authenticator.grantAuthorization(principal, resource, Privileges.CREATE_CATALOG);
    assertThat(
            authenticator.authorizeAll(
                principal, resource, Privileges.USE_CATALOG, Privileges.CREATE_CATALOG))
        .isTrue();
  }

  @Test
  void testListAuthorizations() {
    UUID principal = UUID.randomUUID();
    UUID resource = UUID.randomUUID();
    List<Privileges> actions = Arrays.asList(Privileges.USE_CATALOG, Privileges.CREATE_CATALOG);

    assertThat(authenticator.listAuthorizations(principal, resource)).isEmpty();
    actions.forEach(action -> authenticator.grantAuthorization(principal, resource, action));
    List<Privileges> result = authenticator.listAuthorizations(principal, resource);
    assertThat(result).isEqualTo(actions);
  }

  @Test
  void testListAuthorizationsForAllUsers() {
    UUID principal = UUID.randomUUID();
    UUID principal2 = UUID.randomUUID();
    UUID resource = UUID.randomUUID();
    UUID resource2 = UUID.randomUUID();

    List<Privileges> actions = Arrays.asList(Privileges.USE_CATALOG, Privileges.CREATE_CATALOG);
    List<Privileges> actions2 = Arrays.asList(Privileges.CREATE_CATALOG, Privileges.SELECT);
    Map<UUID, List<Privileges>> empty = authenticator.listAuthorizations(resource);
    assertThat(empty).isEmpty();

    actions.forEach(action -> authenticator.grantAuthorization(principal, resource, action));
    actions.forEach(action -> authenticator.grantAuthorization(principal, resource2, action));
    actions2.forEach(action -> authenticator.grantAuthorization(principal2, resource, action));
    Map<UUID, List<Privileges>> expected = Map.of(principal, actions, principal2, actions2);
    assertThat(authenticator.listAuthorizations(resource)).isEqualTo(expected);
  }

  private void useResourceTypes(
      Map<UUID, SecurableType> securableTypes, Map<UUID, TableType> tableTypes) throws Exception {
    ResourcePrivilegeResolver resolver =
        new TestResourcePrivilegeResolver(securableTypes, tableTypes);
    authenticator = new JCasbinAuthorizer(hibernateConfigurator, metastoreId, resolver);
  }

  private static final class TestResourcePrivilegeResolver implements ResourcePrivilegeResolver {
    private final Map<UUID, SecurableType> securableTypes;
    private final Map<UUID, TableType> tableTypes;

    private TestResourcePrivilegeResolver(
        Map<UUID, SecurableType> securableTypes, Map<UUID, TableType> tableTypes) {
      this.securableTypes = securableTypes;
      this.tableTypes = tableTypes;
    }

    @Override
    public boolean isAssignable(UUID resourceId, Privileges privilege) {
      TableType tableType = tableTypes.get(resourceId);
      return tableType == null
          ? PrivilegePolicy.isAssignable(securableTypes.get(resourceId), privilege)
          : PrivilegePolicy.isAssignable(tableType, privilege);
    }

    @Override
    public boolean isApplicable(UUID resourceId, Privileges privilege) {
      TableType tableType = tableTypes.get(resourceId);
      return tableType == null
          ? PrivilegePolicy.isApplicable(securableTypes.get(resourceId), privilege)
          : PrivilegePolicy.isApplicable(tableType, privilege);
    }
  }
}
