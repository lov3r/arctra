package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.checkpoint.CheckpointAlreadyExistsException;
import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.checkpoint.SuspensionCheckpoint;
import cn.bitcss.arctra.evidence.Evidence;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * Integration tests for {@link JdbcCheckpointStore}.
 *
 * <p>Uses H2 embedded database to prove JDBC persistence behavior.
 *
 * @author lov3r
 * @since M6-T4E
 */
@DisplayName("JdbcCheckpointStore")
class JdbcCheckpointStoreTest {

  private EmbeddedDatabase database;
  private JdbcCheckpointStore store;

  @BeforeEach
  void setUp() {
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("jdbc-durable-recovery-schema.sql")
            .build();

    store = new JdbcCheckpointStore(database);
  }

  @AfterEach
  void tearDown() {
    if (database != null) {
      database.shutdown();
    }
  }

  @Test
  @DisplayName("Create and load checkpoint")
  void createAndLoad() {
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-123",
            1L,
            "test-key",
            "session-abc",
            List.of(new PendingToolCall("op-1", "tc-1", "query_logs", "{}")),
            List.of(new Evidence("tool-x", "result")));

    store.create(checkpoint);

    Optional<SuspensionCheckpoint> loaded = store.load("proc-123");

    assertThat(loaded).isPresent();
    assertThat(loaded.get()).isEqualTo(checkpoint);
  }

  @Test
  @DisplayName("Load non-existent checkpoint returns empty")
  void loadNonExistent() {
    Optional<SuspensionCheckpoint> loaded = store.load("unknown-process");

    assertThat(loaded).isEmpty();
  }

  @Test
  @DisplayName("Create duplicate processId throws CheckpointAlreadyExistsException")
  void createDuplicateProcessId() {
    SuspensionCheckpoint checkpoint1 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-dup",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    store.create(checkpoint1);

    SuspensionCheckpoint checkpoint2 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-dup", // same processId
            2L, // different version
            "key",
            "session",
            List.of(new PendingToolCall("op-2", "tc-2", "tool", "{}")),
            List.of());

    assertThatThrownBy(() -> store.create(checkpoint2))
        .isInstanceOf(CheckpointAlreadyExistsException.class)
        .hasMessageContaining("proc-dup");
  }

  @Test
  @DisplayName("Preserve nullable sessionId")
  void nullableSessionId() {
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-null-session",
            1L,
            "key",
            null, // null sessionId
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    store.create(checkpoint);

    Optional<SuspensionCheckpoint> loaded = store.load("proc-null-session");

    assertThat(loaded).isPresent();
    assertThat(loaded.get().sessionId()).isNull();
  }

  @Test
  @DisplayName("Restart simulation: store instance A creates, instance B loads")
  void restartSimulation() {
    // Simulate store instance A
    JdbcCheckpointStore storeA = new JdbcCheckpointStore(database);

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-restart",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    storeA.create(checkpoint);

    // Discard store instance A (simulate JVM restart)
    storeA = null;

    // Create new store instance B (simulates new JVM)
    JdbcCheckpointStore storeB = new JdbcCheckpointStore(database);

    Optional<SuspensionCheckpoint> loaded = storeB.load("proc-restart");

    assertThat(loaded).isPresent();
    assertThat(loaded.get()).isEqualTo(checkpoint);
  }

  @Test
  @DisplayName("replaceIfVersion with matching version succeeds")
  void replaceIfVersionSuccess() {
    SuspensionCheckpoint v1 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-replace",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    store.create(v1);

    SuspensionCheckpoint v2 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-replace",
            2L, // incremented version
            "key",
            "session",
            List.of(new PendingToolCall("op-2", "tc-2", "tool", "{}")),
            List.of());

    boolean replaced = store.replaceIfVersion("proc-replace", 1L, v2);

    assertThat(replaced).isTrue();

    Optional<SuspensionCheckpoint> loaded = store.load("proc-replace");
    assertThat(loaded).isPresent();
    assertThat(loaded.get().checkpointVersion()).isEqualTo(2L);
  }

  @Test
  @DisplayName("replaceIfVersion with stale version fails")
  void replaceIfVersionStale() {
    SuspensionCheckpoint v1 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-stale",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    store.create(v1);

    SuspensionCheckpoint v2 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-stale",
            2L,
            "key",
            "session",
            List.of(new PendingToolCall("op-2", "tc-2", "tool", "{}")),
            List.of());

    // Replace v1 → v2
    store.replaceIfVersion("proc-stale", 1L, v2);

    // Attempt stale replace (v1 → v3)
    SuspensionCheckpoint v3 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-stale",
            3L,
            "key",
            "session",
            List.of(new PendingToolCall("op-3", "tc-3", "tool", "{}")),
            List.of());

    boolean replaced = store.replaceIfVersion("proc-stale", 1L, v3);

    assertThat(replaced).isFalse();

    // Checkpoint remains at v2
    Optional<SuspensionCheckpoint> loaded = store.load("proc-stale");
    assertThat(loaded).isPresent();
    assertThat(loaded.get().checkpointVersion()).isEqualTo(2L);
  }

  @Test
  @DisplayName("deleteIfVersion with matching version succeeds")
  void deleteIfVersionSuccess() {
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-delete",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    store.create(checkpoint);

    boolean deleted = store.deleteIfVersion("proc-delete", 1L);

    assertThat(deleted).isTrue();

    Optional<SuspensionCheckpoint> loaded = store.load("proc-delete");
    assertThat(loaded).isEmpty();
  }

  @Test
  @DisplayName("deleteIfVersion with stale version fails")
  void deleteIfVersionStale() {
    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-delete-stale",
            2L, // version 2
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    store.create(checkpoint);

    // Attempt delete with wrong version
    boolean deleted = store.deleteIfVersion("proc-delete-stale", 1L);

    assertThat(deleted).isFalse();

    // Checkpoint still exists
    Optional<SuspensionCheckpoint> loaded = store.load("proc-delete-stale");
    assertThat(loaded).isPresent();
  }

  @Test
  @DisplayName("Concurrent replaceIfVersion: exactly one succeeds")
  void concurrentReplaceExactlyOneSucceeds() throws Exception {
    SuspensionCheckpoint v1 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-concurrent",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    store.create(v1);

    // Create two independent store instances
    JdbcCheckpointStore storeA = new JdbcCheckpointStore(database);
    JdbcCheckpointStore storeB = new JdbcCheckpointStore(database);

    SuspensionCheckpoint v2 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-concurrent",
            2L,
            "key",
            "session",
            List.of(new PendingToolCall("op-2", "tc-2", "tool-a", "{}")),
            List.of());

    SuspensionCheckpoint v3 =
        new SuspensionCheckpoint(
            "1.0",
            "proc-concurrent",
            3L,
            "key",
            "session",
            List.of(new PendingToolCall("op-3", "tc-3", "tool-b", "{}")),
            List.of());

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(2);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(2);

    // Thread A
    executor.submit(
        () -> {
          try {
            startLatch.await();
            boolean result = storeA.replaceIfVersion("proc-concurrent", 1L, v2);
            if (result) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } catch (Exception e) {
            failureCount.incrementAndGet();
          } finally {
            doneLatch.countDown();
          }
        });

    // Thread B
    executor.submit(
        () -> {
          try {
            startLatch.await();
            boolean result = storeB.replaceIfVersion("proc-concurrent", 1L, v3);
            if (result) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
          } catch (Exception e) {
            failureCount.incrementAndGet();
          } finally {
            doneLatch.countDown();
          }
        });

    // Start both threads simultaneously
    startLatch.countDown();
    doneLatch.await(5, TimeUnit.SECONDS);

    executor.shutdown();

    // Exactly one CAS must succeed
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failureCount.get()).isEqualTo(1);

    // Final checkpoint is either v2 or v3
    Optional<SuspensionCheckpoint> loaded = store.load("proc-concurrent");
    assertThat(loaded).isPresent();
    assertThat(loaded.get().checkpointVersion()).isIn(2L, 3L);
  }

  @Test
  @DisplayName("Preserve operationId exactly across persistence")
  void operationIdPreserved() {
    String operationId = "op-uuid-exact-12345";

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-opid",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall(operationId, "tc-1", "tool", "{}")),
            List.of());

    store.create(checkpoint);

    Optional<SuspensionCheckpoint> loaded = store.load("proc-opid");

    assertThat(loaded).isPresent();
    assertThat(loaded.get().pendingBatch().get(0).operationId()).isEqualTo(operationId);
  }

  @Test
  @DisplayName("Preserve multiple pending operations with distinct operationIds")
  void multipleOperationIdsPreserved() {
    List<PendingToolCall> pending =
        List.of(
            new PendingToolCall("op-1", "tc-1", "tool-a", "{}"),
            new PendingToolCall("op-2", "tc-2", "tool-b", "{}"),
            new PendingToolCall("op-3", "tc-3", "tool-c", "{}"));

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint("1.0", "proc-multi-op", 1L, "key", "session", pending, List.of());

    store.create(checkpoint);

    Optional<SuspensionCheckpoint> loaded = store.load("proc-multi-op");

    assertThat(loaded).isPresent();
    assertThat(loaded.get().pendingBatch()).containsExactlyElementsOf(pending);
  }

  @Test
  @DisplayName("Cross-instance visibility after create")
  void crossInstanceVisibility() {
    JdbcCheckpointStore storeA = new JdbcCheckpointStore(database);
    JdbcCheckpointStore storeB = new JdbcCheckpointStore(database);

    SuspensionCheckpoint checkpoint =
        new SuspensionCheckpoint(
            "1.0",
            "proc-cross",
            1L,
            "key",
            "session",
            List.of(new PendingToolCall("op-1", "tc-1", "tool", "{}")),
            List.of());

    storeA.create(checkpoint);

    // Store B immediately reads (strong read-after-write)
    Optional<SuspensionCheckpoint> loaded = storeB.load("proc-cross");

    assertThat(loaded).isPresent();
    assertThat(loaded.get()).isEqualTo(checkpoint);
  }
}
