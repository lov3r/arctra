package cn.bitcss.arctra.runtime.react;

/**
 * Recovery execution cannot proceed due to invocation uncertainty.
 *
 * <p><strong>M6-T4C Phase 1: Recovery fail-closed boundary.</strong>
 *
 * <p>Thrown when explicit recovery classification detects that one or more pending operations have
 * {@link InvocationRecoveryClassification#MAY_HAVE_INVOKED} status, meaning physical invocation may
 * have already occurred but external outcome is unknown.
 *
 * <h2>Phase 1 Semantics</h2>
 *
 * <p>This is a <strong>safety boundary</strong>, not a configurable recovery policy. When recovery
 * classification cannot establish that an operation is safe to execute, the framework fails closed
 * rather than risking duplicate execution of uncertain operations.
 *
 * <h2>What This Is NOT</h2>
 *
 * <ul>
 *   <li>NOT a retry policy decision
 *   <li>NOT a recoverable error (no automatic retry)
 *   <li>NOT an operator intervention workflow trigger
 *   <li>NOT a signal to query external systems
 * </ul>
 *
 * <h2>Future Recovery Policy</h2>
 *
 * <p>Phase 2+ may introduce recovery policy that decides how to handle uncertain operations:
 *
 * <ul>
 *   <li>Automatic retry (if idempotent)
 *   <li>External system query (via receipts/idempotency keys)
 *   <li>Operator intervention (manual decision)
 *   <li>Abort process (fail permanently)
 * </ul>
 *
 * <p>Until then, this exception represents the safe default: cannot proceed, manual investigation
 * required.
 *
 * @author lov3r
 * @since M6-T4C Phase 1
 */
final class RecoveryUncertaintyException extends RuntimeException {

  private final String processId;
  private final String uncertainOperationId;

  /**
   * Create recovery uncertainty exception.
   *
   * @param message diagnostic message
   * @param processId process identifier
   * @param uncertainOperationId operation ID that has uncertain invocation state
   */
  RecoveryUncertaintyException(
      String message, String processId, String uncertainOperationId) {
    super(message);
    this.processId = processId;
    this.uncertainOperationId = uncertainOperationId;
  }

  /**
   * Get process identifier.
   *
   * @return process ID
   */
  String getProcessId() {
    return processId;
  }

  /**
   * Get uncertain operation identifier.
   *
   * @return operation ID with MAY_HAVE_INVOKED status
   */
  String getUncertainOperationId() {
    return uncertainOperationId;
  }
}
