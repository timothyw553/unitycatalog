package io.unitycatalog.server.lifecycle;

import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.persist.TableCleanupRepository;
import io.unitycatalog.server.persist.TableCleanupRepository.CleanupTask;
import io.unitycatalog.server.persist.utils.FileOperations;
import io.unitycatalog.server.persist.utils.FileOperations.DeleteBatchResult;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Processes one leased cleanup task at a time, independently of thread scheduling. */
final class ManagedTableCleanupWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(ManagedTableCleanupWorker.class);
  private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
  private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

  private final TableCleanupRepository cleanupRepository;
  private final FileOperations fileOperations;
  private final UnityCatalogAuthorizer authorizer;
  private final Duration pollInterval;
  private final Duration sliceDuration;
  private final int deleteBatchSize;

  ManagedTableCleanupWorker(
      TableCleanupRepository cleanupRepository,
      FileOperations fileOperations,
      UnityCatalogAuthorizer authorizer,
      ServerProperties serverProperties) {
    this.cleanupRepository = cleanupRepository;
    this.fileOperations = fileOperations;
    this.authorizer = authorizer;
    pollInterval = serverProperties.getManagedTableCleanupPollInterval();
    sliceDuration = serverProperties.getManagedTableCleanupSliceDuration();
    deleteBatchSize = serverProperties.getManagedTableCleanupBatchSize();
  }

  /**
   * Returns false only when no task was ready, allowing the caller to wait before polling again.
   */
  boolean processNext(String workerId, BooleanSupplier active) {
    Date now = new Date();
    Optional<CleanupTask> claimed =
        cleanupRepository.claimNext(workerId, now, Date.from(now.toInstant().plus(LEASE_DURATION)));
    claimed.ifPresent(task -> processClaimedTask(workerId, task, active));
    return claimed.isPresent();
  }

  private void processClaimedTask(String workerId, CleanupTask task, BooleanSupplier active) {
    long deadlineNanos = System.nanoTime() + sliceDuration.toNanos();
    try {
      NormalizedURL storageLocation = NormalizedURL.from(task.storageLocation());
      while (active.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
        Date now = new Date();
        if (!cleanupRepository.renewLease(
            task.tableId(), workerId, now, Date.from(now.toInstant().plus(LEASE_DURATION)))) {
          logLostLease(task);
          return;
        }

        DeleteBatchResult result = fileOperations.deleteBatch(storageLocation, deleteBatchSize);
        if (!active.getAsBoolean()) {
          releaseAfterStop(task, workerId);
          return;
        }
        if (result == DeleteBatchResult.COMPLETE) {
          Date completionTime = new Date();
          if (!cleanupRepository.renewLease(
              task.tableId(),
              workerId,
              completionTime,
              Date.from(completionTime.toInstant().plus(LEASE_DURATION)))) {
            logLostLease(task);
            return;
          }
          clearAuthorizations(task);
          if (cleanupRepository.complete(task.tableId(), workerId, new Date())) {
            LOGGER.info("Completed managed table cleanup for {}", task.tableId());
          } else {
            logLostLease(task);
          }
          return;
        }
        if (System.nanoTime() >= deadlineNanos) {
          Date rescheduleTime = new Date();
          if (!cleanupRepository.reschedule(
              task.tableId(),
              workerId,
              rescheduleTime,
              Date.from(rescheduleTime.toInstant().plus(pollInterval)))) {
            logLostLease(task);
          }
          return;
        }
      }
      releaseAfterStop(task, workerId);
    } catch (RuntimeException e) {
      if (active.getAsBoolean()) {
        recordFailure(task, workerId, e);
      } else {
        releaseAfterStop(task, workerId);
      }
    }
  }

  private void recordFailure(CleanupTask task, String workerId, RuntimeException failure) {
    Duration retryDelay = retryDelay(task.attemptCount());
    try {
      Date failureTime = new Date();
      if (!cleanupRepository.recordFailure(
          task.tableId(),
          workerId,
          failureTime,
          Date.from(failureTime.toInstant().plus(retryDelay)),
          failure)) {
        logLostLease(task);
        return;
      }
      LOGGER.warn(
          "Managed table cleanup failed for {} with {}; retrying in {}",
          task.tableId(),
          failure.getClass().getSimpleName(),
          retryDelay);
    } catch (RuntimeException releaseFailure) {
      LOGGER.error(
          "Failed to release managed table cleanup task {} with {}",
          task.tableId(),
          releaseFailure.getClass().getSimpleName());
    }
  }

  private void releaseAfterStop(CleanupTask task, String workerId) {
    try {
      Date now = new Date();
      if (!cleanupRepository.reschedule(task.tableId(), workerId, now, now)) {
        logLostLease(task);
      }
    } catch (RuntimeException e) {
      LOGGER.warn(
          "Failed to release managed table cleanup task {} during shutdown with {}",
          task.tableId(),
          e.getClass().getSimpleName());
    }
  }

  private void clearAuthorizations(CleanupTask task) {
    authorizer.clearAuthorizationsForResource(task.tableId());
    authorizer.removeHierarchyChild(task.schemaId(), task.tableId());
  }

  private static void logLostLease(CleanupTask task) {
    LOGGER.info("Lost managed table cleanup lease for {}", task.tableId());
  }

  private static Duration retryDelay(int priorAttemptCount) {
    long exponentialSeconds = 1L << Math.min(priorAttemptCount, 30);
    return Duration.ofSeconds(Math.min(exponentialSeconds, MAX_RETRY_DELAY.toSeconds()));
  }
}
