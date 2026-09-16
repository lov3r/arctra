package cn.bitcss.arctra.recovery;

import java.util.List;
import java.util.Objects;

/**
 * Recovery execution cannot proceed due to invocation uncertainty.
 *
 * <p><strong>M6-T4C Phase 1: Recovery fail-closed boundary.</strong>
 *
 * <p><strong>M6-T5.1: Multi-attempt uncertainty.</strong>
 *
 * <p>Thrown when explicit recovery classification detects that one or more physical invocation
 * attempts for a logical operation have uncertain state, meaning physical invocation may have
 * already occurred but external outcome is unknown.
 *
 * <h2>Fail-Closed Semantics</h2>
 *
 * <p>This is a <strong>safety boundary</strong>, not a configurable recovery policy. When recovery
 * classification cannot establish that an operation is safe to execute, the framework fails closed
 * rather than risking duplicate execution of uncertain operations.
 *
 * <h2>Multiple Concurrent Attempts</h2>
 *
 * <p>M6-T5.1: Due to at-least-once semantics, multiple workers may concurrently attempt the same
 * logical operation, each creating a unique physical attempt. If any attempt remains unresolved
 * after restart, all unresolved attemptIds are exposed through this exception.
 *
 * <h2>Resolution Requirement</h2>
 *
 * <p>Operator must resolve ALL unresolved attempts before recovery can continue. Use {@link
 * #getUnresolvedAttemptIds()} to enumerate attempts requiring resolution.
 *
 * <p><strong>Checkpoint remains valid.</strong> Exception is thrown before CHECK B, so suspended
 * state is preserved for future recovery attempts.
 *
 * @since M6-T4C Phase 1
 * @since M6-T5.1 Multi-attempt support
 */
public final class RecoveryUncertaintyException extends RuntimeException {

  private final String processId;
  private final String operationId;
  private final List<String> unresolvedAttemptIds;

  /**
   * Create recovery uncertainty exception.
   *
   * @param message diagnostic message
   * @param processId process identifier
   * @param operationId logical operation ID with uncertain invocation state
   * @param unresolvedAttemptIds list of unresolved physical attempt IDs (non-empty)
   * @throws NullPointerException if any parameter is null
   * @throws IllegalArgumentException if unresolvedAttemptIds is empty
   */
  public RecoveryUncertaintyException(
      String message, String processId, String operationId, List<String> unresolvedAttemptIds) {
    super(message);
    Objects.requireNonNull(processId, "processId cannot be null");
    Objects.requireNonNull(operationId, "operationId cannot be null");
    Objects.requireNonNull(unresolvedAttemptIds, "unresolvedAttemptIds cannot be null");
    if (unresolvedAttemptIds.isEmpty()) {
      throw new IllegalArgumentException("unresolvedAttemptIds cannot be empty");
    }
    this.processId = processId;
    this.operationId = operationId;
    this.unresolvedAttemptIds = List.copyOf(unresolvedAttemptIds);
  }

  /**
   * Get process identifier.
   *
   * @return process ID
   */
  public String getProcessId() {
    return processId;
  }

  /**
   * Get logical operation identifier.
   *
   * @return operation ID with MAY_HAVE_INVOKED status
   */
  public String getOperationId() {
    return operationId;
  }

  /**
   * Get unresolved physical attempt identifiers.
   *
   * <p>M6-T5.1: Returns all physical attempts for this logical operation that have invocation
   * intent but no recovery resolution. All attempts in this list must be resolved before recovery
   * can continue.
   *
   * @return immutable list of unresolved attempt IDs (never empty)
   */
  public List<String> getUnresolvedAttemptIds() {
    return unresolvedAttemptIds;
  }
}
