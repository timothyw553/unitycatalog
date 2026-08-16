package io.unitycatalog.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.server.auth.AllowingAuthorizer;
import io.unitycatalog.server.model.DataSourceFormat;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.TableCleanupRepository;
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import io.unitycatalog.server.persist.dao.SchemaInfoDAO;
import io.unitycatalog.server.persist.dao.StagingTableDAO;
import io.unitycatalog.server.persist.dao.TableCleanupTaskDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.utils.FileOperations;
import io.unitycatalog.server.persist.utils.FileOperations.DeleteBatchResult;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedTableCleanupWorkerTest {
  private static final String CATALOG = "worker_catalog";
  private static final String SCHEMA = "worker_schema";
  private static final String TABLE = "worker_table";

  @TempDir Path temporaryDirectory;

  private SessionFactory sessionFactory;
  private ServerProperties serverProperties;
  private Repositories repositories;
  private UUID schemaId;

  @BeforeEach
  void setUp() {
    Properties settings = new Properties();
    settings.setProperty(Property.SERVER_ENV.getKey(), "test");
    settings.setProperty(Property.MANAGED_TABLE_RETENTION_DURATION.getKey(), "PT0S");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_POLL_INTERVAL.getKey(), "PT0.02S");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_SLICE_DURATION.getKey(), "PT2S");
    settings.setProperty(Property.MANAGED_TABLE_CLEANUP_BATCH_SIZE.getKey(), "2");
    serverProperties = new ServerProperties(settings);

    Properties hibernateProperties = new Properties();
    hibernateProperties.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
    hibernateProperties.setProperty(
        "hibernate.connection.url",
        "jdbc:h2:mem:cleanup-worker-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
  void deletesInBatchesAndClearsAuthorization() throws Exception {
    Path tableDirectory = temporaryDirectory.resolve(TABLE);
    Files.createDirectories(tableDirectory.resolve("partition"));
    Files.writeString(tableDirectory.resolve("_delta_log.json"), "{}");
    Files.writeString(tableDirectory.resolve("part-1.parquet"), "one");
    Files.writeString(tableDirectory.resolve("partition/part-2.parquet"), "two");
    UUID tableId = persistManagedTable(tableDirectory);
    repositories.getTableRepository().deleteTable(CATALOG, SCHEMA, TABLE);
    assertThat(repositories.getTableCleanupRepository().enqueueExpiredTables(new Date())).isOne();

    RecordingAuthorizer authorizer = new RecordingAuthorizer();
    ManagedTableCleanupWorker worker =
        new ManagedTableCleanupWorker(
            repositories.getTableCleanupRepository(),
            new FileOperations(null, serverProperties),
            authorizer,
            serverProperties);
    while (worker.processNext("worker", () -> true)) {
      // Keep draining until the durable queue is empty.
    }

    assertThat(tableAndTaskAreGone(tableId)).isTrue();
    assertThat(tableDirectory).doesNotExist();
    assertThat(authorizer.clearedTableId).isEqualTo(tableId);
    assertThat(authorizer.removedParentId).isEqualTo(schemaId);
    assertThat(authorizer.removedChildId).isEqualTo(tableId);
  }

  @Test
  void gracefulStopReleasesTheClaimImmediately() throws Exception {
    Path tableDirectory = temporaryDirectory.resolve(TABLE);
    Files.createDirectories(tableDirectory);
    UUID tableId = persistManagedTable(tableDirectory);
    repositories.getTableRepository().deleteTable(CATALOG, SCHEMA, TABLE);
    assertThat(repositories.getTableCleanupRepository().enqueueExpiredTables(new Date())).isOne();

    AtomicBoolean active = new AtomicBoolean(true);
    FileOperations stoppingFileOperations =
        new FileOperations(null, serverProperties) {
          @Override
          public DeleteBatchResult deleteBatch(NormalizedURL path, int maxFiles) {
            active.set(false);
            return DeleteBatchResult.MORE_WORK;
          }
        };
    ManagedTableCleanupWorker worker =
        new ManagedTableCleanupWorker(
            repositories.getTableCleanupRepository(),
            stoppingFileOperations,
            new RecordingAuthorizer(),
            serverProperties);

    assertThat(worker.processNext("stopping", active::get)).isTrue();
    Date now = new Date();
    Optional<TableCleanupRepository.CleanupTask> replacement =
        repositories
            .getTableCleanupRepository()
            .claimNext("replacement", now, Date.from(now.toInstant().plusSeconds(30)));
    assertThat(replacement).isPresent();
    assertThat(replacement.orElseThrow().tableId()).isEqualTo(tableId);
  }

  private UUID persistManagedTable(Path storageLocation) {
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
                  .stagingLocation(storageLocation.toUri().toString())
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
                  .url(storageLocation.toUri().toString())
                  .columns(List.of())
                  .createdAt(now)
                  .build());
          return null;
        },
        "Failed to persist test table",
        /* readOnly = */ false);
    return tableId;
  }

  private boolean tableAndTaskAreGone(UUID tableId) {
    try (Session session = sessionFactory.openSession()) {
      return session.get(TableInfoDAO.class, tableId) == null
          && session.get(TableCleanupTaskDAO.class, tableId) == null;
    }
  }

  private static final class RecordingAuthorizer extends AllowingAuthorizer {
    private UUID clearedTableId;
    private UUID removedParentId;
    private UUID removedChildId;

    @Override
    public boolean clearAuthorizationsForResource(UUID resource) {
      clearedTableId = resource;
      return true;
    }

    @Override
    public boolean removeHierarchyChild(UUID parent, UUID child) {
      removedParentId = parent;
      removedChildId = child;
      return true;
    }
  }
}
