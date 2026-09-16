package cn.bitcss.arctra.runtime.react.durable;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InMemoryInvocationStateStore}.
 *
 * <p>Validates recovery-critical invocation-intent authority semantics.
 *
 * @author lov3r
 * @since M6-T4A
 */
class InMemoryInvocationStateStoreTest {

  private InMemoryInvocationStateStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryInvocationStateStore();
  }

  // Test A — Record fact

  @Test
  void recordInvocationIntent_shouldStoreIntent() {
    // Given
    String processId = "proc-123";
    String operationId = "op-456";

    // When
    store.recordInvocationIntent(processId, operationId, "attempt-test");

    // Then
    assertThat(store.hasInvocationIntent(processId, operationId, "attempt-test"))
        .as("Intent should be recorded")
        .isTrue();
  }

  // Test B — Duplicate recording (idempotency)

  @Test
  void recordInvocationIntent_duplicateRecording_shouldSucceed() {
    // Given
    String processId = "proc-123";
    String operationId = "op-456";
    store.recordInvocationIntent(processId, operationId, "attempt-test");

    // When - record same intent again
    assertThatCode(() -> store.recordInvocationIntent(processId, operationId, "attempt-test"))
        .as("Duplicate recording should succeed without error")
        .doesNotThrowAnyException();

    // Then - intent still exists (idempotent state write)
    assertThat(store.hasInvocationIntent(processId, operationId, "attempt-test"))
        .as("Intent should still be recorded after duplicate")
        .isTrue();
  }

  @Test
  void recordInvocationIntent_multipleWorkers_shouldNotClaim() {
    // Given - simulate two workers recording intent for same operation
    String processId = "proc-123";
    String operationId = "op-456";

    // When - both workers record intent
    store.recordInvocationIntent(processId, operationId, "attempt-test"); // Worker A
    store.recordInvocationIntent(processId, operationId, "attempt-test"); // Worker B

    // Then - both succeed (no claiming, at-least-once preserved)
    assertThat(store.hasInvocationIntent(processId, operationId, "attempt-test"))
        .as("Intent recorded by both workers")
        .isTrue();
    // Note: This test proves idempotent write, not claiming.
    // Both workers MAY proceed to execute (proven in integration test).
  }

  // Test C — Distinct operations

  @Test
  void recordInvocationIntent_distinctOperations_shouldRemainDistinct() {
    // Given
    String processId = "proc-123";
    String opA = "op-A";
    String opB = "op-B";

    // When
    store.recordInvocationIntent(processId, opA, "attempt-test");
    store.recordInvocationIntent(processId, opB, "attempt-test");

    // Then
    assertThat(store.hasInvocationIntent(processId, opA, "attempt-test"))
        .as("Intent for op-A should be recorded")
        .isTrue();
    assertThat(store.hasInvocationIntent(processId, opB, "attempt-test"))
        .as("Intent for op-B should be recorded")
        .isTrue();
  }

  // Test D — Distinct processes

  @Test
  void recordInvocationIntent_distinctProcesses_shouldRemainDistinct() {
    // Given
    String proc1 = "proc-1";
    String proc2 = "proc-2";
    String operationId = "op-A";

    // When
    store.recordInvocationIntent(proc1, operationId, "attempt-test");
    store.recordInvocationIntent(proc2, operationId, "attempt-test");

    // Then
    assertThat(store.hasInvocationIntent(proc1, operationId, "attempt-test"))
        .as("Intent for proc-1 should be recorded")
        .isTrue();
    assertThat(store.hasInvocationIntent(proc2, operationId, "attempt-test"))
        .as("Intent for proc-2 should be recorded")
        .isTrue();
  }

  // Test E — Thread safety

  @Test
  void recordInvocationIntent_concurrentAccess_shouldBeThreadSafe() throws InterruptedException {
    // Given
    String processId = "proc-123";
    int threadCount = 10;
    int operationsPerThread = 100;

    // When - multiple threads record intents concurrently
    Thread[] threads = new Thread[threadCount];
    for (int t = 0; t < threadCount; t++) {
      final int threadIndex = t;
      threads[t] =
          new Thread(
              () -> {
                for (int i = 0; i < operationsPerThread; i++) {
                  String operationId = "op-" + threadIndex + "-" + i;
                  store.recordInvocationIntent(processId, operationId, "attempt-test");
                }
              });
      threads[t].start();
    }

    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join();
    }

    // Then - all intents should be recorded without corruption
    for (int t = 0; t < threadCount; t++) {
      for (int i = 0; i < operationsPerThread; i++) {
        String operationId = "op-" + t + "-" + i;
        assertThat(store.hasInvocationIntent(processId, operationId, "attempt-test"))
            .as("Intent for " + operationId + " should be recorded")
            .isTrue();
      }
    }
  }

  // Validation tests

  @Test
  void recordInvocationIntent_nullProcessId_shouldThrow() {
    assertThatThrownBy(() -> store.recordInvocationIntent(null, "op-123", "attempt-test"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("processId cannot be null");
  }

  @Test
  void recordInvocationIntent_nullOperationId_shouldThrow() {
    assertThatThrownBy(() -> store.recordInvocationIntent("proc-123", null, "attempt-test"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("operationId cannot be null");
  }

  @Test
  void recordInvocationIntent_blankProcessId_shouldThrow() {
    assertThatThrownBy(() -> store.recordInvocationIntent("   ", "op-123", "attempt-test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("processId cannot be blank");
  }

  @Test
  void recordInvocationIntent_blankOperationId_shouldThrow() {
    assertThatThrownBy(() -> store.recordInvocationIntent("proc-123", "   ", "attempt-test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operationId cannot be blank");
  }

  @Test
  void hasInvocationIntent_notRecorded_shouldReturnFalse() {
    // Given - no intent recorded
    String processId = "proc-123";
    String operationId = "op-456";

    // When/Then
    assertThat(store.hasInvocationIntent(processId, operationId, "attempt-test"))
        .as("Intent should not exist for unrecorded operation")
        .isFalse();
  }

  @Test
  void hasInvocationIntent_differentProcess_shouldReturnFalse() {
    // Given
    store.recordInvocationIntent("proc-A", "op-123", "attempt-test");

    // When/Then
    assertThat(store.hasInvocationIntent("proc-B", "op-123", "attempt-test"))
        .as("Intent for different process should not exist")
        .isFalse();
  }

  @Test
  void hasInvocationIntent_differentOperation_shouldReturnFalse() {
    // Given
    store.recordInvocationIntent("proc-123", "op-A", "attempt-test");

    // When/Then
    assertThat(store.hasInvocationIntent("proc-123", "op-B", "attempt-test"))
        .as("Intent for different operation should not exist")
        .isFalse();
  }

  @Test
  void clear_shouldRemoveAllIntents() {
    // Given
    store.recordInvocationIntent("proc-1", "op-A", "attempt-test");
    store.recordInvocationIntent("proc-1", "op-B", "attempt-test");
    store.recordInvocationIntent("proc-2", "op-C", "attempt-test");

    // When
    store.clear();

    // Then
    assertThat(store.hasInvocationIntent("proc-1", "op-A", "attempt-test")).isFalse();
    assertThat(store.hasInvocationIntent("proc-1", "op-B", "attempt-test")).isFalse();
    assertThat(store.hasInvocationIntent("proc-2", "op-C", "attempt-test")).isFalse();
  }
}
