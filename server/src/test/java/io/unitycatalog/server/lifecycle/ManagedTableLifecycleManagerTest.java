package io.unitycatalog.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.server.auth.AllowingAuthorizer;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import io.unitycatalog.server.persist.dao.SchemaInfoDAO;
import io.unitycatalog.server.persist.dao.TableCleanupTaskDAO;
import io.unitycatalog.server.persist.utils.FileOperations;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedTableLifecycleManagerTest {
  private static final String CATALOG = "lifecycle_catalog";
  private static final String SCHEMA = "lifecycle_schema";

  @TempDir Path temporaryDirectory;

  private HibernateConfigurator hibernateConfigurator;
  private SessionFactory sessionFactory;
  private ServerProperties serverProperties;
  private Repositories repositories;
  private UUID schemaId;

  @BeforeEach
  void setUp() {
    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    settings.setProperty(Property.MANAGED_TABLE_LIFECYCLE_ENABLED.getKey(), "true");
    settings.setProperty(Property.MANAGED_TABLE_RETENTION_DURATION.getKey(), "PT0S");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_ENABLED.getKey(), "true");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_POLL_INTERVAL.getKey(), "PT0.02S");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_WORKER_COUNT.getKey(), "1");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_SLICE_DURATION.getKey(), "PT0.1S");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_BATCH_SIZE.getKey(), "2");
    serverProperties = new ServerProperties(settings);

    Properties hibernateProperties = new Properties();
    hibernateProperties.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
    hibernateProperties.setProperty(
        "hibernate.connection.url",
        "jdbc:h2:mem:managed-lifecycle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    hibernateProperties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    hibernateConfigurator = new HibernateConfigurator(hibernateProperties);
    sessionFactory = hibernateConfigurator.getSessionFactory();
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
  void workerDrainsReadyTasksWithoutPollingBetweenThem() throws Exception {
    Date now = new Date();
    UUID first = persistCleanupTask("first", now);
    UUID second = persistCleanupTask("second", now);

    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    settings.setProperty(Property.MANAGED_TABLE_LIFECYCLE_ENABLED.getKey(), "true");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_ENABLED.getKey(), "true");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_POLL_INTERVAL.getKey(), "PT5S");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_WORKER_COUNT.getKey(), "1");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_SLICE_DURATION.getKey(), "PT1S");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_BATCH_SIZE.getKey(), "10");
    ServerProperties slowPollProperties = new ServerProperties(settings);

    FileOperations fileOperations = new FileOperations(null, slowPollProperties);
    try (ManagedTableLifecycleManager manager =
        new ManagedTableLifecycleManager(
            repositories.getTableCleanupRepository(),
            fileOperations,
            new AllowingAuthorizer(),
            slowPollProperties)) {
      manager.start();
      await(Duration.ofSeconds(1), () -> cleanupTasksAreGone(first, second));
    }
  }

  private UUID persistCleanupTask(String name, Date now) {
    UUID tableId = UUID.randomUUID();
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          session.persist(
              TableCleanupTaskDAO.builder()
                  .tableId(tableId)
                  .schemaId(schemaId)
                  .storageLocation(temporaryDirectory.resolve(name).toUri().toString())
                  .createdAt(now)
                  .nextAttemptAt(now)
                  .attemptCount(0)
                  .build());
          return null;
        },
        "Failed to persist cleanup task",
        /* readOnly = */ false);
    return tableId;
  }

  private boolean cleanupTasksAreGone(UUID... tableIds) {
    try (Session session = sessionFactory.openSession()) {
      return java.util.Arrays.stream(tableIds)
          .allMatch(tableId -> session.get(TableCleanupTaskDAO.class, tableId) == null);
    }
  }

  private static void await(Duration timeout, BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }
}
