package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.*;

import cn.bitcss.arctra.runtime.react.durable.AttemptIds;
import cn.bitcss.arctra.runtime.react.durable.InvocationAttempt;
import cn.bitcss.arctra.runtime.react.durable.JdbcInvocationStateStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * Integration tests for {@link JdbcInvocationStateStore}.
 *
 * <p>Uses H2 embedded database to prove JDBC invocation-intent persistence behavior.
 *
 * @author lov3r
 * @since M6-T4E
 */
@DisplayName("JdbcInvocationStateStore")
class JdbcInvocationStateStoreTest {

  private EmbeddedDatabase database;
  private JdbcInvocationStateStore store;

  @BeforeEach
  void setUp() {
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("jdbc-durable-recovery-schema.sql")
            .build();

    store = new JdbcInvocationStateStore(database);
  }

  @AfterEach
  void tearDown() {
    if (database != null) {
      database.shutdown();
    }
  }

  @Test
  @DisplayName("Record and check intent")
  void recordAndCheck() {
    store.recordInvocationIntent("proc-1", "op-A", "attempt-test");

    java.util.List<InvocationAttempt> attempts = store.findAttempts("proc-1", "op-A");

    assertThat(attempts).isNotEmpty();
    assertThat(attempts.get(0).attemptId()).isEqualTo("attempt-test");
  }

  @Test
  @DisplayName("Intent absent returns false")
  void intentAbsent() {
    java.util.List<InvocationAttempt> attempts = store.findAttempts("proc-1", "op-A");

    assertThat(attempts).isEmpty();
  }

  @Test
  @DisplayName("Process isolation: different processes have separate intents")
  void processIsolation() {
    store.recordInvocationIntent("proc-A", "op-X", "attempt-test");

    assertThat(store.findAttempts("proc-A", "op-X")).isNotEmpty();
    assertThat(store.findAttempts("proc-B", "op-X")).isEmpty();
  }

  @Test
  @DisplayName("Operation isolation: different operations within same process")
  void operationIsolation() {
    store.recordInvocationIntent("proc-1", "op-X", "attempt-test");

    assertThat(store.findAttempts("proc-1", "op-X")).isNotEmpty();
    assertThat(store.findAttempts("proc-1", "op-Y")).isEmpty();
  }

  @Test
  @DisplayName("Duplicate intent write is idempotent (both succeed)")
  void duplicateIntentIdempotent() {
    // First write
    assertThatNoException()
        .isThrownBy(() -> store.recordInvocationIntent("proc-dup", "op-dup", "attempt-test"));

    // Duplicate write - must succeed (idempotent)
    assertThatNoException()
        .isThrownBy(() -> store.recordInvocationIntent("proc-dup", "op-dup", "attempt-test"));

    // Intent exists
    assertThat(store.findAttempts("proc-dup", "op-dup")).isNotEmpty();
  }

  @Test
  @DisplayName("Concurrent duplicate intent writes: both succeed")
  void concurrentDuplicateIntentWrites() throws Exception {
    JdbcInvocationStateStore storeA = new JdbcInvocationStateStore(database);
    JdbcInvocationStateStore storeB = new JdbcInvocationStateStore(database);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(2);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger exceptionCount = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(2);

    // Thread A
    executor.submit(
        () -> {
          try {
            startLatch.await();
            storeA.recordInvocationIntent("proc-concurrent", "op-concurrent", "attempt-test");
            successCount.incrementAndGet();
          } catch (Exception e) {
            exceptionCount.incrementAndGet();
          } finally {
            doneLatch.countDown();
          }
        });

    // Thread B
    executor.submit(
        () -> {
          try {
            startLatch.await();
            storeB.recordInvocationIntent("proc-concurrent", "op-concurrent", "attempt-test");
            successCount.incrementAndGet();
          } catch (Exception e) {
            exceptionCount.incrementAndGet();
          } finally {
            doneLatch.countDown();
          }
        });

    // Start both threads
    startLatch.countDown();
    doneLatch.await(5, TimeUnit.SECONDS);

    executor.shutdown();

    // Both must succeed (idempotent, not claiming)
    assertThat(successCount.get()).isEqualTo(2);
    assertThat(exceptionCount.get()).isEqualTo(0);

    // Intent exists
    assertThat(store.findAttempts("proc-concurrent", "op-concurrent")).isNotEmpty();
  }

