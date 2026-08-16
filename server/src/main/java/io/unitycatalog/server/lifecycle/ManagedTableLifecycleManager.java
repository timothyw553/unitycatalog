package io.unitycatalog.server.lifecycle;

import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.persist.TableCleanupRepository;
import io.unitycatalog.server.persist.utils.FileOperations;
import io.unitycatalog.server.utils.ServerProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs one tombstone scanner and a bounded pool of continuously draining cleanup workers. */
public final class ManagedTableLifecycleManager implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ManagedTableLifecycleManager.class);
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

  private final TableCleanupRepository cleanupRepository;
  private final ManagedTableCleanupWorker worker;
  private final boolean enabled;
  private final Duration pollInterval;
  private final int workerCount;

  private RunContext activeRun;

  public ManagedTableLifecycleManager(
      TableCleanupRepository cleanupRepository,
      FileOperations fileOperations,
      UnityCatalogAuthorizer authorizer,
      ServerProperties serverProperties) {
    this.cleanupRepository = cleanupRepository;
    worker =
        new ManagedTableCleanupWorker(
            cleanupRepository, fileOperations, authorizer, serverProperties);
    enabled =
        serverProperties.isManagedTableLifecycleEnabled()
            && serverProperties.isManagedTableCleanupEnabled();
    pollInterval = serverProperties.getManagedTableCleanupPollInterval();
    workerCount = serverProperties.getManagedTableCleanupWorkerCount();
  }

  public synchronized void start() {
    if (!enabled || activeRun != null) {
      return;
    }
    RunContext run = new RunContext(workerCount);
    activeRun = run;
    run.scanner.scheduleWithFixedDelay(
        () -> scan(run), 0, Math.max(1, pollInterval.toMillis()), TimeUnit.MILLISECONDS);
    for (int i = 0; i < workerCount; i++) {
      String workerId = UUID.randomUUID().toString();
      run.workers.execute(() -> workerLoop(run, workerId));
    }
    LOGGER.info("Started managed table cleanup with {} workers", workerCount);
  }

  private void scan(RunContext run) {
    if (!run.active.get()) {
      return;
    }
    try {
      int enqueued = cleanupRepository.enqueueExpiredTables();
      if (enqueued > 0) {
        LOGGER.info("Enqueued {} managed tables for cleanup", enqueued);
      }
    } catch (RuntimeException e) {
      LOGGER.error("Failed to scan for expired managed tables", e);
    }
  }

  private void workerLoop(RunContext run, String workerId) {
    while (run.active.get() && !Thread.currentThread().isInterrupted()) {
      try {
        if (worker.processNext(workerId, run.active::get)) {
          continue;
        }
      } catch (RuntimeException e) {
        LOGGER.error("Failed to claim a managed table cleanup task", e);
      }
      awaitWork(run);
    }
  }

  private void awaitWork(RunContext run) {
    try {
      run.stopSignal.await(Math.max(1, pollInterval.toMillis()), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public synchronized void stop() {
    RunContext run = activeRun;
    activeRun = null;
    if (run == null) {
      return;
    }
    run.active.set(false);
    run.stopSignal.countDown();
    run.scanner.shutdownNow();
    run.workers.shutdownNow();
    awaitTermination(run.scanner);
    awaitTermination(run.workers);
    LOGGER.info("Stopped managed table cleanup");
  }

  private static void awaitTermination(ExecutorService executor) {
    try {
      if (!executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        LOGGER.warn("Managed table cleanup thread did not stop within {}", STOP_TIMEOUT);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    stop();
  }

  private static final class RunContext {
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final ScheduledExecutorService scanner =
        Executors.newSingleThreadScheduledExecutor(new LifecycleThreadFactory("scanner"));
    private final ExecutorService workers;

    private RunContext(int workerCount) {
      workers = Executors.newFixedThreadPool(workerCount, new LifecycleThreadFactory("worker"));
    }
  }

  private static final class LifecycleThreadFactory implements ThreadFactory {
    private final AtomicInteger threadNumber = new AtomicInteger();
    private final String role;

    private LifecycleThreadFactory(String role) {
      this.role = role;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread =
          new Thread(
              runnable, "managed-table-cleanup-" + role + "-" + threadNumber.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
