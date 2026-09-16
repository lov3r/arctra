package cn.bitcss.arctra.runtime;

import cn.bitcss.arctra.recovery.OperationResolution;
import cn.bitcss.arctra.recovery.RecoveryUncertaintyException;
import java.util.Optional;

/**
 * Recovery resolution capability for operator-driven uncertainty resolution.
 *
 * <p><strong>M6-T5: Durable Recovery Execution & Resolution.</strong>
 *
 * <p>Provides application/operator API for resolving uncertain physical invocation attempts. When
 * recovery classification detects {@code MAY_HAVE_INVOKED} status, operator can use this API to
 * instruct Arctra how to proceed based on external reconciliation.
 *
 * <h2>Resolution Types</h2>
 *
 * <ul>
 *   <li><strong>NOT_EXECUTED</strong>: External authority confirms physical attempt did not execute.
 *       Framework will create new attempt and execute the operation.
 *   <li><strong>EXECUTED</strong>: External authority provides completed outcome. Framework will skip
 *       physical invocation and continue using recovered result.
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * try {
 *   runtime.resumeProcess(processId, version, signal);
 * } catch (RecoveryUncertaintyException e) {
 *   // Operator reconciles external system state
 *   for (String attemptId : e.getUnresolvedAttemptIds()) {
 *     if (externalSystem.wasExecuted(attemptId)) {
 *       String result = externalSystem.getResult(attemptId);
 *       runtime.recovery().resolveAsExecuted(
 *           e.getProcessId(), version, e.getOperationId(), attemptId, result);
 *     } else {
 *       runtime.recovery().resolveAsNotExecuted(
 *           e.getProcessId(), version, e.getOperationId(), attemptId);
 *     }
 *   }
 *   // Retry resume
 *   runtime.resumeProcess(processId, version, signal);
 * }
 * }</pre>
 *
 * <h2>Validation</h2>
 *
 * <p>Resolution writes are validated against current checkpoint:
 *
 * <ul>
 *   <li>Checkpoint version must match (CAS-style stale protection)
 *   <li>Operation must belong to current pending batch
 *   <li>Attempt must exist for that operation
 *   <li>Invocation intent must exist
 *   <li>Resolution must not conflict with existing resolution
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>Submitting the same semantic resolution multiple times succeeds idempotently. Submitting a
 * conflicting resolution throws {@code RecoveryResolutionConflictException}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe.
 *
 * @since M6-T5
 */
public interface RecoveryResolution {

  /**
   * Resolve physical attempt as not executed.
   *
   * <p>Operator/reconciliation confirms that the physical attempt did not produce external side
   * effects. Framework may create a new attempt and execute the logical operation.
   *
   * <h3>Validation</h3>
   *
   * <ul>
   *   <li>Checkpoint must exist
   *   <li>checkpointVersion must match current checkpoint version
   *   <li>operationId must belong to current pending batch
   *   <li>attemptId must exist for that operation
   *   <li>Invocation intent must exist
   * </ul>
   *
   * @param processId process identifier (non-null, non-blank)
   * @param checkpointVersion expected checkpoint version (CAS-style protection)
   * @param operationId logical operation identifier (non-null, non-blank)
   * @param attemptId physical attempt identifier (non-null, non-blank)
   * @throws cn.bitcss.arctra.runtime.react.StaleRecoveryResolutionException if checkpoint version
   *     mismatch
   * @throws cn.bitcss.arctra.runtime.react.InvalidRecoveryResolutionException if
   *     operation/attempt invalid
   * @throws cn.bitcss.arctra.runtime.react.RecoveryResolutionConflictException if conflicting
   *     resolution exists
   * @throws NullPointerException if any parameter is null
   * @throws IllegalArgumentException if any string parameter is blank
   */
  void resolveAsNotExecuted(
      String processId, long checkpointVersion, String operationId, String attemptId);

  /**
   * Resolve physical attempt as executed with recovered result.
   *
   * <p>Operator/reconciliation provides the completed outcome of the physical attempt. Framework
   * will skip physical delegate invocation and continue using the recovered result.
   *
   * <h3>Validation</h3>
   *
   * <p>Same as {@link #resolveAsNotExecuted}, plus:
   *
   * <ul>
   *   <li>recoveredResult must be non-null
   * </ul>
   *
   * @param processId process identifier (non-null, non-blank)
   * @param checkpointVersion expected checkpoint version (CAS-style protection)
   * @param operationId logical operation identifier (non-null, non-blank)
   * @param attemptId physical attempt identifier (non-null, non-blank)
   * @param recoveredResult recovered result content (non-null)
   * @throws cn.bitcss.arctra.runtime.react.StaleRecoveryResolutionException if checkpoint version
   *     mismatch
   * @throws cn.bitcss.arctra.runtime.react.InvalidRecoveryResolutionException if
   *     operation/attempt invalid
   * @throws cn.bitcss.arctra.runtime.react.RecoveryResolutionConflictException if conflicting
   *     resolution exists
   * @throws NullPointerException if any parameter is null
   * @throws IllegalArgumentException if any string parameter is blank
   */
  void resolveAsExecuted(
      String processId,
      long checkpointVersion,
      String operationId,
      String attemptId,
      String recoveredResult);

  /**
   * Get recorded resolution for a physical attempt.
   *
   * <p>Returns the recovery resolution if one has been recorded for the specified attempt.
   *
   * @param processId process identifier (non-null, non-blank)
   * @param operationId logical operation identifier (non-null, non-blank)
   * @param attemptId physical attempt identifier (non-null, non-blank)
   * @return resolution if recorded, empty otherwise
   * @throws NullPointerException if any parameter is null
   * @throws IllegalArgumentException if any string parameter is blank
   */
  Optional<cn.bitcss.arctra.recovery.OperationResolution> getResolution(
      String processId, String operationId, String attemptId);
}