  @Test
  @DisplayName("Restart simulation: store instance A records, instance B reads")
  void restartSimulation() {
    JdbcInvocationStateStore storeA = new JdbcInvocationStateStore(database);

    storeA.recordInvocationIntent("proc-restart", "op-restart", "attempt-test");

    // Discard store A (simulate JVM restart)
    storeA = null;

    // Create new store instance B
    JdbcInvocationStateStore storeB = new JdbcInvocationStateStore(database);

    java.util.List<InvocationAttempt> attempts = storeB.findAttempts("proc-restart", "op-restart");

    assertThat(attempts).isNotEmpty();
  }

  @Test
  @DisplayName("Cross-instance visibility: store A records, store B immediately reads")
  void crossInstanceVisibility() {
    JdbcInvocationStateStore storeA = new JdbcInvocationStateStore(database);
    JdbcInvocationStateStore storeB = new JdbcInvocationStateStore(database);

    storeA.recordInvocationIntent("proc-cross", "op-cross", "attempt-test");

    // Store B reads immediately (strong read-after-write)
    java.util.List<InvocationAttempt> attempts = storeB.findAttempts("proc-cross", "op-cross");

    assertThat(attempts).isNotEmpty();
  }

  @Test
  @DisplayName("Null processId throws NullPointerException on record")
  void nullProcessIdOnRecord() {
    assertThatThrownBy(() -> store.recordInvocationIntent(null, "op-1", "attempt-test"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("processId");
  }

  @Test
  @DisplayName("Blank processId throws IllegalArgumentException on record")
  void blankProcessIdOnRecord() {
    assertThatThrownBy(() -> store.recordInvocationIntent("  ", "op-1", "attempt-test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId");
  }

  @Test
  @DisplayName("Null operationId throws NullPointerException on record")
  void nullOperationIdOnRecord() {
    assertThatThrownBy(() -> store.recordInvocationIntent("proc-1", null, "attempt-test"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("operationId");
  }

  @Test
  @DisplayName("Blank operationId throws IllegalArgumentException on record")
  void blankOperationIdOnRecord() {
    assertThatThrownBy(() -> store.recordInvocationIntent("proc-1", "  ", "attempt-test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operationId");
  }

  @Test
  @DisplayName("Null processId throws NullPointerException on check")
  void nullProcessIdOnCheck() {
    assertThatThrownBy(() -> store.findAttempts(null, "op-1"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("processId");
  }

  @Test
  @DisplayName("Blank processId throws IllegalArgumentException on check")
  void blankProcessIdOnCheck() {
    assertThatThrownBy(() -> store.findAttempts("", "op-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId");
  }

  @Test
  @DisplayName("Null operationId throws NullPointerException on check")
  void nullOperationIdOnCheck() {
    assertThatThrownBy(() -> store.findAttempts("proc-1", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("operationId");
  }

  @Test
  @DisplayName("Blank operationId throws IllegalArgumentException on check")
  void blankOperationIdOnCheck() {
    assertThatThrownBy(() -> store.findAttempts("proc-1", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operationId");
  }

  @Test
  @DisplayName("Multiple intents for same process")
  void multipleIntentsSameProcess() {
    store.recordInvocationIntent("proc-multi", "op-1", "attempt-test");
    store.recordInvocationIntent("proc-multi", "op-2", "attempt-test");
    store.recordInvocationIntent("proc-multi", "op-3", "attempt-test");

    assertThat(store.findAttempts("proc-multi", "op-1")).isNotEmpty();
    assertThat(store.findAttempts("proc-multi", "op-2")).isNotEmpty();
    assertThat(store.findAttempts("proc-multi", "op-3")).isNotEmpty();
    assertThat(store.findAttempts("proc-multi", "op-4")).isEmpty();
  }
}
