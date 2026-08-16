package io.unitycatalog.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.DataSourceFormat;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.TableCleanupRepository;
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import io.unitycatalog.server.persist.dao.SchemaInfoDAO;
import io.unitycatalog.server.persist.dao.StagingTableDAO;
import io.unitycatalog.server.persist.dao.TableCleanupTaskDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableCleanupHandoffTest {
  private static final String CATALOG = "handoff_catalog";
  private static final String SCHEMA = "handoff_schema";

  @TempDir Path temporaryDirectory;

  private SessionFactory sessionFactory;
  private Repositories repositories;
  private TableCleanupRepository cleanupRepository;
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
        "jdbc:h2:mem:cleanup-handoff-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    hibernateProperties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    sessionFactory = new HibernateConfigurator(hibernateProperties).getSessionFactory();
    repositories = new Repositories(sessionFactory, serverProperties);
    cleanupRepository = repositories.getTableCleanupRepository();

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
  void expiredTombstoneBecomesTheSingleDurableTask() {
    Date now = new Date();
    UUID tableId = persistTombstone("expired", tablePath("expired"), now, true);

    assertThat(cleanupRepository.enqueueExpiredTables()).isOne();

    try (Session session = sessionFactory.openSession()) {
      assertThat(session.get(TableInfoDAO.class, tableId)).isNull();
      assertThat(session.get(StagingTableDAO.class, tableId)).isNull();
      assertThat(session.get(TableCleanupTaskDAO.class, tableId)).isNotNull();
    }
    String fullName = CATALOG + "." + SCHEMA + ".expired";
    assertThatThrownBy(() -> repositories.getTableRepository().restoreTable(fullName))
        .isInstanceOf(BaseException.class)
        .extracting(error -> ((BaseException) error).getErrorCode())
        .isEqualTo(ErrorCode.TABLE_NOT_FOUND);
  }

  @Test
  void malformedTombstoneDoesNotRollbackHealthyHandoff() {
    Date now = new Date();
    UUID malformedId = persistTombstone("malformed", " ", now, false);
    UUID healthyId = persistTombstone("healthy", tablePath("healthy"), now, true);

    assertThat(cleanupRepository.enqueueExpiredTables()).isOne();

    try (Session session = sessionFactory.openSession()) {
      assertThat(session.get(TableInfoDAO.class, malformedId)).isNotNull();
      assertThat(session.get(TableCleanupTaskDAO.class, malformedId)).isNull();
      assertThat(session.get(TableInfoDAO.class, healthyId)).isNull();
      assertThat(session.get(StagingTableDAO.class, healthyId)).isNull();
      assertThat(session.get(TableCleanupTaskDAO.class, healthyId)).isNotNull();
    }
  }

  @Test
  void unexpiredTombstoneRemainsRestorable() {
    Date now = new Date();
    UUID tableId =
        persistTombstone(
            "retained", tablePath("retained"), Date.from(now.toInstant().plusSeconds(60)), true);

    assertThat(cleanupRepository.enqueueExpiredTables()).isZero();
    try (Session session = sessionFactory.openSession()) {
      assertThat(session.get(TableInfoDAO.class, tableId)).isNotNull();
      assertThat(session.get(TableCleanupTaskDAO.class, tableId)).isNull();
    }
  }

  @Test
  void forcedSchemaDeleteHandsOffRestorableTables() {
    Date now = new Date();
    UUID tableId = persistTombstone("cascaded", tablePath("cascaded"), now, true);

    repositories.getSchemaRepository().deleteSchema(CATALOG + "." + SCHEMA, true);

    try (Session session = sessionFactory.openSession()) {
      assertThat(session.get(TableInfoDAO.class, tableId)).isNull();
      assertThat(session.get(TableCleanupTaskDAO.class, tableId)).isNotNull();
      assertThat(session.get(SchemaInfoDAO.class, schemaId)).isNull();
    }
  }

  @Test
  void disabledLifecycleStillHandsOffAnExistingTombstone() {
    UUID tableId = persistTombstone("rollback", tablePath("rollback"), new Date(), true);
    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    Repositories disabledRepositories =
        new Repositories(sessionFactory, new ServerProperties(settings));

    disabledRepositories.getSchemaRepository().deleteSchema(CATALOG + "." + SCHEMA, true);

    try (Session session = sessionFactory.openSession()) {
      assertThat(session.get(TableInfoDAO.class, tableId)).isNull();
      assertThat(session.get(TableCleanupTaskDAO.class, tableId)).isNotNull();
    }
  }

  private UUID persistTombstone(
      String name, String storageLocation, Date purgeAfter, boolean withStagingRow) {
    UUID tableId = UUID.randomUUID();
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          Date deletedAt = Date.from(Instant.ofEpochMilli(purgeAfter.getTime()).minusSeconds(1));
          if (withStagingRow) {
            session.persist(
                StagingTableDAO.builder()
                    .id(tableId)
                    .schemaId(schemaId)
                    .name(name)
                    .stagingLocation(storageLocation)
                    .createdAt(deletedAt)
                    .accessedAt(deletedAt)
                    .stageCommitted(true)
                    .stageCommittedAt(deletedAt)
                    .build());
          }
          session.persist(
              TableInfoDAO.builder()
                  .id(tableId)
                  .schemaId(schemaId)
                  .name(name)
                  .type(TableType.MANAGED.getValue())
                  .dataSourceFormat(DataSourceFormat.DELTA.getValue())
                  .url(storageLocation)
                  .columns(List.of())
                  .createdAt(deletedAt)
                  .deletedAt(deletedAt)
                  .purgeAfter(purgeAfter)
                  .build());
          return null;
        },
        "Failed to persist tombstoned table",
        /* readOnly = */ false);
    return tableId;
  }

  private String tablePath(String name) {
    return temporaryDirectory.resolve(name).toUri().toString();
  }
}
