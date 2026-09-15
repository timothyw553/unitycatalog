package io.unitycatalog.server.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.unitycatalog.server.persist.StorageCleanupTaskRepository;
import io.unitycatalog.server.persist.StorageCleanupTaskRepository.Claim;
import io.unitycatalog.server.persist.StorageCleanupTaskRepository.CleanupFailureReport;
import io.unitycatalog.server.persist.dao.StorageCleanupTaskDAO.ResourceType;
import io.unitycatalog.server.persist.utils.FileOperations;
import io.unitycatalog.server.utils.NormalizedURL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.SupportsPrefixOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorageCleanupWorkerTest {
  private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
  private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration INITIAL_DELAY = Duration.ofHours(1);
  private static final Duration RETRY_BACKOFF = Duration.ofMinutes(1);
  private static final UUID RESOURCE_ID = UUID.randomUUID();
  private static final UUID LEASE_TOKEN = UUID.randomUUID();
  private static final NormalizedURL LOCATION =
      NormalizedURL.from("s3://bucket/tables/" + RESOURCE_ID);
  private static final String PREFIX = LOCATION + "/";

  private final StorageCleanupTaskRepository taskRepository =
      mock(StorageCleanupTaskRepository.class);
  private final FileOperations fileOperations = mock(FileOperations.class);
  private final ExecutorService executor = mock(ExecutorService.class);
  private final SupportsPrefixOperations fileIO = mock(SupportsPrefixOperations.class);

  @BeforeEach
  void setUp() {
    setClaim(ResourceType.TABLE, LOCATION.toString());
    when(fileOperations.getCleanupFileIO(LOCATION, SOCKET_TIMEOUT)).thenReturn(fileIO);
    when(fileIO.listPrefix(PREFIX)).thenReturn(List.of());
    when(executor.submit(any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              CompletableFuture<Void> result = new CompletableFuture<>();
              try {
                invocation.getArgument(0, Runnable.class).run();
                result.complete(null);
              } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
              }
              return result;
            });
  }

  @Test
  void returnsWithoutWorkWhenClaimIsEmpty() {
    when(taskRepository.claim(LEASE_DURATION, INITIAL_DELAY)).thenReturn(Optional.empty());

    assertThat(worker().runOnce()).isFalse();

    verifyNoInteractions(fileOperations, executor);
  }

  @Test
  void deletesPrefixAndVerifiesItIsEmpty() {
    when(fileIO.listPrefix(PREFIX)).thenReturn(List.of());

    assertThat(worker().runOnce()).isTrue();

    verify(fileIO).deletePrefix(PREFIX);
    verify(fileIO).listPrefix(PREFIX);
    verify(fileIO).close();
    verify(taskRepository).finish(RESOURCE_ID, LEASE_TOKEN);
    verify(taskRepository, never()).reportFailure(any(), any(), any());
  }

  @Test
  void retriesWhenFilesRemain() {
    when(fileIO.listPrefix(PREFIX)).thenReturn(List.of(new FileInfo(PREFIX + "remaining", 1, 1)));

    assertThat(worker().runOnce()).isTrue();

    verifyFailure("Storage cleanup failed: IllegalStateException");
  }

  @Test
  void retriesStorageFailureWithoutItsMessage() {
    doThrow(new IllegalArgumentException("secret")).when(fileIO).deletePrefix(PREFIX);

    assertThat(worker().runOnce()).isTrue();

    verifyFailure("Storage cleanup failed: IllegalArgumentException");
  }

  @Test
  void cancelsAndRetriesTimedOutDeletion() throws Exception {
    Future<?> deletion = mock(Future.class);
    doReturn(deletion).when(executor).submit(any(Runnable.class));
    when(deletion.get(ATTEMPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
        .thenThrow(new TimeoutException());

    assertThat(worker().runOnce()).isTrue();

    verify(deletion).cancel(true);
    verify(fileIO, never()).listPrefix(any());
    verifyFailure("Storage cleanup failed: TimeoutException");
  }

  @Test
  void cancelsAndRestoresInterrupt() throws Exception {
    Future<?> deletion = mock(Future.class);
    doReturn(deletion).when(executor).submit(any(Runnable.class));
    when(deletion.get(ATTEMPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
        .thenThrow(new InterruptedException());

    try {
      assertThat(worker().runOnce()).isTrue();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    verify(deletion).cancel(true);
    verifyFailure("Storage cleanup failed: InterruptedException");
  }

  @Test
  void validatesTaskBeforeCreatingFileIO() {
    setClaim(ResourceType.TABLE, "s3://bucket/volumes/" + RESOURCE_ID);

    assertThat(worker().runOnce()).isTrue();

    verifyNoInteractions(fileOperations, executor);
    verifyFailure("Storage cleanup failed: IllegalArgumentException");
  }

  @Test
  void closesItsBoundedExecutor() {
    worker().close();

    verify(executor).shutdownNow();
  }

  private StorageCleanupWorker worker() {
    return new StorageCleanupWorker(
        taskRepository,
        fileOperations,
        executor,
        LEASE_DURATION,
        SOCKET_TIMEOUT,
        ATTEMPT_TIMEOUT,
        INITIAL_DELAY,
        RETRY_BACKOFF);
  }

  private void setClaim(ResourceType type, String location) {
    when(taskRepository.claim(LEASE_DURATION, INITIAL_DELAY))
        .thenReturn(Optional.of(new Claim(type, RESOURCE_ID, location, LEASE_TOKEN)));
  }

  private void verifyFailure(String error) {
    verify(taskRepository)
        .reportFailure(RESOURCE_ID, LEASE_TOKEN, new CleanupFailureReport(error, RETRY_BACKOFF));
    verify(taskRepository, never()).finish(eq(RESOURCE_ID), eq(LEASE_TOKEN));
  }
}
