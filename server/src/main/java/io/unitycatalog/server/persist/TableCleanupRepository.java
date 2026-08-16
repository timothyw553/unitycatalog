package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.dao.TableCleanupTaskDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.utils.DatabaseTime;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.NormalizedURL;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persistence boundary for managed-table cleanup handoff, leasing, and completion. */
public class TableCleanupRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(TableCleanupRepository.class);
  private static final int ENQUEUE_BATCH_SIZE = 100;
  private static final int CLAIM_CANDIDATE_COUNT = 10;

  private final Repositories repositories;
  private final SessionFactory sessionFactory;

  public TableCleanupRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.repositories = repositories;
    this.sessionFactory = sessionFactory;
  }

  /** Immutable task projection safe to use after the claiming transaction closes. */
  public record CleanupTask(
      UUID tableId, UUID schemaId, String storageLocation, int attemptCount) {}

  /**
   * Hands eligible tombstones to durable cleanup. Each table uses its own transaction so one
   * corrupt row cannot roll back or starve the rest of the batch.
   */
  public int enqueueExpiredTables() {
    int enqueued = 0;
    for (UUID tableId : findExpiredTableIds()) {
      try {
        if (enqueueExpiredTable(tableId)) {
          enqueued++;
        }
      } catch (RuntimeException e) {
        LOGGER.error(
            "Failed to enqueue managed table {} for cleanup with {}",
            tableId,
            e.getClass().getSimpleName());
      }
    }
    return enqueued;
  }

  private List<UUID> findExpiredTableIds() {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          Query<UUID> query =
              session.createQuery(
                  "SELECT t.id FROM TableInfoDAO t"
                      + " WHERE t.type = :managedType"
                      + " AND t.deletedAt IS NOT NULL"
                      + " AND t.purgeAfter <= CURRENT_TIMESTAMP"
                      + " ORDER BY t.purgeAfter, t.id",
                  UUID.class);
          query.setParameter("managedType", TableType.MANAGED.getValue());
          query.setMaxResults(ENQUEUE_BATCH_SIZE);
          return query.getResultList();
        },
        "Failed to find expired managed tables",
        /* readOnly = */ true);
  }

  private boolean enqueueExpiredTable(UUID tableId) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          TableInfoDAO table =
              session.find(TableInfoDAO.class, tableId, LockModeType.PESSIMISTIC_WRITE);
          if (table == null) {
            return false;
          }
          Date now = DatabaseTime.now(session);
          if (!TableType.MANAGED.getValue().equals(table.getType())
              || table.getDeletedAt() == null
              || table.getPurgeAfter() == null
              || table.getPurgeAfter().after(now)) {
            return false;
          }
          persistTask(session, table, now, now);
          TableMetadataPurger.purge(repositories, session, table);
          return true;
        },
        "Failed to enqueue managed table " + tableId,
        /* readOnly = */ false);
  }

  /** Atomically captures managed storage before a parent cascade removes table metadata. */
  void enqueueForCleanup(Session session, TableInfoDAO table, Duration delay) {
    if (session.get(TableCleanupTaskDAO.class, table.getId()) == null) {
      Date now = DatabaseTime.now(session);
      persistTask(session, table, now, Date.from(now.toInstant().plus(delay)));
    }
    TableMetadataPurger.purge(repositories, session, table);
  }

  private static void persistTask(
      Session session, TableInfoDAO table, Date createdAt, Date nextAttemptAt) {
    // Normalize before metadata is removed so malformed locations fail the handoff transaction.
    NormalizedURL storageLocation = NormalizedURL.from(table.getUrl());
    if (storageLocation.isStorageRoot()) {
      throw new BaseException(
          ErrorCode.DATA_LOSS, "Managed table storage location resolves to a storage root");
    }
    session.persist(
        TableCleanupTaskDAO.builder()
            .tableId(table.getId())
            .schemaId(table.getSchemaId())
            .storageLocation(storageLocation.toString())
            .createdAt(createdAt)
            .nextAttemptAt(nextAttemptAt)
            .attemptCount(0)
            .build());
  }

  /** Claims one available task with an expiring database lease. */
  public Optional<CleanupTask> claimNext(String workerId, Duration leaseDuration) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          Date now = DatabaseTime.now(session);
          Date leaseExpiresAt = Date.from(now.toInstant().plus(leaseDuration));
          Query<UUID> candidates =
              session.createQuery(
                  "SELECT t.tableId FROM TableCleanupTaskDAO t"
                      + " WHERE t.nextAttemptAt <= :now"
                      + " AND (t.leaseExpiresAt IS NULL OR t.leaseExpiresAt <= :now)"
                      + " ORDER BY t.nextAttemptAt, t.createdAt, t.tableId",
                  UUID.class);
          candidates.setParameter("now", now);
          candidates.setMaxResults(CLAIM_CANDIDATE_COUNT);

          for (UUID tableId : candidates.getResultList()) {
            int claimed =
                session
                    .createMutationQuery(
                        "UPDATE TableCleanupTaskDAO t"
                            + " SET t.leaseOwner = :workerId, t.leaseExpiresAt = :leaseExpiresAt"
                            + " WHERE t.tableId = :tableId"
                            + " AND t.nextAttemptAt <= :now"
                            + " AND (t.leaseExpiresAt IS NULL OR t.leaseExpiresAt <= :now)")
                    .setParameter("workerId", workerId)
                    .setParameter("leaseExpiresAt", leaseExpiresAt)
                    .setParameter("tableId", tableId)
                    .setParameter("now", now)
                    .executeUpdate();
            if (claimed == 1) {
              return Optional.of(toCleanupTask(session.get(TableCleanupTaskDAO.class, tableId)));
            }
          }
          return Optional.empty();
        },
        "Failed to claim managed table cleanup task",
        /* readOnly = */ false);
  }

  private static CleanupTask toCleanupTask(TableCleanupTaskDAO task) {
    return new CleanupTask(
        task.getTableId(), task.getSchemaId(), task.getStorageLocation(), task.getAttemptCount());
  }

  /** Extends a claim before another bounded delete request. */
  public boolean renewLease(UUID tableId, String workerId, Duration leaseDuration) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          Date now = DatabaseTime.now(session);
          Date leaseExpiresAt = Date.from(now.toInstant().plus(leaseDuration));
          return session
                  .createMutationQuery(
                      "UPDATE TableCleanupTaskDAO t SET t.leaseExpiresAt = :leaseExpiresAt"
                          + " WHERE t.tableId = :tableId AND t.leaseOwner = :workerId"
                          + " AND t.leaseExpiresAt > :now")
                  .setParameter("leaseExpiresAt", leaseExpiresAt)
                  .setParameter("tableId", tableId)
                  .setParameter("workerId", workerId)
                  .setParameter("now", now)
                  .executeUpdate()
              == 1;
        },
        "Failed to renew managed table cleanup lease " + tableId,
        /* readOnly = */ false);
  }

  /** Releases an incomplete task so another table gets the next worker slice. */
  public boolean reschedule(UUID tableId, String workerId, Duration delay) {
    return releaseClaim(tableId, workerId, delay, null);
  }

  /** Records a failed attempt without persisting a potentially credential-bearing error message. */
  public boolean recordFailure(UUID tableId, String workerId, Duration delay, Throwable failure) {
    return releaseClaim(tableId, workerId, delay, failure.getClass().getSimpleName());
  }

  private boolean releaseClaim(UUID tableId, String workerId, Duration delay, String failureClass) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          Date now = DatabaseTime.now(session);
          Date nextAttemptAt = Date.from(now.toInstant().plus(delay));
          String failureUpdate =
              failureClass == null
                  ? ""
                  : ", t.attemptCount = t.attemptCount + 1, t.lastFailure = :lastFailure";
          var update =
              session.createMutationQuery(
                  "UPDATE TableCleanupTaskDAO t"
                      + " SET t.leaseOwner = NULL, t.leaseExpiresAt = NULL,"
                      + " t.nextAttemptAt = :nextAttemptAt"
                      + failureUpdate
                      + " WHERE t.tableId = :tableId AND t.leaseOwner = :workerId"
                      + " AND t.leaseExpiresAt > :now");
          update.setParameter("nextAttemptAt", nextAttemptAt);
          update.setParameter("tableId", tableId);
          update.setParameter("workerId", workerId);
          update.setParameter("now", now);
          if (failureClass != null) {
            update.setParameter("lastFailure", failureClass);
          }
          return update.executeUpdate() == 1;
        },
        "Failed to release managed table cleanup task " + tableId,
        /* readOnly = */ false);
  }

  /** Removes a completed task only while the caller still owns its lease. */
  public boolean complete(UUID tableId, String workerId) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          TableCleanupTaskDAO task =
              session.find(TableCleanupTaskDAO.class, tableId, LockModeType.PESSIMISTIC_WRITE);
          if (task == null) {
            return false;
          }
          Date now = DatabaseTime.now(session);
          if (!workerId.equals(task.getLeaseOwner())
              || task.getLeaseExpiresAt() == null
              || !task.getLeaseExpiresAt().after(now)) {
            return false;
          }
          session.remove(task);
          return true;
        },
        "Failed to complete managed table cleanup task " + tableId,
        /* readOnly = */ false);
  }
}
