package io.unitycatalog.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

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

class TableCleanupRepositoryTest {
  private static final String CATALOG = "cleanup_catalog";
  private static final String SCHEMA = "cleanup_schema";

  @TempDir Path temporaryDirectory;

  private HibernateConfigurator hibernateConfigurator;
  private SessionFactory sessionFactory;
  private Repositories repositories;
  private TableCleanupRepository cleanupRepository;
  private UUID schemaId;

  @BeforeEach
  void setUp() {
    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    settings.setProperty(Property.MANAGED_TABLE_RETENTION_DURATION.getKey(), "PT0S");
    ServerProperties serverProperties = new ServerProperties(settings);

    Properties hibernateProperties = new Properties();
    hibernateProperties.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
    hibernateProperties.setProperty(
        "hibernate.connection.url",
        "jdbc:h2:mem:cleanup-repository-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    hibernateProperties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    hibernateConfigurator = new HibernateConfigurator(hibernateProperties);
    sessionFactory = hibernateConfigurator.getSessionFactory();
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
  void staleOwnerCannotMutateAReclaimedTask() {
    Date now = new Date();
    UUID tableId = persistTombstone("reclaimed", tablePath("reclaimed"), now, true);
    assertThat(cleanupRepository.enqueueExpiredTables(now)).isOne();

    assertThat(cleanupRepository.claimNext("first", now, Date.from(now.toInstant().minusMillis(1))))
        .isPresent();
    assertThat(
            cleanupRepository.renewLease(
                tableId, "first", now, Date.from(now.toInstant().plusSeconds(30))))
        .isFalse();
    assertThat(
            cleanupRepository.claimNext("second", now, Date.from(now.toInstant().plusSeconds(30))))
        .isPresent();

    assertThat(cleanupRepository.reschedule(tableId, "first", now, now)).isFalse();
    assertThat(
            cleanupRepository.recordFailure(
                tableId,
                "first",
                now,
                now,
                new IllegalStateException("https://example.invalid/?secret=do-not-store")))
        .isFalse();
    assertThat(cleanupRepository.complete(tableId, "first", now)).isFalse();

    try (Session session = sessionFactory.openSession()) {
      TableCleanupTaskDAO task = session.get(TableCleanupTaskDAO.class, tableId);
      assertThat(task.getLeaseOwner()).isEqualTo("second");
      assertThat(task.getAttemptCount()).isZero();
      assertThat(task.getLastFailure()).isNull();
    }

    assertThat(
            cleanupRepository.recordFailure(
                tableId,
                "second",
                now,
                now,
                new IllegalStateException("https://example.invalid/?secret=do-not-store")))
        .isTrue();
    try (Session session = sessionFactory.openSession()) {
      TableCleanupTaskDAO task = session.get(TableCleanupTaskDAO.class, tableId);
      assertThat(task.getAttemptCount()).isOne();
      assertThat(task.getLastFailure()).isEqualTo("IllegalStateException");
    }
  }

  @Test
  void expiredOwnerCannotMutateAnUnclaimedTask() {
    Date now = new Date();
    UUID tableId = persistTombstone("expired-lease", tablePath("expired-lease"), now, true);
    assertThat(cleanupRepository.enqueueExpiredTables(now)).isOne();
    assertThat(
            cleanupRepository.claimNext("expired", now, Date.from(now.toInstant().minusMillis(1))))
        .isPresent();

    assertThat(cleanupRepository.reschedule(tableId, "expired", now, now)).isFalse();
    assertThat(
            cleanupRepository.recordFailure(
                tableId, "expired", now, now, new IllegalStateException("failure")))
        .isFalse();
    assertThat(cleanupRepository.complete(tableId, "expired", now)).isFalse();

    try (Session session = sessionFactory.openSession()) {
      TableCleanupTaskDAO task = session.get(TableCleanupTaskDAO.class, tableId);
      assertThat(task.getLeaseOwner()).isEqualTo("expired");
      assertThat(task.getAttemptCount()).isZero();
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
