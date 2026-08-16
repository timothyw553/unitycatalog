package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.dao.TableCleanupTaskDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.NormalizedURL;
import jakarta.persistence.LockModeType;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persistence boundary for atomically handing expired managed tables to durable cleanup. */
public class TableCleanupRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(TableCleanupRepository.class);
  private static final int ENQUEUE_BATCH_SIZE = 100;

  private final Repositories repositories;
  private final SessionFactory sessionFactory;

  public TableCleanupRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.repositories = repositories;
    this.sessionFactory = sessionFactory;
  }

  /**
   * Hands eligible tombstones to durable cleanup. Each table uses its own transaction so one
   * corrupt row cannot roll back or starve the rest of the batch.
   */
  public int enqueueExpiredTables(Date now) {
    int enqueued = 0;
    for (UUID tableId : findExpiredTableIds(now)) {
      try {
        if (enqueueExpiredTable(tableId, now)) {
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

  private List<UUID> findExpiredTableIds(Date now) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          Query<UUID> query =
              session.createQuery(
                  "SELECT t.id FROM TableInfoDAO t"
                      + " WHERE t.type = :managedType"
                      + " AND t.deletedAt IS NOT NULL"
                      + " AND t.purgeAfter <= :now"
                      + " ORDER BY t.purgeAfter, t.id",
                  UUID.class);
          query.setParameter("managedType", TableType.MANAGED.getValue());
          query.setParameter("now", now);
          query.setMaxResults(ENQUEUE_BATCH_SIZE);
          return query.getResultList();
        },
        "Failed to find expired managed tables",
        /* readOnly = */ true);
  }

  private boolean enqueueExpiredTable(UUID tableId, Date now) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          TableInfoDAO table =
              session.find(TableInfoDAO.class, tableId, LockModeType.PESSIMISTIC_WRITE);
          if (table == null) {
            return false;
          }
          if (!TableType.MANAGED.getValue().equals(table.getType())
              || table.getDeletedAt() == null
              || table.getPurgeAfter() == null
              || table.getPurgeAfter().after(now)) {
            return false;
          }
          persistTask(session, table, now);
          TableMetadataPurger.purge(repositories, session, table);
          return true;
        },
        "Failed to enqueue managed table " + tableId,
        /* readOnly = */ false);
  }

  /** Atomically captures managed storage before a parent cascade removes table metadata. */
  void enqueueForImmediateCleanup(Session session, TableInfoDAO table, Date now) {
    if (session.get(TableCleanupTaskDAO.class, table.getId()) == null) {
      persistTask(session, table, now);
    }
    TableMetadataPurger.purge(repositories, session, table);
  }

  private static void persistTask(Session session, TableInfoDAO table, Date now) {
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
            .createdAt(now)
            .nextAttemptAt(now)
            .attemptCount(0)
            .build());
  }
}
