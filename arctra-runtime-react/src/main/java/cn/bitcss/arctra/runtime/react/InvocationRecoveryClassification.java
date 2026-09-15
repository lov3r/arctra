package cn.bitcss.arctra.runtime.react;

/**
 * Recovery classification for pending tool operations.
 *
 * <p><strong>M6-T4C Phase 1: Recovery classification fact authority.</strong>
 *
 * <p>Classifies pending operations based on invocation-intent state to distinguish operations that
 * are safe to execute from those requiring recovery policy decisions.
 *
 * <h2>Classification Semantics</h2>
 *
 * <ul>
 *   <li>{@link #DEFINITELY_NOT_DISPATCHED}: Intent absent → physical invocation never occurred →
 *       safe to execute
 *   <li>{@link #MAY_HAVE_INVOKED}: Intent exists → physical invocation may have occurred →
 *       uncertain, requires recovery policy
 * </ul>
 *
 * <h2>Authority Boundaries</h2>
 *
 * <p>This classification concerns <strong>invocation uncertainty only</strong>, not external
 * outcomes:
 *
 * <ul>
 *   <li>Does NOT indicate tool success/failure
 *   <li>Does NOT indicate external commit/rollback
 *   <li>Does NOT indicate retry feasibility
 *   <li>Does NOT provide operation ownership
 * </ul>
 *
 * <h2>Read Failure</h2>
 *
 * <p>Storage read failure is NOT a classification state. If {@link
 * InvocationStateStore#hasInvocationIntent(String, String)} throws, the exception propagates
 * (unknown ≠ absent).
 *
 * @author lov3r
 * @since M6-T4C Phase 1
 */
enum InvocationRecoveryClassification {

  /**
   * Operation definitely not dispatched for physical execution.
   *
   * <p>Invocation intent is authoritatively absent. Physical invocation never crossed the mandatory
   * pre-call gate (M6-T4A). Safe to execute as first attempt.
   */
  DEFINITELY_NOT_DISPATCHED,

  /**
   * Operation may have been invoked.
   *
   * <p>Invocation intent exists. Physical invocation may have occurred (gate crossed), but external
   * outcome unknown. Requires recovery policy decision (retry, query external, operator
   * intervention, etc.).
   *
   * <p><strong>Phase 1 behavior:</strong> Fail closed with {@link RecoveryUncertaintyException}.
   * Recovery policy deferred to future phases.
   */
  MAY_HAVE_INVOKED
}
