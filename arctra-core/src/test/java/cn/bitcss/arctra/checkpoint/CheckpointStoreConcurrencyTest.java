package cn.bitcss.arctra.checkpoint;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M5-T4 Phase 8: CheckpointStore concurrency atomicity tests.
 *
 * <p>Verifies that InMemoryCheckpointStore provides correct atomic CAS semantics under concurrent
 * operations. Tests the low-level store primitives independently of engine logic.
 *
 * @author lov3r
 * @since M5-T4 Phase 8
 */
@DisplayName("M5-T4 Phase 8: CheckpointStore Concurrency")
class CheckpointStoreConcurrencyTest {

  /**
   * replaceIfVersion: concurrent calls with same expected version → exactly one succeeds.
   */
  @Test
  @DisplayName("replaceIfVersion concurrent - exactly one success")
  void replaceIfVersion_concurrentExactlyOneSuccess() throws Exception {
    InMemoryCheckpointStore store = new InMemoryCheckpointStore();

    // Create initial checkpoint v1
    SuspensionCheckpoint initial =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            1L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-init", "toolInit", "{}")),
            List.of());
    store.create(initial);

    // Two replacement candidates (both targeting v1 → v2)
    SuspensionCheckpoint replacement1 =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            2L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-1", "toolA", "{}")),
            List.of());

    SuspensionCheckpoint replacement2 =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            2L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-2", "toolB", "{}")),
            List.of());

    // Concurrent execution
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    List<SuspensionCheckpoint> winnerCheckpoint = new ArrayList<>();

    Runnable replaceTask1 =
        () -> {
          try {
            barrier.await(); // Synchronize start
            boolean success = store.replaceIfVersion("P100", 1L, replacement1);
            if (success) {
              successCount.incrementAndGet();
              synchronized (winnerCheckpoint) {
                winnerCheckpoint.add(replacement1);
              }
            } else {
              failureCount.incrementAndGet();
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };

    Runnable replaceTask2 =
        () -> {
          try {
            barrier.await(); // Synchronize start
            boolean success = store.replaceIfVersion("P100", 1L, replacement2);
            if (success) {
              successCount.incrementAndGet();
              synchronized (winnerCheckpoint) {
                winnerCheckpoint.add(replacement2);
              }
            } else {
              failureCount.incrementAndGet();
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };

    Thread t1 = new Thread(replaceTask1);
    Thread t2 = new Thread(replaceTask2);
    t1.start();
    t2.start();
    t1.join();
    t2.join();

    // Assert: exactly one success, one failure
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failureCount.get()).isEqualTo(1);

    // Assert: store contains exactly one v2 checkpoint (the winner)
    SuspensionCheckpoint stored = store.load("P100").orElseThrow();
    assertThat(stored.checkpointVersion()).isEqualTo(2L);
    assertThat(winnerCheckpoint).hasSize(1);
    assertThat(stored).isEqualTo(winnerCheckpoint.get(0));
  }

  /**
   * deleteIfVersion: concurrent calls with same expected version → exactly one succeeds.
   */
  @Test
  @DisplayName("deleteIfVersion concurrent - exactly one success")
  void deleteIfVersion_concurrentExactlyOneSuccess() throws Exception {
    InMemoryCheckpointStore store = new InMemoryCheckpointStore();

    // Create initial checkpoint v1
    SuspensionCheckpoint initial =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            1L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-init", "toolInit", "{}")),
            List.of());
    store.create(initial);

    // Concurrent deletion
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    Runnable deleteTask =
        () -> {
          try {
            barrier.await(); // Synchronize start
            boolean success = store.deleteIfVersion("P100", 1L);
            if (success) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };

    Thread t1 = new Thread(deleteTask);
    Thread t2 = new Thread(deleteTask);
    t1.start();
    t2.start();
    t1.join();
    t2.join();

    // Assert: exactly one success, one failure
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failureCount.get()).isEqualTo(1);

    // Assert: checkpoint deleted
    assertThat(store.load("P100")).isEmpty();
  }

  /**
   * replaceIfVersion race with different expected versions → only matching version succeeds.
   */
  @Test
  @DisplayName("replaceIfVersion with stale version fails")
  void replaceIfVersion_staleVersionFails() throws Exception {
    InMemoryCheckpointStore store = new InMemoryCheckpointStore();

    // Create initial checkpoint v1
    SuspensionCheckpoint cp1 =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            1L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-init", "toolInit", "{}")),
            List.of());
    store.create(cp1);

    // One thread advances v1 → v2
    SuspensionCheckpoint cp2 =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            2L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("tc-2", "toolA", "{}")),
            List.of());

    // Another thread tries stale v1 → v2 (same expected v1)
    SuspensionCheckpoint cp2_stale =
        new SuspensionCheckpoint(
            SuspensionCheckpoint.CURRENT_SCHEMA_VERSION,
            "P100",
            2L,
            "test-key",
            "session-1",
            List.of(new PendingToolCall("stale", "stale", "{}")),
            List.of());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger t1Success = new AtomicInteger(0);
    AtomicInteger t2Success = new AtomicInteger(0);

    Thread t1 =
        new Thread(
            () -> {
              boolean success = store.replaceIfVersion("P100", 1L, cp2);
              if (success) t1Success.incrementAndGet();
              latch.countDown();
            });

    Thread t2 =
        new Thread(
            () -> {
              try {
                latch.await(); // Wait for t1 to complete
                boolean success = store.replaceIfVersion("P100", 1L, cp2_stale);
                if (success) t2Success.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    t1.start();
    t2.start();
    t1.join();
    t2.join();

    // Assert: only first succeeded, second failed (stale version)
    assertThat(t1Success.get()).isEqualTo(1);
    assertThat(t2Success.get()).isZero();

    // Assert: store contains first replacement (not stale)
    SuspensionCheckpoint stored = store.load("P100").orElseThrow();
    assertThat(stored).isEqualTo(cp2);
    assertThat(stored.pendingBatch().get(0).toolName()).isEqualTo("toolA"); // Not the stale one
  }
}
