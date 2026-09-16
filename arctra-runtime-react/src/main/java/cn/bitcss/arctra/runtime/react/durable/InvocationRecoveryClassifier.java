package cn.bitcss.arctra.runtime.react.durable;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import cn.bitcss.arctra.recovery.OperationResolution;
import cn.bitcss.arctra.recovery.RecoveryResolutionConflictException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Invocation recovery classifier for pending operations.
 *
 * <p><strong>M6-T4C Phase 1: Recovery classification authority.</strong>
 *
 * <p><strong>M6-T5: Multi-attempt aggregation.</strong>
 *
 * <p>Classifies pending tool operations based on {@link InvocationStateStore} intent and resolution
 * state to distinguish operations safe to execute from those requiring recovery policy.
 *
 * <h2>M6-T5.1: Operation-Level Aggregation</h2>
 *
 * <p>A logical operation may have multiple physical attempts due to concurrent workers or retries.
 * Classification aggregates ALL attempts for an operation:
 *
 * <pre>
 * Rule 1: No attempts → DEFINITELY_NOT_DISPATCHED
 * Rule 2: ANY unresolved attempt → MAY_HAVE_INVOKED (fail-closed)
 * Rule 3: No unresolved + ANY EXECUTED → RESOLVED_EXECUTED
 * Rule 4: All attempts NOT_EXECUTED → RESOLVED_NOT_EXECUTED (eligible for new attempt)
 * </pre>
 *
 * <h2>Responsibility</h2>
 *
 * <p>Sole responsibility: Query invocation-state authority and produce classification fact for each
 * logical operation in a pending batch.
 *
 * @since M6-T4C
 * @since M6-T5 Multi-attempt aggregation
 */
public final class InvocationRecoveryClassifier {

  private final InvocationStateStore invocationStateStore;

  /**
   * Create classifier.
   *
   * @param invocationStateStore invocation-state authority
   * @throws NullPointerException if invocationStateStore is null
   */
  public InvocationRecoveryClassifier(InvocationStateStore invocationStateStore) {
    this.invocationStateStore =
        Objects.requireNonNull(invocationStateStore, "invocationStateStore cannot be null");
  }

  /**
   * Classify operation by aggregating all physical attempts.
   *
   * <p><strong>M6-T5.1: Multi-attempt aggregation.</strong>
   *
   * <p>Queries all durable attempts for the logical operation and applies aggregation rules.
   *
   * @param processId process identifier
   * @param operation pending tool call
   * @return classification result
   * @throws RuntimeException if invocation-state read fails (fail closed)
   */
  RecoveryClassificationResult classify(String processId, PendingToolCall operation) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operation, "operation cannot be null");

    String operationId = operation.operationId();

    // Query all attempts for this operation
    List<InvocationAttempt> attempts = invocationStateStore.findAttempts(processId, operationId);

    // Rule 1: No attempts → DEFINITELY_NOT_DISPATCHED
    if (attempts.isEmpty()) {
      return RecoveryClassificationResult.definitelyNotDispatched(operationId);
    }

    // Rule 2: ANY unresolved attempt → MAY_HAVE_INVOKED
    List<String> unresolvedAttemptIds =
        attempts.stream()
            .filter(InvocationAttempt::isUnresolved)
            .map(InvocationAttempt::attemptId)
            .collect(Collectors.toList());

    if (!unresolvedAttemptIds.isEmpty()) {
      return RecoveryClassificationResult.mayHaveInvoked(operationId, unresolvedAttemptIds);
    }

    // Rule 3: No unresolved + ANY EXECUTED → RESOLVED_EXECUTED
    List<InvocationAttempt> executedAttempts =
        attempts.stream().filter(InvocationAttempt::isResolvedExecuted).toList();

    if (!executedAttempts.isEmpty()) {
      // Validate result consistency if multiple EXECUTED resolutions exist
      if (executedAttempts.size() > 1) {
        validateExecutedResultConsistency(operationId, executedAttempts);
      }

      // Use the first EXECUTED resolution
      OperationResolution resolution = executedAttempts.get(0).resolution().orElseThrow();
      return RecoveryClassificationResult.resolvedExecuted(
          operationId, resolution.attemptId(), resolution.recoveredResult().orElseThrow());
    }

    // Rule 4: All attempts RESOLVED_NOT_EXECUTED → eligible for new attempt
    return RecoveryClassificationResult.resolvedNotExecuted(operationId);
  }

  /**
   * Validate that multiple EXECUTED resolutions have consistent results.
   *
   * <p>Throws {@link RecoveryResolutionConflictException} if different results exist.
   */
  private void validateExecutedResultConsistency(
      String operationId, List<InvocationAttempt> executedAttempts) {

    Set<String> distinctResults = new HashSet<>();
    for (InvocationAttempt attempt : executedAttempts) {
      String result = attempt.resolution().flatMap(OperationResolution::recoveredResult).orElse("");
      distinctResults.add(result);
    }

    if (distinctResults.size() > 1) {
      throw new RecoveryResolutionConflictException(
          String.format(
              "Multiple EXECUTED resolutions with different results for operation %s: %s",
              operationId, distinctResults));
    }
  }

  /**
   * Get the invocation state store (package-private for coordinator access).
   *
   * @return invocation state store
   * @since M6-T5
   */
  InvocationStateStore getInvocationStateStore() {
    return invocationStateStore;
  }
}
