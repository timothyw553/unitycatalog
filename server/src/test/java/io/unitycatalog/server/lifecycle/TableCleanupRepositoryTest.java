package io.unitycatalog.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.AwsIamRoleRequest;
import io.unitycatalog.server.model.CreateCredentialRequest;
import io.unitycatalog.server.model.CreateExternalLocation;
import io.unitycatalog.server.model.CredentialPurpose;
import io.unitycatalog.server.model.DataSourceFormat;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.model.UpdateExternalLocation;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.TableCleanupRepository;
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import io.unitycatalog.server.persist.dao.CredentialDAO;
import io.unitycatalog.server.persist.dao.ExternalLocationDAO;
import io.unitycatalog.server.persist.dao.SchemaInfoDAO;
import io.unitycatalog.server.persist.dao.StagingTableDAO;
import io.unitycatalog.server.persist.dao.TableCleanupTaskDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.utils.DatabaseTime;
import io.unitycatalog.server.persist.utils.HibernateConfigurator;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import io.unitycatalog.server.utils.ServerProperties.Property;
import java.nio.file.Path;
import java.time.Duration;
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
    settings.setProperty(Property.MANAGED_TABLE_LIFECYCLE_ENABLED.getKey(), "true");
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
    assertThat(cleanupRepository.enqueueExpiredTables()).isOne();

    assertThat(cleanupRepository.claimNext("first", Duration.ofSeconds(30))).isPresent();
    expireLease(tableId);
    assertThat(cleanupRepository.renewLease(tableId, "first", Duration.ofSeconds(30))).isFalse();
    assertThat(cleanupRepository.claimNext("second", Duration.ofSeconds(30))).isPresent();

    assertThat(cleanupRepository.reschedule(tableId, "first", Duration.ZERO)).isFalse();
    assertThat(
            cleanupRepository.recordFailure(
                tableId,
                "first",
                Duration.ZERO,
                new IllegalStateException("https://example.invalid/?secret=do-not-store")))
        .isFalse();
    assertThat(cleanupRepository.complete(tableId, "first")).isFalse();

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
                Duration.ZERO,
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
    assertThat(cleanupRepository.enqueueExpiredTables()).isOne();
    assertThat(cleanupRepository.claimNext("expired", Duration.ofSeconds(30))).isPresent();
    expireLease(tableId);

    assertThat(cleanupRepository.reschedule(tableId, "expired", Duration.ZERO)).isFalse();
    assertThat(
            cleanupRepository.recordFailure(
                tableId, "expired", Duration.ZERO, new IllegalStateException("failure")))
        .isFalse();
    assertThat(cleanupRepository.complete(tableId, "expired")).isFalse();

    try (Session session = sessionFactory.openSession()) {
      TableCleanupTaskDAO task = session.get(TableCleanupTaskDAO.class, tableId);
      assertThat(task.getLeaseOwner()).isEqualTo("expired");
      assertThat(task.getAttemptCount()).isZero();
    }
  }

  @Test
  void lifecyclePathIsDeniedForEveryOverlapBeforeAndAfterHandoff() {
    Date now = new Date();
    NormalizedURL tablePath = NormalizedURL.from(tablePath("guarded"));
    UUID tableId = persistTombstone("guarded", tablePath.toString(), now, true);
    List<NormalizedURL> overlappingPaths =
        List.of(
            NormalizedURL.from(temporaryDirectory.toUri()),
            tablePath,
            NormalizedURL.from(tablePath + "/part-000.parquet"));

    overlappingPaths.forEach(this::assertPathDenied);
    assertThat(cleanupRepository.enqueueExpiredTables()).isOne();
    overlappingPaths.forEach(this::assertPathDenied);

    assertThat(cleanupRepository.claimNext("worker", Duration.ofSeconds(30))).isPresent();
    assertThat(cleanupRepository.complete(tableId, "worker")).isTrue();
    overlappingPaths.forEach(
        path ->
            assertThatCode(
                    () -> repositories.getExternalLocationUtils().validatePathNotBeingDeleted(path))
                .doesNotThrowAnyException());
  }

  @Test
  void cleanupCredentialSourceCannotBeRetargetedOrDeleted() {
    Date now = new Date();
    String credentialName = "cleanup_credential";
    String locationName = "cleanup_location";
    String location = NormalizedURL.from(temporaryDirectory.toUri()).toString();
    persistCredentialSource(credentialName, locationName, location, now);
    persistTombstone("protected", tablePath("protected"), now, true);

    assertCleanupSourceProtected(credentialName, locationName, location);
    assertThat(cleanupRepository.enqueueExpiredTables()).isOne();
    assertCleanupSourceProtected(credentialName, locationName, location);
  }

  @Test
  void newExternalLocationCannotOverlapCleanupStorage() {
    Date now = new Date();
    persistTombstone("new-location", tablePath("new-location"), now, true);
    CreateExternalLocation request =
        new CreateExternalLocation()
            .name("overlapping_location")
            .url(temporaryDirectory.toUri().toString())
            .credentialName("unused");

    assertFailedPrecondition(
        () -> repositories.getExternalLocationRepository().addExternalLocation(request));
    assertThat(cleanupRepository.enqueueExpiredTables()).isOne();
    assertFailedPrecondition(
        () -> repositories.getExternalLocationRepository().addExternalLocation(request));
  }

  @Test
  void committedStagingRowIsNeverAStandaloneCredentialTarget() {
    UUID tableId = UUID.randomUUID();
    Date now = new Date();
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          session.persist(
              StagingTableDAO.builder()
                  .id(tableId)
                  .schemaId(schemaId)
                  .name("committed")
                  .stagingLocation(tablePath("committed"))
                  .createdAt(now)
                  .accessedAt(now)
                  .stageCommitted(true)
                  .stageCommittedAt(now)
                  .build());
          return null;
        },
        "Failed to persist committed staging row",
        /* readOnly = */ false);

    assertTableNotFound(
        () -> repositories.getTableRepository().getStorageLocationForTableOrStagingTable(tableId));
    assertTableNotFound(
        () ->
            repositories.getTableRepository().getCatalogSchemaIdsByTableOrStagingTableId(tableId));
  }

  private void expireLease(UUID tableId) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          TableCleanupTaskDAO task = session.get(TableCleanupTaskDAO.class, tableId);
          task.setLeaseExpiresAt(Date.from(DatabaseTime.now(session).toInstant().minusSeconds(1)));
          return null;
        },
        "Failed to expire test lease",
        /* readOnly = */ false);
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

  private void persistCredentialSource(
      String credentialName, String locationName, String location, Date now) {
    CredentialDAO credential =
        CredentialDAO.from(
            new CreateCredentialRequest()
                .name(credentialName)
                .purpose(CredentialPurpose.STORAGE)
                .awsIamRole(
                    new AwsIamRoleRequest().roleArn("arn:aws:iam::123456789012:role/cleanup")),
            "test");
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          session.persist(credential);
          session.persist(
              ExternalLocationDAO.builder()
                  .id(UUID.randomUUID())
                  .name(locationName)
                  .url(location)
                  .credentialId(credential.getId())
                  .createdAt(now)
                  .build());
          return null;
        },
        "Failed to persist cleanup credential source",
        /* readOnly = */ false);
  }

  private void assertCleanupSourceProtected(
      String credentialName, String locationName, String location) {
    assertFailedPrecondition(
        () ->
            repositories
                .getExternalLocationRepository()
                .updateExternalLocation(locationName, new UpdateExternalLocation().url(location)));
    assertFailedPrecondition(
        () ->
            repositories
                .getExternalLocationRepository()
                .deleteExternalLocation(locationName, true));
    assertFailedPrecondition(
        () -> repositories.getCredentialRepository().deleteCredential(credentialName, true));
  }

  private void assertPathDenied(NormalizedURL path) {
    assertThatThrownBy(
            () -> repositories.getExternalLocationUtils().validatePathNotBeingDeleted(path))
        .isInstanceOf(BaseException.class)
        .extracting(error -> ((BaseException) error).getErrorCode())
        .isEqualTo(ErrorCode.PERMISSION_DENIED);
  }

  private static void assertTableNotFound(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BaseException.class)
        .extracting(error -> ((BaseException) error).getErrorCode())
        .isEqualTo(ErrorCode.TABLE_NOT_FOUND);
  }

  private static void assertFailedPrecondition(Runnable action) {
    assertThatThrownBy(() -> runWithRequestContext(action))
        .isInstanceOf(BaseException.class)
        .extracting(error -> ((BaseException) error).getErrorCode())
        .isEqualTo(ErrorCode.FAILED_PRECONDITION);
  }

  private static void runWithRequestContext(Runnable action) {
    ServiceRequestContext context =
        ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/")).build();
    try (var ignored = context.push()) {
      action.run();
    }
  }
}
