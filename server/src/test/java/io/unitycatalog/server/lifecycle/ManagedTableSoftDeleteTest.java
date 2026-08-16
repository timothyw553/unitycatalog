package io.unitycatalog.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.DataSourceFormat;
import io.unitycatalog.server.model.TableInfo;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.DeltaCommitRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.TableRepository;
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import io.unitycatalog.server.persist.dao.SchemaInfoDAO;
import io.unitycatalog.server.persist.dao.StagingTableDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedTableSoftDeleteTest {
  private static final String CATALOG = "soft_delete_catalog";
  private static final String SCHEMA = "soft_delete_schema";
  private static final String TABLE = "soft_delete_table";

  @TempDir Path temporaryDirectory;

  private SessionFactory sessionFactory;
  private Repositories repositories;
  private UUID schemaId;

  @BeforeEach
  void setUp() {
    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    settings.setProperty(Property.MANAGED_TABLE_LIFECYCLE_ENABLED.getKey(), "true");
    settings.setProperty(Property.MANAGED_TABLE_RETENTION_DURATION.getKey(), "PT0S");
    ServerProperties serverProperties = new ServerProperties(settings);

    Properties hibernateProperties = new Properties();
    hibernateProperties.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
    hibernateProperties.setProperty(
        "hibernate.connection.url",
        "jdbc:h2:mem:managed-soft-delete-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    hibernateProperties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    sessionFactory = new HibernateConfigurator(hibernateProperties).getSessionFactory();
    repositories = new Repositories(sessionFactory, serverProperties);

    UUID catalogId = UUID.randomUUID();
    schemaId = UUID.randomUUID();
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          session.persist(
              CatalogInfoDAO.builder().id(catalogId).name(CATALOG).createdAt(new Date()).build());
          session.persist(
              SchemaInfoDAO.builder()
                  .id(schemaId)
                  .catalogId(catalogId)
                  .name(SCHEMA)
                  .createdAt(new Date())
                  .build());
          return null;
        },
        "Failed to persist test namespace",
        /* readOnly = */ false);
  }

  @AfterEach
  void tearDown() {
    sessionFactory.close();
  }

  @Test
  void dropHidesManagedTableUntilRestore() {
    UUID tableId = persistManagedTable();
    TableRepository tableRepository = repositories.getTableRepository();

    assertThat(tableRepository.deleteTable(CATALOG, SCHEMA, TABLE).softDeleted()).isTrue();
    assertTableNotFound(() -> tableRepository.getTable(fullName()));
    assertTableNotFound(() -> tableRepository.getStorageLocationForTableOrStagingTable(tableId));
    assertTableNotFound(() -> tableRepository.getCatalogSchemaIdsByTableOrStagingTableId(tableId));
    assertThat(
            tableRepository
                .listTables(CATALOG, SCHEMA, Optional.empty(), Optional.empty(), true, true)
                .getTables())
        .isEmpty();

    TableInfo restored = tableRepository.restoreTable(fullName());
    assertThat(restored.getTableId()).isEqualTo(tableId.toString());
    try (Session session = sessionFactory.openSession()) {
      TableInfoDAO table = session.get(TableInfoDAO.class, tableId);
      assertThat(table.getDeletedAt()).isNull();
      assertThat(table.getPurgeAfter()).isNull();
    }
  }

  @Test
  void dropPersistsTheConfiguredRetentionDeadline() {
    UUID tableId = persistManagedTable();
    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    settings.setProperty(Property.MANAGED_TABLE_LIFECYCLE_ENABLED.getKey(), "true");
    settings.setProperty(Property.MANAGED_TABLE_RETENTION_DURATION.getKey(), "PT1H");
    TableRepository retainedTableRepository =
        new TableRepository(repositories, sessionFactory, new ServerProperties(settings));

    retainedTableRepository.deleteTable(CATALOG, SCHEMA, TABLE);

    try (Session session = sessionFactory.openSession()) {
      TableInfoDAO table = session.get(TableInfoDAO.class, tableId);
      assertThat(table.getPurgeAfter().getTime() - table.getDeletedAt().getTime())
          .isEqualTo(Duration.ofHours(1).toMillis());
    }
  }

  @Test
  void cloudDropIncludesCredentialDrainInTheDeadline() {
    UUID tableId = persistManagedTable("s3://managed-bucket/tables/" + UUID.randomUUID());
    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    settings.setProperty(Property.MANAGED_TABLE_LIFECYCLE_ENABLED.getKey(), "true");
    settings.setProperty(Property.MANAGED_TABLE_RETENTION_DURATION.getKey(), "PT0S");
    settings.setProperty(Property.MANAGED_TABLE_CREDENTIAL_DRAIN_DURATION.getKey(), "PT1H");

    new TableRepository(repositories, sessionFactory, new ServerProperties(settings))
        .deleteTable(CATALOG, SCHEMA, TABLE);

    try (Session session = sessionFactory.openSession()) {
      TableInfoDAO table = session.get(TableInfoDAO.class, tableId);
      assertThat(table.getPurgeAfter().getTime() - table.getDeletedAt().getTime())
          .isEqualTo(Duration.ofHours(1).toMillis());
    }
  }

  @Test
  void disabledLifecyclePreservesLegacyDeletion() throws Exception {
    Path tableDirectory = temporaryDirectory.resolve(TABLE);
    Files.createDirectories(tableDirectory);
    Files.writeString(tableDirectory.resolve("part.parquet"), "data");
    UUID tableId = persistManagedTable(tableDirectory.toUri().toString());

    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    TableRepository disabledRepository =
        new TableRepository(repositories, sessionFactory, new ServerProperties(settings));

    assertThat(disabledRepository.deleteTable(CATALOG, SCHEMA, TABLE).softDeleted()).isFalse();
    try (Session session = sessionFactory.openSession()) {
      assertThat(session.get(TableInfoDAO.class, tableId)).isNull();
      assertThat(session.get(StagingTableDAO.class, tableId)).isNotNull();
    }
    assertThat(tableDirectory).doesNotExist();
  }

  @Test
  void ccv2CommitLockRejectsATombstone() {
    UUID tableId = persistManagedTable();
    repositories.getTableRepository().deleteTable(CATALOG, SCHEMA, TABLE);

    assertTableNotFound(
        () ->
            TransactionManager.executeWithTransaction(
                sessionFactory,
                session -> {
                  TableInfoDAO table = session.get(TableInfoDAO.class, tableId);
                  DeltaCommitRepository.lockTableForCommit(
                      session, table, tableId, Optional.empty());
                  return null;
                },
                "Commit lock should reject a tombstone",
                /* readOnly = */ false));
  }

  @Test
  void databaseRejectsHalfWrittenTombstone() {
    UUID tableId = persistManagedTable();

    assertThatThrownBy(
            () ->
                TransactionManager.executeWithTransaction(
                    sessionFactory,
                    session -> {
                      session.get(TableInfoDAO.class, tableId).setDeletedAt(new Date());
                      return null;
                    },
                    "Invalid tombstone should fail",
                    /* readOnly = */ false))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void credentialValidationRejectsATombstone() {
    String location = "s3://managed-bucket/tables/" + UUID.randomUUID();
    UUID tableId = persistManagedTable(location);
    repositories.getTableRepository().deleteTable(CATALOG, SCHEMA, TABLE);

    assertErrorCode(
        () ->
            repositories
                .getTableRepository()
                .validateManagedCredentialIssuance(
                    tableId,
                    NormalizedURL.from(location),
                    System.currentTimeMillis() + Duration.ofMinutes(1).toMillis()),
        ErrorCode.TABLE_NOT_FOUND);
  }

  @Test
  void credentialValidationRejectsAnUnboundedCloudCredential() {
    String location = "s3://managed-bucket/tables/" + UUID.randomUUID();
    UUID tableId = persistManagedTable(location);

    assertErrorCode(
        () ->
            repositories
                .getTableRepository()
                .validateManagedCredentialIssuance(
                    tableId, NormalizedURL.from(location), /* expirationTime = */ null),
        ErrorCode.FAILED_PRECONDITION);
  }

  @Test
  void credentialValidationAcceptsABoundedCloudCredential() {
    String location = "s3://managed-bucket/tables/" + UUID.randomUUID();
    UUID tableId = persistManagedTable(location);

    repositories
        .getTableRepository()
        .validateManagedCredentialIssuance(
            tableId,
            NormalizedURL.from(location),
            System.currentTimeMillis() + Duration.ofMinutes(1).toMillis());
  }

  @Test
  void credentialValidationDoesNotConstrainExternalTables() {
    String location = "s3://external-bucket/tables/" + UUID.randomUUID();
    UUID tableId = persistManagedTable(location);
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          session.get(TableInfoDAO.class, tableId).setType(TableType.EXTERNAL.getValue());
          return null;
        },
        "Convert test table to external",
        /* readOnly = */ false);

    repositories
        .getTableRepository()
        .validateManagedCredentialIssuance(
            tableId, NormalizedURL.from(location), /* expirationTime = */ null);
  }

  @Test
  void publicPathCredentialMustBeBoundedWhileLifecycleIsEnabled() {
    NormalizedURL path = NormalizedURL.from("s3://external-bucket/path/" + UUID.randomUUID());

    assertErrorCode(
        () ->
            repositories
                .getTableRepository()
                .validatePathCredentialIssuance(path, /* expirationTime = */ null),
        ErrorCode.FAILED_PRECONDITION);
  }

  private UUID persistManagedTable() {
    return persistManagedTable(temporaryDirectory.resolve(TABLE).toUri().toString());
  }

  private UUID persistManagedTable(String storageLocation) {
    UUID tableId = UUID.randomUUID();
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          Date now = new Date();
          session.persist(
              StagingTableDAO.builder()
                  .id(tableId)
                  .schemaId(schemaId)
                  .name(TABLE)
                  .stagingLocation(storageLocation)
                  .createdAt(now)
                  .accessedAt(now)
                  .stageCommitted(true)
                  .stageCommittedAt(now)
                  .build());
          session.persist(
              TableInfoDAO.builder()
                  .id(tableId)
                  .schemaId(schemaId)
                  .name(TABLE)
                  .type(TableType.MANAGED.getValue())
                  .dataSourceFormat(DataSourceFormat.DELTA.getValue())
                  .url(storageLocation)
                  .columns(List.of())
                  .createdAt(now)
                  .build());
          return null;
        },
        "Failed to persist test table",
        /* readOnly = */ false);
    return tableId;
  }

  private String fullName() {
    return CATALOG + "." + SCHEMA + "." + TABLE;
  }

  private static void assertTableNotFound(Runnable action) {
    assertErrorCode(action, ErrorCode.TABLE_NOT_FOUND);
  }

  private static void assertErrorCode(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BaseException.class)
        .extracting(error -> ((BaseException) error).getErrorCode())
        .isEqualTo(expected);
  }
}
