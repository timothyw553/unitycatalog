package io.unitycatalog.server.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StorageCleanupPollerTest {
  private static final Duration INTERVAL = Duration.ofMillis(1);

  private final StorageCleanupWorker worker = mock(StorageCleanupWorker.class);
  private final StorageCleanupPoller poller = new StorageCleanupPoller(worker);

  @AfterEach
  void closePoller() {
    poller.close();
  }

  @Test
  void startsPollsAndCloses() throws Exception {
    CountDownLatch polled = new CountDownLatch(1);
    when(worker.runOnce())
        .thenAnswer(
            ignored -> {
              polled.countDown();
              return false;
            });

    poller.start(INTERVAL);
    poller.start(INTERVAL);

    assertThat(polled.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(poller.isRunning()).isTrue();
    poller.close();
    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void retriesAfterUnexpectedPollFailure() throws Exception {
    CountDownLatch recovered = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(worker.runOnce())
        .thenAnswer(
            ignored -> {
              if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("first poll failed");
              }
              recovered.countDown();
              return false;
            });

    poller.start(INTERVAL);

    assertThat(recovered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(calls).hasValueGreaterThanOrEqualTo(2);
  }

  @Test
  void doesNotOverlapPolls() throws Exception {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondFinished = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximumActive = new AtomicInteger();
    when(worker.runOnce())
        .thenAnswer(
            ignored -> {
              int call = calls.incrementAndGet();
              maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
              try {
                if (call == 1) {
                  firstStarted.countDown();
                  releaseFirst.await();
                } else {
                  secondFinished.countDown();
                }
                return false;
              } finally {
                active.decrementAndGet();
              }
            });

    poller.start(INTERVAL);
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
    releaseFirst.countDown();

    assertThat(secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(maximumActive).hasValue(1);
  }

  @Test
  void rejectsSubMillisecondIntervals() {
    for (Duration interval : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1))) {
      assertThatThrownBy(() -> poller.start(interval))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Cleanup poll interval must be at least one millisecond");
    }
    assertThat(poller.isRunning()).isFalse();
  }
}
