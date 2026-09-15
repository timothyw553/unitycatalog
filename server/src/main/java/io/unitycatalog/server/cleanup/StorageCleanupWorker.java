package io.unitycatalog.server.cleanup;

import io.unitycatalog.server.persist.StorageCleanupTaskRepository;
import io.unitycatalog.server.persist.StorageCleanupTaskRepository.Claim;
import io.unitycatalog.server.persist.StorageCleanupTaskRepository.CleanupFailureReport;
import io.unitycatalog.server.persist.dao.StorageCleanupTaskDAO.ResourceType;
import io.unitycatalog.server.persist.utils.FileOperations;
import io.unitycatalog.server.utils.NormalizedURL;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.SupportsPrefixOperations;

/** Claims and processes at most one ready storage cleanup task. */
public final class StorageCleanupWorker implements AutoCloseable {
  private static final int MAX_ERROR_LENGTH = 2048;

  private final StorageCleanupTaskRepository taskRepository;
  private final FileOperations fileOperations;
  private final ExecutorService executor;
  private final Duration leaseDuration;
  private final Duration socketTimeout;
  private final Duration attemptTimeout;
  private final Duration initialDelay;
  private final Duration retryBackoff;

  public StorageCleanupWorker(
      StorageCleanupTaskRepository taskRepository,
      FileOperations fileOperations,
      Duration leaseDuration,
      Duration socketTimeout,
      Duration attemptTimeout,
      Duration initialDelay,
      Duration retryBackoff) {
    this(
        taskRepository,
        fileOperations,
        newExecutor(),
        leaseDuration,
        socketTimeout,
        attemptTimeout,
        initialDelay,
        retryBackoff);
  }

  StorageCleanupWorker(
      StorageCleanupTaskRepository taskRepository,
      FileOperations fileOperations,
      ExecutorService executor,
      Duration leaseDuration,
      Duration socketTimeout,
      Duration attemptTimeout,
      Duration initialDelay,
      Duration retryBackoff) {
    this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
    this.fileOperations = Objects.requireNonNull(fileOperations, "fileOperations");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
    this.socketTimeout = Objects.requireNonNull(socketTimeout, "socketTimeout");
    this.attemptTimeout = Objects.requireNonNull(attemptTimeout, "attemptTimeout");
    this.initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
    this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
  }

  /** Returns whether this worker claimed a task. */
  public boolean runOnce() {
    Optional<Claim> claimed = taskRepository.claim(leaseDuration, initialDelay);
    if (claimed.isEmpty()) {
      return false;
    }

    Claim claim = claimed.get();
    try {
      cleanup(claim);
      taskRepository.finish(claim.resourceId(), claim.leaseToken());
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      taskRepository.reportFailure(
          claim.resourceId(),
          claim.leaseToken(),
          new CleanupFailureReport(safeError(exception), retryBackoff));
    }
    return true;
  }

  private void cleanup(Claim claim)
      throws InterruptedException, ExecutionException, TimeoutException {
    NormalizedURL location = validateTask(claim);
    String prefix = location + "/";
    try (SupportsPrefixOperations prefixOperations =
        fileOperations.getCleanupFileIO(location, socketTimeout)) {
      Future<?> deletion = executor.submit(() -> prefixOperations.deletePrefix(prefix));
      try {
        deletion.get(attemptTimeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException | TimeoutException exception) {
        deletion.cancel(true);
        throw exception;
      }
      Iterable<FileInfo> remaining = prefixOperations.listPrefix(prefix);
      if (remaining.iterator().hasNext()) {
        throw new IllegalStateException("Storage cleanup did not empty the task prefix");
      }
    }
  }

  private static NormalizedURL validateTask(Claim claim) {
    Objects.requireNonNull(claim, "claim");
    ResourceType resourceType = Objects.requireNonNull(claim.resourceType(), "resourceType");
    String segment =
        switch (resourceType) {
          case TABLE, STAGING_TABLE -> "tables";
          case VOLUME -> "volumes";
          case REGISTERED_MODEL -> "models";
          case MODEL_VERSION -> "versions";
        };
    NormalizedURL location = NormalizedURL.from(claim.storageLocation());
    String path = location.toUri().getPath();
    if (claim.resourceId() == null
        || path == null
        || !path.endsWith("/" + segment + "/" + claim.resourceId())) {
      throw new IllegalArgumentException(
          "Cleanup task location does not match its " + resourceType + " resource id");
    }
    return location;
  }

  private static String safeError(Exception exception) {
    Throwable failure =
        exception instanceof ExecutionException && exception.getCause() != null
            ? exception.getCause()
            : exception;
    String type = failure.getClass().getSimpleName();
    String error = "Storage cleanup failed" + (type.isEmpty() ? "" : ": " + type);
    return error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
  }

  private static ExecutorService newExecutor() {
    return new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),
        runnable -> {
          Thread thread = new Thread(runnable, "uc-storage-cleanup");
          thread.setDaemon(true);
          return thread;
        });
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
