package cn.bitcss.arctra.runtime.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import org.junit.jupiter.api.Test;

/**
 * Tests for InvocationRecoveryClassifier.
 *
 * <p><strong>M6-T4C.1: Executable recovery path closure.</strong>
 *
 * <p>Direct behavioral proof of classification semantics.
 *
 * @author lov3r
 * @since M6-T4C.1
 */
class InvocationRecoveryClassifierTest {

  // Test 1: Intent Absent → DEFINITELY_NOT_DISPATCHED

  @Test
  void intentAbsent_shouldClassify_definitelyNotDispatched() {
    // Given - store with no intent recorded
    InvocationStateStore store = new InMemoryInvocationStateStore();
    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(store);

    // Given - pending operation
    PendingToolCall operation = new PendingToolCall("op-123", "tc-123", "test-tool", "{}");

    // When - classify
    RecoveryClassificationResult classification =
        classifier.classify("proc-test", operation);

    // Then - definitely not dispatched (intent absent)
    assertThat(classification.type())
        .as("Intent absent should classify as DEFINITELY_NOT_DISPATCHED")
        .isEqualTo(RecoveryClassificationType.DEFINITELY_NOT_DISPATCHED);
  }

  // Test 2: Intent Present → MAY_HAVE_INVOKED

  @Test
  void intentPresent_shouldClassify_mayHaveInvoked() {
    // Given - store with intent already recorded
    InvocationStateStore store = new InMemoryInvocationStateStore();
    store.recordInvocationIntent("proc-test", "op-123", "attempt-test");

    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(store);

    // Given - pending operation matching the recorded intent
    PendingToolCall operation = new PendingToolCall("op-123", "tc-123", "test-tool", "{}");

    // When - classify
    RecoveryClassificationResult classification =
        classifier.classify("proc-test", operation);

    // Then - may have invoked (intent exists)
    assertThat(classification.type())
        .as("Intent present should classify as MAY_HAVE_INVOKED")
        .isEqualTo(RecoveryClassificationType.MAY_HAVE_INVOKED);
  }

  // Test 3: Read Failure → Exception Propagates

  @Test
  void readFailure_shouldPropagateException_notClassifyAsAbsent() {
    // Given - failing store that throws on read
    InvocationStateStore failingStore =
        new InvocationStateStore() {
          @Override
          public void recordInvocationIntent(String processId, String operationId, String attemptId) {
            // Not used in this test
          }

          @Override
          public boolean hasInvocationIntent(String processId, String operationId, String attemptId) {
            // Not used in M6-T5 (uses findAttempts instead)
            return false;
          }

          @Override
          public java.util.List<InvocationAttempt> findAttempts(String processId, String operationId) {
            throw new RuntimeException("Test storage read failure");
          }

          @Override
          public void recordResolution(
              String processId,
              String operationId,
              String attemptId,
              cn.bitcss.arctra.recovery.ResolutionType type,
              String recoveredResult) {
            // No-op
          }

          @Override
          public java.util.Optional<cn.bitcss.arctra.recovery.OperationResolution> getResolution(
              String processId, String operationId, String attemptId) {
            return java.util.Optional.empty();
          }
        };

    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(failingStore);

    PendingToolCall operation = new PendingToolCall("op-123", "tc-123", "test-tool", "{}");

    // When/Then - read failure propagates as exception (not classification)
    assertThatThrownBy(() -> classifier.classify("proc-test", operation))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Test storage read failure");

    // Critical: Read failure does NOT become false classification
    // Unknown ≠ Absent - exception must propagate
  }

  // Test 4: Multiple Operations, Distinct Classifications

  @Test
  void multipleOperations_shouldClassifyIndependently() {
    // Given - store with intent for op-A only
    InvocationStateStore store = new InMemoryInvocationStateStore();
    store.recordInvocationIntent("proc-test", "op-A", "attempt-test");

    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(store);

    PendingToolCall opA = new PendingToolCall("op-A", "tc-A", "tool-A", "{}");
    PendingToolCall opB = new PendingToolCall("op-B", "tc-B", "tool-B", "{}");

    // When - classify both
    RecoveryClassificationResult classA = classifier.classify("proc-test", opA);
    RecoveryClassificationResult classB = classifier.classify("proc-test", opB);

    // Then - independent classifications
    assertThat(classA.type())
        .as("op-A has intent")
        .isEqualTo(RecoveryClassificationType.MAY_HAVE_INVOKED);

    assertThat(classB.type())
        .as("op-B has no intent")
        .isEqualTo(RecoveryClassificationType.DEFINITELY_NOT_DISPATCHED);
  }

  // Test 5: Process Isolation

  @Test
  void differentProcesses_shouldIsolateIntentState() {
    // Given - intent recorded for proc-1 only
    InvocationStateStore store = new InMemoryInvocationStateStore();
    store.recordInvocationIntent("proc-1", "op-A", "attempt-test");

    InvocationRecoveryClassifier classifier = new InvocationRecoveryClassifier(store);

    PendingToolCall operation = new PendingToolCall("op-A", "tc-A", "tool-A", "{}");

    // When - classify for proc-1 and proc-2
    RecoveryClassificationResult class1 = classifier.classify("proc-1", operation);
    RecoveryClassificationResult class2 = classifier.classify("proc-2", operation);

    // Then - process isolation
    assertThat(class1.type())
        .as("proc-1 has intent")
        .isEqualTo(RecoveryClassificationType.MAY_HAVE_INVOKED);

    assertThat(class2.type())
        .as("proc-2 has no intent (different process)")
        .isEqualTo(RecoveryClassificationType.DEFINITELY_NOT_DISPATCHED);
  }
}
