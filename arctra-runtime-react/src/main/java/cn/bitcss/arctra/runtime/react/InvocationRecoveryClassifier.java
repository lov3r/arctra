package cn.bitcss.arctra.runtime.react;

import cn.bitcss.arctra.checkpoint.PendingToolCall;
import java.util.Objects;

/**
 * Invocation recovery classifier for pending operations.
 *
 * <p><strong>M6-T4C Phase 1: Recovery classification authority.</strong>
 *
 * <p>Classifies pending tool operations based on {@link InvocationStateStore} intent state to
 * distinguish operations safe to execute from those requiring recovery policy.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Sole responsibility: Query invocation-intent authority and produce classification fact.
 *
 * <pre>
 * PendingToolCall + processId + InvocationStateStore
 *   → DEFINITELY_NOT_DISPATCHED (safe)
 *   → MAY_HAVE_INVOKED (uncertain)
 * </pre>
 *
 * <h2>Classification Truth Table</h2>
 *
 * <ul>
 *   <li>{@code hasInvocationIntent() = false} → {@link
 *       InvocationRecoveryClassification#DEFINITELY_NOT_DISPATCHED}
 *   <li>{@code hasInvocationIntent() = true} → {@link
 *       InvocationRecoveryClassification#MAY_HAVE_INVOKED}
 *   <li>{@code hasInvocationIntent() throws} → exception propagates (unknown ≠ absent)
 * </ul>
 *
 * <h2>Side-Effect Free</h2>
 *
 * <p>This classifier is read-only. It does NOT:
 *
 * <ul>
 *   <li>Write invocation intent
 *   <li>Invoke tools
 *   <li>Modify checkpoints
 *   <li>Emit execution events
 *   <li>Query external systems
 *   <li>Make recovery policy decisions
 * </ul>
 *
 * <h2>Authority Boundaries</h2>
 *
 * <p>Depends ONLY on {@link InvocationStateStore} (invocation-intent authority). Does NOT depend
 * on:
 *
 * <ul>
 *   <li>ExecutionLedger (event absence not authoritative)
 *   <li>External systems (external outcome unknown)
 *   <li>Checkpoint metadata (no restart markers)
 * </ul>
 *
 * @author lov3r
 * @since M6-T4C Phase 1
 */
final class InvocationRecoveryClassifier {

  private final InvocationStateStore invocationStateStore;

  /**
   * Create classifier.
   *
   * @param invocationStateStore invocation-intent authority
   */
  InvocationRecoveryClassifier(InvocationStateStore invocationStateStore) {
    this.invocationStateStore =
        Objects.requireNonNull(invocationStateStore, "invocationStateStore cannot be null");
  }

  /**
   * Classify pending operation.
   *
   * <p>Queries invocation-intent state to determine whether operation is safe to execute.
   *
   * <p><strong>Read failure semantics:</strong> If {@link
   * InvocationStateStore#hasInvocationIntent(String, String)} throws, the exception propagates.
   * Storage read failure does NOT become {@code false} classification. Unknown state must not be
   * treated as definitive absence.
   *
   * @param processId process identifier
   * @param operation pending tool operation to classify
   * @return classification (DEFINITELY_NOT_DISPATCHED or MAY_HAVE_INVOKED)
   * @throws RuntimeException if invocation-state read fails (fail closed)
   */
  InvocationRecoveryClassification classify(String processId, PendingToolCall operation) {
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operation, "operation cannot be null");

    // Query invocation-intent authority
    // If this throws (storage failure), exception propagates - unknown != absent
    boolean hasIntent = invocationStateStore.hasInvocationIntent(processId, operation.operationId());

    if (hasIntent) {
      // Intent exists → gate was crossed → physical invocation may have occurred
      return InvocationRecoveryClassification.MAY_HAVE_INVOKED;
    } else {
      // Intent authoritatively absent → gate never crossed → safe to execute
      return InvocationRecoveryClassification.DEFINITELY_NOT_DISPATCHED;
    }
  }
}
